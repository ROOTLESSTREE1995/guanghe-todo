package cn.banxu.app;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.NotificationListenerService;
import org.json.JSONObject;
import java.lang.ref.WeakReference;

/** Local diagnostic counters contain no app names, notification text, titles, or people. */
final class ListenerHealth {
    private static final Object LOCK = new Object();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ListenerRetryPolicy RETRIES = new ListenerRetryPolicy();
    private static WeakReference<CaptureService> owner = new WeakReference<>(null);
    private static volatile boolean connected;
    private static Runnable pendingRetry;
    private static final String[] TIMES = {"lastConnectedAt", "lastDisconnectedAt", "lastEventAt", "lastSavedAt", "lastRebindAt"};
    private static final String[] COUNTS = {"receivedCount", "savedCount", "filteredCount", "noTextCount", "duplicateCount", "malformedCount"};

    private ListenerHealth() {}

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("listener_health", Context.MODE_PRIVATE);
    }

    static JSONObject snapshot(Context c) throws Exception {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            JSONObject out = new JSONObject().put("connected", connected && Repository.notificationAccess(c))
                .put("lastOutcome", p.getString("lastOutcome", ""))
                .put("deviceManufacturer", Build.MANUFACTURER).put("deviceModel", Build.MODEL)
                .put("osRelease", Build.VERSION.RELEASE).put("sdkInt", Build.VERSION.SDK_INT);
            for (String name : TIMES) out.put(name, p.getLong(name, 0));
            for (String name : COUNTS) out.put(name, p.getLong(name, 0));
            return out;
        }
    }

    static void onConnected(CaptureService service) {
        synchronized (LOCK) {
            owner = new WeakReference<>(service);
            connected = true;
            cancelRetryLocked();
        }
        long at = System.currentTimeMillis();
        Context app = service.getApplicationContext();
        BanxuApp.IO.execute(() -> { recordTime(app, "lastConnectedAt", at); BanxuApp.changed(); });
    }

    static void onDisconnected(CaptureService service) {
        synchronized (LOCK) {
            if (owner.get() != null && owner.get() != service) return;
            owner.clear();
            connected = false;
        }
        long at = System.currentTimeMillis();
        Context app = service.getApplicationContext();
        BanxuApp.IO.execute(() -> {
            recordTime(app, "lastDisconnectedAt", at);
            requestRepair(app);
            BanxuApp.changed();
        });
    }

    static void onDestroyed(CaptureService service) {
        synchronized (LOCK) { if (!connected || owner.get() != service) return; }
        onDisconnected(service);
    }

    /** Call off the main thread. A request is not evidence that Android has bound the listener. */
    static String requestRepair(Context context) {
        Context app = context.getApplicationContext();
        try {
            if (!new SecureSettings(app).publicJson().optBoolean("captureEnabled")) {
                cancelRetry();
                return "disabled";
            }
            if (!Repository.notificationAccess(app)) {
                cancelRetry();
                return "permission_required";
            }
            synchronized (LOCK) {
                if (connected) { cancelRetryLocked(); return "connected"; }
                long elapsedNow = SystemClock.elapsedRealtime();
                if (!RETRIES.acquire(elapsedNow)) {
                    // A quick reconnect/disconnect can cancel the old retry before its interval ends.
                    scheduleRetryLocked(app, RETRIES.remainingThrottleDelay(elapsedNow));
                    return "throttled";
                }
                cancelRetryLocked();
            }
            NotificationListenerService.requestRebind(new ComponentName(app, CaptureService.class));
            recordTime(app, "lastRebindAt", System.currentTimeMillis());
            synchronized (LOCK) {
                scheduleRetryLocked(app, RETRIES.retryDelay());
            }
            BanxuApp.changed();
            return "requested";
        } catch (Exception unavailable) {
            cancelRetry();
            return "unavailable";
        }
    }

    private static void cancelRetry() { synchronized (LOCK) { cancelRetryLocked(); } }
    private static void scheduleRetryLocked(Context app, long delay) {
        if (connected || delay <= 0 || pendingRetry != null) return;
        pendingRetry = new Runnable() {
            @Override public void run() {
                synchronized (LOCK) {
                    if (pendingRetry != this) return;
                    pendingRetry = null;
                }
                BanxuApp.IO.execute(() -> { requestRepair(app); BanxuApp.changed(); });
            }
        };
        MAIN.postDelayed(pendingRetry, delay);
    }
    private static void cancelRetryLocked() {
        if (pendingRetry != null) MAIN.removeCallbacks(pendingRetry);
        pendingRetry = null;
    }

    private static void recordTime(Context c, String key, long at) {
        synchronized (LOCK) { prefs(c).edit().putLong(key, at).apply(); }
    }

    /** Called only after the saved capture switch and source allowlist have both matched. */
    static void event(Context c, long at) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            p.edit().putLong("lastEventAt", at).putLong("receivedCount", p.getLong("receivedCount", 0) + 1).apply();
        }
    }

    static void outcome(Context c, String result, int saved, int duplicate) {
        synchronized (LOCK) {
            SharedPreferences p = prefs(c);
            SharedPreferences.Editor edit = p.edit().putString("lastOutcome", result);
            if (saved > 0) edit.putLong("savedCount", p.getLong("savedCount", 0) + saved).putLong("lastSavedAt", System.currentTimeMillis());
            if (duplicate > 0) edit.putLong("duplicateCount", p.getLong("duplicateCount", 0) + duplicate);
            switch (result) {
                case "conversation_filtered": case "group_summary": case "ongoing": case "outgoing_only":
                    edit.putLong("filteredCount", p.getLong("filteredCount", 0) + 1); break;
                case "no_text": edit.putLong("noTextCount", p.getLong("noTextCount", 0) + 1); break;
                case "malformed": edit.putLong("malformedCount", p.getLong("malformedCount", 0) + 1); break;
                default: break;
            }
            edit.apply();
        }
        BanxuApp.changed();
    }
}
