package hmgapi.mods.wireless.dipo;

import android.content.ContentResolver;
import android.os.SystemClock;
import android.provider.Settings;

/**
 * Which phone projects: the owner's choice and what's on now, kept by the projection manager
 * (PpManager, in system_server, from Phone Projection's service) in two Settings.Global values
 * every process of this mod reads (GPM, DiPO, the CarPlay bridge: all the system user):
 *
 *   hmgapi_projection_choice  "<type> <address>"   type 1 CarPlay, 2 Android Auto, 0 not known yet
 *   hmgapi_projection_state   "<starting|on|off> <type> <address> <elapsedRealtime ms>"
 *
 * The choice is the phone the owner (or the car) last connected for projection, from any
 * screen (Device Connections, Phone Projection's pages, auto connection), or the one whose
 * session last came up. The mod's own automatic starts (the CarPlay bridge, asking a phone
 * back when you park or the hotspot is back) go through allowed() first: only the chosen
 * phone, and only while nothing is starting or on, so two phones never fight over the car.
 * DiPO's part's copy of hmgapi.mods.wireless.Choice (a part can't reach the mod's main classes);
 * keep the two the same (tools/aa-support/test.sh compares them).
 */
final class Choice {
    private Choice() {}

    public static final String CHOICE = "hmgapi_projection_choice", STATE = "hmgapi_projection_state";
    public static final int CARPLAY = 1, ANDROID_AUTO = 2;
    /** a session starting longer than this is taken as failed */
    static final long STARTING_MS = 60000;

    /** may the mod start a session of this type with this phone now? */
    public static boolean allowed(ContentResolver cr, int type, String address) {
        if (busy(cr)) return false;
        String[] c = split(Settings.Global.getString(cr, CHOICE), 2);
        if (c == null) return true;   // no choice yet: the first phone
        if (!c[1].equalsIgnoreCase(address)) return false;
        return "0".equals(c[0]) || String.valueOf(type).equals(c[0]);
    }

    /** may the mod offer this type of session to whichever phone (CarPlay's advertising)? */
    public static boolean allowedKind(ContentResolver cr, int type) {
        if (busy(cr)) return false;
        String[] c = split(Settings.Global.getString(cr, CHOICE), 2);
        return c == null || "0".equals(c[0]) || String.valueOf(type).equals(c[0]);
    }

    /** a session starting (in the last minute) or on, of any type */
    public static boolean busy(ContentResolver cr) {
        String[] s = split(Settings.Global.getString(cr, STATE), 4);
        if (s == null || "off".equals(s[0])) return false;
        long at;
        try {
            at = Long.parseLong(s[3]);
        } catch (NumberFormatException e) {
            return false;
        }
        long now = SystemClock.elapsedRealtime();
        if (at > now) return false;   // from before a restart
        return "on".equals(s[0]) || now - at < STARTING_MS;
    }

    /** "1 AA:BB:..." for the owner to read, or null */
    public static String describe(ContentResolver cr) {
        String[] c = split(Settings.Global.getString(cr, CHOICE), 2);
        if (c == null) return null;
        return ("1".equals(c[0]) ? "CarPlay" : "2".equals(c[0]) ? "Android Auto" : "projection") + " on the phone " + c[1];
    }

    static void putChoice(ContentResolver cr, int type, String address) {
        Settings.Global.putString(cr, CHOICE, type + " " + address.toUpperCase());
    }

    static void putState(ContentResolver cr, String state, int type, String address) {
        Settings.Global.putString(cr, STATE, state + " " + type + " " + (address == null ? "-" : address.toUpperCase()) + " " + SystemClock.elapsedRealtime());
    }

    static String[] state(ContentResolver cr) {
        return split(Settings.Global.getString(cr, STATE), 4);
    }

    static String[] split(String v, int n) {
        if (v == null) return null;
        String[] w = v.trim().split(" ");
        return w.length >= n ? w : null;
    }
}
