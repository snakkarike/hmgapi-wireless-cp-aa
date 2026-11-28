package hmgapi.mods.wireless.dipo;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import com.broadcom.bt.hfdevice.hfbtcm.HfDeviceBTCMManager;
import com.tcc.iap2.IIap2Listener;
import com.tcc.iap2.IIap2Service;
import com.tcc.iap2.Iap2ListenerInfo;
import com.tcc.iap2.Iap2NotiMsg;

import hmgapi.hook.Car;
import hmgapi.hook.Cluster;
import hmgapi.hook.LoadedApp;
import hmgapi.hook.MethodHook;
import hmgapi.hook.ModContext;
import hmgapi.hook.Points;

/**
 * The Wireless mod in DiPO (the CarPlay app): its app part, linked against DiPO's
 * libraries (com.tcc.iap2, com.broadcom.bt). Wireless.appLoaded starts it.
 *
 * Turn-by-turn ("tbt"): DiPO's own route guidance manager only exists when the car's
 * stepofspec is 999 or more, and stays off (HMG-API never changes stepofspec). HMG-API
 * registers its own listener with the iAP2 server for route guidance (the server sends
 * those notifications to every listener that asks), and CpTurns sends them to the
 * cluster. DiPO still starts and stops the phone's route guidance itself when the
 * phone or the car takes turn-by-turn; a stop clears the HUD (hook on the iAP2 proxy).
 *
 * Native patches (in memory, the files stay stock): libCarPlayCore takes Wi-Fi sessions
 * ("bridge"); libCarPlayAudio offers AAC/Opus on Wi-Fi and libCarPlayUtils sends its
 * audio conversion to the mod's libleafiaaac.so ("audio").
 *
 * Bridge ("bridge"): wireless CarPlay's Bluetooth bridge (Bridge), plus what DiPO needs
 * for it: its own wireless CarPlay off (that's Hyundai's, not this one), its iAP2
 * listener ignoring the bridge's sessions, the reconnect crash fix and the first-connect
 * notice fix.
 *
 * Now Playing ("now_playing", with the bridge): song, artist and album art on wireless
 * CarPlay, which DiPO only asks the phone for on USB (nowPlaying).
 */
public final class Dipo {
    private Dipo() {}

    static final String IAP2_ACTION = "com.remote.service.IAP2SERVICE";
    static final String CP_FIRST_CONNECTED = "com.android.app.PhoneProjection.CP_FIRST_CONNECTED";
    /** iAP2 listener type for route guidance (notifications 120, 121, 122) */
    static final int LISTENER_ROUTE_GUIDANCE = 4096;
    static final int RG_UPDATE = 120, RG_MANEUVER = 121, RG_LANES = 122;
    static final String[] RG_COMPONENTS = { "RouteGuidanceComponent0" };

    static ModContext mod;
    static LoadedApp app;
    static volatile IIap2Service iap2;
    static boolean tbt, bridge, nowPlaying;
    /** Now Playing asked again this long after the CarPlay channel session comes up */
    static final long NP_DELAY = 1500;
    static Handler npHandler;
    /** DiPO's iAP2 manager from the CarPlay channel session's connect (Now Playing asked again through it after a re-attach) */
    static volatile Object npManager;
    static Handler turnsHandler;
    static CpTurns turns;
    static RgListener rg;
    /** asks for route guidance after every comp-5 ready, and the watchdog after each Start (RgWatch) */
    static RgWatch rgWatch;
    private static Handler rgHandler;
    /** set while HMG-API itself calls the iAP2 server (its own route guidance restart) */
    static final ThreadLocal<Boolean> OWN = new ThreadLocal<Boolean>();
    /** onSessionDetails' device id while it runs, and whether its address lookup came back empty */
    static final ThreadLocal<String> SESSION = new ThreadLocal<String>();
    static final ThreadLocal<Boolean> NO_ADDRESS = new ThreadLocal<Boolean>();

    static void log(String m) {
        mod.log(m);
    }

    static void error(String m, Throwable t) {
        mod.error(m, t);
    }

    /** Wireless.appLoaded, in DiPO's process */
    public static void start(ModContext m, LoadedApp a) {
        mod = m;
        app = a;
        tbt = m.settingBoolean("tbt", true);
        bridge = m.settingBoolean("bridge", true);
        nowPlaying = bridge && m.settingBoolean("now_playing", true);
        CarPlayLog.on = m.settingBoolean("carplay_log", false);
        CarPlayLog.apply("start");
        // the CarPlay libraries, patched in memory once DiPO loads them (files stay stock)
        if (bridge) m.nativePatch(a, "libCarPlayCore.so");   // AirPlay sessions over Wi-Fi
        if (m.settingBoolean("audio", true)) {
            m.nativePatch(a, "libCarPlayAudio.so");   // offer AAC and Opus on Wi-Fi
            m.nativePatch(a, "libCarPlayUtils.so");   // its AudioConverter calls go to libleafiaaac.so
        }
        if (tbt) turnByTurn();
        if (!tbt && !bridge) return;
        rgWatch();
        if (bridge) bridge();
        Intent i = new Intent(IAP2_ACTION);
        boolean bound = a.context.bindService(i, new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder b) {
                IIap2Service s = IIap2Service.Stub.asInterface(b);
                iap2 = s;
                if (tbt) registerRouteGuidance(s);
                if (bridge) Bridge.onIap2Bound(s);
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                iap2 = null;
                log("DiPO: iAP2 server gone");
            }
        }, Context.BIND_AUTO_CREATE);
        log("DiPO: iAP2 server " + (bound ? "binding" : "can't be bound"));
    }

    private static void turnByTurn() {
        if (Car.stepOfSpec() >= 999) {
            log("CarPlay: stepofspec is 999 or more, DiPO's own turn-by-turn runs (HMG-API's stays off)");
            tbt = false;
            return;
        }
        Cluster c = Cluster.of(app.context);
        if (c == null) {
            tbt = false;
            return;
        }
        HandlerThread t = new HandlerThread("hmgapi-cp-tbt");
        t.start();
        turnsHandler = new Handler(t.getLooper());
        turns = new CpTurns(app.context, c, new Runnable() {
            @Override
            public void run() {
                RgWatch w = rgWatch;
                if (w != null) w.started();   // a Start of ours: watched
                restartRouteGuidance();
            }
        });
        turns.standardTurns = mod.settingBoolean("tbt_standard", false);
        turns.sideStreets = turns.standardTurns && mod.settingBoolean("tbt_side_streets", false);
        if (turns.standardTurns) log("CarPlay: turns in the car's own format near a maneuver (standard turns)"
                + (turns.sideStreets ? ", with the iPhone's side streets" : ""));
        rg = new RgListener();
        mod.hook(app.classLoader, "dipo.iap2_proxy.stop_route_guidance", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (OWN.get() != null) return;
                // logged every time (issue #24): a stop mid-route ends the phone's updates
                log("CarPlay: DiPO stopped route guidance");
                RgWatch w = rgWatch;
                if (w != null) w.stopped();
                post(new Runnable() {
                    @Override
                    public void run() {
                        turns.stop();
                    }
                });
            }
        });
        log("CarPlay: turn-by-turn to the cluster (HMG-API's route guidance listener)");
    }

    static void post(Runnable r) {
        final Runnable run = r;
        turnsHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    run.run();
                } catch (Throwable x) {
                    error("CarPlay turn-by-turn", x);
                }
            }
        });
    }

    static void registerRouteGuidance(IIap2Service s) {
        try {
            Iap2ListenerInfo info = new Iap2ListenerInfo();
            info.onSetSupportType(LISTENER_ROUTE_GUIDANCE);
            info.onSetListenerName("HMGAPI_RG");
            log("CarPlay: Iap2SvcInit(HMGAPI_RG, 0x1000) = " + s.Iap2SvcInit(rg, info));
        } catch (Throwable t) {
            error("CarPlay: route guidance listener", t);
        }
    }

    /** after a source change: Stop, then Start, so the phone sends its whole current state (the build's HmgApiRgRestart) */
    static void restartRouteGuidance() {
        IIap2Service s = iap2;
        if (s == null) return;
        OWN.set(Boolean.TRUE);
        try {
            s.Iap2SvcStopRouteGuidanceUpdate(RG_COMPONENTS);
            Thread.sleep(200);
            s.Iap2SvcStartRouteGuidanceUpdate(RG_COMPONENTS, true, true);
        } catch (Throwable t) {
            // the session went away meanwhile
        } finally {
            OWN.remove();
        }
    }

    /** HMG-API's iAP2 listener for route guidance: everything goes to CpTurns on its thread */
    static final class RgListener extends IIap2Listener.Stub {
        @Override
        public void connected(int component, String serial) {}

        @Override
        public void disconnected(final int component) {
            // as DiPO's listener: component 2 isn't a session, and the bridge's own sessions end while wireless CarPlay runs
            if (component == 2 || (bridge && Bridge.dipoIgnoreDisconnect(component))) return;
            post(new Runnable() {
                @Override
                public void run() {
                    turns.disconnected();
                }
            });
        }

        @Override
        public void notify(final int what, int a1, int a2, final Iap2NotiMsg msg) {
            if ((what != RG_UPDATE && what != RG_MANEUVER && what != RG_LANES) || msg == null) return;
            RgWatch w = rgWatch;
            if (w != null && what != RG_LANES) w.update();
            post(new Runnable() {
                @Override
                public void run() {
                    if (what == RG_UPDATE) turns.routeGuidance(msg);
                    else if (what == RG_MANEUVER) turns.maneuver(msg);
                    else turns.lanes(msg);
                }
            });
        }
    }

    private static void bridge() {
        final ClassLoader cl = app.classLoader;
        Bridge.cl = cl;
        Bridge.context = app.context;
        // Broadcom's manager: DiPO registers its listener once the service is connected; the bridge's follows
        mod.hook(cl, "dipo.btcm.register_listener", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                if (!Bridge.isOurs(p.args[0])) Bridge.onBtcmReady((HfDeviceBTCMManager) p.thisObject);
            }
        });
        // DiPO's CarPlay callbacks (observed)
        mod.hook(cl, "dipo.carplay.session_created", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                CarPlayLog.apply("session");
                Bridge.onCpSessionCreated(p.thisObject);
            }
        });
        mod.hook(cl, "dipo.carplay.session_started", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionStarted((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.session_finalized", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpSessionFinalized((Integer) p.args[0]);
            }
        });
        mod.hook(cl, "dipo.carplay.iap_message", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                Bridge.onCpIapIn(p.thisObject, (byte[]) p.args[0], (Integer) p.args[1]);
            }
        });
        // the CarPlay library's disableBluetooth: DiPO disconnects the phone's whole Bluetooth link, as for
        // wired CarPlay. On a wireless session some phones then end it after about a minute (#5), so the
        // link stays up while wireless CarPlay runs (persist.ev6.cpwl.keepbt=0: DiPO's way). A car whose
        // map has no such point yet (made from a Car Report older than HMG-API 1.1.0) goes without: the
        // hooks after this one must still go in.
        if (Points.has("dipo.carplay.disable_bluetooth")) {
            mod.hook(cl, "dipo.carplay.disable_bluetooth", new MethodHook() {
                @Override
                protected void beforeHookedMethod(Param p) {
                    if (!Bridge.sCpWireless || "0".equals(Bridge.prop("persist.ev6.cpwl.keepbt", "1"))) return;
                    log("DiPO: Bluetooth kept up for wireless CarPlay (disableBluetooth skipped)");
                    p.setResult(null);
                }
            });
        } else {
            log("DiPO: this car's map has no dipo.carplay.disable_bluetooth: Bluetooth isn't kept up for wireless CarPlay");
        }
        // DiPO's own wireless CarPlay (Hyundai's) stays off: the feature file is there for the other apps
        mod.hook(cl, "dipo.utils.is_support_wireless_carplay", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                p.setResult(false);
            }
        });
        // DiPO's iAP2 listener: a Bluetooth CarPlay session (4) is a phone like USB (1); the
        // CarPlay-channel session (5) only turns its notifications on; the bridge's sessions
        // ending, and the channel session's identification, aren't DiPO's business
        mod.hook(cl, "dipo.iap2_listener.connected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    Object manager = Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject));
                    Points.call(cl, "dipo.iap2.set_notify_on", null, manager, true);
                    npManager = manager;
                    if (nowPlaying) nowPlaying(manager, true);
                    p.setResult(null);
                } else if (c == Bridge.COMPONENT_BT_CARPLAY) {
                    p.args[0] = Bridge.COMPONENT_USB;
                }
            }
        });
        mod.hook(cl, "dipo.iap2_listener.disconnected", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                int c = (Integer) p.args[0];
                if (nowPlaying && c == Bridge.COMPONENT_CARPLAY_CLIENT) {
                    nowPlaying(Points.get(cl, "dipo.iap2_client.manager", Points.get(cl, "dipo.iap2_listener.owner", p.thisObject)), false);
                }
                if (Bridge.dipoIgnoreDisconnect(c)) p.setResult(null);
            }
        });
        mod.hook(cl, "dipo.iap2_listener.notify", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (Bridge.dipoDropNoti((Integer) p.args[0])) p.setResult(null);
            }
        });
        reconnectFix(cl);
        firstConnectNotice();
        {
            // the iAP2 server's word that a session's link failed (the CarPlay channel's is attached again), or that a
            // session is identified and authenticated (a re-attach counts only then, and route guidance is asked for
            // again after every one: Bridge, Reattach, RgWatch). Always on now: RgWatch needs it with re-attach off too.
            IntentFilter f = new IntentFilter(Bridge.ACTION_IAP2_CONNECTFAIL);
            f.addAction(Bridge.ACTION_IAP2_CONNECTSUCCESS);
            app.context.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int comp = i.getIntExtra(Bridge.EXTRA_IAP2_COMPONENT, -1);
                    if (Bridge.ACTION_IAP2_CONNECTSUCCESS.equals(i.getAction())) Bridge.onIap2ConnectSuccess(comp);
                    else Bridge.onIap2ConnectFail(comp);
                }
            }, f);
        }
        if (nowPlaying) {
            HandlerThread t = new HandlerThread("hmgapi-cp-np");
            t.start();
            npHandler = new Handler(t.getLooper());
        }
        log("bridge: wireless CarPlay Bluetooth bridge in DiPO");
    }

    /**
     * Now Playing on wireless CarPlay. DiPO asks the phone for Now Playing (Iap2SvcNowPlayingStart)
     * only when a USB CarPlay session starts. On wireless, iAP2 then moves from the Bluetooth
     * session to the CarPlay channel, whose identification the bridge keeps from DiPO, so a
     * subscription made before the move gets one update, then nothing. Once the channel
     * session is up: stop and start Now Playing again, NP_DELAY later and on its own thread (a
     * binder call into the iAP2 server from its own callback could deadlock); stopped when the
     * channel session ends. Through DiPO's own iAP2 connection, so its listener gets the
     * updates as on USB: the manager's one IIap2Service field (EV6 and Ioniq 6 260507), or
     * HMG-API's own binding when there isn't one.
     */
    private static void nowPlaying(final Object manager, final boolean start) {
        npHandler.removeCallbacksAndMessages(null);
        npHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                IIap2Service s = dipoIap2(manager);
                if (s == null) {
                    log("Now Playing: no iAP2 connection");
                    return;
                }
                try {
                    s.Iap2SvcNowPlayingStop();
                    if (start) s.Iap2SvcNowPlayingStart();
                    log(start ? "Now Playing: CarPlay channel session up, asking the phone again" : "Now Playing: CarPlay channel session ended, stopped");
                } catch (Throwable t) {
                    error("Now Playing", t);
                }
            }
        }, start ? NP_DELAY : 0);
    }

    /**
     * After a re-attach of the CarPlay channel's iAP2 session (Bridge, Reattach): the phone is
     * asked for route guidance (Stop, then Start, as a source change does) and Now Playing again.
     */
    static void askAgain() {
        if (tbt && turnsHandler != null) {
            post(new Runnable() {
                @Override
                public void run() {
                    restartRouteGuidance();
                }
            });
        }
        if (nowPlaying && npHandler != null) nowPlaying(npManager, true);
    }

    private static void rgWatch() {
        HandlerThread t = new HandlerThread("hmgapi-cp-rg");
        t.start();
        rgHandler = new Handler(t.getLooper());
        rgWatch = new RgWatch(new RgWatch.Host() {
            @Override
            public long now() {
                return android.os.SystemClock.elapsedRealtime();
            }

            @Override
            public Object schedule(Runnable r, long delayMs) {
                rgHandler.postDelayed(r, delayMs);
                return r;
            }

            @Override
            public void cancel(Object token) {
                rgHandler.removeCallbacks((Runnable) token);
            }

            @Override
            public void askAgain() {
                Dipo.askAgain();
            }

            @Override
            public void restart() {
                if (tbt && turnsHandler != null) {
                    post(new Runnable() {
                        @Override
                        public void run() {
                            restartRouteGuidance();
                        }
                    });
                }
            }

            @Override
            public void log(String m) {
                Dipo.log("CarPlay: " + m);
            }
        }, tbt);
    }

    /** Bridge: the CarPlay channel's session (RgWatch) */
    static void rgSessionUp() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionUp();
    }

    static void rgSessionEnd() {
        RgWatch w = rgWatch;
        if (w != null) w.sessionEnd();
    }

    static void rgLinkDown(String why) {
        RgWatch w = rgWatch;
        if (w != null) w.linkDown(why);
    }

    /** Reattach's ask after a re-attach is ready: through RgWatch (logged, watched) */
    static void rgAsk(String how) {
        RgWatch w = rgWatch;
        if (w != null) w.ask(how);
        else askAgain();
    }

    /** DiPO's iAP2 connection: the one IIap2Service field of its iAP2 manager (else HMG-API's) */
    private static IIap2Service dipoIap2(Object manager) {
        if (manager != null) {
            for (Class<?> c = manager.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (java.lang.reflect.Field f : c.getDeclaredFields()) {
                    if (f.getType() != IIap2Service.class) continue;
                    try {
                        f.setAccessible(true);
                        Object s;
                        synchronized (manager) {
                            s = f.get(manager);
                        }
                        if (s != null) return (IIap2Service) s;
                    } catch (Throwable t) {
                        error("Now Playing: DiPO's iAP2 connection", t);
                    }
                }
            }
        }
        return iap2;
    }

    /**
     * DiPO crash on a wireless reconnect (stock bug): an iAP2 drop clears the phone's iAP2
     * Bluetooth address, and a wireless session can restart before iAP2 is back;
     * onSessionDetails upper-cases that address unchecked. As the bridge build: when it's
     * missing there, the session's own device id stands in, or (no id either) the stored
     * CarPlay address is kept. Only inside onSessionDetails; every other caller checks.
     */
    private static void reconnectFix(final ClassLoader cl) {
        mod.hook(cl, "dipo.session_listener.session_details", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                SESSION.set(p.args[0] == null ? null : (String) p.args[0]);
            }

            @Override
            protected void afterHookedMethod(Param p) {
                SESSION.remove();
                NO_ADDRESS.remove();
            }
        });
        mod.hook(cl, "dipo.iap2.bt_address", new MethodHook() {
            @Override
            protected void afterHookedMethod(Param p) {
                String id = SESSION.get();
                if (id == null || p.getResult() != null) return;
                p.setResult(id);
                if (id.isEmpty()) NO_ADDRESS.set(Boolean.TRUE);
                log("bridge: no iAP2 Bluetooth address on session details, using the session's (" + (id.isEmpty() ? "none, keeping the stored one" : id) + ")");
            }
        });
        mod.hook(cl, "dipo.bt.set_carplay_bt_address", new MethodHook() {
            @Override
            protected void beforeHookedMethod(Param p) {
                if (NO_ADDRESS.get() != null) p.setResult(null);
            }
        });
    }

    /**
     * CarPlay's first-connect notice: DiPO looks it up by the phone's Bluetooth address
     * (before iAP2 identification), but saves it under the iPhone's serial, so on a
     * wireless connect it came up every time. As the bridge build: also saved under the
     * Bluetooth address of the last iAP2 session.
     */
    private static void firstConnectNotice() {
        app.context.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String id = Bridge.sLastBtId;
                if (id == null) return;
                try {
                    SharedPreferences sp = c.getSharedPreferences("pref_dipo", 0);
                    if (!sp.edit().putBoolean(id, true).commit()) log("bridge: first-connect notice not saved for " + id);
                } catch (Throwable t) {
                    error("bridge: first-connect notice", t);
                }
            }
        }, new IntentFilter(CP_FIRST_CONNECTED));
    }
}
