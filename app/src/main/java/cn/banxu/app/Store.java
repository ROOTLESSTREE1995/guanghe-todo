package cn.banxu.app;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.UUID;

/** All writes use one SQLite connection; original messages commit before extraction. */
public final class Store extends SQLiteOpenHelper {
    static final String AUTOMATIC_INTAKE_MIGRATION = "automatic_intake_v1";
    private static volatile Store instance;
    public static Store get(Context context) {
        if (instance == null) synchronized (Store.class) { if (instance == null) instance = new Store(context.getApplicationContext()); }
        return instance;
    }
    private Store(Context c) { super(c, "banxu.db", null, 1); setWriteAheadLoggingEnabled(true); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE inbox(id TEXT PRIMARY KEY,payload TEXT NOT NULL,status TEXT NOT NULL,is_demo INTEGER NOT NULL DEFAULT 0,attempts INTEGER NOT NULL DEFAULT 0,next_at INTEGER NOT NULL DEFAULT 0,received_at INTEGER NOT NULL)");
        db.execSQL("CREATE INDEX queue_idx ON inbox(status,next_at)");
        db.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,payload TEXT NOT NULL,source_id TEXT,source_index INTEGER,reminded_at INTEGER NOT NULL DEFAULT 0,updated_at INTEGER NOT NULL,is_demo INTEGER NOT NULL DEFAULT 0,UNIQUE(source_id,source_index))");
        db.execSQL("CREATE TABLE captures(notification_key TEXT PRIMARY KEY,content_hash TEXT NOT NULL,received_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY,value TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { throw new IllegalStateException("数据库需要迁移"); }
    public synchronized void recoverInterrupted() { getWritableDatabase().execSQL("UPDATE inbox SET status='pending' WHERE status='processing'"); }
    /** Updates the old confirmation queue without touching completed, demo, or manually waiting items. */
    public synchronized boolean activateLegacyReviews(int leadMinutes) throws Exception {
        if ("done".equals(meta(AUTOMATIC_INTAKE_MIGRATION))) return false;
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (!"pending-reminders".equals(meta(AUTOMATIC_INTAKE_MIGRATION))) {
                JSONArray items = allItems();
                for (int i = 0; i < items.length(); i++) {
                    JSONObject item = items.getJSONObject(i);
                    if (!DomainRules.shouldAutoActivate(item.optString("status"), item.optBoolean("isDemo"))) continue;
                    String status = DomainRules.automaticStatus(item.optString("kind"));
                    item.put("status", status).put("needsReview", false)
                        .put("remindAt", DomainRules.deriveReminder(item.optString("kind"), status, item.optLong("dueAt"), leadMinutes));
                    putItem(item, true);
                }
            }
            // Keep this pending until Repository has scheduled alarms after the transaction commits.
            setMeta(AUTOMATIC_INTAKE_MIGRATION, "pending-reminders");
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return true;
    }
    public synchronized String addInbox(String pkg, String title, String text, long receivedAt, String notificationKey, String hash) throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            if (notificationKey != null) {
                try (Cursor cur = db.rawQuery("SELECT content_hash,received_at FROM captures WHERE notification_key=?", new String[]{notificationKey})) {
                    if (cur.moveToFirst() && hash.equals(cur.getString(0)) && receivedAt - cur.getLong(1) < 86_400_000L) { db.setTransactionSuccessful(); return null; }
                }
                ContentValues seen = new ContentValues(); seen.put("notification_key", notificationKey); seen.put("content_hash", hash); seen.put("received_at", receivedAt);
                db.insertWithOnConflict("captures", null, seen, SQLiteDatabase.CONFLICT_REPLACE);
                db.delete("captures", "received_at<?", new String[]{String.valueOf(receivedAt - 7 * 86_400_000L)});
            }
            String id = UUID.randomUUID().toString();
            JSONObject raw = new JSONObject().put("id", id).put("packageName", pkg).put("title", title).put("text", text)
                .put("receivedAt", receivedAt).put("receivedZone", java.time.ZoneId.systemDefault().getId())
                .put("status", "pending").put("error", "").put("isDemo", false);
            ContentValues values = new ContentValues(); values.put("id", id); values.put("payload", raw.toString()); values.put("status", "pending"); values.put("received_at", receivedAt);
            db.insertOrThrow("inbox", null, values); db.setTransactionSuccessful(); return id;
        } finally { db.endTransaction(); }
    }
    public synchronized JSONArray allItems() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT payload FROM items ORDER BY updated_at DESC", null)) { while (cur.moveToNext()) out.put(new JSONObject(cur.getString(0))); }
        return out;
    }
    public synchronized JSONArray allInbox() throws Exception {
        JSONArray out = new JSONArray();
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT payload,status FROM inbox ORDER BY received_at DESC", null)) {
            while (cur.moveToNext()) out.put(new JSONObject(cur.getString(0)).put("status", cur.getString(1)));
        }
        return out;
    }
    public synchronized JSONObject item(String id) throws Exception {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT payload FROM items WHERE id=?", new String[]{id})) { return cur.moveToFirst() ? new JSONObject(cur.getString(0)) : null; }
    }
    public synchronized JSONObject inbox(String id) throws Exception {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT payload,status,attempts FROM inbox WHERE id=?", new String[]{id})) {
            return cur.moveToFirst() ? new JSONObject(cur.getString(0)).put("status", cur.getString(1)).put("attempts", cur.getInt(2)) : null;
        }
    }
    public synchronized void putItem(JSONObject item, boolean resetReminder) throws Exception {
        String id = item.getString("id"); ContentValues v = new ContentValues(); v.put("payload", item.toString()); v.put("updated_at", item.getLong("updatedAt")); v.put("is_demo", item.optBoolean("isDemo") ? 1 : 0);
        if (resetReminder) v.put("reminded_at", 0);
        if (getWritableDatabase().update("items", v, "id=?", new String[]{id}) == 0) { v.put("id", id); getWritableDatabase().insertOrThrow("items", null, v); }
    }
    public synchronized void deleteItem(String id) { getWritableDatabase().delete("items", "id=?", new String[]{id}); }
    public synchronized JSONArray itemIdsForSource(String sourceId) {
        JSONArray ids = new JSONArray();
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT id FROM items WHERE source_id=?", new String[]{sourceId})) {
            while (cur.moveToNext()) ids.put(cur.getString(0));
        }
        return ids;
    }
    /** Repository holds this Store's monitor while cancelling associated reminders first. */
    public synchronized void deleteInbox(String id) {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            db.delete("items", "source_id=?", new String[]{id});
            db.delete("inbox", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    public synchronized long remindedAt(String id) {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT reminded_at FROM items WHERE id=?", new String[]{id})) { return cur.moveToFirst() ? cur.getLong(0) : 0; }
    }
    public synchronized void markReminded(String id, long at) { ContentValues v = new ContentValues(); v.put("reminded_at", at); getWritableDatabase().update("items", v, "id=?", new String[]{id}); }
    public synchronized boolean hasPending() {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT 1 FROM inbox WHERE status='pending' AND is_demo=0 LIMIT 1", null)) { return cur.moveToFirst(); }
    }
    public synchronized boolean isProcessing() {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT 1 FROM inbox WHERE status='processing' LIMIT 1", null)) { return cur.moveToFirst(); }
    }
    public synchronized long nextPendingAt() {
        try (Cursor cur = getReadableDatabase().rawQuery("SELECT MIN(next_at) FROM inbox WHERE status='pending' AND is_demo=0", null)) { return cur.moveToFirst() ? cur.getLong(0) : 0; }
    }
    public synchronized JSONObject claim() throws Exception {
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            try (Cursor cur = db.rawQuery("SELECT id FROM inbox WHERE status='pending' AND is_demo=0 AND next_at<=? ORDER BY received_at LIMIT 1", new String[]{String.valueOf(System.currentTimeMillis())})) {
                if (!cur.moveToFirst()) { db.setTransactionSuccessful(); return null; }
                String id = cur.getString(0); db.execSQL("UPDATE inbox SET status='processing',attempts=attempts+1 WHERE id=?", new Object[]{id});
                JSONObject raw = inbox(id); db.setTransactionSuccessful(); return raw;
            }
        } finally { db.endTransaction(); }
    }
    public synchronized JSONArray finishExtraction(String id, JSONArray extracted) throws Exception {
        JSONArray committed = new JSONArray();
        SQLiteDatabase db = getWritableDatabase(); db.beginTransaction();
        try {
            JSONObject raw = inbox(id);
            // Dismissal while an HTTP request is in flight must win.
            if (raw == null || !"processing".equals(raw.optString("status"))) { db.setTransactionSuccessful(); return committed; }
            for (int i = 0; i < extracted.length(); i++) {
                JSONObject item = extracted.getJSONObject(i); ContentValues v = new ContentValues();
                v.put("id", item.getString("id")); v.put("payload", item.toString()); v.put("source_id", id); v.put("source_index", i); v.put("updated_at", item.getLong("updatedAt"));
                if (db.insertWithOnConflict("items", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1) committed.put(item);
            }
            setInboxStatus(id, "processed", "", false, 0); clearExtractionErrorIfRecovered(); db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        return committed;
    }
    public synchronized void failExtraction(String id, String message, boolean retry) throws Exception {
        JSONObject raw = inbox(id); if (raw == null || !"processing".equals(raw.optString("status"))) return;
        boolean again = retry && raw.optInt("attempts") < 3;
        if (!again) message = message.replace("稍后自动重试", "请手动重试");
        setInboxStatus(id, again ? "pending" : "error", message, false, again ? System.currentTimeMillis() + (long)Math.pow(2, raw.optInt("attempts")) * 30_000 : 0);
        setMeta("lastError", message);
    }
    public synchronized void setInboxStatus(String id, String status, String error, boolean resetAttempts, long nextAt) throws Exception {
        JSONObject raw = inbox(id); if (raw == null) throw new IllegalArgumentException("消息不存在");
        raw.remove("attempts"); raw.put("status", status).put("error", error);
        ContentValues v = new ContentValues(); v.put("status", status); v.put("payload", raw.toString()); v.put("next_at", nextAt); if (resetAttempts) v.put("attempts", 0);
        getWritableDatabase().update("inbox", v, "id=?", new String[]{id});
    }
    public synchronized void resetPendingDelay() { getWritableDatabase().execSQL("UPDATE inbox SET next_at=0 WHERE status='pending'"); }
    private void clearExtractionErrorIfRecovered() {
        String error = meta("lastError");
        boolean extractionError = error.startsWith("AI ") || error.startsWith("API Key") || error.startsWith("DeepSeek")
            || error.startsWith("网络连接") || error.startsWith("模型或请求") || error.startsWith("请求过于")
            || error.startsWith("整理") || error.startsWith("本地整理") || error.startsWith("后台整理");
        if (!extractionError) return;
        try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM inbox WHERE status IN ('error','pending') AND is_demo=0 LIMIT 1", null)) {
            if (!c.moveToFirst()) setMeta("lastError", "");
        }
    }
    public synchronized void clearErrorStartingWith(String... prefixes) {
        String error = meta("lastError");
        for (String prefix : prefixes) if (error.startsWith(prefix)) { setMeta("lastError", ""); return; }
    }
    public synchronized void setMeta(String key, String value) { ContentValues v = new ContentValues(); v.put("name", key); v.put("value", value); getWritableDatabase().insertWithOnConflict("metadata", null, v, SQLiteDatabase.CONFLICT_REPLACE); }
    public synchronized String meta(String key) { try (Cursor c = getReadableDatabase().rawQuery("SELECT value FROM metadata WHERE name=?", new String[]{key})) { return c.moveToFirst() ? c.getString(0) : ""; } }
    public synchronized boolean hasDemo() { try (Cursor c = getReadableDatabase().rawQuery("SELECT 1 FROM items WHERE is_demo=1 LIMIT 1", null)) { return c.moveToFirst(); } }
    public synchronized void clearDemo() { SQLiteDatabase db = getWritableDatabase(); db.beginTransaction(); try { db.delete("items", "is_demo=1", null); db.delete("inbox", "is_demo=1", null); db.setTransactionSuccessful(); } finally { db.endTransaction(); } }
}
