package cn.banxu.app;

import android.app.AlarmManager;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;

public final class ReminderScheduler {
    public static final String CHANNEL = "followups_v1";
    private ReminderScheduler() {}
    static void createChannel(Context c) {
        NotificationChannel channel = new NotificationChannel(CHANNEL, "待办与返校核查", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("到点提醒核查，学生是否返校须由老师确认");
        channel.setLockscreenVisibility(android.app.Notification.VISIBILITY_PRIVATE);
        c.getSystemService(NotificationManager.class).createNotificationChannel(channel);
    }
    private static PendingIntent pending(Context c, String id) {
        Intent intent = new Intent(c, ReminderReceiver.class).setAction("cn.banxu.app.REMIND").setData(Uri.parse("banxu://reminder/" + Uri.encode(id))).putExtra("itemId", id);
        return PendingIntent.getBroadcast(c, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    static void cancel(Context c, String id) {
        c.getSystemService(AlarmManager.class).cancel(pending(c, id));
        c.getSystemService(NotificationManager.class).cancel(id, 1);
    }
    static void schedule(Context c, JSONObject item) {
        String id = item.optString("id");
        c.getSystemService(AlarmManager.class).cancel(pending(c, id));
        if (!DomainRules.shouldRemind(item.optString("kind"), item.optString("status"), item.optLong("remindAt"), item.optBoolean("isDemo"))) {
            c.getSystemService(NotificationManager.class).cancel(id, 1); return;
        }
        if (Store.get(c).remindedAt(id) > 0) return;
        c.getSystemService(NotificationManager.class).cancel(id, 1);
        // Do not mark delivered when notification permission is denied; reschedule on return.
        if (!Repository.canNotify(c)) return;
        long when = Math.max(System.currentTimeMillis() + 1000, item.optLong("remindAt"));
        AlarmManager manager = c.getSystemService(AlarmManager.class);
        try {
            if (Repository.canExact(c)) manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c, id));
            else manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c, id));
        } catch (SecurityException ex) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, when, pending(c, id));
        }
    }
    static void rescheduleAll(Context c) {
        // A deletion or edit must not race a snapshot and recreate an obsolete alarm.
        synchronized (Repository.class) {
            try { JSONArray items = Store.get(c).allItems(); for (int i = 0; i < items.length(); i++) schedule(c, items.getJSONObject(i)); Store.get(c).clearErrorStartingWith("提醒恢复失败"); }
            catch (Exception ex) { Store.get(c).setMeta("lastError", "提醒恢复失败，请打开事项重新保存时间"); }
        }
    }
}
