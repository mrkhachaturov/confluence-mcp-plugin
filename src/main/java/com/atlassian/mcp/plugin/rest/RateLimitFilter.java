package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 120 calls/min, keyed per authenticated user (and per client IP for unauthenticated or
 * unresolved-user requests). Runs before {@link AccessControlFilter}, and §4.1 deliberately
 * lets unauthenticated traffic reach the chain, so the anonymous case gets a real per-IP
 * bucket rather than a shared null key (spec §6.3). Over-limit returns 429 with
 * {@code RateLimit-*} + {@code Retry-After} headers.
 */
@Named("mcpRateLimitFilter")
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int LIMIT_PER_MIN = 120;
    private static final String ENDPOINT = "mcp";

    private final RateLimiter rateLimiter;

    @Inject
    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        String bucket = resolveBucket(httpReq);
        if (!rateLimiter.isAllowed(bucket, ENDPOINT, LIMIT_PER_MIN)) {
            log.warn("[MCP-SEC] rate limit exceeded for bucket={}", bucket);
            RateLimiter.Snapshot snap = rateLimiter.snapshot(bucket, ENDPOINT, LIMIT_PER_MIN);
            setRateLimitHeaders(httpResp, snap);
            httpResp.setHeader("Retry-After", Long.toString(Math.max(1L, snap.resetSeconds)));
            httpResp.sendError(429, "Rate limit exceeded");
            return;
        }
        RateLimiter.Snapshot snap = rateLimiter.snapshot(bucket, ENDPOINT, LIMIT_PER_MIN);
        setRateLimitHeaders(httpResp, snap);
        chain.doFilter(req, resp);
    }

    private static String resolveBucket(HttpServletRequest req) {
        try {
            ConfluenceUser user = AuthenticatedUserThreadLocal.get();
            if (user != null && user.getKey() != null) {
                return "u:" + user.getKey().getStringValue();
            }
        } catch (Exception ignored) {
        }
        return "ip:" + clientIp(req);
    }

    private static void setRateLimitHeaders(HttpServletResponse resp, RateLimiter.Snapshot snap) {
        resp.setHeader("RateLimit-Limit", Integer.toString(snap.limit));
        resp.setHeader("RateLimit-Remaining", Integer.toString(snap.remaining));
        resp.setHeader("RateLimit-Reset", Long.toString(snap.resetSeconds));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
