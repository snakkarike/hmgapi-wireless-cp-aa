package hmgapi.mods.wireless.dipo;

/**
 * The "carplay_log" setting: CarPlay's own log, for finding out why a session ends. The CarPlay
 * stack logs through LogUtils in the head unit's libCarPlayCoreUtils.so, to logcat as
 * "TccCarPlay"; on some builds (seen on a US K5, 260507) its categories log nothing.
 * LogControl(".*:level=info") raises every category to info, as a US EV6 logged in 2026-10.
 * LogUtils keeps the action for categories created later, so once at DiPO's start is enough;
 * it is sent again when a session is created, in case the stack set its own levels meanwhile.
 *
 * Off by default: the lines name the phone (model, name, Bluetooth address). The problem log's
 * tag list (TccCarPlay) keeps them and its scrubber takes the addresses out.
 */
final class CarPlayLog {
    private CarPlayLog() {}

    static final String LIB = "/system/lib/libcarplaylog.so";
    static final String COMMAND = ".*:level=info";

    static volatile boolean on, loaded, failed;

    static native int control(String command);

    /** DiPO's start and each new session: raise CarPlay's log levels (logs the first result only) */
    static void apply(String when) {
        if (!on || failed) return;
        try {
            if (!loaded) {
                System.load(LIB);
                loaded = true;
            }
            int r = control(COMMAND);
            if (r != 0) {
                failed = true;
                Dipo.log("CarPlay log: LogControl failed (" + r + ", " + why(r) + "), staying as it was");
            } else if ("start".equals(when)) {
                Dipo.log("CarPlay log: on (" + COMMAND + "), as TccCarPlay in logcat");
            }
        } catch (Throwable t) {
            failed = true;
            Dipo.error("CarPlay log: can't switch it on", t);
        }
    }

    static String why(int r) {
        switch (r) {
            case -1: return "no command";
            case -2: return "libCarPlayCoreUtils.so not found";
            case -3: return "no LogControl in libCarPlayCoreUtils.so";
            case -4: return "command unreadable";
            default: return "LogUtils error";
        }
    }
}
