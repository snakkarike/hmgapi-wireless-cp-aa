package hmgapi.mods.wireless.dipo;

/**
 * Re-attach of the CarPlay channel's iAP2 session (component 5) when its link fails while
 * the CarPlay session is still up.
 *
 * The iAP2 server gives up on a link whose packets go unacknowledged (LimitMaxResend,
 * ACTION_IAP2_CONNECTFAIL, then "eioErrState true" / mInitStatus 0x403), and it never
 * starts that session again by itself: turn-by-turn and Now Playing then stay off until the
 * CarPlay session ends. The bridge asks for the handover again (BT_ATTACHED for component
 * 5: the server stops the dead session and starts a new one) with a backoff of 2 s, 5 s,
 * 15 s, then every 30 s, at most MAX_TRIES times per CarPlay session.
 *
 * A re-attach only counts as good once the server says the new session is ready: its
 * ACTION_IAP2_CONNECTSUCCESS for component 5 (sent when identification and authentication
 * are both done, mInitStatus == 3), or the identification-accepted notification that goes
 * to the listeners at the same moment. "iAP2 connected comp=5" is only the link coming up
 * (taking that as success failed on an Ioniq 6: the link came up three times and
 * identification never finished). A re-attach that isn't ready READY_TIMEOUT_MS after it
 * was asked for counts as failed, and the backoff goes on. Once ready, route guidance and
 * Now Playing are asked for again.
 *
 * No Android here (host-tested): Bridge is the host. Every method is synchronized; the
 * host runs the timers on one thread.
 */
final class Reattach {
    interface Host {
        /** a monotonic clock, ms */
        long now();

        /** run r after delayMs; returns a token for cancel */
        Object schedule(Runnable r, long delayMs);

        void cancel(Object token);

        /** reset the CarPlay channel's client state (and its sender) and ask for the handover (component 5) again */
        void handover(int attempt);

        /** ask the phone for route guidance and Now Playing again */
        void askAgain();

        void log(String m);
    }

    static final long[] BACKOFF_MS = { 2000, 5000, 15000 };
    static final long LATER_MS = 30000;
    static final int MAX_TRIES = 10;
    /** a re-attach whose session isn't ready (identified + authenticated) this long after asking counts as failed */
    static final long READY_TIMEOUT_MS = 8000;
    /** route guidance and Now Playing asked again this long after the new session is ready */
    static final long ASK_DELAY_MS = 1000;
    /** failures reported this soon after asking (before the new session is ready) are the dead session's own teardown */
    static final long GRACE_MS = 5000;

    private final Host host;
    private boolean session, scheduled, waiting, linkUp, gaveUp;
    /** re-attaches asked for this CarPlay session, and failures since the last ready session (the backoff step) */
    private int tries, streak;
    /** bumped for every timer, so a stale one does nothing */
    private int gen;
    private Object timer;
    private long askedAt;

    Reattach(Host host) {
        this.host = host;
    }

    /** the CarPlay channel's iAP2 session was handed over (once per CarPlay session) */
    synchronized void sessionUp() {
        if (session) return;
        session = true;
        tries = 0;
        streak = 0;
        scheduled = false;
        waiting = false;
        linkUp = false;
        gaveUp = false;
    }

    /** the CarPlay session ended: nothing more to re-attach */
    synchronized void sessionEnd() {
        if (!session) return;
        session = false;
        cancelTimer();
        gen++;
        if (scheduled || waiting || tries > 0) host.log("CarPlay session ended: iAP2 re-attach state cleared (" + tries + " re-attach" + (tries == 1 ? "" : "es") + " this session)");
        scheduled = false;
        waiting = false;
        linkUp = false;
    }

    synchronized boolean active() {
        return session;
    }

    /** a re-attach is planned or under way (its own disconnect of the dead session is expected) */
    synchronized boolean busy() {
        return scheduled || waiting;
    }

    synchronized int tries() {
        return tries;
    }

    /** component 5's iAP2 link failed while the CarPlay session is up */
    synchronized void lost(String why) {
        if (!session || scheduled) return;
        if (waiting && host.now() - askedAt < GRACE_MS) {
            host.log("iAP2 comp 5 lost (" + why + ") right after re-attach #" + tries + " was asked for: ignored (the old session's teardown)");
            return;
        }
        fail(why);
    }

    /** the current link (or the re-attach under way) failed: plan the next re-attach, or give up */
    private void fail(String why) {
        waiting = false;
        linkUp = false;
        cancelTimer();
        gen++;
        if (tries >= MAX_TRIES) {
            if (!gaveUp) host.log("iAP2 comp 5 lost (" + why + "): no more re-attaches this CarPlay session (" + MAX_TRIES + " tried)");
            gaveUp = true;
            return;
        }
        tries++;
        long d = streak < BACKOFF_MS.length ? BACKOFF_MS[streak] : LATER_MS;
        streak++;
        scheduled = true;
        final int g = ++gen;
        host.log("iAP2 comp 5 lost (" + why + "): re-attach #" + tries + " in " + d / 1000 + " s");
        timer = host.schedule(new Runnable() {
            @Override
            public void run() {
                fire(g);
            }
        }, d);
    }

    private synchronized void fire(int g) {
        if (!session || !scheduled || g != gen) return;
        scheduled = false;
        waiting = true;
        linkUp = false;
        final int g2 = ++gen;
        askedAt = host.now();
        host.handover(tries);
        timer = host.schedule(new Runnable() {
            @Override
            public void run() {
                timeout(g2);
            }
        }, READY_TIMEOUT_MS);
    }

    private synchronized void timeout(int g) {
        if (!session || !waiting || g != gen) return;
        timer = null;
        String m = linkUp ? "re-attach #" + tries + ": link up but iAP2 not ready after " + READY_TIMEOUT_MS / 1000 + " s"
                : "re-attach #" + tries + ": iAP2 comp 5 not connected after " + READY_TIMEOUT_MS / 1000 + " s";
        host.log(m);
        fail(linkUp ? "not ready in " + READY_TIMEOUT_MS / 1000 + " s" : "not connected in " + READY_TIMEOUT_MS / 1000 + " s");
    }

    /** component 5's iAP2 link is up ("iAP2 connected comp=5"): not yet a usable session */
    synchronized void connected() {
        if (!session || !waiting || linkUp) return;
        linkUp = true;
        host.log("re-attach #" + tries + ": iAP2 comp 5 link up after " + (host.now() - askedAt) + " ms, waiting for identification");
    }

    /**
     * the server's word that component 5's session is ready (ACTION_IAP2_CONNECTSUCCESS, or identification accepted);
     * true when it was a re-attach's ready (whose ask this schedules), false otherwise (RgWatch asks then)
     */
    synchronized boolean ready(String how) {
        if (!session || !waiting) return false;
        waiting = false;
        linkUp = false;
        streak = 0;
        cancelTimer();
        host.log("re-attached (#" + tries + "): iAP2 comp 5 ready (" + how + ") " + (host.now() - askedAt) + " ms after asking");
        final int g = ++gen;
        timer = host.schedule(new Runnable() {
            @Override
            public void run() {
                ask(g);
            }
        }, ASK_DELAY_MS);
        return true;
    }

    private synchronized void ask(int g) {
        if (!session || g != gen) return;
        timer = null;
        host.askAgain();
        host.log("route guidance + now playing asked again");
    }

    private void cancelTimer() {
        if (timer != null) host.cancel(timer);
        timer = null;
    }
}
