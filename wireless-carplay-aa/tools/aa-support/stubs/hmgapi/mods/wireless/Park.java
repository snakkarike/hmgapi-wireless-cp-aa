package hmgapi.mods.wireless;

/** Counts the park teardown's start in GPM; Park itself runs against HMG-API's car events (on the car). */
final class Park {
    static int starts;
    static void start(hmgapi.hook.ModContext mod, hmgapi.hook.LoadedApp app) { starts++; }
}
