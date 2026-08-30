package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Pulls the Authorization header and the resolved Confluence user off each request and stashes them
 * into the per-request {@link McpTransportContext}. Tool dispatch in {@link McpToolAdapter} reads
 * these via {@code exchange.transportContext().get(key)}.
 *
 * <p>Confluence-native: the authenticated principal comes from {@link AuthenticatedUserThreadLocal}
 * (Confluence's auth filters populate it before our non-before-login filter chain runs), not SAL
 * {@code UserManager} (spec §5 row 2). Session-user binding enforcement lives in {@code
 * SessionBindingFilter}, not here.
 */
@Named("confluenceAuthContextExtractor")
public class ConfluenceAuthContextExtractor
    implements McpTransportContextExtractor<HttpServletRequest> {

  public static final String CTX_AUTH_HEADER = "authHeader";
  // CTX_AUTH_HEADER is consumed by McpToolAdapter; the two user keys below are reserved
  // for a future consumer (no structuredContent layer yet).
  public static final String CTX_CONFLUENCE_USER = "confluenceUser";
  public static final String CTX_CONFLUENCE_USER_KEY = "confluenceUserKey";

  @Override
  public McpTransportContext extract(HttpServletRequest request) {
    Map<String, Object> ctx = new HashMap<>(4);
    String authHeader = request.getHeader("Authorization");
    if (authHeader != null && !authHeader.isEmpty()) {
      ctx.put(CTX_AUTH_HEADER, authHeader);
    }
    try {
      ConfluenceUser user = AuthenticatedUserThreadLocal.get();
      if (user != null) {
        ctx.put(CTX_CONFLUENCE_USER, user.getName());
        if (user.getKey() != null) {
          ctx.put(CTX_CONFLUENCE_USER_KEY, user.getKey().getStringValue());
        }
      }
    } catch (Exception ignored) {
      // Best-effort: never throw out of the extractor.
    }
    return McpTransportContext.create(ctx);
  }
}
