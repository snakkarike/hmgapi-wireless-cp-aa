package hmgapi.mods.wireless;

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
 * Wireless Android Auto when the car is parked (setting "park"), in GPM's process. HMG-API's
 * car events: at "parked" (the driver got out, by the owner's rule) and "shutdown" (the head
 * unit is about to go off), a wireless session is ended the way the protocol does it
 * (GalConnectionManager.sessionSendByeByeRequest, reason 1 user selection), so the phone lets
 * go of the car at once instead of holding on until the head unit cuts the Wi-Fi. At
 * "unparked" (switched on again before the head unit went off) and "wake" (back from a
 * QuickBoot suspend), GPM starts wireless Android Auto again with the same phone
 * (GalReceiverService.connectWirelessSession, what Phone Projection's connect does).
 *
 * The car's hotspot coming back (Android's WIFI_AP_STATE_CHANGED, enabled) asks the phone back
 * too: the stock apps already end the session when the car is switched off, and HMG-API's home
 * Wi-Fi turns the hotspot off while the car is off (or on request), so the phone of the last
 * wireless session (GalReceiverService.disconnected) is asked back once the hotspot is up again,
 * whatever ended it, unless it came back by itself.
 *
 * A wired session is never ended. GPM's service is GalReceiverService (its onCreate gives the
 * instance); the phone on wireless Android Auto is its WirelessExplorer's connected device.
 */
final class Park {
    private Park() {}

    static final int BYEBYE_USER_SELECTION = 1;
    static final long MAIN_WAIT_MS = 2000, WAKE_DELAY_MS = 8000;
    static final String[] POINTS = { "gpm.receiver_service.on_create", "gpm.receiver_service.connection_manager",
        "gpm.receiver_service.wireless_explorer", "gpm.wireless_explorer.connected_device", "gpm.connection_manager.send_byebye",
        "gpm.receiver_service.connect_wireless_session" };

    static volatile Object service;
    /** the phone whose session this ended (its Bluetooth address), until it's brought back */
    static volatile String ended;
    /** the phone of the wireless session that ended last, whoever ended it (null once asked back) */
    static volatile String lastPhone;
    static final int AP_ENABLED = 13;
    /** after the hotspot is back, the car's own reconnect goes first: the mod only fills in */
    static final long HOTSPOT_DELAY_MS = 15000;
    static volatile android.content.ContentResolver cr;

    static void start(final ModContext mod, final LoadedApp app) {
        for (String p : POINTS) {
            if (!Points.has(p)) {
                mod.log("Android Auto: " + p + " isn't in this car's map: the session stays up when you park");
                return;
            }
        }
        final ClassLoader cl = app.classLoader;
        mod.hook(cl, "gpm.receiver_service.on_create", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                service = p.thisObject;
            }
        });
        if (Points.has("gpm.receiver_service.disconnected")) {
            mod.hook(cl, "gpm.receiver_service.disconnected", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    try {
                        String phone = wirelessPhone(cl, p.thisObject);
                        if (phone != null) lastPhone = phone;
                    } catch (Throwable t) {
                        mod.error("Android Auto: the phone of the session that ended", t);
                    }
                }
            });
        }
        final Handler main = new Handler(Looper.getMainLooper());
        if (app.context != null) cr = app.context.getContentResolver();
        if (app.context != null) {
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    if (i.getIntExtra("wifi_state", -1) != AP_ENABLED) return;
                    if (ended == null) ended = lastPhone;
                    lastPhone = null;
                    back(mod, cl, main, "the car's hotspot back", HOTSPOT_DELAY_MS);
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
        mod.log("Android Auto: ends its wireless session when you park and leave, asks the phone back when the car is on or its hotspot is back");
    }

    /** the phone on wireless Android Auto now, or null */
    static String wirelessPhone(ClassLoader cl, Object svc) {
        Object explorer = Points.get(cl, "gpm.receiver_service.wireless_explorer", svc);
        if (explorer == null) return null;
        Object a = Points.call(cl, "gpm.wireless_explorer.connected_device", explorer);
        return a == null || String.valueOf(a).isEmpty() ? null : String.valueOf(a);
    }

    static void end(final ModContext mod, final ClassLoader cl, Handler main, final String event) {
        final Object svc = service;
        if (svc == null) return;
        onMain(mod, main, new Runnable() {
            @Override
            public void run() {
                try {
                    String phone = wirelessPhone(cl, svc);
                    if (phone == null) {
                        mod.log("Android Auto: " + event + ", no wireless session to end");
                        return;
                    }
                    Object manager = Points.get(cl, "gpm.receiver_service.connection_manager", svc);
                    if (manager == null) return;
                    Points.call(cl, "gpm.connection_manager.send_byebye", manager, BYEBYE_USER_SELECTION);
                    ended = phone;
                    mod.log("Android Auto: " + event + ": ended the wireless session (ByeBye)");
                } catch (Throwable t) {
                    mod.error("Android Auto: ending the session at " + event, t);
                }
            }
        });
    }

    static void back(final ModContext mod, final ClassLoader cl, final Handler main, final String event, long delayMs) {
        final String phone = ended;
        final Object svc = service;
        if (phone == null || svc == null) return;
        final Runnable again = new Runnable() {
            @Override
            public void run() {
                try {
                    if (ended == null) return;
                    if (wirelessPhone(cl, svc) != null) {   // the phone came back by itself
                        ended = null;
                        return;
                    }
                    if (cr != null && !Choice.allowed(cr, Choice.ANDROID_AUTO, phone)) {   // another phone is chosen, or one is starting
                        ended = null;
                        mod.log("Android Auto: " + event + ": not asking " + phone + " back (" + (Choice.busy(cr) ? "a session is starting or on"
                                : "the chosen phone is another: " + Choice.describe(cr)) + ")");
                        return;
                    }
                    Points.call(cl, "gpm.receiver_service.connect_wireless_session", svc, phone);
                    ended = null;
                    mod.log("Android Auto: " + event + ": asked the phone back (wireless session)");
                } catch (Throwable t) {
                    mod.error("Android Auto: bringing the session back at " + event, t);
                }
            }
        };
        if (delayMs > 0) main.postDelayed(again, delayMs);   // after a resume: let Bluetooth come back first
        else onMain(mod, main, again);
    }

    /** runs r on GPM's main thread (where GPM's own calls run) and waits for it, MAIN_WAIT_MS at most */
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
            if (!done.await(MAIN_WAIT_MS, TimeUnit.MILLISECONDS)) mod.log("Android Auto: GPM's main thread is busy; the request waits there");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
