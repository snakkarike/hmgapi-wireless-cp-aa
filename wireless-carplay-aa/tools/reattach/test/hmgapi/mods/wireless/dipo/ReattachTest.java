package hmgapi.mods.wireless.dipo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * Host test of Reattach (the CarPlay channel's iAP2 re-attach, fake events on a fake clock)
 * and CpTx (the CarPlay channel's sender, real threads and a fake native send that can
 * block). A link failure, the backoff, the handover asked again, the new link up, the
 * session ready (only the server's real success counts), the ready timeout, route guidance
 * and Now Playing asked again; the session ending; the try limit. A sender blocked in the
 * library across a re-attach: abandoned, never waited for, a fresh sender for the new link,
 * and the late return discarded (generation guard).
 */
public final class ReattachTest {
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

    /** a fake Bridge: a clock, timers, and every handover / ask / log line recorded */
    static class Fake implements Reattach.Host {
        long t;
        final List<long[]> due = new ArrayList<long[]>();   // {when, id}
        final List<Runnable> runs = new ArrayList<Runnable>();
        final List<String> logs = new ArrayList<String>();
        final List<Integer> handovers = new ArrayList<Integer>();
        final List<Long> handoverAt = new ArrayList<Long>();
        int asks;

        @Override
        public long now() {
            return t;
        }

        @Override
        public Object schedule(Runnable r, long delayMs) {
            runs.add(r);
            long[] d = { t + delayMs, runs.size() - 1 };
            due.add(d);
            return d;
        }

        @Override
        public void cancel(Object token) {
            due.remove(token);
        }

        @Override
        public void handover(int attempt) {
            handovers.add(attempt);
            handoverAt.add(t);
        }

        @Override
        public void askAgain() {
            asks++;
        }

        @Override
        public void log(String m) {
            logs.add(m);
        }

        /** the clock forward by ms, running every timer that comes due, in order */
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

        int pending() {
            return due.size();
        }

        String last() {
            return logs.isEmpty() ? "" : logs.get(logs.size() - 1);
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
    }

    static final String CF = "ACTION_IAP2_CONNECTFAIL / LimitMaxResend";
    static final String OK = "ACTION_IAP2_CONNECTSUCCESS";

    public static void main(String[] a) throws Exception {
        reattach();
        cpTx();
        System.out.println(oks + " ok, " + (fail == 0 ? "0 failed" : "FAILED"));
        System.exit(fail);
    }

    static void reattach() {
        // 1. fail -> backoff -> handover -> link up -> ready (the server's success) -> RG/NP asked again
        Fake h = new Fake();
        Reattach r = new Reattach(h);
        r.lost("before any session");
        eq("no session: a failure does nothing", 0, h.pending());
        r.sessionUp();
        r.connected();   // the first handover's connect is not a re-attach
        r.ready(OK);
        eq("the session's first connect / ready asks nothing", 0, h.asks);
        eq("...and plans nothing", 0, h.pending());
        r.lost(CF);
        eq("failure logged with the attempt and its wait", "iAP2 comp 5 lost (" + CF + "): re-attach #1 in 2 s", h.last());
        check("busy while planned", r.busy());
        r.lost(CF);
        r.lost("iAP2 disconnected comp=5");
        eq("the server's repeated broadcasts plan only one re-attach", 1, h.pending());
        r.ready(OK);
        check("a success broadcast before the handover is asked does nothing", r.busy() && h.asks == 0);
        h.advance(1999);
        eq("nothing asked before 2 s", 0, h.handovers.size());
        h.advance(1);
        eq("handover asked again at 2 s", "[1]", h.handovers.toString());
        check("busy while waiting for the new session", r.busy());
        h.advance(60);
        r.connected();
        check("link up logged, waiting for identification", h.last().startsWith("re-attach #1: iAP2 comp 5 link up after 60 ms, waiting for identification"));
        check("link up alone is not success: still busy", r.busy());
        h.advance(940);
        r.lost("iAP2 disconnected comp=5");
        eq("the dead session's own disconnect right after asking is ignored", 1, h.handovers.size());
        check("...and logged as ignored", h.last().contains("ignored"));
        h.advance(2000);
        eq("link up but no ready: nothing asked", 0, h.asks);
        r.ready(OK);
        eq("re-attached only on the server's success", "re-attached (#1): iAP2 comp 5 ready (" + OK + ") 3000 ms after asking", h.last());
        check("not busy once ready", !r.busy());
        r.ready("identification accepted");
        eq("the second success signal (noti 0) changes nothing", "re-attached (#1): iAP2 comp 5 ready (" + OK + ") 3000 ms after asking", h.last());
        h.advance(999);
        eq("RG + NP not asked before 1 s", 0, h.asks);
        h.advance(1);
        eq("RG + NP asked again once", 1, h.asks);
        eq("asked-again logged", "route guidance + now playing asked again", h.last());
        eq("no timers left", 0, h.pending());
        h.advance(120000);
        eq("nothing more happens on a good link", 1, h.handovers.size());

        // 2. the 11-14 case: every re-attach's link comes up, iAP2 never gets ready, and the server reports nothing more
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost(CF);
        long[] want = { 2000, 5000, 15000, 30000, 30000 };
        long prev = 0;
        for (int i = 0; i < want.length; i++) {
            h.advance(want[i]);
            eq("re-attach #" + (i + 1) + " asked " + want[i] / 1000 + " s after the previous one failed", want[i], h.handoverAt.get(i) - prev);
            h.advance(60);
            r.connected();
            h.advance(7939);
            eq("#" + (i + 1) + ": no next attempt before 8 s", i + 1, h.handovers.size());
            h.advance(1);
            prev = h.t;
            check("#" + (i + 1) + ": 'link up but iAP2 not ready after 8 s' logged", h.logged("re-attach #" + (i + 1) + ": link up but iAP2 not ready after 8 s"));
        }
        check("the timeouts drive the backoff (waits in the log)", h.logged("not ready in 8 s): re-attach #3 in 15 s") && h.logged("not ready in 8 s): re-attach #4 in 30 s"));
        eq("never asked RG/NP on links that never got ready", 0, h.asks);

        // 3. a re-attach whose link never even comes up times out too
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost(CF);
        h.advance(2000);
        h.advance(8000);
        check("no link in 8 s: logged", h.logged("re-attach #1: iAP2 comp 5 not connected after 8 s"));
        check("...and the next one planned", h.logged("not connected in 8 s): re-attach #2 in 5 s"));
        h.advance(5000);
        eq("...and asked", 2, h.handovers.size());

        // 4. after a ready re-attach the backoff starts from 2 s again; a link that only came up doesn't reset it
        r.connected();
        r.ready("identification accepted");
        check("noti 0 counts as ready too", h.logged("re-attached (#2): iAP2 comp 5 ready (identification accepted)"));
        h.advance(60000);
        r.lost(CF);
        eq("backoff back to 2 s after a ready link", "iAP2 comp 5 lost (" + CF + "): re-attach #3 in 2 s", h.last());
        h.advance(2000);
        r.connected();
        h.advance(8000);   // up, never ready
        eq("link-up-only doesn't reset the backoff", "iAP2 comp 5 lost (not ready in 8 s): re-attach #4 in 5 s", h.last());

        // 5. session end clears everything; nothing after it
        h.advance(500);
        r.sessionEnd();
        eq("session end cancels the planned re-attach", 0, h.pending());
        check("session end logged", h.last().startsWith("CarPlay session ended: iAP2 re-attach state cleared (4 re-attaches"));
        check("not busy after the session", !r.busy() && !r.active());
        h.advance(60000);
        eq("no re-attach after the session ended", 3, h.handovers.size());
        r.lost(CF);
        r.connected();
        r.ready(OK);
        h.advance(60000);
        eq("failures after the session end do nothing", 3, h.handovers.size());
        eq("...and ask nothing more (the one ask is from the ready re-attach in 4)", 1, h.asks);

        // 6. session ends while waiting for the new session, and while the ask is pending
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost("x");
        h.advance(2000);
        r.sessionEnd();
        r.connected();
        r.ready(OK);
        h.advance(60000);
        eq("ready after the session ended asks nothing", 0, h.asks);
        r.sessionUp();
        eq("a new session starts with no tries", 0, r.tries());
        r.lost("x");
        h.advance(2000);
        r.connected();
        r.ready(OK);
        h.advance(500);
        r.sessionEnd();
        h.advance(10000);
        eq("session end before the ask: not asked", 0, h.asks);

        // 7. the link fails again before the ask: the ask is dropped, the next re-attach planned
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost("x");
        h.advance(2000);
        h.advance(6000);
        r.connected();
        r.ready(OK);
        h.advance(500);
        r.lost("y");
        h.advance(2000);
        eq("re-attach #2 asked 2 s later", 2, h.handovers.size());
        eq("the dropped ask never ran", 0, h.asks);

        // 8. a failure between grace (5 s) and the ready timeout counts at once
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost("x");
        h.advance(2000);
        r.connected();
        h.advance(5100);
        r.lost(CF);
        eq("CONNECTFAIL 5.1 s after asking: next re-attach planned", "iAP2 comp 5 lost (" + CF + "): re-attach #2 in 5 s", h.last());
        h.advance(4000);
        eq("the old ready timeout doesn't fire on top (generation)", 0, h.count("not ready after"));

        // 9. at most 10 re-attaches a session (driven by the ready timeout alone)
        h = new Fake();
        r = new Reattach(h);
        r.sessionUp();
        r.lost(CF);
        h.advance(1000000);
        eq("10 re-attaches, no more", Reattach.MAX_TRIES, h.handovers.size());
        eq("giving up is logged once", 1, h.count("no more re-attaches this CarPlay session (10 tried)"));
        eq("no timers left after giving up", 0, h.pending());
        r.lost(CF);
        eq("...still once after more failures", 1, h.count("no more re-attaches this CarPlay session (10 tried)"));
        r.sessionEnd();
        r.sessionUp();
        r.lost("x");
        h.advance(2000);
        eq("the next CarPlay session may re-attach again", Reattach.MAX_TRIES + 1, h.handovers.size());
    }

    // ---- CpTx: real threads ----

    static final class TxHost implements CpTx.Host {
        final List<String> logs = Collections.synchronizedList(new ArrayList<String>());

        @Override
        public long now() {
            return System.nanoTime() / 1000000;
        }

        @Override
        public int tid() {
            return (int) Thread.currentThread().getId();
        }

        @Override
        public void log(String m) {
            logs.add(m);
        }

        boolean logged(String part) {
            synchronized (logs) {
                for (String l : logs) if (l.contains(part)) return true;
            }
            return false;
        }

        String find(String part) {
            synchronized (logs) {
                for (String l : logs) if (l.contains(part)) return l;
            }
            return null;
        }
    }

    /** the native send: records (thread, first byte); a packet whose first byte is 99 blocks until released */
    static final class Sink implements CpTx.Sink {
        final List<String> sent = Collections.synchronizedList(new ArrayList<String>());
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch inside = new CountDownLatch(1);
        volatile int concurrent, maxConcurrent;

        @Override
        public int send(Object cp, byte[] b, int len) throws Throwable {
            synchronized (this) {
                concurrent++;
                if (concurrent > maxConcurrent) maxConcurrent = concurrent;
            }
            try {
                if (b[0] == 99) {
                    inside.countDown();
                    release.await();
                }
                sent.add(Thread.currentThread().getName() + ":" + b[0] + ":" + cp);
                return 0;
            } finally {
                synchronized (this) {
                    concurrent--;
                }
            }
        }

        String all() {
            synchronized (sent) {
                return sent.toString();
            }
        }
    }

    interface Cond {
        boolean ok();
    }

    static boolean waitFor(Cond c) throws InterruptedException {
        for (int i = 0; i < 300; i++) {
            if (c.ok()) return true;
            Thread.sleep(10);
        }
        return c.ok();
    }

    static byte[] p(int v) {
        return new byte[] { (byte) v, 0, 0 };
    }

    /** a fake Bridge on the fake clock whose handover resets the sender, as Bridge.reattachCpClient does */
    static final class BridgeFake extends Fake {
        final CpTx tx;

        BridgeFake(CpTx tx) {
            this.tx = tx;
        }

        @Override
        public void handover(int attempt) {
            super.handover(attempt);
            tx.reset("re-attach #" + attempt, true);
        }
    }

    static void cpTx() throws Exception {
        // 10. normal sending, in order, on one thread
        TxHost th = new TxHost();
        final Sink s = new Sink();
        final CpTx tx = new CpTx(th, s, "cp-tx");
        for (int i = 1; i <= 5; i++) tx.send("L1", p(i));
        check("packets go out in order on tx thread 1", waitFor(new Cond() {
            public boolean ok() {
                return s.sent.size() == 5;
            }
        }) && s.all().equals("[cp-tx-1:1:L1, cp-tx-1:2:L1, cp-tx-1:3:L1, cp-tx-1:4:L1, cp-tx-1:5:L1]"));

        // 11. stuck sender across a re-attach (the 11-14 case)
        tx.send("L1", p(99));   // blocks in the library
        check("the send is inside the library", s.inside.await(3, java.util.concurrent.TimeUnit.SECONDS));
        for (int i = 10; i < 20; i++) tx.send("L1", p(i));   // the dead link piles up behind it
        check("blocked time is seen (watchdog input)", waitFor(new Cond() {
            public boolean ok() {
                return tx.blockedMs() >= 50;
            }
        }));
        check("describe names the blocked thread and the queue", tx.describe().startsWith("3 bytes, thread cp-tx-1 tid ") && tx.describe().endsWith("10 queued behind it"));
        BridgeFake bh = new BridgeFake(tx);
        Reattach r = new Reattach(bh);
        r.sessionUp();
        r.lost(CF);
        long t0 = System.nanoTime();
        bh.advance(2000);   // re-attach #1: handover -> tx.reset, must not wait for the blocked call
        long tookMs = (System.nanoTime() - t0) / 1000000;
        check("the re-attach never waits for the blocked call (" + tookMs + " ms)", tookMs < 500);
        String ab = th.find("tx thread 1 abandoned");
        check("'tx thread 1 abandoned (blocked M ms, 10 queued sends dropped; re-attach #1), new tx thread 2' logged",
                ab != null && ab.matches("tx thread 1 abandoned \\(blocked \\d+ ms, 10 queued sends dropped; re-attach #1\\), new tx thread 2"));
        eq("one abandoned thread still in the library", 1, tx.stuck());
        eq("the new link's sender is thread 2", 2, tx.threadNumber());
        for (int i = 30; i < 33; i++) tx.send("L2", p(i));   // the new link's link sync / identification
        check("the new link's packets go out at once on tx thread 2 while thread 1 is still blocked", waitFor(new Cond() {
            public boolean ok() {
                return s.all().contains("cp-tx-2:32:L2");
            }
        }) && s.all().contains("cp-tx-2:30:L2, cp-tx-2:31:L2, cp-tx-2:32:L2"));
        check("...and none of the dead link's queued packets went out", !s.all().contains(":10:L1") && !s.all().contains(":19:L1"));
        eq("the new link isn't blocked", 0L, tx.blockedMs());
        r.connected();
        r.ready(OK);
        check("re-attach #1 counts once the server is ready", bh.logged("re-attached (#1)"));

        // 12. generation guard: the old call returns late
        tx.send("L2", p(40));
        s.release.countDown();
        check("the late return is logged as discarded", waitFor(new Cond() {
            public boolean ok() {
                return th.logged("tx thread 1 (abandoned) returned after");
            }
        }) && th.find("tx thread 1 (abandoned) returned after").endsWith("rc=0: result discarded, thread ends"));
        check("the old thread sent nothing after its late return", waitFor(new Cond() {
            public boolean ok() {
                return s.all().contains("cp-tx-2:40:L2");
            }
        }) && !s.all().matches(".*cp-tx-1:(1\\d|3\\d|40):.*"));
        int before = s.sent.size();
        check("the old thread ended", waitFor(new Cond() {
            public boolean ok() {
                return tx.stuck() == 0;
            }
        }));
        check("only the blocked packet itself left the old thread after the reset", s.all().contains("cp-tx-1:99:L1") && before == s.sent.size());
        check("the slow send is not reported as the new link's", !th.logged("send blocked") || !th.find("send blocked").contains("cp-tx-2"));
        check("thread cp-tx-1 is gone", waitFor(new Cond() {
            public boolean ok() {
                for (Thread t : Thread.getAllStackTraces().keySet()) if (t.getName().equals("cp-tx-1") && t.isAlive()) return false;
                return true;
            }
        }));

        // 13. an idle sender reset (session end): stopped, its thread ends, the next session starts a fresh one lazily
        tx.reset("CarPlay session ended", false);
        check("idle sender stopped and logged", th.logged("tx thread 2 stopped (0 queued sends dropped; CarPlay session ended)"));
        eq("no sender until the next link sends", 0, tx.threadNumber());
        tx.send("L3", p(50));
        check("the next session's packet goes out on tx thread 3", waitFor(new Cond() {
            public boolean ok() {
                return s.all().contains("cp-tx-3:50:L3");
            }
        }));

        // 14. queue bound while blocked
        TxHost th2 = new TxHost();
        Sink s2 = new Sink();
        CpTx tx2 = new CpTx(th2, s2, "q");
        tx2.send("A", p(99));
        s2.inside.await(3, java.util.concurrent.TimeUnit.SECONDS);
        for (int i = 0; i < CpTx.MAX_QUEUE + 10; i++) tx2.send("A", p(1));
        eq("the queue stops at MAX_QUEUE", CpTx.MAX_QUEUE, tx2.queued());
        check("the overflow is logged once (not per packet)", th2.logged("send queue full") && count(th2, "send queue full") == 1);
        tx2.reset("re-attach #1", true);
        eq("the reset drops the whole queue", 0, tx2.queued());
        s2.release.countDown();

        // 15. many re-attaches with a sender stuck each time: one stuck thread per abandoned link, all reported, none waited for
        TxHost th3 = new TxHost();
        final List<CountDownLatch> gates = Collections.synchronizedList(new ArrayList<CountDownLatch>());
        CpTx tx3 = new CpTx(th3, new CpTx.Sink() {
            public int send(Object cp, byte[] b, int len) throws Throwable {
                CountDownLatch g = new CountDownLatch(1);
                gates.add(g);
                g.await();
                return 0;
            }
        }, "m");
        for (int k = 0; k < 3; k++) {
            tx3.send("x", p(1));
            final int want = k + 1;
            waitFor(new Cond() {
                public boolean ok() {
                    return gates.size() == want;
                }
            });
            Thread.sleep(20);
            tx3.reset("re-attach #" + (k + 1), true);
        }
        eq("three abandoned threads still blocked", 3, tx3.stuck());
        check("the count is in the log", th3.logged("[3 abandoned threads still blocked in the library]"));
        for (CountDownLatch g : gates) g.countDown();
        check("all of them end once the library lets go", waitFor(new Cond() {
            public boolean ok() {
                return tx3.stuck() == 0;
            }
        }));
    }

    static int count(TxHost h, String part) {
        int n = 0;
        synchronized (h.logs) {
            for (String l : h.logs) if (l.contains(part)) n++;
        }
        return n;
    }
}
