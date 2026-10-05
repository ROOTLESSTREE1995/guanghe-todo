package cn.banxu.app;

/** A bounded recovery burst; elapsed time prevents clock changes from bypassing the limit. */
final class ListenerRetryPolicy {
    static final long MIN_INTERVAL_MS = 15_000L;
    static final long WINDOW_MS = 120_000L;
    private static final int MAX_ATTEMPTS = 3;
    private long windowStartedAt = -1, lastAttemptAt = -1;
    private int attempts;

    boolean acquire(long elapsedNow) {
        if (lastAttemptAt >= 0 && elapsedNow - lastAttemptAt < MIN_INTERVAL_MS) return false;
        if (windowStartedAt < 0 || elapsedNow - windowStartedAt >= WINDOW_MS) {
            windowStartedAt = elapsedNow;
            attempts = 0;
        }
        if (attempts >= MAX_ATTEMPTS) return false;
        attempts++;
        lastAttemptAt = elapsedNow;
        return true;
    }

    long retryDelay() {
        return attempts > 0 && attempts < MAX_ATTEMPTS ? MIN_INTERVAL_MS << (attempts - 1) : 0;
    }

    long remainingThrottleDelay(long elapsedNow) {
        if (attempts >= MAX_ATTEMPTS || lastAttemptAt < 0) return 0;
        return Math.max(0, MIN_INTERVAL_MS - (elapsedNow - lastAttemptAt));
    }
}
