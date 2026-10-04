package cn.banxu.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!(Intent.ACTION_BOOT_COMPLETED.equals(action) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action) || Intent.ACTION_DATE_CHANGED.equals(action)
                || "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED".equals(action))) return;
        PendingResult pending = goAsync(); Context app = c.getApplicationContext();
        WidgetUpdater.requestUpdate(app);
        BanxuApp.IO.execute(() -> {
            try { ReminderScheduler.rescheduleAll(app); Repository.process(app); }
            finally { pending.finish(); }
        });
    }
}
