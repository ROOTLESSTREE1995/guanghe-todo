package cn.banxu.app;

import org.junit.Test;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import static org.junit.Assert.*;

public class WidgetModelTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final long NOW = at("2026-10-02T10:00:00+08:00");
    private static long at(String iso) { return java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
    private static WidgetModel.Item item(String id, String kind, String status, long time, boolean demo) {
        return new WidgetModel.Item(id, kind, status, "事项" + id, "学生" + id, "explicit", time, NOW, demo);
    }
    @Test public void filtersClosedDemoAndSeparatesLeave() {
        List<WidgetModel.Item> all = Arrays.asList(item("a", "task", "todo", 0, false), item("b", "followup", "waiting", 0, false),
            item("c", "task", "done", 0, false), item("d", "task", "todo", 0, true), item("e", "leave", "tracking", 0, false),
            item("f", "leave", "returned", 0, false), item("g", "leave", "ignored", 0, false));
        assertEquals(2, WidgetModel.select(all, false).size());
        assertEquals("e", WidgetModel.select(all, true).get(0).id);
        assertEquals(1, WidgetModel.select(all, true).size());
    }
    @Test public void sortsOverdueThenFutureThenUnknownWithoutLosingWaiting() {
        List<WidgetModel.Item> result = WidgetModel.select(Arrays.asList(item("unknown", "task", "todo", 0, false),
            item("future", "task", "todo", NOW + 86400000, false), item("past", "task", "waiting", NOW - 60000, false)), false);
        assertEquals("past", result.get(0).id); assertEquals("future", result.get(1).id); assertEquals("unknown", result.get(2).id);
    }
    @Test public void midnightChangesTomorrowIntoToday() {
        WidgetModel.Item task = item("a", "task", "todo", at("2026-10-03T09:00:00+08:00"), false);
        assertEquals("明天 09:00", WidgetModel.timeLabel(task, NOW, ZONE));
        assertEquals("今天 09:00", WidgetModel.timeLabel(task, at("2026-10-03T00:00:00+08:00"), ZONE));
    }
    @Test public void estimatedLeaveStillClearlyNeedsReturnVerification() {
        WidgetModel.Item leave = new WidgetModel.Item("a", "leave", "tracking", "请假", "", "estimated", NOW, NOW, false);
        assertEquals("请假 · 姓名待补", WidgetModel.title(leave));
        assertEquals("待核查 · 暂定 · 今天 10:00 返校", WidgetModel.timeLabel(leave, NOW, ZONE));
        assertTrue(WidgetModel.overdue(leave, NOW));
        assertEquals("返校时间待补", WidgetModel.timeLabel(item("b", "leave", "tracking", 0, false), NOW, ZONE));
    }
    @Test public void nextRefreshUsesNextDeadlineOrLocalMidnight() {
        long soon = NOW + 60000;
        assertEquals(soon + 1000, WidgetModel.nextChange(Arrays.asList(item("a", "task", "todo", soon, false)), NOW, ZONE));
        assertEquals(at("2026-10-03T00:00:00+08:00"), WidgetModel.nextChange(Arrays.asList(
            item("past", "task", "todo", NOW - 1, false), item("demo", "task", "todo", soon, true),
            item("done", "task", "done", soon, false)), NOW, ZONE));
    }
    @Test public void timestampLabelsFollowCurrentZoneAndIncludeDifferentYear() {
        WidgetModel.Item task = item("a", "task", "todo", at("2027-01-01T09:00:00+08:00"), false);
        assertEquals("2027年1月1日 09:00", WidgetModel.timeLabel(task, NOW, ZONE));
        assertEquals("2027年1月1日 01:00", WidgetModel.timeLabel(task, NOW, ZoneId.of("UTC")));
    }
    @Test public void unknownTimeKeepsWaitingContext() {
        assertEquals("等反馈 · 时间待补", WidgetModel.timeLabel(item("a", "followup", "waiting", 0, false), NOW, ZONE));
    }
    @Test public void compactTimeKeepsStatusAndClockTogetherAndCrossDayDatesExplicit() {
        WidgetModel.Item leave = new WidgetModel.Item("a", "leave", "tracking", "请假", "学生 A", "estimated", NOW, NOW, false);
        assertEquals("待核查·暂定10:00", WidgetModel.compactTimeLabel(leave, NOW, ZONE));
        WidgetModel.Item task = new WidgetModel.Item("b", "task", "todo", "提交名单", "", "estimated", NOW, NOW, false);
        assertEquals("逾期·暂定10:00", WidgetModel.compactTimeLabel(task, NOW, ZONE));
        assertEquals("明天09:00", WidgetModel.compactTimeLabel(item("c", "task", "todo", at("2026-10-03T09:00:00+08:00"), false), NOW, ZONE));
        assertEquals("2027/1/1 09:00", WidgetModel.compactTimeLabel(item("d", "task", "todo", at("2027-01-01T09:00:00+08:00"), false), NOW, ZONE));
    }
    @Test public void everyItemRemainsReachableAndPageClampsAfterDeletion() {
        java.util.ArrayList<WidgetModel.Item> items = new java.util.ArrayList<>();
        for (int i = 0; i < 123; i++) items.add(item("item" + i, "task", "todo", NOW + i, false));
        assertEquals(3, WidgetModel.pageCount(items.size()));
        assertEquals(50, WidgetModel.page(items, 0).size());
        assertEquals("item50", WidgetModel.page(items, 1).get(0).id);
        assertEquals("item122", WidgetModel.page(items, 2).get(22).id);
        assertEquals(23, WidgetModel.page(items, 999).size());
        assertEquals(0, WidgetModel.clampPage(2, 5));
        assertEquals(1, WidgetModel.pageCount(0));
        assertTrue(WidgetModel.page(java.util.Collections.emptyList(), 5).isEmpty());
    }
}
