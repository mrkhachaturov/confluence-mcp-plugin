package com.atlassian.mcp.plugin.rest;

import com.atlassian.annotations.security.UnrestrictedAccess;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.sal.api.ApplicationProperties;
import com.atlassian.sal.api.component.ComponentLocator;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Filter with location="before-login" that:
 * 1. Passes through /plugins/servlet/mcp-oauth/* requests (handled by OAuthServlet)
 * 2. Directly serves /.well-known/oauth-* responses (can't use servlets at root)
 * 3. Passes /plugins/servlet/mcp through Confluence's login layer so the MCP filter chain's
 *    AccessControlFilter can return a JSON 401 instead of an HTML login redirect.
 */
@UnrestrictedAccess
public class OAuthAnonymousFilter implements Filter {

    public OAuthAnonymousFilter() {
    }

    @Override
    public void init(FilterConfig filterConfig) {
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest req = (HttpServletRequest) request;
        HttpServletResponse resp = (HttpServletResponse) response;
        String uri = req.getRequestURI();

        // Handle /.well-known/* directly — servlets can't serve at root
        if (uri.contains("/.well-known/oauth-") || uri.contains("/.well-known/openid-configuration")) {
            handleWellKnown(uri, resp);
            return;
        }

        // /plugins/servlet/mcp — let it pass Confluence's login layer so it reaches the MCP
        // filter chain, where AccessControlFilter returns a JSON 401 + WWW-Authenticate rather
        // than an HTML login redirect (spec §4.1). This is reachability, NOT authorization —
        // real auth still happens at AccessControlFilter.
        // Everything else (mcp-oauth servlet, /plugins/servlet/mcp) — pass through.
        chain.doFilter(request, response);
    }

    private void handleWellKnown(String uri, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");

        String baseUrl = getBaseUrl();
        String oauthBase = baseUrl + "/plugins/servlet/mcp-oauth";

        if (uri.contains("oauth-protected-resource")) {
            resp.getWriter().write("{\"resource\":\"" + baseUrl + "/plugins/servlet/mcp\","
                    + "\"authorization_servers\":[\"" + oauthBase + "\"]}");

        } else if (uri.contains("oauth-authorization-server")) {
            resp.getWriter().write("{\"issuer\":\"" + oauthBase + "\","
                    + "\"authorization_endpoint\":\"" + oauthBase + "/authorize\","
                    + "\"token_endpoint\":\"" + oauthBase + "/token\","
                    + "\"registration_endpoint\":\"" + oauthBase + "/register\","
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"],"
                    + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
                    + "\"code_challenge_methods_supported\":[\"S256\"],"
                    + "\"scopes_supported\":[\"WRITE\"],"
                    + "\"client_id_metadata_document_supported\":true}");
        } else if (uri.contains("openid-configuration")) {
            resp.getWriter().write("{\"issuer\":\"" + oauthBase + "\","
                    + "\"authorization_endpoint\":\"" + oauthBase + "/authorize\","
                    + "\"token_endpoint\":\"" + oauthBase + "/token\","
                    + "\"registration_endpoint\":\"" + oauthBase + "/register\","
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"],"
                    + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
                    + "\"code_challenge_methods_supported\":[\"S256\"],"
                    + "\"scopes_supported\":[\"WRITE\"],"
                    + "\"client_id_metadata_document_supported\":true}");
        } else {
            resp.setStatus(404);
            resp.getWriter().write("{\"error\":\"Not found\"}");
        }
    }

    private String getBaseUrl() {
        McpPluginConfig config = getConfig();
        if (config != null) {
            String override = config.getConfluenceBaseUrlOverride();
            if (override != null && !override.isEmpty()) return override;
        }
        try {
            ApplicationProperties props = ComponentLocator.getComponent(ApplicationProperties.class);
            if (props != null) return props.getBaseUrl().toString();
        } catch (Exception e) { /* fall through */ }
        return "";
    }

    private McpPluginConfig getConfig() {
        try {
            return ComponentLocator.getComponent(McpPluginConfig.class);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public void destroy() {
    }
}
