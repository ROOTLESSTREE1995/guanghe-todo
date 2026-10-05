package cn.banxu.app;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.RemoteViews;
import org.json.JSONArray;
import org.json.JSONObject;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** One coalesced local refresh updates every size; no messages or API keys leave the device. */
public final class WidgetUpdater {
    private static final ScheduledExecutorService WORK = Executors.newSingleThreadScheduledExecutor();
    private static ScheduledFuture<?> queued;
    static final String PAGE_ACTION = "cn.banxu.app.WIDGET_PAGE";
    private static final Set<Integer> RESET_SCROLL = ConcurrentHashMap.newKeySet();
    private static final class Variant {
        final Class<? extends WidgetBaseProvider> provider;
        final boolean leaves;
        final String size;
        Variant(Class<? extends WidgetBaseProvider> provider, boolean leaves, String size) {
            this.provider = provider; this.leaves = leaves; this.size = size;
        }
        int width() { return "list".equals(size) ? 300 : 160; }
        int height() { return "mini".equals(size) ? 64 : 160; }
    }
    private static final Variant[] VARIANTS = {
        new Variant(TodoWidgetProvider.class, false, "list"), new Variant(LeaveWidgetProvider.class, true, "list"),
        new Variant(TodoMiniWidgetProvider.class, false, "mini"), new Variant(LeaveMiniWidgetProvider.class, true, "mini"),
        new Variant(TodoCompactWidgetProvider.class, false, "compact"), new Variant(LeaveCompactWidgetProvider.class, true, "compact")
    };
    private WidgetUpdater() {}

    public static boolean isPinSupported(Context c) {
        return c.getSystemService(AppWidgetManager.class).isRequestPinAppWidgetSupported();
    }
    /** True means the launcher accepted the request, not that the user has placed the widget. */
    public static boolean pin(Context c, String kind) {
        return pin(c, kind, "list");
    }
    public static boolean pin(Context c, String kind, String size) {
        if (!("todo".equals(kind) || "leave".equals(kind)) || !isPinSupported(c)) return false;
        try {
            for (Variant variant : VARIANTS) if (variant.leaves == "leave".equals(kind) && variant.size.equals(size))
                return c.getSystemService(AppWidgetManager.class).requestPinAppWidget(new ComponentName(c, variant.provider), null, null);
            return false;
        } catch (RuntimeException ignored) { return false; }
    }
    private static Variant variant(Context c, ComponentName provider) {
        for (Variant variant : VARIANTS) if (new ComponentName(c, variant.provider).equals(provider)) return variant;
        return null;
    }
    private static int[] dimensions(Context c, AppWidgetManager manager, int id, Variant variant) {
        Bundle options = manager.getAppWidgetOptions(id);
        boolean landscape = c.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        int width = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH : AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, variant.width());
        int height = options.getInt(landscape ? AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT : AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, variant.height());
        return new int[]{width > 0 ? width : variant.width(), height > 0 ? height : variant.height()};
    }
    public static synchronized void requestUpdate(Context context) {
        Context app = context.getApplicationContext();
        if (queued != null) queued.cancel(false);
        queued = WORK.schedule(() -> updateAllNow(app), 250, TimeUnit.MILLISECONDS);
    }
    static void updateFromBroadcast(Context context, BroadcastReceiver.PendingResult pending) {
        updateFromBroadcast(context, pending, null);
    }
    static void updateFromBroadcast(Context context, BroadcastReceiver.PendingResult pending, Intent intent) {
        Context app = context.getApplicationContext();
        WORK.execute(() -> {
            try {
                if (intent != null && PAGE_ACTION.equals(intent.getAction())) changePage(app, intent);
                updateAllNow(app);
            } finally { pending.finish(); }
        });
    }
    static int storedPage(Context c, int widgetId) { return c.getSharedPreferences("home_widget_pages", Context.MODE_PRIVATE).getInt("page_" + widgetId, 0); }
    private static void savePage(Context c, int widgetId, int page) {
        c.getSharedPreferences("home_widget_pages", Context.MODE_PRIVATE).edit().putInt("page_" + widgetId, page).apply();
    }
    private static void changePage(Context c, Intent intent) {
        int id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1), delta = intent.getIntExtra("pageDelta", 0);
        if (id <= 0 || (delta != -1 && delta != 1)) return;
        AppWidgetManager manager = c.getSystemService(AppWidgetManager.class);
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(id);
        Variant variant = info == null ? null : variant(c, info.provider);
        if (variant == null) return;
        int[] size = dimensions(c, manager, id, variant);
        if (WidgetLayout.choose(size[0], size[1], c.getResources().getConfiguration().fontScale).mode != WidgetLayout.Mode.LIST) return;
        savePage(c, id, Math.max(0, storedPage(c, id) + delta)); RESET_SCROLL.add(id);
    }
    static final class Snapshot {
        final List<WidgetModel.Item> items;
        final boolean failed;
        Snapshot(List<WidgetModel.Item> items, boolean failed) { this.items = items; this.failed = failed; }
    }
    static Snapshot read(Context context) {
        try {
            JSONArray data = Store.get(context).allItems();
            List<WidgetModel.Item> items = new ArrayList<>();
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.getJSONObject(i);
                items.add(new WidgetModel.Item(item.optString("id"), item.optString("kind"), item.optString("status"),
                    item.optString("title"), item.optString("student").trim(), item.optString("timePrecision"),
                    item.optLong("dueAt"), item.optLong("updatedAt"), item.optBoolean("isDemo")));
            }
            return new Snapshot(items, false);
        } catch (Exception ignored) { return new Snapshot(Collections.emptyList(), true); }
    }
    private static void updateAllNow(Context c) {
        AppWidgetManager manager = c.getSystemService(AppWidgetManager.class);
        boolean hasWidgets = false;
        for (Variant variant : VARIANTS) if (manager.getAppWidgetIds(new ComponentName(c, variant.provider)).length > 0) hasWidgets = true;
        if (!hasWidgets) { cancelClock(c); return; }
        Snapshot snapshot = read(c);
        long now = System.currentTimeMillis();
        for (Variant variant : VARIANTS) for (int id : manager.getAppWidgetIds(new ComponentName(c, variant.provider))) {
            int[] size = dimensions(c, manager, id, variant);
            WidgetLayout layout = WidgetLayout.choose(size[0], size[1], c.getResources().getConfiguration().fontScale);
            if (layout.mode == WidgetLayout.Mode.LIST) updateOne(c, manager, id, variant.leaves, snapshot, now, size[1]);
            else updateSmall(c, manager, id, variant.leaves, snapshot, now, layout);
        }
        scheduleClock(c, WidgetModel.nextChange(snapshot.items, now, ZoneId.systemDefault()));
    }
    @SuppressWarnings("deprecation") // The service adapter is used only on Android 8–11.
    private static void updateOne(Context c, AppWidgetManager manager, int widgetId, boolean leaves, Snapshot snapshot, long now, int height) {
        List<WidgetModel.Item> items = WidgetModel.select(snapshot.items, leaves);
        int page = WidgetModel.clampPage(storedPage(c, widgetId), items.size()), pages = WidgetModel.pageCount(items.size());
        if (page != storedPage(c, widgetId)) { savePage(c, widgetId, page); RESET_SCROLL.add(widgetId); }
        List<WidgetModel.Item> visible = WidgetModel.page(items, page);
        RemoteViews view = new RemoteViews(c.getPackageName(), R.layout.widget_dashboard);
        view.setTextViewText(R.id.widget_title, leaves ? "请假返校" : "待办事项");
        view.setTextViewText(R.id.widget_count, snapshot.failed ? "—" : String.valueOf(items.size()));
        view.setTextColor(R.id.widget_count, c.getColor(leaves ? R.color.widget_amber : R.color.widget_green));
        view.setContentDescription(R.id.widget_header, leaves ? "查看全部请假与返校事项" : "查看全部待办事项");
        view.setOnClickPendingIntent(R.id.widget_header, open(c, widgetId, leaves, false));
        view.setOnClickPendingIntent(R.id.widget_empty, open(c, widgetId, leaves, false));
        view.setOnClickPendingIntent(R.id.widget_footer, open(c, widgetId, leaves, false));
        view.setPendingIntentTemplate(R.id.widget_list, leaves ? open(c, widgetId, true, true) : WidgetActions.listTemplate(c, widgetId));
        view.setTextViewText(R.id.widget_empty_title, snapshot.failed ? "暂时无法读取事项" : leaves ? "暂无待返校学生" : "眼前的事，都安排好了");
        view.setTextViewText(R.id.widget_empty_detail, snapshot.failed ? "点击打开光合待办重试。" : leaves ? "新识别的请假会自动出现在这里。" : "新整理的待办会自动出现在这里。");
        view.setViewVisibility(R.id.widget_list, items.isEmpty() ? View.GONE : View.VISIBLE);
        view.setViewVisibility(R.id.widget_empty, items.isEmpty() ? View.VISIBLE : View.GONE);
        String footer = snapshot.failed ? "点击打开应用" : items.isEmpty() ? "消息整理后自动同步" : "自动同步 · 上下滑动查看";
        view.setTextViewText(R.id.widget_footer, footer);
        view.setViewVisibility(R.id.widget_pager, pages > 1 ? View.VISIBLE : View.GONE);
        view.setTextViewText(R.id.widget_page_number, "第 " + (page + 1) + " / " + pages + " 页");
        view.setViewVisibility(R.id.widget_previous, page > 0 ? View.VISIBLE : View.INVISIBLE);
        view.setViewVisibility(R.id.widget_next, page < pages - 1 ? View.VISIBLE : View.INVISIBLE);
        view.setOnClickPendingIntent(R.id.widget_previous, pageIntent(c, widgetId, -1));
        view.setOnClickPendingIntent(R.id.widget_next, pageIntent(c, widgetId, 1));
        view.setOnClickPendingIntent(R.id.widget_page_number, open(c, widgetId, leaves, false));
        if (Build.VERSION.SDK_INT >= 31) {
            RemoteViews.RemoteCollectionItems.Builder collection = new RemoteViews.RemoteCollectionItems.Builder()
                .setHasStableIds(true).setViewTypeCount(1);
            for (WidgetModel.Item item : visible) {
                collection.addItem(WidgetModel.stableId(item.id), row(c, item, now));
            }
            view.setRemoteAdapter(R.id.widget_list, collection.build());
        } else {
            Intent adapter = new Intent(c, WidgetListService.class).putExtra("leaveWidget", leaves)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId)
                .setData(Uri.parse("banxu://widget-list/" + widgetId + "/" + (leaves ? "leave" : "todo") + "/" + page));
            view.setRemoteAdapter(R.id.widget_list, adapter);
        }
        // Very short placements still show at least one item; the footer yields its space.
        view.setViewVisibility(R.id.widget_footer, height < 180 || pages > 1 ? View.GONE : View.VISIBLE);
        boolean compactHeader = height < 190;
        view.setViewVisibility(R.id.widget_brand, compactHeader ? View.GONE : View.VISIBLE);
        view.setViewPadding(R.id.widget_root, dp(c, compactHeader ? 12 : 16), dp(c, compactHeader ? 8 : 16),
            dp(c, compactHeader ? 12 : 16), dp(c, compactHeader ? 8 : 16));
        view.setViewPadding(R.id.widget_header, 0, 0, 0, dp(c, compactHeader ? 0 : 10));
        view.setTextViewTextSize(R.id.widget_title, android.util.TypedValue.COMPLEX_UNIT_SP, compactHeader ? 17 : 20);
        WidgetActions.UndoSnapshot undo = WidgetActions.undoSnapshot(c, widgetId);
        boolean canUndo = !leaves && undo.available;
        view.setViewVisibility(R.id.widget_undo, canUndo ? View.VISIBLE : View.GONE);
        if (canUndo) {
            view.setTextViewText(R.id.widget_undo, "已完成 · 撤销");
            view.setOnClickPendingIntent(R.id.widget_undo, WidgetActions.undoIntent(c, widgetId));
            view.setViewVisibility(R.id.widget_footer, View.GONE);
            view.setViewVisibility(R.id.widget_pager, View.GONE);
        }
        if (RESET_SCROLL.remove(widgetId)) view.setScrollPosition(R.id.widget_list, 0);
        manager.updateAppWidget(widgetId, view);
        if (Build.VERSION.SDK_INT < 31) manager.notifyAppWidgetViewDataChanged(widgetId, R.id.widget_list);
    }
    private static void updateSmall(Context c, AppWidgetManager manager, int widgetId, boolean leaves, Snapshot snapshot, long now, WidgetLayout layout) {
        List<WidgetModel.Item> items = WidgetModel.select(snapshot.items, leaves);
        boolean mini = layout.mode == WidgetLayout.Mode.MINI;
        RemoteViews view = new RemoteViews(c.getPackageName(), mini ? R.layout.widget_mini : R.layout.widget_compact);
        view.setInt(R.id.widget_small_root, "setBackgroundResource", leaves ? R.drawable.widget_leave_card : R.drawable.widget_todo_card);
        int accent = c.getColor(leaves ? R.color.widget_amber : R.color.widget_green);
        view.setTextViewText(R.id.widget_small_title, leaves ? "请假" : "待办");
        view.setTextViewText(R.id.widget_small_count, snapshot.failed ? "—" : String.valueOf(items.size()));
        view.setTextColor(R.id.widget_small_title, accent);
        view.setTextColor(R.id.widget_small_count, accent);
        PendingIntent all = open(c, widgetId, leaves, false);
        view.setOnClickPendingIntent(R.id.widget_small_root, all);
        view.setOnClickPendingIntent(R.id.widget_small_header, all);
        String allDescription = (leaves ? "全部请假返校" : "全部待办") + "，" + (snapshot.failed ? "总数暂不可用" : items.size() + "项") + "，点击查看全部";
        view.setContentDescription(R.id.widget_small_header, allDescription);
        if (mini) view.setContentDescription(R.id.widget_small_root, allDescription);
        WidgetActions.UndoSnapshot undo = WidgetActions.undoSnapshot(c, widgetId);
        boolean canUndo = !leaves && undo.available;
        view.setViewVisibility(R.id.widget_small_undo, canUndo ? View.VISIBLE : View.GONE);
        if (canUndo) view.setOnClickPendingIntent(R.id.widget_small_undo, WidgetActions.undoIntent(c, widgetId));
        if (mini) {
            view.setViewVisibility(R.id.widget_small_body, canUndo ? View.GONE : View.VISIBLE);
            // Launcher padding leaves less than one cell for content: always prioritize title and time.
            view.setViewVisibility(R.id.widget_small_header, View.GONE);
        }
        if (items.isEmpty()) {
            view.setTextViewText(R.id.widget_small_item_1, snapshot.failed ? "暂时无法读取" : leaves ? "暂无待返校" : "暂无待办");
            view.setTextViewText(R.id.widget_small_time_1, snapshot.failed ? "点击打开重试" : "新事项自动同步");
            view.setOnClickPendingIntent(R.id.widget_small_row_1, all);
            view.setViewVisibility(R.id.widget_small_complete_1, View.GONE);
        } else {
            fixedRow(c, view, widgetId, items.get(0), now, R.id.widget_small_row_1, R.id.widget_small_item_1, R.id.widget_small_time_1, R.id.widget_small_complete_1);
        }
        if (!mini) {
            boolean second = !canUndo && layout.rows > 1 && items.size() > 1;
            view.setViewVisibility(R.id.widget_small_slot_2, second ? View.VISIBLE : View.GONE);
            view.setViewVisibility(R.id.widget_small_divider, second ? View.VISIBLE : View.GONE);
            if (second) fixedRow(c, view, widgetId, items.get(1), now, R.id.widget_small_row_2, R.id.widget_small_item_2, R.id.widget_small_time_2, R.id.widget_small_complete_2);
        }
        manager.updateAppWidget(widgetId, view);
    }
    private static void fixedRow(Context c, RemoteViews view, int widgetId, WidgetModel.Item item, long now, int rowId, int titleId, int timeId, int completeId) {
        String title = DomainRules.clipped(WidgetModel.title(item), 120), time = WidgetModel.compactTimeLabel(item, now, ZoneId.systemDefault());
        view.setTextViewText(titleId, title);
        view.setTextViewText(timeId, time);
        view.setTextColor(timeId, c.getColor(WidgetModel.overdue(item, now) ? R.color.widget_danger : R.color.widget_muted));
        view.setContentDescription(rowId, title + "，" + WidgetModel.timeLabel(item, now, ZoneId.systemDefault()) + "，点击查看详情");
        boolean actionable = "task".equals(item.kind) || "followup".equals(item.kind);
        view.setViewVisibility(completeId, actionable ? View.VISIBLE : View.GONE);
        if (actionable) {
            view.setContentDescription(completeId, "完成：" + title);
            view.setOnClickPendingIntent(completeId, WidgetActions.completeIntent(c, widgetId, item.id));
        }
        Intent intent = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .setData(new Uri.Builder().scheme("banxu").authority("widget-item").appendPath(String.valueOf(widgetId)).appendPath(item.id).build())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("route", "leave".equals(item.kind) ? "leaves" : "today").putExtra("itemId", item.id);
        view.setOnClickPendingIntent(rowId, PendingIntent.getActivity(c, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
    static RemoteViews row(Context c, WidgetModel.Item item, long now) {
        boolean leave = "leave".equals(item.kind), overdue = WidgetModel.overdue(item, now);
        RemoteViews row = new RemoteViews(c.getPackageName(), R.layout.widget_row);
        String title = DomainRules.clipped(WidgetModel.title(item), 120);
        String time = WidgetModel.timeLabel(item, now, ZoneId.systemDefault());
        row.setTextViewText(R.id.widget_row_title, title);
        row.setTextViewText(R.id.widget_row_time, time);
        row.setTextViewText(R.id.widget_marker, leave ? "◷" : "○");
        int color = c.getColor(overdue ? R.color.widget_danger : leave ? R.color.widget_amber : R.color.widget_green);
        row.setTextColor(R.id.widget_marker, color);
        row.setTextColor(R.id.widget_row_time, overdue ? color : c.getColor(R.color.widget_muted));
        row.setContentDescription(R.id.widget_row, title + "，" + time + "，点击查看详情");
        row.setOnClickFillInIntent(R.id.widget_row, new Intent().putExtra("widgetAction", "open").putExtra("itemId", item.id).putExtra("route", leave ? "leaves" : "today"));
        if (!leave) {
            row.setContentDescription(R.id.widget_marker, "完成：" + title);
            row.setOnClickFillInIntent(R.id.widget_marker, new Intent().putExtra("widgetAction", "complete").putExtra("itemId", item.id));
        }
        return row;
    }
    private static PendingIntent open(Context c, int id, boolean leaves, boolean template) {
        Intent intent = new Intent(c, MainActivity.class).setAction(Intent.ACTION_VIEW)
            .setData(Uri.parse("banxu://widget/" + id + "/" + (template ? "row" : "header")))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra("route", leaves ? "leaves" : "today");
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (template ? (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0) : PendingIntent.FLAG_IMMUTABLE);
        return PendingIntent.getActivity(c, id, intent, flags);
    }
    private static PendingIntent clockIntent(Context c) {
        return PendingIntent.getBroadcast(c, 6217, new Intent(c, WidgetRefreshReceiver.class)
            .setAction("cn.banxu.app.WIDGET_CLOCK"), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static PendingIntent pageIntent(Context c, int widgetId, int delta) {
        Intent intent = new Intent(c, WidgetRefreshReceiver.class).setAction(PAGE_ACTION)
            .setData(Uri.parse("banxu://widget-page/" + widgetId + "/" + delta))
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId).putExtra("pageDelta", delta);
        return PendingIntent.getBroadcast(c, widgetId, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }
    private static int dp(Context c, int value) { return Math.round(value * c.getResources().getDisplayMetrics().density); }
    private static void scheduleClock(Context c, long at) {
        // Never wake a sleeping phone; use the existing reminder permission when it is available.
        AlarmManager alarms = c.getSystemService(AlarmManager.class);
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExact(AlarmManager.RTC, at, clockIntent(c));
            else alarms.set(AlarmManager.RTC, at, clockIntent(c));
        } catch (SecurityException ignored) { alarms.set(AlarmManager.RTC, at, clockIntent(c)); }
    }
    private static void cancelClock(Context c) { c.getSystemService(AlarmManager.class).cancel(clockIntent(c)); }
}
