package cn.banxu.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Local widget mutations. Repository's lock makes read/check/save atomic with app edits. */
public final class WidgetActions {
    static final String ACTION = "cn.banxu.app.WIDGET_ACTION";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Map<Integer, Runnable> EXPIRATIONS = new HashMap<>();
    private WidgetActions() {}

    public static final class UndoSnapshot {
        public final boolean available;
        public final String itemId, message;
        public final long until;
        private UndoSnapshot(boolean available, String itemId, long until) {
            this.available = available; this.itemId = itemId; this.until = until;
            this.message = available ? "已完成 · 撤销" : "";
        }
    }
    private static final UndoSnapshot NONE = new UndoSnapshot(false, "", 0);

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences("widget_actions", Context.MODE_PRIVATE);
    }
    private static String key(int widgetId) { return "undo_" + widgetId; }
    private static Intent intent(Context c, int widgetId, String action, String... segments) {
        Uri.Builder uri = new Uri.Builder().scheme("banxu").authority("widget-action")
            .appendPath(String.valueOf(widgetId)).appendPath(action);
        for (String segment : segments) uri.appendPath(segment);
        return new Intent(c, WidgetActionReceiver.class).setAction(ACTION).setData(uri.build());
    }
    private static PendingIntent immutable(Context c, int widgetId, String action, String... segments) {
        return PendingIntent.getBroadcast(c, widgetId, intent(c, widgetId, action, segments),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    public static PendingIntent completeIntent(Context c, int widgetId, String itemId) {
        return immutable(c, widgetId, "complete", itemId);
    }
    public static PendingIntent undoIntent(Context c, int widgetId) {
        synchronized (Repository.class) {
            try {
                JSONObject record = record(c, widgetId);
                if (record != null) return immutable(c, widgetId, "undo", record.optString("token"), record.optString("itemId"));
            } catch (Exception ignored) { }
            return immutable(c, widgetId, "undo", "expired", "");
        }
    }
    /** Collection rows require fill-in intents; widget identity is fixed in the explicit template URI. */
    public static PendingIntent listTemplate(Context c, int widgetId) {
        int mutable = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        return PendingIntent.getBroadcast(c, widgetId, intent(c, widgetId, "list"), PendingIntent.FLAG_UPDATE_CURRENT | mutable);
    }

    private static JSONObject record(Context c, int widgetId) throws Exception {
        String value = prefs(c).getString(key(widgetId), null);
        return value == null ? null : new JSONObject(value);
    }
    private static boolean validWidget(Context c, int widgetId) {
        if (widgetId <= 0) return false;
        AppWidgetProviderInfo info = c.getSystemService(AppWidgetManager.class).getAppWidgetInfo(widgetId);
        return info != null && (new ComponentName(c, TodoWidgetProvider.class).equals(info.provider)
            || new ComponentName(c, TodoMiniWidgetProvider.class).equals(info.provider)
            || new ComponentName(c, TodoCompactWidgetProvider.class).equals(info.provider));
    }
    private static boolean canComplete(JSONObject item) {
        return item != null && WidgetActionPolicy.canComplete(item.optString("kind"), item.optString("status"), item.optBoolean("isDemo"));
    }
    private static boolean fresh(JSONObject record) {
        return WidgetActionPolicy.withinUndo(record.optLong("issuedAt"), record.optLong("until"),
            record.optLong("issuedElapsed", -1), System.currentTimeMillis(), SystemClock.elapsedRealtime());
    }
    private static boolean matches(JSONObject item, JSONObject record, String token) throws Exception {
        return item != null && WidgetActionPolicy.canUndo(item.optString("kind"), item.optString("status"),
            item.optBoolean("isDemo"), item.optLong("updatedAt"), record.optLong("completedAt"),
            fingerprint(item), record.optString("fingerprint"), token, record.optString("token"));
    }
    /** Sorted field names make the digest independent of JSONObject insertion order; no text is saved in preferences. */
    private static String fingerprint(JSONObject item) throws Exception {
        List<String> names = new ArrayList<>();
        java.util.Iterator<String> keys = item.keys(); while (keys.hasNext()) names.add(keys.next());
        Collections.sort(names);
        StringBuilder value = new StringBuilder();
        for (String name : names) value.append(JSONObject.quote(name)).append(':').append(new JSONArray().put(item.get(name)).toString()).append('\n');
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.toString().getBytes(StandardCharsets.UTF_8));
        StringBuilder hash = new StringBuilder();
        for (byte b : bytes) hash.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return hash.toString();
    }
    public static UndoSnapshot undoSnapshot(Context c, int widgetId) {
        synchronized (Repository.class) {
            try {
                JSONObject record = record(c, widgetId);
                if (record == null) return NONE;
                if (!validWidget(c, widgetId) || !fresh(record)
                    || !matches(Store.get(c).item(record.optString("itemId")), record, record.optString("token"))) {
                    clear(c, widgetId, record);
                    return NONE;
                }
                scheduleExpiry(c.getApplicationContext(), widgetId, record);
                return new UndoSnapshot(true, record.optString("itemId"), record.optLong("until"));
            } catch (Exception ignored) { return NONE; }
        }
    }
    private static boolean clear(Context c, int widgetId, JSONObject record) {
        Runnable old = EXPIRATIONS.remove(widgetId); if (old != null) MAIN.removeCallbacks(old);
        if (record != null) try { c.getSystemService(AlarmManager.class).cancel(immutable(c, widgetId, "expire", record.optString("token"))); }
        catch (RuntimeException ignored) { }
        return prefs(c).edit().remove(key(widgetId)).commit();
    }
    private static void scheduleExpiry(Context c, int widgetId, JSONObject record) {
        Runnable old = EXPIRATIONS.remove(widgetId); if (old != null) MAIN.removeCallbacks(old);
        String token = record.optString("token");
        long remaining = Math.max(1, Math.min(record.optLong("until") - System.currentTimeMillis(),
            record.optLong("issuedElapsed") + WidgetActionPolicy.UNDO_MS - SystemClock.elapsedRealtime()));
        Runnable expiry = () -> BanxuApp.IO.execute(() -> {
            synchronized (Repository.class) { expire(c, widgetId, token); }
            WidgetUpdater.requestUpdate(c);
        });
        EXPIRATIONS.put(widgetId, expiry); MAIN.postDelayed(expiry, remaining);
        // The short handler gives prompt feedback while alive; the non-wakeup alarm survives process death.
        try {
            AlarmManager alarms = c.getSystemService(AlarmManager.class);
            PendingIntent pending = immutable(c, widgetId, "expire", token);
            long at = SystemClock.elapsedRealtime() + remaining;
            if (Repository.canExact(c)) alarms.setExact(AlarmManager.ELAPSED_REALTIME, at, pending);
            else alarms.set(AlarmManager.ELAPSED_REALTIME, at, pending);
        } catch (RuntimeException ignored) { /* Read-time validation always rejects an expired undo. */ }
    }
    private static void expire(Context c, int widgetId, String token) {
        try {
            JSONObject record = record(c, widgetId);
            if (record != null && token.equals(record.optString("token"))) {
                if (!fresh(record)) clear(c, widgetId, record);
                else scheduleExpiry(c, widgetId, record);
            }
        } catch (Exception ignored) { }
    }

    /** Called only by the receiver's serial I/O worker; returns a non-sensitive feedback message. */
    static String receive(Context c, Intent incoming) {
        Uri uri = incoming.getData();
        if (!ACTION.equals(incoming.getAction()) || uri == null || !"banxu".equals(uri.getScheme())
            || !"widget-action".equals(uri.getAuthority())) return "";
        List<String> path = uri.getPathSegments(); if (path.size() < 2) return "";
        int widgetId;
        try { widgetId = Integer.parseInt(path.get(0)); } catch (NumberFormatException invalid) { return ""; }
        String action = path.get(1);
        synchronized (Repository.class) {
            try {
                if ("expire".equals(action) && path.size() == 3) { expire(c, widgetId, path.get(2)); return ""; }
                if (!validWidget(c, widgetId)) return "小组件已变更，请重新添加后再试";
                if ("undo".equals(action) && path.size() == 4) return undo(c, widgetId, path.get(2), path.get(3));
                String itemId;
                if ("complete".equals(action) && path.size() == 3) itemId = path.get(2);
                else if ("list".equals(action) && path.size() == 2) {
                    action = incoming.getStringExtra("widgetAction"); itemId = incoming.getStringExtra("itemId");
                    // A failed collection read has no item ID. Its explicit retry link still opens the list.
                    // The provider/widget ownership check above applies before this item-free route.
                    if ("open-list".equals(action)) {
                        c.startActivity(new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                            .setData(new Uri.Builder().scheme("banxu").authority("widget").appendPath(String.valueOf(widgetId)).appendPath("read-error").build())
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("route", "today"));
                        return "";
                    }
                    if (!("complete".equals(action) || "open".equals(action))) return "";
                } else return "";
                if (itemId == null || itemId.isEmpty() || itemId.length() > 100) return "";
                JSONObject item = Store.get(c).item(itemId);
                if (!canComplete(item)) return "事项已变更，小组件正在刷新";
                if ("open".equals(action)) {
                    c.startActivity(new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
                        .setData(new Uri.Builder().scheme("banxu").authority("widget-item").appendPath(String.valueOf(widgetId)).appendPath(itemId).build())
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra("route", "today").putExtra("itemId", itemId));
                    return "";
                }
                return complete(c, widgetId, item);
            } catch (Exception failed) { return "操作未确认，请打开应用检查事项状态"; }
        }
    }
    private static String complete(Context c, int widgetId, JSONObject before) throws Exception {
        // An old undo must not be presented as the result of this new completion attempt.
        if (!clear(c, widgetId, record(c, widgetId))) return "暂时无法保存操作，请稍后重试";
        String id = before.getString("id");
        Repository.saveItem(c, new JSONObject().put("id", id).put("status", "done"));
        JSONObject after = Store.get(c).item(id);
        if (after == null || !"done".equals(after.optString("status"))) return "操作未确认，请打开应用检查事项状态";
        long now = System.currentTimeMillis();
        JSONObject record = new JSONObject().put("itemId", id).put("previousStatus", before.getString("status"))
            .put("completedAt", after.getLong("updatedAt")).put("fingerprint", fingerprint(after))
            .put("token", UUID.randomUUID().toString()).put("issuedAt", now).put("until", now + WidgetActionPolicy.UNDO_MS)
            .put("issuedElapsed", SystemClock.elapsedRealtime());
        if (!prefs(c).edit().putString(key(widgetId), record.toString()).commit()) {
            clear(c, widgetId, record);
            return "事项已完成，暂时无法提供撤销";
        }
        scheduleExpiry(c, widgetId, record);
        return "已完成，可在小组件中撤销";
    }
    private static String undo(Context c, int widgetId, String token, String itemId) throws Exception {
        JSONObject record = record(c, widgetId);
        if (record == null || !token.equals(record.optString("token")) || !itemId.equals(record.optString("itemId"))) return "这次撤销已失效";
        JSONObject item = Store.get(c).item(itemId);
        if (!fresh(record) || !matches(item, record, token)
            || !WidgetActionPolicy.canComplete(item.optString("kind"), record.optString("previousStatus"), false)) {
            clear(c, widgetId, record);
            return "事项已变更或撤销已过期";
        }
        try {
            Repository.saveItem(c, new JSONObject().put("id", itemId).put("status", record.getString("previousStatus")));
            return "已撤销完成";
        } finally { clear(c, widgetId, record); }
    }
}
