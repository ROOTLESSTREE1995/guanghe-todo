package cn.banxu.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

/** Explicit local PendingIntents only; database work never blocks the broadcast thread. */
public final class WidgetActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !WidgetActions.ACTION.equals(intent.getAction())) return;
        Context app = context.getApplicationContext();
        PendingResult pending = goAsync();
        BanxuApp.IO.execute(() -> {
            try {
                String message = WidgetActions.receive(app, intent);
                if (!message.isEmpty()) new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(app, message, Toast.LENGTH_SHORT).show());
            } finally {
                WidgetUpdater.requestUpdate(app);
                pending.finish();
            }
        });
    }
}
