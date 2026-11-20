package hmgapi.mods.wireless.dipo;

/**
 * Makes sure the phone's route guidance (turn-by-turn) actually flows once wireless CarPlay
 * is up.
 *
 * On wireless, DiPO asks for route guidance (Iap2SvcStartRouteGuidanceUpdate) while iAP2 is
 * still on the Bluetooth session (component 4), about 3 s before the CarPlay channel's
 * session (component 5) is ready; the bridge then stops the Bluetooth session and the
 * subscription is lost (seen in an Ioniq 6 log). So:
 *
 * 1. After every comp-5 ready (the server's ACTION_IAP2_CONNECTSUCCESS or identification
 *    accepted) while the bridge owns the CarPlay session, route guidance (Stop, 200 ms,
 *    Start) and Now Playing are asked for again ASK_DELAY_MS later: the first handover here,
 *    a re-attach through Reattach's own ask (which comes back through ask()). One ask per
 *    ready: further ready signals for the same link do nothing.
 * 2. A watchdog: after each Start of ours, if no route guidance
 *    update (notification 120 or 121) has arrived, it is asked for again after 3 s, then 7,
 *    15 and 30 s more (at 3, 10, 25 and 55 s), at most 4 times.
 *    The first update stops it (and is logged, also after the last ask); a new Start restarts the schedule; DiPO's own Stop ends it.
 *    While comp 5 is down (owned session, not ready) it never asks: it stops and the next
 *    ready asks instead.
 *
 * DiPO's own Start isn't observed (the car's hook map has only the proxy's Stop), so the
 * watchdog follows HMG-API's Starts; (1) is what covers DiPO's early one.
 *
 * No Android here (host-tested). Every method is synchronized; the host runs the timers on
 * one thread and its asks on other threads (never blocking here).
 */
final class RgWatch {
    interface Host {
        /** a monotonic clock, ms */
        long now();

        /** run r after delayMs; returns a token for cancel */
        Object schedule(Runnable r, long delayMs);

        void cancel(Object token);

        /** route guidance (Stop, Start) and Now Playing asked again (Dipo.askAgain) */
        void askAgain();

        /** route guidance only: Stop, 200 ms, Start (Dipo.restartRouteGuidance) */
        void restart();

        void log(String m);
    }

    /** the ask after comp 5 is ready */
    static final long ASK_DELAY_MS = 1000;
    /** the watchdog's waits (3, 7, 15 and 30 s sleeps): checks at 3, 10, 25 and 55 s after a Start */
    static final long[] CHECK_MS = { 3000, 7000, 15000, 30000 };

    private final Host host;
    /** with turn-by-turn off there's no route guidance to watch (asks still ask Now Playing) */
    private final boolean watch;
    /** the bridge owns the CarPlay channel's session; its comp-5 session is ready; it was ready before (a later ready is a re-attach) */
    private boolean owned, ready, everReady;
    private Object askTimer, checkTimer;
    private int askGen, watchGen;
    private boolean watching;
    private long startedAt;
    private int step, reasks;

    RgWatch(Host host, boolean watch) {
        this.host = host;
        this.watch = watch;
    }

    /**
     * comp 5 is ready (Bridge, on the server's success or identification accepted): Reattach
     * first (a re-attach under way asks by itself, 1 s later), then this, which asks for
     * every other ready; linkUp: the signal is surely comp 5's (its link is up)
     */
    static void ready(Reattach r, RgWatch w, String how, boolean linkUp) {
        boolean reattach = r != null && r.ready(how);
        if (w != null && (reattach || linkUp)) w.ready(!reattach);
    }

    /** the bridge handed iAP2 over to the CarPlay channel (a new CarPlay session) */
    synchronized void sessionUp() {
        cancelAsk();
        stopWatch();
        owned = true;
        ready = false;
        everReady = false;
    }

    /** the CarPlay session ended: nothing more to ask */
    synchronized void sessionEnd() {
        if (!owned) return;
        owned = false;
        ready = false;
        cancelAsk();
        if (watching) host.log("route guidance watchdog off (CarPlay session ended)");
        stopWatch();
    }

    /** comp 5's link failed, dropped or is being attached again */
    synchronized void linkDown(String why) {
        if (!owned) return;
        ready = false;
        if (askTimer != null) host.log("route guidance ask dropped: comp 5 down (" + why + ")");
        cancelAsk();
        if (watching) host.log("route guidance watchdog waits for comp 5 (" + why + ")");
        stopWatch();
    }

    synchronized boolean linkReady() {
        return !owned || ready;
    }

    /** comp 5 ready; ask: this schedules the ask (false: Reattach's re-attach asks) */
    synchronized void ready(boolean ask) {
        if (!owned || ready) return;   // one ask per ready
        ready = true;
        final String how = everReady ? "re-attach" : "first";
        everReady = true;
        if (!ask) return;
        cancelAsk();
        final int g = ++askGen;
        askTimer = host.schedule(new Runnable() {
            @Override
            public void run() {
                scheduledAsk(g, how);
            }
        }, ASK_DELAY_MS);
    }

    private synchronized void scheduledAsk(int g, String how) {
        if (g != askGen || askTimer == null) return;
        askTimer = null;
        ask(how);
    }

    /** route guidance + Now Playing asked again after a comp-5 ready ("first" here, "re-attach" from Reattach) */
    synchronized void ask(String how) {
        if (owned && !ready) {
            host.log("route guidance not asked (" + how + "): comp 5 isn't ready");
            return;
        }
        host.askAgain();
        host.log("route guidance asked after comp 5 ready (" + how + ")");
        arm();
    }

    /** route guidance restarted by HMG-API elsewhere (a source change): watch it */
    synchronized void started() {
        arm();
    }

    /** DiPO stopped route guidance itself (the car's navigation took turn-by-turn back): nothing to watch */
    synchronized void stopped() {
        if (watching) host.log("route guidance watchdog off (DiPO stopped route guidance)");
        stopWatch();
    }

    /** a route guidance update (120) or maneuver (121) reached HMG-API's listener */
    synchronized void update() {
        if (!watching) return;
        long ms = host.now() - startedAt;
        int n = reasks;
        stopWatch();
        host.log("route guidance flowing (first update " + ms + " ms after the ask)" + (n > 0 ? " after " + n + " re-ask" + (n == 1 ? "" : "s") : ""));
    }

    synchronized boolean watching() {
        return watching;
    }

    private void arm() {
        if (!watch) return;
        stopWatch();
        watching = true;
        startedAt = host.now();
        step = 0;
        reasks = 0;
        scheduleCheck();
    }

    private void scheduleCheck() {
        final int g = ++watchGen;
        checkTimer = host.schedule(new Runnable() {
            @Override
            public void run() {
                check(g);
            }
        }, CHECK_MS[step]);
    }

    private synchronized void check(int g) {
        if (!watching || g != watchGen) return;
        checkTimer = null;
        if (owned && !ready) {
            host.log("no route guidance yet, but comp 5 is down: waiting for it to be ready");
            stopWatch();
            return;
        }
        reasks++;
        step++;
        boolean last = step >= CHECK_MS.length;
        host.log("no route guidance yet, asking again (" + reasks + (last ? ", last" : "") + ")");
        host.restart();
        if (!last) scheduleCheck();   // after the last ask it only waits: the first update still logs "flowing"
    }

    private void stopWatch() {
        watching = false;
        watchGen++;
        if (checkTimer != null) host.cancel(checkTimer);
        checkTimer = null;
    }

    private void cancelAsk() {
        askGen++;
        if (askTimer != null) host.cancel(askTimer);
        askTimer = null;
    }
}
