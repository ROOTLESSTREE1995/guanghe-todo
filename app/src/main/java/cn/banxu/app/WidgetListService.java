package cn.banxu.app;

import android.content.Context;
import android.content.Intent;
import android.widget.RemoteViews;
import android.widget.RemoteViewsService;
import java.util.Collections;
import java.util.List;

/** Android 8–11 collection adapter; Android 12+ receives RemoteCollectionItems directly. */
public final class WidgetListService extends RemoteViewsService {
    @Override public RemoteViewsFactory onGetViewFactory(Intent intent) {
        return new Factory(getApplicationContext(), intent.getBooleanExtra("leaveWidget", false),
            intent.getIntExtra(android.appwidget.AppWidgetManager.EXTRA_APPWIDGET_ID, -1));
    }
    private static final class Factory implements RemoteViewsFactory {
        private final Context context;
        private final boolean leaves;
        private final int widgetId;
        private volatile List<WidgetModel.Item> items = Collections.emptyList();
        private volatile boolean failed;
        Factory(Context c, boolean leaves, int id) { context = c; this.leaves = leaves; widgetId = id; }
        @Override public void onCreate() { onDataSetChanged(); }
        @Override public void onDataSetChanged() {
            WidgetUpdater.Snapshot snapshot = WidgetUpdater.read(context);
            items = WidgetModel.page(WidgetModel.select(snapshot.items, leaves), WidgetUpdater.storedPage(context, widgetId)); failed = snapshot.failed;
        }
        @Override public void onDestroy() { items = Collections.emptyList(); }
        @Override public int getCount() { return failed ? 1 : items.size(); }
        @Override public RemoteViews getViewAt(int position) {
            if (failed) {
                RemoteViews row = new RemoteViews(context.getPackageName(), R.layout.widget_row);
                row.setTextViewText(R.id.widget_row_title, "暂时无法读取事项");
                row.setTextViewText(R.id.widget_row_time, "点击打开光合待办重试");
                row.setOnClickFillInIntent(R.id.widget_row, new Intent().putExtra("route", leaves ? "leaves" : "today"));
                return row;
            }
            List<WidgetModel.Item> current = items;
            return position >= 0 && position < current.size() ? WidgetUpdater.row(context, current.get(position), System.currentTimeMillis()) : null;
        }
        @Override public RemoteViews getLoadingView() { return null; }
        @Override public int getViewTypeCount() { return 1; }
        @Override public long getItemId(int position) {
            List<WidgetModel.Item> current = items;
            return !failed && position >= 0 && position < current.size() ? WidgetModel.stableId(current.get(position).id) : position;
        }
        @Override public boolean hasStableIds() { return true; }
    }
}
