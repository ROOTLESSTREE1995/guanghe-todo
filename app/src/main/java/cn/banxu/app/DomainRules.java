package cn.banxu.app;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** Pure policy: no Android or network dependencies. */
public final class DomainRules {
    private DomainRules() {}
    public static final Set<String> KINDS = new HashSet<>(Arrays.asList("task", "leave", "followup"));
    public static final Set<String> STATUSES = new HashSet<>(Arrays.asList("review", "todo", "waiting", "tracking", "approved", "returned", "done", "ignored"));
    public static String localKind(String text) {
        if (contains(text, "请假", "续假", "返校", "回校")) return "leave";
        if (contains(text, "反馈", "回复", "联系", "回电", "聊聊", "跟进", "发给您", "发给你")) return "followup";
        if (contains(text, "通知", "提交", "截止", "上交", "收齐", "开会", "会议", "填写", "完成", "报名", "回执", "报送")) return "task";
        return null;
    }
    private static boolean contains(String text, String... tokens) {
        for (String t : tokens) if (text.contains(t)) return true;
        return false;
    }
    public static boolean matchesConversation(String title, String filters) {
        if (filters == null || filters.trim().isEmpty()) return true;
        for (String word : filters.split("\\r?\\n")) if (!word.trim().isEmpty() && title.contains(word.trim())) return true;
        return false;
    }
    public static boolean isClosed(String status) { return "done".equals(status) || "returned".equals(status) || "ignored".equals(status); }
    /** Tracking a reported absence does not grant or infer leave approval. */
    public static String automaticStatus(String kind) { return "leave".equals(kind) ? "tracking" : "todo"; }
    public static boolean shouldAutoActivate(String status, boolean demo) { return !demo && "review".equals(status); }
    public static boolean tracksReturn(String status) { return "tracking".equals(status) || "approved".equals(status); }
    public static boolean shouldRemind(String kind, String status, long remindAt, boolean demo) {
        return !demo && remindAt > 0 && !isClosed(status) && !"review".equals(status)
                && (!"leave".equals(kind) || tracksReturn(status));
    }
    public static long deriveReminder(String kind, String status, long dueAt, int leadMinutes) {
        if (dueAt <= 0 || isClosed(status) || "review".equals(status)) return 0;
        if ("leave".equals(kind)) return tracksReturn(status) ? dueAt : 0;
        return Math.max(1, dueAt - Math.max(0, leadMinutes) * 60_000L);
    }
    /** Offset is mandatory: vague dates and local datetimes stay unknown. */
    public static long parseExplicitIso(String value) {
        if (value == null || value.trim().isEmpty()) return 0;
        try { long time = OffsetDateTime.parse(value).toInstant().toEpochMilli(); return time > 0 ? time : 0; }
        catch (RuntimeException ignored) { return 0; }
    }
    public static long validatedTimestamp(Object value) {
        if (!(value instanceof Number)) throw new IllegalArgumentException("日期必须为时间戳");
        double n = ((Number) value).doubleValue();
        if (!Double.isFinite(n) || n < 0 || n > 4_102_444_800_000L || n != Math.floor(n)) throw new IllegalArgumentException("日期无效");
        return ((Number) value).longValue();
    }
    public static String clipped(String s, int max) { return s.length() > max ? s.substring(0, max) : s; }
}
