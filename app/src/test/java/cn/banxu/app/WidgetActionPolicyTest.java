package cn.banxu.app;

import org.junit.Test;
import static org.junit.Assert.*;

public class WidgetActionPolicyTest {
    @Test public void onlyOpenRealTodosAndFollowupsCanComplete() {
        assertTrue(WidgetActionPolicy.canComplete("task", "todo", false));
        assertTrue(WidgetActionPolicy.canComplete("followup", "waiting", false));
        assertTrue(WidgetActionPolicy.canComplete("task", "review", false));
        assertFalse(WidgetActionPolicy.canComplete("task", "todo", true));
        for (String status : new String[]{"done", "ignored", "returned", "approved", "tracking", "unknown"})
            assertFalse(WidgetActionPolicy.canComplete("task", status, false));
        assertFalse(WidgetActionPolicy.canComplete("leave", "tracking", false));
    }
    @Test public void undoEndsExactlyAtTenSecondsAndClockChangesCannotExtendIt() {
        assertTrue(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 100_000, 5_000));
        assertTrue(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 109_999, 14_999));
        assertFalse(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 110_000, 14_999));
        assertFalse(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 109_000, 15_000));
        assertFalse(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 99_999, 5_100));
        assertFalse(WidgetActionPolicy.withinUndo(100_000, 110_000, 5_000, 100_100, 100));
        assertFalse(WidgetActionPolicy.withinUndo(100_000, 120_000, 5_000, 100_100, 5_100));
    }
    @Test public void changedDeletedOrNewerOperationsCannotBeUndone() {
        assertTrue(WidgetActionPolicy.canUndo("task", "done", false, 42, 42, "hash", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "todo", false, 42, 42, "hash", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("leave", "done", false, 42, 42, "hash", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "done", true, 42, 42, "hash", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "done", false, 43, 42, "hash", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "done", false, 42, 42, "edited-in-same-millisecond", "hash", "token", "token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "done", false, 42, 42, "hash", "hash", "older-token", "new-token"));
        assertFalse(WidgetActionPolicy.canUndo("task", "done", false, 42, 42, "", "", "token", "token"));
    }
}
