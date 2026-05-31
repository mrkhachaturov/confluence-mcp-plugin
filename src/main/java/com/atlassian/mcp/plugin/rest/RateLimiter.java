package com.atlassian.mcp.plugin.rest;

import jakarta.inject.Named;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-key sliding-window rate limiter.
 *
 * <p>Each {@code ip:endpoint} (or {@code u:userKey:endpoint}) key gets its OWN one-minute window
 * that starts at the key's first request in the window — so keys reset independently rather than
 * all snapping to a shared wall-clock minute (no cross-boundary 2x burst, no global wipe).
 *
 * <p>When the key map reaches its cap, stale (expired-window) entries are evicted lazily; only if
 * the map is still full of <em>active</em> keys is the oldest active key dropped. New keys are
 * never blanket-rejected, closing the "fill the map to deny everyone else" DoS of the old design.
 */
@Named
public class RateLimiter {

    private static final int MAX_TRACKED_KEYS = 10_000;
    private static final long WINDOW_MS = 60_000; // 1 minute

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

    private static final class Window {
        long startMs;
        int count;

        Window(long startMs) {
            this.startMs = startMs;
            this.count = 0;
        }
    }

    /**
     * Snapshot of current rate-limit state for a key + endpoint, used to emit RateLimit-*
     * response headers per draft-ietf-httpapi-ratelimit-headers-09.
     */
    public static final class Snapshot {
        public final int limit;
        public final int remaining;
        public final long resetSeconds;

        public Snapshot(int limit, int remaining, long resetSeconds) {
            this.limit = limit;
            this.remaining = remaining;
            this.resetSeconds = resetSeconds;
        }
    }

    /**
     * Check if a request for the given key to the given endpoint is allowed, consuming a slot.
     *
     * @param ip         bucket key (remote IP or {@code u:userKey})
     * @param endpoint   logical endpoint name (e.g. "register", "token", "mcp")
     * @param maxPerMin  maximum requests per minute
     * @return true if allowed, false if rate limited
     */
    public boolean isAllowed(String ip, String endpoint, int maxPerMin) {
        long now = System.currentTimeMillis();
        String key = ip + ":" + endpoint;
        Window w = windows.computeIfAbsent(key, k -> new Window(now));
        boolean allowed;
        synchronized (w) {
            if (now - w.startMs >= WINDOW_MS) {
                w.startMs = now;
                w.count = 0;
            }
            w.count++;
            allowed = w.count <= maxPerMin;
        }
        evictIfNeeded(now);
        return allowed;
    }

    /**
     * Read-only inspection of the current window state. Does NOT consume a slot.
     * {@code resetSeconds} is the time until this key's window rolls over.
     */
    public Snapshot snapshot(String ip, String endpoint, int maxPerMin) {
        long now = System.currentTimeMillis();
        String key = ip + ":" + endpoint;
        Window w = windows.get(key);
        if (w == null) {
            return new Snapshot(maxPerMin, maxPerMin, WINDOW_MS / 1000);
        }
        synchronized (w) {
            long elapsed = now - w.startMs;
            if (elapsed >= WINDOW_MS) {
                return new Snapshot(maxPerMin, maxPerMin, WINDOW_MS / 1000);
            }
            int remaining = Math.max(0, maxPerMin - w.count);
            long resetSeconds = Math.max(0L, (WINDOW_MS - elapsed + 999) / 1000);
            return new Snapshot(maxPerMin, remaining, resetSeconds);
        }
    }

    /**
     * Lazy eviction: only runs when at/over the cap. First drops keys whose window has expired;
     * if still full (all keys active), drops the single oldest-started key. Never rejects callers.
     */
    private void evictIfNeeded(long now) {
        if (windows.size() < MAX_TRACKED_KEYS) {
            return;
        }
        Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
        String oldestKey = null;
        long oldestStart = Long.MAX_VALUE;
        while (it.hasNext()) {
            Map.Entry<String, Window> e = it.next();
            Window w = e.getValue();
            long start;
            synchronized (w) {
                start = w.startMs;
            }
            if (now - start >= WINDOW_MS) {
                it.remove();
            } else if (start < oldestStart) {
                oldestStart = start;
                oldestKey = e.getKey();
            }
        }
        if (windows.size() >= MAX_TRACKED_KEYS && oldestKey != null) {
            windows.remove(oldestKey);
        }
    }
}
