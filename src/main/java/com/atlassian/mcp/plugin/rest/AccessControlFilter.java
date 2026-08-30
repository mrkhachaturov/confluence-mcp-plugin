package com.atlassian.mcp.plugin.rest;

import com.atlassian.annotations.security.UnrestrictedAccess;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.UrlMode;
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
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the admin-config access policy (ports {@code McpResource.checkAuth} + {@code
 * isAccessAllowed}): 503 if disabled; 401 + WWW-Authenticate JSON if no user; 403 if the user is
 * not allowed. Never a Confluence login redirect (spec §4.1).
 */
@UnrestrictedAccess
@Named("mcpAccessControlFilter")
public class AccessControlFilter implements Filter {

  private static final Logger log = LoggerFactory.getLogger(AccessControlFilter.class);

  private final McpPluginConfig config;
  private final UserAccessor userAccessor;
  private final ApplicationProperties applicationProperties;

  @Inject
  public AccessControlFilter(
      McpPluginConfig config,
      @ComponentImport UserAccessor userAccessor,
      @ComponentImport ApplicationProperties applicationProperties) {
    this.config = config;
    this.userAccessor = userAccessor;
    this.applicationProperties = applicationProperties;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) req;
    HttpServletResponse httpResp = (HttpServletResponse) resp;

    if (!config.isEnabled()) {
      httpResp.setContentType("application/json");
      httpResp.setStatus(503);
      httpResp.getWriter().write("{\"error\":\"MCP server is disabled\"}");
      return;
    }

    ConfluenceUser user = AuthenticatedUserThreadLocal.get();
    if (user == null) {
      log.warn("[MCP-SEC] unauthenticated request from {}", clientIp(httpReq));
      httpResp.setContentType("application/json");
      httpResp.setStatus(401);
      // Advertise the exact scope token the Confluence Application Link registers (WRITE,
      // which already grants read). Confluence's OAuth provider strictly validates requested
      // scope tokens against the client's registered set, so advertising "read write" makes
      // clients request a "read" token that isn't registered -> invalid_scope. See the
      // confluence-mcp OAuth scope lesson.
      String challenge = "Bearer realm=\"confluence-mcp\", scope=\"WRITE\"";
      if (config.isOAuthEnabled()) {
        String resourceMetadata =
            getConfluenceBaseUrl() + "/plugins/servlet/mcp-oauth/protected-resource";
        challenge = challenge + ", resource_metadata=\"" + resourceMetadata + "\"";
      }
      httpResp.setHeader("WWW-Authenticate", challenge);
      httpResp.getWriter().write("{\"error\":\"Authentication required\"}");
      return;
    }

    String username = user.getName();
    String userKey = user.getKey() == null ? null : user.getKey().getStringValue();
    if (!isAccessAllowed(username, userKey)) {
      log.warn("[MCP-SEC] user '{}' not allowed", username);
      httpResp.setHeader("WWW-Authenticate", "Bearer realm=\"confluence-mcp\"");
      httpResp.sendError(HttpServletResponse.SC_FORBIDDEN, "User not allowed");
      return;
    }
    chain.doFilter(req, resp);
  }

  private boolean isAccessAllowed(String username, String userKey) {
    // Explicit opt-in: admin chose to allow every authenticated user. An empty allowlist
    // alone no longer means "allow all" (see McpPluginConfig.isUserAllowed default-deny).
    if (config.isAllowAllAuthenticatedUsers()) return true;
    if (userKey != null && config.isUserAllowed(userKey)) return true;
    if (username != null && config.isUserAllowed(username)) return true;
    Set<String> allowedGroups = config.getAllowedGroups();
    if (!allowedGroups.isEmpty() && username != null) {
      for (String groupName : allowedGroups) {
        if (userAccessor.hasMembership(groupName, username)) return true;
      }
    }
    return false;
  }

  private String getConfluenceBaseUrl() {
    String override = config.getConfluenceBaseUrlOverride();
    if (override != null && !override.isEmpty()) return override;
    try {
      return applicationProperties.getBaseUrl(UrlMode.CANONICAL).toString();
    } catch (Exception e) {
      return "";
    }
  }

  private static String clientIp(HttpServletRequest req) {
    return ClientIp.resolve(req);
  }
}
