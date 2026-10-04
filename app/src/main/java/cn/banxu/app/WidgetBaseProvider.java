package cn.banxu.app;

import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;

abstract class WidgetBaseProvider extends AppWidgetProvider {
    @Override public void onReceive(Context c, Intent intent) {
        // Own one PendingResult for the entire broadcast, including combined enable/update events.
        super.onReceive(c, intent);
        WidgetUpdater.updateFromBroadcast(c, goAsync());
    }
}
