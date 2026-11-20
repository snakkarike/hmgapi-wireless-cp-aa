package android.os;

/** Stand-in: a clock the test sets. */
public final class SystemClock {
    public static long now = 100000;
    public static long elapsedRealtime() { return now; }
}
