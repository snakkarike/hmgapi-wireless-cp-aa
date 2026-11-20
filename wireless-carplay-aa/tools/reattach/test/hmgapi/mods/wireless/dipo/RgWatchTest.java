package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.List;

/**
 * Host test of RgWatch (route guidance asked after every comp-5 ready, and the
 * watchdog after each Start), alone and together with Reattach, on one fake clock. The
 * glue is Bridge's: RgWatch.ready(reattach, watch, how, linkUp) for every ready signal,
 * Reattach's ask going to RgWatch.ask("re-attach"), and linkDown on a re-attach's handover.
 */
public final class RgWatchTest {
    static int fail, oks;

    static void check(String what, boolean ok) {
        System.out.println((ok ? "ok    " : "FAIL  ") + what);
        if (ok) oks++;
        else fail = 1;
    }

    static void eq(String what, Object want, Object got) {
        boolean ok = want == null ? got == null : want.equals(got);
        check(what + (ok ? "" : " (wanted " + want + ", got " + got + ")"), ok);
    }

    /** one clock and timer list for both hosts */
    static final class Clock {
        long t;
        final List<long[]> due = new ArrayList<long[]>();   // {when, id}
        final List<Runnable> runs = new ArrayList<Runnable>();
        final List<String> logs = new ArrayList<String>();

        Object schedule(Runnable r, long delayMs) {
            runs.add(r);
            long[] d = { t + delayMs, runs.size() - 1 };
            due.add(d);
            return d;
        }

        void cancel(Object token) {
            due.remove(token);
        }

        void advance(long ms) {
            long end = t + ms;
            while (true) {
                long[] next = null;
                for (long[] d : due) if (d[0] <= end && (next == null || d[0] < next[0])) next = d;
                if (next == null) break;
                due.remove(next);
                t = next[0];
                runs.get((int) next[1]).run();
            }
            t = end;
        }

        boolean logged(String part) {
            for (String l : logs) if (l.contains(part)) return true;
            return false;
        }

        int count(String part) {
            int n = 0;
            for (String l : logs) if (l.contains(part)) n++;
            return n;
        }

        String last(String part) {
            String r = "";
            for (String l : logs) if (l.contains(part)) r = l;
            return r;
        }
    }

    /** RgWatch's host: every ask (Dipo.askAgain) and watchdog restart recorded with its time */
    static final class RgHost implements RgWatch.Host {
        final Clock c;
        final List<Long> asks = new ArrayList<Long>(), restarts = new ArrayList<Long>();

        RgHost(Clock c) {
            this.c = c;
        }

        @Override
        public long now() {
            return c.t;
        }

        @Override
        public Object schedule(Runnable r, long delayMs) {
            return c.schedule(r, delayMs);
        }

        @Override
        public void cancel(Object token) {
            c.cancel(token);
        }

        @Override
        public void askAgain() {
            asks.add(c.t);
        }

        @Override
        public void restart() {
            restarts.add(c.t);
        }

        @Override
        public void log(String m) {
            c.logs.add(m);
        }
    }

    /** Reattach's host as Bridge's: the handover marks comp 5 down, the ask goes through RgWatch */
    static final class ReHost implements Reattach.Host {
        final Clock c;
        RgWatch w;
        int handovers;

        ReHost(Clock c) {
            this.c = c;
        }

        @Override
        public long now() {
            return c.t;
        }

        @Override
        public Object schedule(Runnable r, long delayMs) {
            return c.schedule(r, delayMs);
        }

        @Override
        public void cancel(Object token) {
            c.cancel(token);
        }

        @Override
        public void handover(int attempt) {
            handovers++;
            w.linkDown("re-attach #" + attempt);
        }

        @Override
        public void askAgain() {
            w.ask("re-attach");
        }

        @Override
        public void log(String m) {
            c.logs.add(m);
        }
    }

    static final String OK = "ACTION_IAP2_CONNECTSUCCESS", ID = "identification accepted";

    public static void main(String[] a) {
        firstHandover();
        watchdog();
        stopOnUpdate();
        sessionEnd();
        linkDown();
        reattach();
        misc();
        System.out.println(oks + " ok, " + (fail == 0 ? "0 failed" : "FAILED"));
        System.exit(fail);
    }

    /** the 11-14 case: DiPO's Start went out on comp 4; comp 5 ready -> one ask 1 s later */
    static void firstHandover() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        Reattach r = new Reattach(new ReHost(c));
        w.ready(true);
        RgWatch.ready(r, w, OK, true);
        eq("no CarPlay session owned: a ready asks nothing", 0, c.due.size());
        w.sessionUp();
        r.sessionUp();
        RgWatch.ready(r, w, ID, false);
        eq("identification accepted before comp 5's link is up (comp 4's): nothing", 0, c.due.size());
        c.advance(5000);
        RgWatch.ready(r, w, OK, true);
        c.advance(999);
        eq("first handover: nothing asked before 1 s", 0, h.asks.size());
        RgWatch.ready(r, w, ID, true);   // the same link's second signal (noti 0 after the broadcast)
        c.advance(1);
        eq("first handover: asked once at +1 s", "[6000]", h.asks.toString());
        check("logged: route guidance asked after comp 5 ready (first)", c.logged("route guidance asked after comp 5 ready (first)"));
        c.advance(1000);
        RgWatch.ready(r, w, OK, true);
        c.advance(2000);
        eq("one ask per ready: the duplicate signals asked nothing more", 1, h.asks.size());
        check("the watchdog runs after the ask", w.watching());
    }

    /** no update: asks again at 3, 10, 25 and 55 s (3/7/15/30 s sleeps), then only waits */
    static void watchdog() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);   // re-attach off (no Reattach): RgWatch asks by itself
        c.advance(1000);
        eq("asked at 1 s", "[1000]", h.asks.toString());
        c.advance(2999);
        eq("no re-ask before 3 s", 0, h.restarts.size());
        c.advance(1);
        eq("re-ask 1 at +3 s", "[4000]", h.restarts.toString());
        eq("logged", "no route guidance yet, asking again (1)", c.last("asking again"));
        c.advance(120000);
        eq("re-asks at +3, +10, +25, +55 s", "[4000, 11000, 26000, 56000]", h.restarts.toString());
        eq("4 re-ask lines, the last marked", "no route guidance yet, asking again (4, last)", c.last("asking again"));
        eq("route guidance (Stop/Start) only: Now Playing not re-asked by the watchdog", 1, h.asks.size());
        check("after the last re-ask it still waits for the first update", w.watching());
        c.advance(4000);
        w.update();
        eq("an update after the last re-ask is still logged", "route guidance flowing (first update 127000 ms after the ask) after 4 re-asks", c.last("flowing"));
        check("...and the watchdog is done", !w.watching() && c.due.isEmpty());
        w.update();
        eq("later updates log nothing", 1, c.count("flowing"));
    }

    static void stopOnUpdate() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);
        c.advance(1000);
        c.advance(2000);
        w.update();
        eq("first update logged with its delay", "route guidance flowing (first update 2000 ms after the ask)", c.last("flowing"));
        c.advance(120000);
        eq("stopped at the first update: no re-ask", 0, h.restarts.size());
        // an update between re-asks
        w.started();   // a source change's restart (ours)
        c.advance(3000);
        eq("source change restart watched: re-ask at +3 s", 1, h.restarts.size());
        c.advance(500);
        w.update();
        eq("update after one re-ask", "route guidance flowing (first update 3500 ms after the ask) after 1 re-ask", c.last("flowing"));
        c.advance(120000);
        eq("...then nothing more", 1, h.restarts.size());
        // reset per Start
        long t0 = c.t;
        w.started();
        c.advance(2500);
        w.started();   // a second Start resets the schedule
        c.advance(2999);
        eq("a new Start restarts the schedule (no re-ask at the old +3 s)", 1, h.restarts.size());
        c.advance(1);
        eq("re-ask 3 s after the second Start", Long.valueOf(t0 + 5500), h.restarts.get(1));
        eq("its count starts over", "no route guidance yet, asking again (1)", c.last("asking again"));
        // DiPO's own Stop ends it
        w.stopped();
        c.advance(120000);
        eq("DiPO's Stop: watchdog off", 2, h.restarts.size());
        check("logged", c.logged("route guidance watchdog off (DiPO stopped route guidance)"));
    }

    static void sessionEnd() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);
        c.advance(500);
        w.sessionEnd();
        c.advance(60000);
        eq("session end before the ask: not asked", 0, h.asks.size());
        eq("...nothing pending", 0, c.due.size());
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);
        c.advance(1000);
        c.advance(3000);
        eq("new session: asked, re-ask 1", "1/1", h.asks.size() + "/" + h.restarts.size());
        check("the new session's first ready is 'first' again", c.count("asked after comp 5 ready (first)") == 1);
        w.sessionEnd();
        c.advance(120000);
        eq("session end stops the watchdog", 1, h.restarts.size());
        check("logged", c.logged("route guidance watchdog off (CarPlay session ended)"));
        w.update();
        w.ask("re-attach");
        eq("after the session (not owned: USB-like) an ask still asks", 2, h.asks.size());
    }

    /** never asks while comp 5 is down; the next ready asks (and arms) again */
    static void linkDown() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);
        c.advance(600);
        w.linkDown("iAP2 disconnected comp=5");
        c.advance(60000);
        eq("comp 5 down before the ask: dropped", 0, h.asks.size());
        check("logged", c.logged("route guidance ask dropped: comp 5 down"));
        RgWatch.ready(null, w, OK, true);
        c.advance(1000);
        eq("ready again: asked", 1, h.asks.size());
        check("labelled re-attach (comp 5's second ready this session)", c.logged("route guidance asked after comp 5 ready (re-attach)"));
        c.advance(3000);
        eq("re-ask 1", 1, h.restarts.size());
        w.linkDown("ACTION_IAP2_CONNECTFAIL");
        c.advance(120000);
        eq("comp 5 down: the watchdog never asks", 1, h.restarts.size());
        check("logged: waits for comp 5", c.logged("route guidance watchdog waits for comp 5 (ACTION_IAP2_CONNECTFAIL)"));
        w.ask("re-attach");
        eq("an ask while comp 5 is down is refused", 1, h.asks.size());
        check("logged", c.logged("route guidance not asked (re-attach): comp 5 isn't ready"));
        RgWatch.ready(null, w, ID, true);
        c.advance(1000);
        c.advance(3000);
        eq("ready: asked and watched again", "2/2", h.asks.size() + "/" + h.restarts.size());
    }

    /** with Reattach: the first handover asks through RgWatch, a re-attach through Reattach; one ask per ready */
    static void reattach() {
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, true);
        ReHost rh = new ReHost(c);
        rh.w = w;
        Reattach r = new Reattach(rh);
        w.sessionUp();
        r.sessionUp();
        RgWatch.ready(r, w, OK, true);
        c.advance(1000);
        eq("first handover asked once", 1, h.asks.size());
        c.advance(500);
        w.update();
        // the link fails (Bridge: linkDown, then Reattach.lost)
        w.linkDown("ACTION_IAP2_CONNECTFAIL");
        r.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        c.advance(2000);
        eq("re-attach #1 asked for", 1, rh.handovers);
        r.connected();
        c.advance(1500);
        RgWatch.ready(r, w, OK, true);
        RgWatch.ready(r, w, ID, true);   // both signals for the one ready
        c.advance(999);
        eq("re-attach: nothing before 1 s", 1, h.asks.size());
        c.advance(1);
        eq("re-attach: asked exactly once at +1 s (Reattach's ask through RgWatch)", 2, h.asks.size());
        eq("labelled re-attach", 1, c.count("route guidance asked after comp 5 ready (re-attach)"));
        eq("Reattach's own line still there", 1, c.count("route guidance + now playing asked again"));
        c.advance(3000);
        eq("watchdog after the re-attach's ask", 1, h.restarts.size());
        w.update();
        c.advance(60000);
        eq("no further asks", "2/1", h.asks.size() + "/" + h.restarts.size());
        // a re-attach that never gets ready: the old link's watchdog doesn't ask meanwhile
        w.started();
        w.linkDown("ACTION_IAP2_CONNECTFAIL");
        r.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        c.advance(2000 + 8000 + 5000 + 1000);   // #2 at 2 s, not ready in 8 s, #3 5 s later, link not up yet
        eq("re-attaches #2 and #3", 3, rh.handovers);
        eq("no ask and no re-ask while comp 5 isn't ready", "2/1", h.asks.size() + "/" + h.restarts.size());
        r.connected();
        RgWatch.ready(r, w, OK, true);
        c.advance(1000);
        eq("ready: asked once", 3, h.asks.size());
        // session end between ready and ask
        w.linkDown("ACTION_IAP2_CONNECTFAIL");
        r.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        c.advance(15000);
        r.connected();
        RgWatch.ready(r, w, OK, true);
        c.advance(500);
        r.sessionEnd();
        w.sessionEnd();
        c.advance(120000);
        eq("session end cancels the pending re-attach ask and the watchdog", "3/0", h.asks.size() + "/" + c.due.size());
    }

    static void misc() {
        // turn-by-turn off: the ask still asks (Now Playing) but nothing is watched
        Clock c = new Clock();
        RgHost h = new RgHost(c);
        RgWatch w = new RgWatch(h, false);
        w.sessionUp();
        RgWatch.ready(null, w, OK, true);
        c.advance(120000);
        eq("tbt off: asked, never re-asked", "1/0", h.asks.size() + "/" + h.restarts.size());
        check("tbt off: not watching", !w.watching());
        // a re-attach planned (not yet asked) with a stray identification: not taken as comp 5's ready
        Clock c2 = new Clock();
        RgHost h2 = new RgHost(c2);
        RgWatch w2 = new RgWatch(h2, true);
        ReHost rh2 = new ReHost(c2);
        rh2.w = w2;
        Reattach r2 = new Reattach(rh2);
        w2.sessionUp();
        r2.sessionUp();
        RgWatch.ready(r2, w2, OK, true);
        c2.advance(1000);
        w2.linkDown("ACTION_IAP2_CONNECTFAIL");
        r2.lost("ACTION_IAP2_CONNECTFAIL / LimitMaxResend");
        RgWatch.ready(r2, w2, ID, false);   // noti 0 while the link is down and the re-attach only planned
        c2.advance(1500);
        eq("planned re-attach + noti 0 without comp 5 up: no ask", 1, h2.asks.size());
    }
}
