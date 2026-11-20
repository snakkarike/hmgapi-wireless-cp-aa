package hmgapi.hook;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Records observable app-loading effects; unexpected failures fail the host test. */
public final class ModContext {
    public final Map<String, Boolean> settings = new LinkedHashMap<String, Boolean>();
    public final Map<String, MethodHook> hooks = new LinkedHashMap<String, MethodHook>();
    public final List<String> natives = new ArrayList<String>();
    public final List<String> logs = new ArrayList<String>();
    public int cpStarts;
    public boolean settingBoolean(String key, boolean fallback) {
        return settings.containsKey(key) ? settings.get(key) : fallback;
    }
    public java.lang.reflect.Member hook(ClassLoader cl, String point, MethodHook hook) {
        hooks.put(point, hook);
        return null;
    }
    public void nativePatch(LoadedApp app, String library) { natives.add(library); }
    public void publish(String service, Object value) {}
    public Class<?> appPart(LoadedApp app, String name) {
        if (!"hmgapi.mods.wireless.dipo.Dipo".equals(name)) throw new AssertionError(name);
        return CarPlayPart.class;
    }
    public void log(String message) { logs.add(message); }
    public void error(String message, Throwable error) { throw new AssertionError(message, error); }
    public static final class CarPlayPart {
        public static void start(ModContext mod, LoadedApp app) { mod.cpStarts++; }
    }
}
