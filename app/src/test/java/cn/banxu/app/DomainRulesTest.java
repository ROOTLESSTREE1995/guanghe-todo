package cn.banxu.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class DomainRulesTest {
    @Test public void localRulesNeverInventTaskForEveryChat() {
        assertNull(DomainRules.localKind("谢谢老师！辛苦了"));
        assertEquals("leave", DomainRules.localKind("孩子明天还要续假"));
        assertEquals("followup", DomainRules.localKind("晚上把结果反馈给您"));
        assertEquals("task", DomainRules.localKind("通知：周五前提交名单"));
    }
    @Test public void filteringOccursAtConversationBoundary() {
        assertTrue(DomainRules.matchesConversation("高一年级班主任群", "班主任\n家长群"));
        assertFalse(DomainRules.matchesConversation("购物群", "班主任\n家长群"));
        assertTrue(DomainRules.matchesConversation("任何群", "  \n "));
        assertFalse(DomainRules.matchesConversation("", "家长群"));
    }
    @Test public void vagueOrTimezoneFreeDatesStayUnknown() {
        assertEquals(0, DomainRules.parseExplicitIso("明天下午"));
        assertEquals(0, DomainRules.parseExplicitIso("2026-10-03T10:00:00"));
        assertEquals(0, DomainRules.parseExplicitIso("2026-02-30T10:00:00+08:00"));
        assertEquals(1790992800000L, DomainRules.parseExplicitIso("2026-10-03T10:00:00+08:00"));
    }
    @Test public void automaticallyTrackedAndLegacyApprovedLeavesRemind() {
        assertFalse(DomainRules.shouldRemind("leave", "review", 1000, false));
        assertFalse(DomainRules.shouldRemind("leave", "todo", 1000, false));
        assertFalse(DomainRules.shouldRemind("leave", "returned", 1000, false));
        assertFalse(DomainRules.shouldRemind("leave", "approved", 1000, true));
        assertTrue(DomainRules.shouldRemind("leave", "approved", 1000, false));
        assertTrue(DomainRules.shouldRemind("leave", "tracking", 1000, false));
        assertFalse(DomainRules.shouldRemind("leave", "tracking", 1000, true));
        assertFalse(DomainRules.shouldRemind("leave", "tracking", 0, false));
        assertTrue(DomainRules.shouldRemind("task", "waiting", 1000, false));
        assertFalse(DomainRules.shouldRemind("task", "done", 1000, false));
    }
    @Test public void leaveUsesReturnTimeAndTaskUsesLeadTime() {
        long due = 2_000_000L;
        assertEquals(due, DomainRules.deriveReminder("leave", "tracking", due, 15));
        assertEquals(due, DomainRules.deriveReminder("leave", "approved", due, 15));
        assertEquals(due - 900000L, DomainRules.deriveReminder("task", "todo", due, 15));
        assertEquals(0, DomainRules.deriveReminder("leave", "review", due, 15));
        assertEquals(0, DomainRules.deriveReminder("task", "done", due, 15));
        assertEquals(0, DomainRules.deriveReminder("task", "todo", 0, 15));
        assertEquals(0, DomainRules.deriveReminder("leave", "tracking", 0, 15));
        assertEquals(due - 900000L, DomainRules.deriveReminder("followup", "todo", due, 15));
    }
    @Test public void automaticIntakeDoesNotRequireReviewOrGrantApproval() {
        assertEquals("todo", DomainRules.automaticStatus("task"));
        assertEquals("todo", DomainRules.automaticStatus("followup"));
        assertEquals("tracking", DomainRules.automaticStatus("leave"));
        assertTrue(DomainRules.STATUSES.contains("tracking"));
        assertTrue(DomainRules.shouldRemind("task", DomainRules.automaticStatus("task"), 1000, false));
    }
    @Test public void migrationOnlyActivatesRealReviewItems() {
        assertTrue(DomainRules.shouldAutoActivate("review", false));
        assertFalse(DomainRules.shouldAutoActivate("review", true));
        for (String status : DomainRules.STATUSES) {
            if (!"review".equals(status)) assertFalse(status, DomainRules.shouldAutoActivate(status, false));
        }
    }
    @Test public void elapsedDeadlinesStayOnOriginalDateInsteadOfSilentlyRollingForward() {
        long due = 60_000L;
        assertEquals(due, DomainRules.deriveReminder("leave", "tracking", due, 15));
        assertEquals(1, DomainRules.deriveReminder("task", "todo", due, 15));
        assertTrue(DomainRules.shouldRemind("leave", "tracking", due, false));
        assertEquals(due, DomainRules.deriveReminder("task", "todo", due, -15));
    }
    @Test public void invalidTimestampsRejectedInsteadOfCoercion() {
        Object[] invalid = {-1L, Double.NaN, Double.POSITIVE_INFINITY, 0.5, "1790992800000", 9_000_000_000_000L};
        for (Object v : invalid) { try { DomainRules.validatedTimestamp(v); fail("must reject " + v); } catch (IllegalArgumentException expected) {} }
        assertEquals(0, DomainRules.validatedTimestamp(0));
        assertEquals(1790992800000L, DomainRules.validatedTimestamp(1790992800000L));
    }
}
