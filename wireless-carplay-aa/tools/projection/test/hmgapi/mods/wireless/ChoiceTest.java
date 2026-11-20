package hmgapi.mods.wireless;

import android.os.SystemClock;
import android.provider.Settings;

/**
 * The projection manager's rules (Choice), as PpManager records them: which phone may the mod
 * start a session with, for two Android phones, two iPhones, and one of each, and switching.
 */
public final class ChoiceTest {
    static int fails;
    static final String PIXEL = "AA:AA:AA:AA:AA:01", GALAXY = "AA:AA:AA:AA:AA:02", IPHONE = "CC:CC:CC:CC:CC:01", IPAD = "CC:CC:CC:CC:CC:02";

    static void check(String what, Object want, Object got) {
        boolean ok = want.equals(got);
        System.out.println((ok ? "ok    " : "FAIL  ") + what + (ok ? "" : ": wanted [" + want + "], got [" + got + "]"));
        if (!ok) fails++;
    }

    static void reset() {
        Settings.Global.values.clear();
        SystemClock.now = 100000;
    }

    /** what PpManager does for each of Phone Projection's calls */
    static void connect(int type, String addr, boolean wireless) {
        Choice.putChoice(null, type, addr);
        if (wireless) Choice.putState(null, "starting", type, addr);
    }

    static void connected(int type, String addr) {
        Choice.putChoice(null, type, addr);
        Choice.putState(null, "on", type, addr);
    }

    static void disconnected(int type, String addr) {
        Choice.putState(null, "off", type, addr);
    }

    public static void main(String[] a) {
        int CP = Choice.CARPLAY, AA = Choice.ANDROID_AUTO;

        reset();
        check("no choice yet: the first phone may start", true, Choice.allowed(null, AA, PIXEL));

        // two Android phones
        reset();
        connected(AA, PIXEL);
        disconnected(AA, PIXEL);   // the car off: the session ends
        check("two Androids: the last one is asked back", true, Choice.allowed(null, AA, PIXEL));
        check("... the other isn't", false, Choice.allowed(null, AA, GALAXY));
        connect(AA, GALAXY, true);   // the car's own reconnect starts the other one
        check("... while the car's own reconnect starts one: nobody else", false, Choice.allowed(null, AA, PIXEL));
        connected(AA, GALAXY);
        check("... and the one it started is the chosen phone now", false, Choice.allowed(null, AA, PIXEL));
        disconnected(AA, GALAXY);
        check("... and the one asked back next time", true, Choice.allowed(null, AA, GALAXY));

        // two iPhones
        reset();
        connected(CP, IPHONE);
        disconnected(CP, IPHONE);
        check("two iPhones: the bridge starts the last one", true, Choice.allowed(null, CP, IPHONE));
        check("... not the other", false, Choice.allowed(null, CP, IPAD));
        check("... CarPlay advertised again", true, Choice.allowedKind(null, CP));
        connected(CP, IPHONE);
        check("... nothing while one is on", false, Choice.allowedKind(null, CP));

        // one of each
        reset();
        connected(CP, IPHONE);
        disconnected(CP, IPHONE);
        check("an iPhone last: CarPlay comes back", true, Choice.allowedKind(null, CP));
        check("... Android Auto isn't asked back", false, Choice.allowed(null, AA, PIXEL));
        connected(AA, PIXEL);
        disconnected(AA, PIXEL);
        check("an Android last: Android Auto comes back", true, Choice.allowed(null, AA, PIXEL));
        check("... CarPlay isn't advertised", false, Choice.allowedKind(null, CP));
        check("... the iPhone's bridge doesn't start", false, Choice.allowed(null, CP, IPHONE));

        // switching live: the owner picks the other phone (Device Connections, Phone Projection)
        reset();
        connected(CP, IPHONE);
        connect(AA, PIXEL, true);   // the owner chose the Android: the choice moves at once
        disconnected(CP, IPHONE);   // the car ends CarPlay for it
        check("switching: the iPhone's bridge doesn't come back in", false, Choice.allowed(null, CP, IPHONE));
        connected(AA, PIXEL);
        check("... Android Auto on", false, Choice.allowedKind(null, CP));
        // Device Connections connects a phone over Bluetooth: its type follows when it comes up
        Choice.putChoice(null, 0, IPHONE);
        Choice.putState(null, "off", AA, PIXEL);
        check("Device Connections picked the iPhone: it may start", true, Choice.allowed(null, CP, IPHONE));
        check("... the Android isn't asked back", false, Choice.allowed(null, AA, PIXEL));

        // a start that never finishes, and a restart
        reset();
        connect(AA, PIXEL, true);
        check("starting: nothing else meanwhile", true, Choice.busy(null));
        SystemClock.now += 61000;
        check("starting for over a minute: taken as failed", false, Choice.busy(null));
        connected(AA, PIXEL);
        SystemClock.now = 5000;   // the head unit restarted: the clock since boot starts again
        check("on from before a restart: not on", false, Choice.busy(null));
        check("... the choice kept across it", true, Choice.allowed(null, AA, PIXEL));

        System.out.println(fails == 0 ? "all projection manager checks passed" : fails + " failed");
        System.exit(fails == 0 ? 0 : 1);
    }
}
