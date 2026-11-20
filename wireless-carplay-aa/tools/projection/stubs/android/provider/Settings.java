package android.provider;

/** Stand-in: Settings.Global as a map (the content resolver isn't used). */
public final class Settings {
    public static final class Global {
        public static final java.util.Map<String, String> values = new java.util.HashMap<String, String>();
        public static String getString(android.content.ContentResolver cr, String key) { return values.get(key); }
        public static boolean putString(android.content.ContentResolver cr, String key, String value) { values.put(key, value); return true; }
    }
}
