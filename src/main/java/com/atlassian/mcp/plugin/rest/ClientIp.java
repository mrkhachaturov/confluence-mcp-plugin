package com.atlassian.mcp.plugin.rest;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Resolves the client IP for rate-limit buckets and security logs.
 *
 * <p>{@code X-Forwarded-For} is attacker-controlled, so it is honoured ONLY when the direct
 * socket peer ({@code getRemoteAddr}) is loopback — i.e. a reverse proxy co-located on this
 * host. For any other peer the header is ignored and the real socket address is used. This
 * removes the trivial bypass where an anonymous client rotates {@code X-Forwarded-For} to mint
 * unlimited rate-limit buckets and to poison the {@code [MCP-SEC]} audit trail.
 *
 * <p>Trade-off: a reverse proxy on a different host means anonymous traffic buckets under that
 * proxy's address rather than per real client — a conservative (safe) failure for DoS limiting.
 */
final class ClientIp {

    private ClientIp() {
    }

    static String resolve(HttpServletRequest req) {
        String remote = req.getRemoteAddr();
        if (isLoopback(remote)) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isEmpty()) {
                return xff.split(",")[0].trim();
            }
        }
        return remote;
    }

    private static boolean isLoopback(String ip) {
        if (ip == null) {
            return false;
        }
        return ip.equals("127.0.0.1") || ip.startsWith("127.")
                || ip.equals("0:0:0:0:0:0:0:1") || ip.equals("::1");
    }
}
