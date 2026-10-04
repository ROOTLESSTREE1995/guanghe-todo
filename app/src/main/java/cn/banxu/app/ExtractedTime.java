package cn.banxu.app;

import java.time.ZoneId;
import java.util.List;

/** Verify model-selected text against the source, then calculate dates locally. */
public final class ExtractedTime {
    private ExtractedTime() {}
    public static final class Result {
        public final long dueAt;
        public final String dueText, precision, note;
        private Result(long dueAt, String dueText, String precision, String note) {
            this.dueAt = dueAt; this.dueText = dueText; this.precision = precision; this.note = note;
        }
    }
    public static Result resolve(String source, List<String> parts, long receivedAt, ZoneId zone) {
        if (parts == null || parts.isEmpty()) return unknown("", "消息没有提供具体时间，事项已自动添加，可随时补充。");
        if (parts.size() > 2 || receivedAt <= 0 || zone == null) return unknown("", "时间信息不足，事项已自动添加。");
        String normalizedSource = compact(source), combined = "";
        for (String part : parts) {
            String token = compact(part);
            if (token.isEmpty() || token.length() > 120 || !normalizedSource.contains(token))
                return unknown("", "未在原消息中找到对应时间，事项已添加，暂不安排到点提醒。");
            combined += (combined.isEmpty() ? "" : " ") + part.trim();
        }
        ChineseTimeParser.Result parsed = ChineseTimeParser.resolve(combined, receivedAt, zone);
        if (parsed.dueAt <= 0 || parsed.dueAt > 4_102_444_800_000L)
            return unknown(combined, parsed.note == null || parsed.note.isEmpty() ? "时间尚不明确，事项已添加，可随时补充。" : parsed.note);
        return new Result(parsed.dueAt, combined, parsed.precision, parsed.note);
    }
    private static Result unknown(String phrase, String note) { return new Result(0, phrase, "unknown", note); }
    private static String compact(String value) { return value == null ? "" : value.replaceAll("[\\s\\u3000]+", ""); }
}
