package cn.banxu.app;

import android.Manifest;
import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Calendar;
import java.util.UUID;

public final class Repository {
    private Repository() {}
    private static volatile String apiStatus = "idle", apiMessage = "";
    public static JSONObject state(Context c) throws Exception {
        Store store = Store.get(c);
        JSONObject permissions = new JSONObject().put("notificationAccess", notificationAccess(c)).put("postNotifications", canNotify(c))
            .put("exactAlarms", canExact(c)).put("batteryOptimized", !c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName()));
        return new JSONObject().put("settings", new SecureSettings(c).publicJson()).put("permissions", permissions)
            .put("items", store.allItems()).put("inbox", store.allInbox()).put("lastError", store.meta("lastError"))
            .put("processing", store.isProcessing() || store.hasPending()).put("version", "0.3.0")
            .put("apiTest", new JSONObject().put("status", apiStatus).put("message", apiMessage));
    }
    static boolean notificationAccess(Context c) {
        String enabled = Settings.Secure.getString(c.getContentResolver(), "enabled_notification_listeners");
        if (enabled == null) return false;
        ComponentName own = new ComponentName(c, CaptureService.class);
        for (String item : enabled.split(":")) if (own.equals(ComponentName.unflattenFromString(item))) return true;
        return false;
    }
    static boolean canNotify(Context c) {
        NotificationManager manager = c.getSystemService(NotificationManager.class);
        android.app.NotificationChannel channel = manager.getNotificationChannel(ReminderScheduler.CHANNEL);
        return (Build.VERSION.SDK_INT < 33 || c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
                && manager.areNotificationsEnabled() && (channel == null || channel.getImportance() != NotificationManager.IMPORTANCE_NONE);
    }
    static boolean canExact(Context c) { return Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms(); }
    static boolean sourceAllowed(JSONObject settings, JSONObject raw) {
        if ("manual".equals(raw.optString("packageName"))) return true;
        JSONArray allowed = settings.optJSONArray("allowedPackages"); boolean selected = false;
        if (allowed != null) for (int i = 0; i < allowed.length(); i++) if (raw.optString("packageName").equals(allowed.optString(i))) selected = true;
        return selected && DomainRules.matchesConversation(raw.optString("title"), settings.optString("conversationFilters"));
    }
    public static void saveSettings(Context c, JSONObject patch) throws Exception {
        new SecureSettings(c).save(patch);
        Store.get(c).resetPendingDelay();
        process(c); BanxuApp.changed();
    }
    public static void manual(Context c, JSONObject input) throws Exception {
        String text = requiredText(input, "text", 16000), title = input.optString("title", "手动录入").trim();
        if (title.isEmpty()) title = "手动录入";
        if (title.length() > 300) throw new IllegalArgumentException("消息标题过长");
        Store.get(c).addInbox("manual", title, text, System.currentTimeMillis(), null, null);
        process(c); BanxuApp.changed();
    }
    public static synchronized JSONObject saveItem(Context c, JSONObject patch) throws Exception {
        Store store = Store.get(c); long now = System.currentTimeMillis();
        String id = patch.optString("id", ""); boolean create = id.isEmpty();
        JSONObject item = create ? blankItem(now) : store.item(id);
        if (item == null) throw new IllegalArgumentException("事项不存在");
        String oldStatus = item.optString("status"), oldKind = item.optString("kind"); long oldReminder = item.optLong("remindAt"), oldDue = item.optLong("dueAt");
        for (String name : new String[]{"kind", "title", "student", "detail", "status"}) if (patch.has(name)) {
            if (!(patch.get(name) instanceof String)) throw new IllegalArgumentException("事项格式不正确");
            String s = patch.getString(name).trim();
            if (s.length() > (name.equals("detail") ? 6000 : 300)) throw new IllegalArgumentException("事项内容过长");
            item.put(name, s);
        }
        String kind = item.getString("kind");
        if (create && !patch.has("status")) item.put("status", DomainRules.automaticStatus(kind));
        String status = item.getString("status");
        if (!DomainRules.KINDS.contains(kind) || !DomainRules.STATUSES.contains(status)) throw new IllegalArgumentException("事项类型或状态无效");
        if (item.optString("title").isEmpty()) throw new IllegalArgumentException("请填写事项标题");
        if (kind.equals("leave")) {
            if (!(status.equals("review") || status.equals("tracking") || status.equals("approved") || status.equals("returned") || status.equals("ignored"))) throw new IllegalArgumentException("请假状态无效");
        } else if (status.equals("tracking") || status.equals("approved") || status.equals("returned")) throw new IllegalArgumentException("该状态只适用于请假");
        validateTimeMetadata(patch);
        if (patch.has("dueAt")) {
            long due = DomainRules.validatedTimestamp(patch.get("dueAt"));
            item.put("dueAt", due);
            if (create || oldDue != due) item.put("dueText", "").put("timePrecision", due > 0 ? "manual" : "unknown").put("timeNote", "");
        }
        if (kind.equals("leave") && status.equals("approved")) {
            if (item.optString("student").isEmpty()) throw new IllegalArgumentException("批准前请确认学生姓名");
            if (item.optLong("dueAt") == 0) throw new IllegalArgumentException("批准前请确认预计返校时间");
        }
        if (patch.has("remindAt")) item.put("remindAt", DomainRules.validatedTimestamp(patch.get("remindAt")));
        else if (create || oldDue != item.optLong("dueAt") || !oldStatus.equals(status) || !oldKind.equals(kind))
            item.put("remindAt", DomainRules.deriveReminder(kind, status, item.optLong("dueAt"), new SecureSettings(c).publicJson().optInt("leadMinutes", 15)));
        if (DomainRules.isClosed(status) || status.equals("review") || item.optBoolean("isDemo")) item.put("remindAt", 0);
        item.put("needsReview", status.equals("review")).put("updatedAt", now);
        boolean reset = oldReminder != item.optLong("remindAt") || !oldStatus.equals(status);
        store.putItem(item, reset); ReminderScheduler.schedule(c, item); BanxuApp.changed(); return item;
    }
    static JSONObject blankItem(long now) throws Exception {
        return new JSONObject().put("id", UUID.randomUUID().toString()).put("kind", "task").put("title", "")
            .put("student", "").put("detail", "").put("status", "todo").put("dueAt", 0).put("remindAt", 0)
            .put("createdAt", now).put("updatedAt", now).put("sourceId", "").put("sourceTitle", "").put("sourceText", "")
            .put("sourcePackage", "").put("confidence", 1.0).put("needsReview", false).put("isDemo", false)
            .put("dueText", "").put("timePrecision", "unknown").put("timeNote", "");
    }
    static JSONObject extractedItem(JSONObject candidate, JSONObject raw, int leadMinutes) throws Exception {
        JSONObject item = blankItem(System.currentTimeMillis());
        String kind = candidate.getString("kind"), status = DomainRules.automaticStatus(kind);
        long dueAt = DomainRules.validatedTimestamp(candidate.opt("dueAt") == null ? 0L : candidate.opt("dueAt"));
        validateTimeMetadata(candidate);
        item.put("kind", kind).put("title", candidate.getString("title"))
            .put("student", candidate.optString("student", "")).put("detail", candidate.optString("detail", ""))
            .put("dueAt", dueAt).put("confidence", candidate.optDouble("confidence", 0))
            .put("status", status).put("needsReview", false).put("sourceId", raw.getString("id"))
            .put("remindAt", DomainRules.deriveReminder(kind, status, dueAt, leadMinutes))
            .put("dueText", candidate.optString("dueText", "")).put("timePrecision", candidate.optString("timePrecision", dueAt > 0 ? "exact" : "unknown"))
            .put("timeNote", candidate.optString("timeNote", ""))
            .put("sourceTitle", raw.optString("title")).put("sourceText", raw.optString("text")).put("sourcePackage", raw.optString("packageName"));
        return item;
    }
    private static void validateTimeMetadata(JSONObject value) throws Exception {
        for (String name : new String[]{"dueText", "timePrecision", "timeNote"}) {
            if (!value.has(name)) continue;
            if (!(value.get(name) instanceof String) || value.getString(name).length() > (name.equals("timePrecision") ? 20 : 600))
                throw new IllegalArgumentException("时间说明格式不正确");
        }
        if (value.has("timePrecision") && !java.util.Arrays.asList("exact", "estimated", "unknown", "manual").contains(value.getString("timePrecision")))
            throw new IllegalArgumentException("时间精度无效");
    }
    /** One lock order (Repository, then Store) covers commit, edits, deletion, and alarm creation. */
    static synchronized void finishExtraction(Context c, String sourceId, JSONArray extracted) throws Exception {
        Store store = Store.get(c);
        synchronized (store) {
            JSONArray committed = store.finishExtraction(sourceId, extracted);
            for (int i = 0; i < committed.length(); i++) ReminderScheduler.schedule(c, committed.getJSONObject(i));
        }
    }
    public static synchronized void activateLegacyReviews(Context c) throws Exception {
        Store store = Store.get(c);
        synchronized (store) {
            int leadMinutes = new SecureSettings(c).publicJson().optInt("leadMinutes", 15);
            if (!store.activateLegacyReviews(leadMinutes)) return;
            // Pending migration recovery reschedules persisted items after a crash without changing them again.
            JSONArray items = store.allItems();
            for (int i = 0; i < items.length(); i++) ReminderScheduler.schedule(c, items.getJSONObject(i));
            store.setMeta(Store.AUTOMATIC_INTAKE_MIGRATION, "done");
            store.clearErrorStartingWith("自动待办升级");
        }
    }
    public static synchronized void delete(Context c, String id) { ReminderScheduler.cancel(c, id); Store.get(c).deleteItem(id); BanxuApp.changed(); }
    public static synchronized void deleteInbox(Context c, String id) throws Exception {
        Store store = Store.get(c);
        // Shares saveItem's class lock; an edit cannot reinsert a just-deleted source item.
        // Shares finishExtraction's locks through commit and scheduling; an in-flight response
        // cannot insert items or recreate alarms after this deletion.
        synchronized (store) {
            JSONArray ids = store.itemIdsForSource(id);
            for (int i = 0; i < ids.length(); i++) ReminderScheduler.cancel(c, ids.getString(i));
            store.deleteInbox(id);
        }
        BanxuApp.changed();
    }
    public static void retry(Context c, String id) throws Exception {
        Store store = Store.get(c); JSONObject raw = store.inbox(id);
        if (raw == null || raw.optBoolean("isDemo") || !raw.optString("status").equals("error")) throw new IllegalArgumentException("仅失败消息可重试");
        store.setInboxStatus(id, "pending", "", true, 0); process(c); BanxuApp.changed();
    }
    public static void dismiss(Context c, String id) throws Exception { Store.get(c).setInboxStatus(id, "ignored", "", false, 0); BanxuApp.changed(); }
    public static void process(Context c) {
        Context app = c.getApplicationContext();
        BanxuApp.IO.execute(() -> {
            try {
                JSONObject settings = new SecureSettings(app).publicJson();
                if (settings.optBoolean("cloudEnabled") && settings.optBoolean("hasApiKey")) { ExtractionJobService.schedule(app); return; }
                Store store = Store.get(app); store.resetPendingDelay(); JSONObject raw;
                while ((raw = store.claim()) != null) {
                    try {
                        JSONArray items = new JSONArray(); String text = raw.optString("text");
                        String kind = DomainRules.localKind(raw.optString("title") + "\n" + text);
                        if (kind == null && "manual".equals(raw.optString("packageName"))) kind = "task";
                        if (kind != null) {
                            JSONObject candidate = new JSONObject().put("kind", kind).put("title", DomainRules.clipped(text.replace('\n', ' '), 70))
                                .put("student", "").put("detail", "本地关键词整理。\n" + DomainRules.clipped(text, 2000)).put("dueAt", 0).put("confidence", 0.3)
                                .put("dueText", "").put("timePrecision", "unknown").put("timeNote", "已自动记录；本地整理未确定时间，暂不设到点提醒。");
                            items.put(extractedItem(candidate, raw, settings.optInt("leadMinutes", 15)));
                        }
                        finishExtraction(app, raw.getString("id"), items);
                    } catch (Exception ex) { store.failExtraction(raw.getString("id"), "本地整理失败，可重试或手动录入", false); }
                }
                BanxuApp.changed();
            } catch (Exception ex) { Store.get(app).setMeta("lastError", "整理暂不可用，请检查设置后重试"); BanxuApp.changed(); }
        });
    }
    public static synchronized void testApi(Context c) {
        if (apiStatus.equals("running")) return;
        apiStatus = "running"; apiMessage = "正在连接 DeepSeek…"; BanxuApp.changed();
        // Dedicated worker means a long extraction queue does not make the button appear stuck.
        new Thread(() -> {
            try {
                SecureSettings settings = new SecureSettings(c); String key = settings.apiKey();
                if (key.isEmpty()) throw new DeepSeekClient.ApiException("请先保存 API Key", false);
                DeepSeekClient.test(key, settings.publicJson().optString("model")); apiStatus = "success"; apiMessage = "连接成功，模型可以返回 JSON";
            } catch (DeepSeekClient.ApiException ex) { apiStatus = "error"; apiMessage = ex.getMessage(); }
            catch (Exception ex) { apiStatus = "error"; apiMessage = "连接失败，请检查网络、密钥和模型名称"; }
            BanxuApp.changed();
        }, "banxu-api-test").start();
    }
    public static synchronized void seedDemo(Context c) throws Exception {
        Store store = Store.get(c); if (store.hasDemo()) return; long now = System.currentTimeMillis();
        Calendar day = Calendar.getInstance(); day.set(Calendar.HOUR_OF_DAY, 17); day.set(Calendar.MINUTE, 30); day.set(Calendar.SECOND, 0); day.set(Calendar.MILLISECOND, 0);
        if (day.getTimeInMillis() <= now) day.add(Calendar.DAY_OF_MONTH, 1);
        JSONObject[] examples = {
            demo("task", "收齐家长会回执", "", "todo", day.getTimeInMillis(), "示例：周五前收齐回执，并核对联系方式。"),
            demo("leave", "陈沐阳 · 等待返校核查", "陈沐阳", "tracking", day.getTimeInMillis(), "示例：家长申请就诊，预计放学前返校。"),
            demo("followup", "向家长反馈谈话情况", "林一诺", "waiting", 0, "示例：等待科任老师反馈后，联系家长。"),
            demo("task", "完成活动报名", "", "todo", 0, "示例：通知里只说尽快，已自动记录，暂不设到点提醒。")
        };
        for (JSONObject item : examples) store.putItem(item, false); BanxuApp.changed();
    }
    private static JSONObject demo(String kind, String title, String student, String status, long due, String detail) throws Exception {
        return blankItem(System.currentTimeMillis()).put("kind", kind).put("title", title).put("student", student).put("status", status)
            .put("dueAt", due).put("detail", detail).put("isDemo", true).put("sourceTitle", "演示示例 · 虚构人物")
            .put("sourceText", detail).put("sourcePackage", "demo").put("needsReview", status.equals("review"));
    }
    static String requiredText(JSONObject input, String name, int max) throws Exception {
        if (!(input.opt(name) instanceof String)) throw new IllegalArgumentException("请填写文字内容");
        String value = input.getString(name).trim(); if (value.isEmpty() || value.length() > max) throw new IllegalArgumentException("内容不能为空，且不能超过" + max + "字"); return value;
    }
}
