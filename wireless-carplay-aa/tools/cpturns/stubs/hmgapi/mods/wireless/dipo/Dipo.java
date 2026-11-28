package hmgapi.mods.wireless.dipo;

/** host stand-in: CpTurns only logs through it; the lines are kept for the test */
final class Dipo {
    static final java.util.List<String> lines = new java.util.ArrayList<String>();

    static void log(String m) {
        lines.add(m);
    }
}
