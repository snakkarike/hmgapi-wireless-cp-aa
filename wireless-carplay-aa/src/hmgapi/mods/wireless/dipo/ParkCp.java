package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

import hmgapi.hook.CarEvents;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Wireless CarPlay when the car is parked (setting "park"), in DiPO's process. At HMG-API's
 * "parked" and "shutdown" car events a wireless CarPlay session is ended
 * (CarPlayService.disconnectCarPlay), so the iPhone takes its audio and calls back at once.
 * That also stops DiPO advertising CarPlay again after the session (its restartBonjour flag),
 * so at "unparked" and "wake" the mod advertises it again (CarPlayService.restartBonjour) and
 * the iPhone can come back. A USB session is never ended.
 *
 * The car's hotspot coming back (WIFI_AP_STATE_CHANGED, enabled) advertises CarPlay again too,
 * after any wireless session that ended (the stock apps end it when the car is switched off, and
 * HMG-API's home Wi-Fi turns the hotspot off meanwhile), so the iPhone can rejoin.
 *
 * CarPlayService's instance and the session's transport come from its own callbacks
 * (onSessionCreated, onSessionStarted(1 wireless), onSessionFinalized).
 */
final class ParkCp {
    private ParkCp() {}

    static final int WIRELESS = 1;
    static final long MAIN_WAIT_MS = 2000, WAKE_DELAY_MS = 8000;
    static final String[] POINTS = { "dipo.carplay.session_created", "dipo.carplay.session_started", "dipo.carplay.session_finalized",
        "dipo.carplay.disconnect", "dipo.carplay.restart_bonjour" };

    static volatile Object service;
    static volatile boolean wireless, ended;
    static final int AP_ENABLED = 13;
    /** after the hotspot is back, the car's own reconnect goes first: the mod only fills in */
    static final long HOTSPOT_DELAY_MS = 15000;
    static volatile android.content.ContentResolver cr;

    static void start(final ModContext mod, LoadedApp app) {
        for (String p : POINTS) {
            if (!Points.has(p)) {
                mod.log("CarPlay: " + p + " isn't in this car's map: the session stays up when you park");
                return;
            }
        }
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                service = p.thisObject;
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                service = p.thisObject;
                wireless = p.args.length > 0 && Integer.valueOf(WIRELESS).equals(p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (wireless) ended = true;   // whoever ended it: advertise again when the hotspot is back
                wireless = false;
            }
        });
        final Handler main = new Handler(Looper.getMainLooper());
        if (app.context != null) cr = app.context.getContentResolver();
        if (app.context != null) {
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i.getIntExtra("wifi_state", -1) == AP_ENABLED) back(mod, cl, main, "the car's hotspot back", HOTSPOT_DELAY_MS);
                }
            }, new IntentFilter("android.net.wifi.WIFI_AP_STATE_CHANGED"));
        }
        mod.onCarEvent(app, new CarEvents.Listener() {
            @Override
            public void onCarEvent(String event) {
                if (CarEvents.PARKED.equals(event) || CarEvents.SHUTDOWN.equals(event)) end(mod, cl, main, event);
                else if (CarEvents.UNPARKED.equals(event)) back(mod, cl, main, event, 0);
                else if (CarEvents.WAKE.equals(event)) back(mod, cl, main, event, WAKE_DELAY_MS);
            }
        });
        mod.log("CarPlay: ends its wireless session when you park and leave, back when the car is on again");
    }

    static void end(final ModContext mod, final ClassLoader cl, Handler main, final String event) {
        final Object svc = service;
        if (svc == null) return;
        if (!wireless) {
            mod.log("CarPlay: " + event + ", no wireless session to end");
            return;
        }
        onMain(mod, main, new Runnable() {
            @Override
            public void run() {
                try {
                    Object r = Points.call(cl, "dipo.carplay.disconnect", svc);
                    ended = true;
                    mod.log("CarPlay: " + event + ": ended the wireless session (" + r + ")");
                } catch (Throwable t) {
                    mod.error("CarPlay: ending the session at " + event, t);
                }
            }
        });
    }

    static void back(final ModContext mod, final ClassLoader cl, Handler main, final String event, long delayMs) {
        final Object svc = service;
        if (!ended || svc == null) return;
        Runnable again = new Runnable() {
            @Override
            public void run() {
                try {
                    if (!ended) return;
                    ended = false;
                    if (wireless) return;   // the iPhone came back by itself
                    if (cr != null && !Choice.allowedKind(cr, Choice.CARPLAY)) {   // an Android phone is chosen, or a session is starting
                        mod.log("CarPlay: " + event + ": not advertising (" + (Choice.busy(cr) ? "a session is starting or on"
                                : "the chosen phone is another: " + Choice.describe(cr)) + ")");
                        return;
                    }
                    Object r = Points.call(cl, "dipo.carplay.restart_bonjour", svc);
                    mod.log("CarPlay: " + event + ": advertising wireless CarPlay again (" + r + ")");
                } catch (Throwable t) {
                    mod.error("CarPlay: advertising again at " + event, t);
                }
            }
        };
        if (delayMs > 0) main.postDelayed(again, delayMs);
        else onMain(mod, main, again);
    }

    static void onMain(ModContext mod, Handler main, final Runnable r) {
        final CountDownLatch done = new CountDownLatch(1);
        main.post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    done.countDown();
                }
            }
        });
        try {
            if (!done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) mod.log("CarPlay: DiPO's main thread is busy; the request waits there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
