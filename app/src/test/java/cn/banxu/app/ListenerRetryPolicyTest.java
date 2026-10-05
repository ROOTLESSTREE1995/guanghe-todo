package cn.banxu.app;

import static org.junit.Assert.*;
import org.junit.Test;

public class ListenerRetryPolicyTest {
    @Test public void firstAttemptIsImmediateAndRepeatedClicksAreThrottled() {
        ListenerRetryPolicy policy = new ListenerRetryPolicy();
        assertTrue(policy.acquire(0));
        assertEquals(15_000, policy.retryDelay());
        assertFalse(policy.acquire(0));
        assertFalse(policy.acquire(14_999));
        assertTrue(policy.acquire(15_000));
        assertEquals(30_000, policy.retryDelay());
    }

    @Test public void aRecoveryBurstStopsAfterThreeAttemptsWithoutSchedulingAnother() {
        ListenerRetryPolicy policy = new ListenerRetryPolicy();
        assertTrue(policy.acquire(100));
        assertTrue(policy.acquire(15_100));
        assertTrue(policy.acquire(45_100));
        assertEquals(0, policy.retryDelay());
        assertFalse(policy.acquire(75_100));
        assertFalse(policy.acquire(120_099));
        assertTrue(policy.acquire(120_100));
        assertEquals(15_000, policy.retryDelay());
    }

    @Test public void aQuickDisconnectAfterConnectingWaitsOnlyTheRemainingInterval() {
        ListenerRetryPolicy policy = new ListenerRetryPolicy();
        assertTrue(policy.acquire(100));
        // Connecting cancels the pending callback, but does not erase the attempt budget.
        assertFalse(policy.acquire(2_100));
        assertEquals(13_000, policy.remainingThrottleDelay(2_100));
        assertTrue(policy.acquire(15_100));
        assertFalse(policy.acquire(15_200));
        assertEquals(14_900, policy.remainingThrottleDelay(15_200));
        assertTrue(policy.acquire(30_100));
        assertFalse(policy.acquire(30_200));
        assertEquals(0, policy.remainingThrottleDelay(30_200));
        assertEquals(0, policy.retryDelay());
        assertTrue(policy.acquire(120_100));
    }
}
