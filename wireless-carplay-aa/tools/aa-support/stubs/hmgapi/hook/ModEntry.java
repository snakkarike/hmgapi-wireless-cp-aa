package hmgapi.hook;

public abstract class ModEntry {
    public void zygoteInit(ModContext mod) {}
    public abstract void appLoaded(ModContext mod, LoadedApp app);
}
