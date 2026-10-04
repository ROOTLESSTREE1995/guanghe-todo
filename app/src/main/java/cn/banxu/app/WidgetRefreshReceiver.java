package cn.banxu.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public final class WidgetRefreshReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent intent) { WidgetUpdater.updateFromBroadcast(c, goAsync(), intent); }
}
