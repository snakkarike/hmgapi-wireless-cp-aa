package hmgapi.mods.wireless.dipo;

import java.util.concurrent.LinkedBlockingQueue;

/**
 * The CarPlay channel's iAP2 sender: what the iAP2 server sends for component 5 goes out
 * through the CarPlay library's native send (TccCarPlay_iAPSendMessage), one packet at a
 * time, on a thread of its own.
 *
 * That native call can block for 20 s (seen on an Ioniq 6, during heavy GPU work in DiPO). With
 * one thread sending for every link, the re-attached link's link-sync and
 * identification packets waited behind the dead link's blocked call, and the new link
 * never came up. Now every link has its own sender (a generation: thread + queue). A
 * reset (re-attach, CarPlay session end) abandons the current one: its queue is dropped,
 * and a call still blocked in the library is never waited for; when it returns, its result
 * is discarded and the thread ends without sending anything more (it is no longer the
 * current generation). The next link gets a fresh thread and queue.
 *
 * A packet already inside the blocked call can't be taken back: if the library sends it
 * late, the phone sees one stale packet of the old link (iAP2 drops it by sequence number).
 *
 * No Android here (host-tested): the host gives the clock, the native thread id and the log.
 */
final class CpTx {
    interface Host {
        long now();

        /** this thread's kernel tid (android.os.Process.myTid on the car) */
        int tid();

        void log(String m);
    }

    interface Sink {
        /** the native send; 0 = sent */
        int send(Object cp, byte[] b, int len) throws Throwable;
    }

    /** sends a dead link's thread may still have queued: more than this and new ones are dropped (the link is dead anyway) */
    static final int MAX_QUEUE = 2048;
    /** a send blocked longer than this is logged when it returns */
    static final long SLOW_MS = 2000;

    private static final Object[] STOP = new Object[0];

    private final Host host;
    private final Sink sink;
    private final String name;
    private Worker cur;
    private int threads, gen;
    private volatile int sent, failed, overflow;
    /** abandoned threads still inside a native call */
    private int stuck;

    CpTx(Host host, Sink sink, String name) {
        this.host = host;
        this.sink = sink;
        this.name = name;
    }

    /** one link's sender */
    final class Worker implements Runnable {
        final int n, gen;
        final LinkedBlockingQueue<Object[]> q = new LinkedBlockingQueue<Object[]>();
        final Thread thread;
        volatile boolean dead;
        /** abandoned while inside the library (counted in stuck) */
        boolean abandoned;
        volatile long since;
        volatile int len, tid;

        Worker(int n, int gen) {
            this.n = n;
            this.gen = gen;
            thread = new Thread(this, name + "-" + n);
            thread.setDaemon(true);
        }

        @Override
        public void run() {
            tid = host.tid();
            while (true) {
                Object[] it;
                try {
                    it = q.take();
                } catch (InterruptedException e) {
                    if (dead) return;
                    continue;
                }
                if (it == STOP) return;
                byte[] b = (byte[]) it[1];
                long t0;
                // the generation guard: nothing more goes into the library once this sender is abandoned
                synchronized (CpTx.this) {
                    if (dead || cur != this) return;
                    t0 = host.now();
                    len = b.length;
                    since = t0;
                }
                int rc = -1;
                Throwable err = null;
                try {
                    rc = sink.send(it[0], b, b.length);
                } catch (Throwable t) {
                    err = t;
                }
                long ms = host.now() - t0;
                synchronized (CpTx.this) {
                    since = 0;
                    if (dead || cur != this) {
                        if (abandoned) stuck--;
                        host.log("tx thread " + n + " (abandoned) returned after " + ms + " ms" + (err != null ? " (" + err + ")" : " rc=" + rc)
                                + ": result discarded, thread ends");
                        return;
                    }
                }
                if (err != null) {
                    host.log("CarPlay-channel iAP2 send threw " + err);
                } else if (rc == 0) {
                    int s = ++sent;
                    if (s <= 3 || s % 200 == 0) host.log("CarPlay-channel iAP2 sent " + b.length + " bytes (#" + s + ")");
                } else {
                    int f = ++failed;
                    if (f <= 5 || f % 50 == 0) host.log("CarPlay-channel iAP2 send failed rc=" + rc + " len=" + b.length);
                }
                if (ms > SLOW_MS) {
                    host.log("CarPlay-channel iAP2 send blocked " + ms + " ms (" + b.length + " bytes, thread " + thread.getName() + " tid " + tid + ", " + q.size()
                            + " queued behind it)");
                }
            }
        }
    }

    private Worker start() {
        Worker w = new Worker(++threads, ++gen);
        cur = w;
        w.thread.start();
        return w;
    }

    /** a packet for the current link */
    void send(Object cp, byte[] b) {
        Worker w;
        synchronized (this) {
            w = cur != null ? cur : start();
            if (w.q.size() >= MAX_QUEUE) {
                int o = ++overflow;
                if (o == 1 || o % 500 == 0) host.log("CarPlay-channel iAP2 send queue full (" + MAX_QUEUE + ", tx thread " + w.n + "): packet dropped (" + o + " so far)");
                return;
            }
            w.q.add(new Object[] { cp, b });
        }
    }

    /**
     * the current link is gone (why: "re-attach #n", "session end"): its sender is abandoned
     * and its queue dropped, never waited for; with fresh, the next link's sender starts now
     */
    synchronized void reset(String why, boolean fresh) {
        Worker w = cur;
        cur = null;
        String next = "";
        if (fresh) next = ", new tx thread " + start().n;
        if (w == null) {
            if (fresh) host.log("tx (" + why + "): new tx thread " + cur.n);
            return;
        }
        w.dead = true;
        int dropped = w.q.size();
        w.q.clear();
        long since = w.since;
        if (since != 0) {
            w.abandoned = true;
            stuck++;
            host.log("tx thread " + w.n + " abandoned (blocked " + (host.now() - since) + " ms, " + dropped + " queued sends dropped; " + why + ")" + next
                    + (stuck > 1 ? " [" + stuck + " abandoned threads still blocked in the library]" : ""));
        } else {
            w.q.add(STOP);
            host.log("tx thread " + w.n + " stopped (" + dropped + " queued sends dropped; " + why + ")" + next);
        }
    }

    /** how long the current link's send has been in the library (0: none) */
    synchronized long blockedMs() {
        Worker w = cur;
        long s = w == null ? 0 : w.since;
        return s == 0 ? 0 : host.now() - s;
    }

    /** the current send's start (0: none): a key for "reported once" */
    synchronized long blockedSince() {
        Worker w = cur;
        return w == null ? 0 : w.since;
    }

    synchronized String describe() {
        Worker w = cur;
        if (w == null) return "no tx thread";
        return w.len + " bytes, thread " + w.thread.getName() + " tid " + w.tid + ", " + w.q.size() + " queued behind it";
    }

    synchronized int threadNumber() {
        return cur == null ? 0 : cur.n;
    }

    synchronized int queued() {
        return cur == null ? 0 : cur.q.size();
    }

    synchronized int stuck() {
        return stuck;
    }
}
