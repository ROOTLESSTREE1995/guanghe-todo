package cn.banxu.app;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import org.json.JSONObject;

public final class ReminderReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String id = intent.getStringExtra("itemId"); if (id == null) return;
        try {
            // Serialize reading, notification delivery, and delivery marking against edits/deletes.
            // Use the same Repository -> Store order as extraction commits and migration.
            synchronized (Repository.class) {
            Store store = Store.get(c);
            synchronized (store) {
            JSONObject item = store.item(id);
            if (item == null || !DomainRules.shouldRemind(item.optString("kind"), item.optString("status"), item.optLong("remindAt"), item.optBoolean("isDemo"))) return;
            if (item.optLong("remindAt") > System.currentTimeMillis() + 5000) { ReminderScheduler.schedule(c, item); return; }
            if (store.remindedAt(id) > 0) return;
            if (!Repository.canNotify(c)) { store.setMeta("lastError", "提醒未显示：请开启光合待办通知权限。待办仍保留在应用内。"); BanxuApp.changed(); return; }
            NotificationManager manager = c.getSystemService(NotificationManager.class);
            android.app.NotificationChannel channel = manager.getNotificationChannel(ReminderScheduler.CHANNEL);
            if (channel != null && channel.getImportance() == NotificationManager.IMPORTANCE_NONE) { store.setMeta("lastError", "提醒频道已关闭，请在系统通知设置中开启「待办与返校核查」"); BanxuApp.changed(); return; }
            Intent open = new Intent(c, MainActivity.class).setData(android.net.Uri.parse("banxu://item/" + android.net.Uri.encode(id)))
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("itemId", id);
            PendingIntent content = PendingIntent.getActivity(c, id.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            boolean leave = "leave".equals(item.optString("kind"));
            String student = item.optString("student").trim();
            String title = leave ? "请核查 " + (student.isEmpty() ? "学生" : student) + " 是否返校" : item.optString("title");
            String text = leave ? "预计返校时间已到。核实后在光合待办中确认返校或续假。" : "待办提醒 · 打开光合待办查看原消息与处理进度";
            Notification notification = new Notification.Builder(c, ReminderScheduler.CHANNEL).setSmallIcon(cn.banxu.app.R.drawable.ic_notification)
                .setContentTitle(title).setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(content)
                .setAutoCancel(true).setCategory(Notification.CATEGORY_REMINDER).setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(new Notification.Builder(c, ReminderScheduler.CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("光合待办事务提醒").setContentText("打开应用查看详情").build())
                .build();
            manager.notify(id, 1, notification); store.markReminded(id, System.currentTimeMillis()); store.clearErrorStartingWith("提醒未显示", "提醒频道", "系统提醒"); BanxuApp.changed();
            }
            }
        } catch (Exception ex) { Store.get(c).setMeta("lastError", "系统提醒未能显示，请在应用内核查待办"); BanxuApp.changed(); }
    }
}
