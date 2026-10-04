package cn.banxu.app;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class ExtractedTimeTest {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static long at(String value) { return OffsetDateTime.parse(value).toInstant().toEpochMilli(); }
    private static final long RECEIVED = at("2026-10-02T23:59:00+08:00");
    @Test public void missingTimeDoesNotInventDeadline() {
        ExtractedTime.Result r = ExtractedTime.resolve("请尽快交名单", Collections.emptyList(), RECEIVED, ZONE);
        assertEquals(0, r.dueAt); assertEquals("unknown", r.precision);
    }
    @Test public void hallucinatedOrRewrittenQuotesAreRejected() {
        for (String text : Arrays.asList("明早8点", "2026-10-03T09:00:00+08:00", " ")) {
            assertEquals(0, ExtractedTime.resolve("明早9点提交名单", Arrays.asList(text), RECEIVED, ZONE).dueAt);
        }
    }
    @Test public void morningIsCalculatedFromOriginalMessageDate() {
        ExtractedTime.Result r = ExtractedTime.resolve("请于明早9点前提交名单", Arrays.asList("明早9点前"), RECEIVED, ZONE);
        assertEquals(at("2026-10-03T09:00:00+08:00"), r.dueAt); assertEquals("exact", r.precision);
    }
    @Test public void dateAndClockCanBeSeparateVerbatimQuotes() {
        ExtractedTime.Result r = ExtractedTime.resolve("明天开会，下午三点开始", Arrays.asList("明天", "下午三点"), RECEIVED, ZONE);
        assertEquals(at("2026-10-03T15:00:00+08:00"), r.dueAt);
    }
    @Test public void returnQuoteDoesNotPickLeaveStart() {
        ExtractedTime.Result r = ExtractedTime.resolve("林小试今天8点请假，明天下午3点返校", Arrays.asList("明天下午3点"), RECEIVED, ZONE);
        assertEquals(at("2026-10-03T15:00:00+08:00"), r.dueAt);
    }
    @Test public void nonexistentSchoolTimetableIsNotFabricated() {
        ExtractedTime.Result r = ExtractedTime.resolve("明天放学后请给家长回电", Arrays.asList("明天放学后"), RECEIVED, ZONE);
        assertEquals(0, r.dueAt); assertEquals("unknown", r.precision);
    }
}
