package cn.banxu.app;

/** Pure guards shared by the widget receiver and undo rendering. */
final class WidgetActionPolicy {
    static final long UNDO_MS = 10_000L;
    private WidgetActionPolicy() {}

    static boolean canComplete(String kind, String status, boolean demo) {
        return !demo && ("task".equals(kind) || "followup".equals(kind))
            && ("todo".equals(status) || "waiting".equals(status) || "review".equals(status));
    }

    static boolean withinUndo(long issuedAt, long until, long issuedElapsed, long now, long elapsedNow) {
        // Both clocks must agree that the original ten-second interval is still open.
        // A clock adjustment or reboot must never lengthen an undo window.
        return issuedAt > 0 && until == issuedAt + UNDO_MS && issuedElapsed >= 0
            && now >= issuedAt && now < until
            && elapsedNow >= issuedElapsed && elapsedNow - issuedElapsed < UNDO_MS;
    }

    static boolean canUndo(String kind, String status, boolean demo, long updatedAt, long completedAt,
                           String fingerprint, String expectedFingerprint, String token, String expectedToken) {
        return !demo && ("task".equals(kind) || "followup".equals(kind)) && "done".equals(status)
            && completedAt > 0 && updatedAt == completedAt
            && fingerprint != null && !fingerprint.isEmpty() && fingerprint.equals(expectedFingerprint)
            && token != null && !token.isEmpty() && token.equals(expectedToken);
    }
}
