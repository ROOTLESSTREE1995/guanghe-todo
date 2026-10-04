package cn.banxu.app;

import static org.junit.Assert.*;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import org.junit.Test;

public class ChineseTimeParserTest {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static long timestamp(String iso) { return OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
    private static void resolves(String received, String phrase, String expected) {
        ChineseTimeParser.Result result = ChineseTimeParser.resolve(phrase, timestamp(received), SHANGHAI);
        assertEquals(phrase + ": " + result.note, "exact", result.precision);
        assertEquals(phrase, timestamp(expected), result.dueAt);
    }

    @Test public void relativeChineseTimesUseMessageReceiptDate() {
        String received = "2026-10-02T23:59:00+08:00";
        String[][] cases = {
                {"明早9点", "2026-10-03T09:00:00+08:00"},
                {"明天上午9:30", "2026-10-03T09:30:00+08:00"},
                {"今晚8点", "2026-10-02T20:00:00+08:00"},
                {"今天9点", "2026-10-02T09:00:00+08:00"},
                {"后天上午九点半", "2026-10-04T09:30:00+08:00"},
                {"昨天晚上八点", "2026-10-01T20:00:00+08:00"},
                {"明天下午两点一刻", "2026-10-03T14:15:00+08:00"},
                {"明天九点三刻", "2026-10-03T09:45:00+08:00"},
                {"明天二十三点十分", "2026-10-03T23:10:00+08:00"},
                {"明日上午十一点二十五分", "2026-10-03T11:25:00+08:00"},
                {"明天上午 9：30 前", "2026-10-03T09:30:00+08:00"},
                {"，明天9点前。", "2026-10-03T09:00:00+08:00"},
                {"明天之前", "2026-10-03T00:00:00+08:00"},
                {"截至明天9点", "2026-10-03T09:00:00+08:00"},
                {"今晚12点", "2026-10-03T00:00:00+08:00"}
        };
        for (String[] c : cases) resolves(received, c[0], c[1]);
    }

    @Test public void weekdaysRespectCalendarWeekAndSundayRollover() {
        String[][] cases = {
                {"2026-10-02T12:00:00+08:00", "周日9点", "2026-10-04T09:00:00+08:00"},
                {"2026-10-02T12:00:00+08:00", "星期一9点", "2026-10-05T09:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "下周一9点", "2026-10-05T09:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "下下周一9点", "2026-10-12T09:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "本周一9点", "2026-09-28T09:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "这星期天晚上8点", "2026-10-04T20:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "下礼拜三下午3点", "2026-10-07T15:00:00+08:00"}
        };
        for (String[] c : cases) resolves(c[0], c[1], c[2]);
    }

    @Test public void datesHandleMonthEndsLeapDaysAndYearRollover() {
        String[][] cases = {
                {"2026-10-31T23:59:00+08:00", "明早9点", "2026-11-01T09:00:00+08:00"},
                {"2026-12-31T23:59:00+08:00", "明早9点", "2027-01-01T09:00:00+08:00"},
                {"2026-12-31T12:00:00+08:00", "1月2日9点", "2027-01-02T09:00:00+08:00"},
                {"2026-12-31T12:00:00+08:00", "2026年1月2日9点", "2026-01-02T09:00:00+08:00"},
                {"2026-12-31T12:00:00+08:00", "2027-01-02 09:30", "2027-01-02T09:30:00+08:00"},
                {"2026-12-31T12:00:00+08:00", "二零二七年一月二日九点", "2027-01-02T09:00:00+08:00"},
                {"2026-10-02T12:00:00+08:00", "10月2号9点", "2026-10-02T09:00:00+08:00"},
                {"2026-10-02T12:00:00+08:00", "10月1日9点", "2026-10-01T09:00:00+08:00"},
                {"2026-01-01T12:00:00+08:00", "12月31日9点", "2025-12-31T09:00:00+08:00"},
                {"2028-02-28T12:00:00+08:00", "后天9点", "2028-03-01T09:00:00+08:00"},
                {"2027-10-02T12:00:00+08:00", "2月29日9点", "2028-02-29T09:00:00+08:00"}
        };
        for (String[] c : cases) resolves(c[0], c[1], c[2]);
    }

    @Test public void durationsPreserveReceivedClockIncludingDelayedProcessing() {
        String received = "2026-01-31T23:59:37+08:00";
        resolves(received, "两分钟后", "2026-02-01T00:01:37+08:00");
        resolves(received, "十小时后", "2026-02-01T09:59:37+08:00");
        resolves(received, "三天后", "2026-02-03T23:59:37+08:00");
        resolves(received, "半小时后", "2026-02-01T00:29:37+08:00");
        resolves(received, "一个半小时后", "2026-02-01T01:29:37+08:00");
        resolves(received, "1.5小时后", "2026-02-01T01:29:37+08:00");
        resolves(received, "两个半小时后", "2026-02-01T02:29:37+08:00");
        // Receipt is historical; neither today's date nor a later retry can move this tomorrow.
        resolves(received, "明早9点", "2026-02-01T09:00:00+08:00");
    }

    @Test public void inferredYearExplainsNearestDateAndChoosesFutureOnATie() {
        ChineseTimeParser.Result result = ChineseTimeParser.resolve("10月1日9点", timestamp("2026-10-02T12:00:00+08:00"), SHANGHAI);
        assertTrue(result.note.contains("无年份"));
        assertTrue(result.note.contains("最近"));
        // 2024 is a leap year: July 2 lies 183 days from either neighboring January 1.
        resolves("2024-07-02T12:00:00+08:00", "1月1日9点", "2025-01-01T09:00:00+08:00");
    }

    @Test public void weekendsAndMonthEndsUseTransparentEstimatedDates() {
        String[][] cases = {
                {"2026-10-02T12:00:00+08:00", "周末", "2026-10-04T18:00:00+08:00"},
                {"2026-10-04T23:00:00+08:00", "本周末", "2026-10-04T18:00:00+08:00"},
                {"2026-10-04T12:00:00+08:00", "下周末", "2026-10-11T18:00:00+08:00"},
                {"2026-10-02T12:00:00+08:00", "月底", "2026-10-31T18:00:00+08:00"},
                {"2026-10-02T12:00:00+08:00", "本月底", "2026-10-31T18:00:00+08:00"},
                {"2026-12-31T12:00:00+08:00", "下月底", "2027-01-31T18:00:00+08:00"},
                {"2028-02-02T12:00:00+08:00", "月底", "2028-02-29T18:00:00+08:00"}
        };
        for (String[] c : cases) {
            ChineseTimeParser.Result result = ChineseTimeParser.resolve(c[1], timestamp(c[0]), SHANGHAI);
            assertEquals(c[1], "estimated", result.precision);
            assertEquals(c[1], timestamp(c[2]), result.dueAt);
            assertTrue(c[1], result.note.contains("暂用"));
            assertTrue(c[1], result.note.contains("并非原文确定的期限"));
        }
    }

    @Test public void incompleteTimesExplicitlyMarkTheChosenDefault() {
        String[][] cases = {
                {"明天", "2026-10-03T18:00:00+08:00"},
                {"明天上午", "2026-10-03T09:00:00+08:00"},
                {"明天下午", "2026-10-03T15:00:00+08:00"},
                {"今晚", "2026-10-02T20:00:00+08:00"},
                {"后天中午", "2026-10-04T12:00:00+08:00"},
                {"2026-10-31", "2026-10-31T18:00:00+08:00"}
        };
        for (String[] c : cases) {
            ChineseTimeParser.Result result = ChineseTimeParser.resolve(c[0], timestamp("2026-10-02T12:00:00+08:00"), SHANGHAI);
            assertEquals(c[0], "estimated", result.precision);
            assertEquals(c[0], timestamp(c[1]), result.dueAt);
            assertTrue(c[0], result.note.contains("暂用"));
            assertTrue(c[0], result.note.contains("并非原文确定的期限"));
        }
    }

    @Test public void approximateClockRemainsEstimatedInsteadOfClaimingAnExactDeadline() {
        for (String phrase : new String[]{"明天9点左右", "明天9点许"}) {
            ChineseTimeParser.Result result = ChineseTimeParser.resolve(phrase, timestamp("2026-10-02T12:00:00+08:00"), SHANGHAI);
            assertEquals(phrase, "estimated", result.precision);
            assertEquals(phrase, timestamp("2026-10-03T09:00:00+08:00"), result.dueAt);
            assertTrue(result.note.contains("大约"));
        }
    }

    @Test public void invalidDatesRangesAndSchoolSchedulesStayUnknown() {
        String[] cases = {"", "尽快", "有空", "放学后", "明天下课后", "明天9点到10点", "周末或月底",
                "明天9点或后天10点", "2026-10-02至2026-10-03", "今天、明天", "今天明天9点",
                "2026-02-30 09:00", "2026年2月29日9点", "2月29日9点", "2月30日9点", "13月2日9点", "明天24:70",
                "明天25点", "明天9:60", "明天9:00:60", "明天九点六十分", "上午15点", "中午9点",
                "明天下午11点", "晚上15点", "夜里1点",
                "明天上午9点下午3点", "老师请于明天9点交表", "2026-10-02-2026-10-03", "三十十分钟后"};
        for (String phrase : cases) {
            ChineseTimeParser.Result result = ChineseTimeParser.resolve(phrase, timestamp("2026-10-02T12:00:00+08:00"), SHANGHAI);
            assertEquals(phrase, "unknown", result.precision);
            assertEquals(phrase, 0, result.dueAt);
        }
    }

    @Test public void timezoneComesFromTheMessageAndAmbiguousDstIsNotSilentlyShifted() {
        long instant = timestamp("2026-10-02T23:59:00Z");
        ChineseTimeParser.Result shanghai = ChineseTimeParser.resolve("明早9点", instant, SHANGHAI);
        ChineseTimeParser.Result utc = ChineseTimeParser.resolve("明早9点", instant, ZoneId.of("UTC"));
        assertEquals(timestamp("2026-10-04T09:00:00+08:00"), shanghai.dueAt);
        assertEquals(timestamp("2026-10-03T09:00:00Z"), utc.dueAt);
        for (String phrase : new String[]{"2026-03-08 02:30", "2026-11-01 01:30"}) {
            assertEquals(phrase, "unknown", ChineseTimeParser.resolve(phrase, instant, ZoneId.of("America/New_York")).precision);
        }
    }
}
