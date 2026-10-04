package cn.banxu.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Widget selection and labels, kept independent of Android for repeatable clock tests. */
public final class WidgetModel {
    private WidgetModel() {}
    public static final int PAGE_SIZE = 50;
    public static final class Item {
        public final String id, kind, status, title, student, precision;
        public final long dueAt, updatedAt;
        public final boolean demo;
        public Item(String id, String kind, String status, String title, String student,
                    String precision, long dueAt, long updatedAt, boolean demo) {
            this.id = id; this.kind = kind; this.status = status; this.title = title;
            this.student = student; this.precision = precision; this.dueAt = dueAt;
            this.updatedAt = updatedAt; this.demo = demo;
        }
    }
    public static List<Item> select(List<Item> source, boolean leaves) {
        List<Item> out = new ArrayList<>();
        for (Item item : source) if (!item.demo && !DomainRules.isClosed(item.status)
                && "leave".equals(item.kind) == leaves) out.add(item);
        out.sort(Comparator.comparingLong((Item i) -> i.dueAt > 0 ? i.dueAt : Long.MAX_VALUE)
            .thenComparing(Comparator.comparingLong((Item i) -> i.updatedAt).reversed())
            .thenComparing(i -> i.id));
        return out;
    }
    public static String title(Item item) {
        if ("leave".equals(item.kind)) return !item.student.isEmpty() ? item.student
            : item.title.isEmpty() ? "学生姓名待补" : item.title + " · 姓名待补";
        return item.title.isEmpty() ? "待办事项" : item.title;
    }
    public static boolean overdue(Item item, long now) { return item.dueAt > 0 && item.dueAt <= now; }
    public static String timeLabel(Item item, long now, ZoneId zone) {
        boolean leave = "leave".equals(item.kind);
        if (item.dueAt <= 0) return leave ? "返校时间待补" : "时间待补" + ("waiting".equals(item.status) ? " · 等反馈" : "");
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        java.time.ZonedDateTime due = Instant.ofEpochMilli(item.dueAt).atZone(zone);
        LocalDate date = due.toLocalDate();
        String day = date.equals(today) ? "今天" : date.equals(today.plusDays(1)) ? "明天"
            : due.format(DateTimeFormatter.ofPattern(date.getYear() == today.getYear() ? "M月d日" : "yyyy年M月d日", Locale.CHINA));
        String label = day + " " + due.format(DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA));
        if (leave) label += " 返校";
        if ("estimated".equals(item.precision)) label += " · 暂定";
        if (overdue(item, now)) label += leave ? " · 待核查" : " · 已到期";
        else if (!leave && "waiting".equals(item.status)) label += " · 等反馈";
        return label;
    }
    public static long nextChange(List<Item> items, long now, ZoneId zone) {
        long next = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        for (Item item : items) if (!item.demo && !DomainRules.isClosed(item.status) && item.dueAt > now) next = Math.min(next, item.dueAt + 1000);
        return next;
    }
    public static long stableId(String id) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < id.length(); i++) { hash ^= id.charAt(i); hash *= 0x100000001b3L; }
        return hash;
    }
    public static int pageCount(int count) { return Math.max(1, (count + PAGE_SIZE - 1) / PAGE_SIZE); }
    public static int clampPage(int page, int count) { return Math.max(0, Math.min(page, pageCount(count) - 1)); }
    public static List<Item> page(List<Item> items, int page) {
        int start = clampPage(page, items.size()) * PAGE_SIZE;
        return new ArrayList<>(items.subList(start, Math.min(start + PAGE_SIZE, items.size())));
    }
}
