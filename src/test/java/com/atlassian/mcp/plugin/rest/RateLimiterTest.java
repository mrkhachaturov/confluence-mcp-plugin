package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class RateLimiterTest {

    @Test
    public void allowsUpToLimitThenDenies() {
        RateLimiter rl = new RateLimiter();
        for (int i = 1; i <= 5; i++) {
            assertTrue("request " + i + " should be allowed", rl.isAllowed("ip1", "mcp", 5));
        }
        assertFalse("6th request should be denied", rl.isAllowed("ip1", "mcp", 5));
    }

    @Test
    public void keysAreIndependent_noGlobalClear() {
        RateLimiter rl = new RateLimiter();
        // Exhaust ip1's budget.
        for (int i = 0; i < 5; i++) {
            rl.isAllowed("ip1", "mcp", 5);
        }
        assertFalse(rl.isAllowed("ip1", "mcp", 5));
        // ip2 must be unaffected — the old design cleared ALL counters on a minute roll-over.
        assertTrue("a different key must have its own independent window",
                rl.isAllowed("ip2", "mcp", 5));
    }

    @Test
    public void endpointsAreIndependent() {
        RateLimiter rl = new RateLimiter();
        for (int i = 0; i < 5; i++) {
            rl.isAllowed("ip1", "mcp", 5);
        }
        assertFalse(rl.isAllowed("ip1", "mcp", 5));
        assertTrue("same ip, different endpoint is a different bucket",
                rl.isAllowed("ip1", "register", 5));
    }

    @Test
    public void snapshotReflectsRemainingWithoutConsuming() {
        RateLimiter rl = new RateLimiter();
        rl.isAllowed("ip1", "mcp", 10);
        rl.isAllowed("ip1", "mcp", 10);
        RateLimiter.Snapshot snap = rl.snapshot("ip1", "mcp", 10);
        assertEquals(10, snap.limit);
        assertEquals("two consumed → eight remaining", 8, snap.remaining);
        // snapshot must not consume a slot
        RateLimiter.Snapshot again = rl.snapshot("ip1", "mcp", 10);
        assertEquals(8, again.remaining);
        assertTrue("reset window must be positive", snap.resetSeconds > 0);
    }

    @Test
    public void unseenKeySnapshotIsFullBudget() {
        RateLimiter rl = new RateLimiter();
        RateLimiter.Snapshot snap = rl.snapshot("never-seen", "mcp", 7);
        assertEquals(7, snap.remaining);
    }
}
