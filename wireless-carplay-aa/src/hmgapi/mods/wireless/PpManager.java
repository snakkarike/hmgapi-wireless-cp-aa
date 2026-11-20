package hmgapi.mods.wireless;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Binder;

import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The projection manager, in system_server: Phone Projection's service (PPService, binder
 * "phoneprojection") sees every CarPlay and Android Auto connect from any screen, and every
 * wireless session coming up or ending. From those it keeps Choice's two values: the chosen
 * phone and what's on. Hooked in the zygote, so system_server (the only caller) has it.
 *
 *   connectPP(type, address, name, connection)   the choice (and "starting", wireless)
 *   sendConnectPPFromBT(address, null)            Device Connections connected this phone: the
 *                                                 choice, its type filled in when it comes up
 *   disconnectPP / cancelConnectionPP             "off", for the session it names
 *   onWirelessConnected(type, address)            the choice, "on"
 *   onWirelessDisconnected(type, address)         "off"
 *
 * Switching phones is just choosing the other one on Device Connections: the choice moves at
 * once, so the mod's automatic starts stop competing for the old phone.
 */
final class PpManager {
    private PpManager() {}

    static final String[] POINTS = { "framework.pp_service.connect_pp", "framework.pp_service.send_connect_pp_from_bt",
        "framework.pp_service.disconnect_pp", "framework.pp_service.cancel_connection_pp",
        "framework.pp_service.on_wireless_connected", "framework.pp_service.on_wireless_disconnected" };
    static volatile ContentResolver cr;

    static void zygote(final ModContext mod) {
        for (String p : POINTS) {
            if (!Points.has(p)) {
                mod.log("projection manager: " + p + " isn't in this car's map: phones aren't kept apart");
                return;
            }
        }
        mod.hook(null, POINTS[0], new Before(mod, "connect") {
            @Override
            void on(ContentResolver r, Object[] a) {
                int type = (Integer) a[0], conn = (Integer) a[3];
                String addr = (String) a[1];
                if ((type != Choice.CARPLAY && type != Choice.ANDROID_AUTO) || empty(addr)) return;
                Choice.putChoice(r, type, addr);
                if (conn == 1) Choice.putState(r, "starting", type, addr);
                mod.log("projection: " + name(type) + " asked for " + addr + (conn == 1 ? " (wireless)" : " (USB)") + ": the chosen phone");
            }
        });
        mod.hook(null, POINTS[1], new Before(mod, "Device Connections") {
            @Override
            void on(ContentResolver r, Object[] a) {
                String addr = (String) a[0];
                if (empty(addr)) return;
                String[] c = Choice.split(android.provider.Settings.Global.getString(r, Choice.CHOICE), 2);
                int type = c != null && c[1].equalsIgnoreCase(addr) ? Integer.parseInt(c[0]) : 0;   // known for this phone: kept
                Choice.putChoice(r, type, addr);
                mod.log("projection: Device Connections connected " + addr + ": the chosen phone");
            }
        });
        MethodHook off = new Before(mod, "disconnect") {
            @Override
            void on(ContentResolver r, Object[] a) {
                int type = (Integer) a[0];
                String addr = (String) a[1];
                String[] s = Choice.state(r);
                if (s != null && !"off".equals(s[0]) && String.valueOf(type).equals(s[1]) && (empty(addr) || s[2].equalsIgnoreCase(addr))) {
                    Choice.putState(r, "off", type, addr);
                }
            }
        };
        mod.hook(null, POINTS[2], off);
        mod.hook(null, POINTS[3], off);
        mod.hook(null, POINTS[4], new After(mod, "connected") {
            @Override
            void on(ContentResolver r, Object[] a) {
                int type = (Integer) a[0];
                String addr = (String) a[1];
                if (empty(addr)) return;
                Choice.putChoice(r, type, addr);
                Choice.putState(r, "on", type, addr);
                mod.log("projection: " + name(type) + " on with " + addr);
            }
        });
        mod.hook(null, POINTS[5], new After(mod, "disconnected") {
            @Override
            void on(ContentResolver r, Object[] a) {
                Choice.putState(r, "off", (Integer) a[0], (String) a[1]);
            }
        });
    }

    static String name(int type) {
        return type == Choice.CARPLAY ? "CarPlay" : type == Choice.ANDROID_AUTO ? "Android Auto" : "projection";
    }

    static boolean empty(String s) {
        return s == null || s.isEmpty();
    }

    /** system_server's own context (no app here): ActivityThread's system context */
    static ContentResolver resolver() throws Exception {
        if (cr == null) {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object t = at.getMethod("currentActivityThread").invoke(null);
            cr = ((Context) at.getMethod("getSystemContext").invoke(t)).getContentResolver();
        }
        return cr;
    }

    abstract static class Before extends MethodHook {
        final ModContext mod;
        final String what;

        Before(ModContext mod, String what) {
            this.mod = mod;
            this.what = what;
        }

        abstract void on(ContentResolver r, Object[] a) throws Exception;

        @Override
        protected void beforeHookedMethod(Param p) {
            run(p);
        }

        void run(Param p) {
            long id = Binder.clearCallingIdentity();   // the settings write is the manager's, not the caller's
            try {
                on(resolver(), p.args);
            } catch (Throwable t) {
                mod.error("projection manager: " + what, t);
            } finally {
                Binder.restoreCallingIdentity(id);
            }
        }
    }

    abstract static class After extends Before {
        After(ModContext mod, String what) {
            super(mod, what);
        }

        @Override
        protected void beforeHookedMethod(Param p) {}

        @Override
        protected void afterHookedMethod(Param p) {
            run(p);
        }
    }
}
