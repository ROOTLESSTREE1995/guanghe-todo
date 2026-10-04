package cn.banxu.app;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;
import java.lang.ref.WeakReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BanxuApp extends Application {
    static final ExecutorService IO = Executors.newSingleThreadExecutor();
    static final ExecutorService NETWORK = Executors.newSingleThreadExecutor();
    private static WeakReference<MainActivity> activity = new WeakReference<>(null);
    private static volatile BanxuApp app;
    static void attach(MainActivity a) { activity = new WeakReference<>(a); }
    static void changed() {
        new Handler(Looper.getMainLooper()).post(() -> { MainActivity a = activity.get(); if (a != null) a.notifyWeb(); });
        if (app != null) WidgetUpdater.requestUpdate(app);
    }
    @Override public void onCreate() {
        super.onCreate();
        app = this;
        ReminderScheduler.createChannel(this);
        // Complete crash recovery before Android can start a new JobService in this process.
        Store.get(this).recoverInterrupted();
        try { Repository.activateLegacyReviews(this); }
        catch (Exception ex) { Store.get(this).setMeta("lastError", "自动待办升级尚未完成，请重新打开应用重试"); }
        // Recover the tiny commit-to-AlarmManager window even when a notification service
        // restarts this process without opening an Activity.
        ReminderScheduler.rescheduleAll(this);
        WidgetUpdater.requestUpdate(this);
        Repository.process(this);
    }
    @Override public void onConfigurationChanged(android.content.res.Configuration configuration) {
        super.onConfigurationChanged(configuration);
        WidgetUpdater.requestUpdate(this);
    }
}
