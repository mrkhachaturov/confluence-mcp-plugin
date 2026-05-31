package com.atlassian.mcp.plugin.admin;

import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.config.UrlSafety;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolRegistry;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.net.URI;
import java.util.*;
import java.util.stream.Collectors;

@Path("/")
public class ConfigResource {

    private final McpPluginConfig config;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;
    private final ApplicationProperties applicationProperties;

    @Inject
    public ConfigResource(McpPluginConfig config, ToolRegistry toolRegistry,
                          PermissionManager permissionManager,
                          @ComponentImport ApplicationProperties applicationProperties) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
        this.applicationProperties = applicationProperties;
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public Response getConfig() {
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null || !permissionManager.isConfluenceAdministrator(user)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }

        List<Map<String, Object>> allTools = toolRegistry.getAllTools().stream()
                .sorted(Comparator.comparing(McpTool::name))
                .map(tool -> {
                    Map<String, Object> t = new LinkedHashMap<>();
                    t.put("name", tool.name());
                    t.put("description", tool.description());
                    t.put("isWrite", tool.isWriteTool());
                    t.put("requiredPlugin", tool.requiredPluginKey());
                    return t;
                })
                .collect(Collectors.toList());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("allowedUsers", String.join(",", config.getAllowedUserKeys()));
        result.put("allowedGroups", String.join(",", config.getAllowedGroups()));
        result.put("allowAllAuthenticatedUsers", config.isAllowAllAuthenticatedUsers());
        result.put("disabledTools", String.join(",", config.getDisabledTools()));
        result.put("readOnlyMode", config.isReadOnlyMode());
        result.put("confluenceBaseUrl", config.getConfluenceBaseUrlOverride());
        result.put("oauthClientId", config.getOAuthClientId());
        String secret = config.getOAuthClientSecret();
        result.put("oauthClientSecretSet", secret != null && !secret.isEmpty());
        result.put("oauthEnabled", config.isOAuthEnabled());
        result.put("allTools", allTools);
        return Response.ok(result).build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response putConfig(Map<String, Object> body, @Context HttpServletRequest request) {
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null || !permissionManager.isConfluenceAdministrator(user)) {
            return Response.status(Response.Status.FORBIDDEN).build();
        }
        // CSRF defense: this mutating endpoint changes the MCP control plane (enable, allowlist,
        // read-only, OAuth secret, base-URL override). A forged cross-site request carries a
        // foreign Origin/Referer, so we reject any whose host doesn't match this Confluence
        // instance. Browser fetch/XHR always sends Origin on non-GET; a non-browser caller with
        // neither header is not a cross-site browser attack and is allowed.
        if (!isSameOriginRequest(request)) {
            return Response.status(Response.Status.FORBIDDEN)
                    .entity(Map.of("error", "CSRF check failed: cross-origin request rejected"))
                    .type(MediaType.APPLICATION_JSON).build();
        }

        if (body.containsKey("enabled")) {
            config.setEnabled(Boolean.parseBoolean(body.get("enabled").toString()));
        }
        if (body.containsKey("allowedUsers")) {
            config.setAllowedUserKeys(body.get("allowedUsers").toString());
        }
        if (body.containsKey("allowedGroups")) {
            config.setAllowedGroups(body.get("allowedGroups").toString());
        }
        if (body.containsKey("allowAllAuthenticatedUsers")) {
            config.setAllowAllAuthenticatedUsers(
                    Boolean.parseBoolean(body.get("allowAllAuthenticatedUsers").toString()));
        }
        if (body.containsKey("disabledTools")) {
            config.setDisabledTools(body.get("disabledTools").toString());
        }
        if (body.containsKey("readOnlyMode")) {
            config.setReadOnlyMode(Boolean.parseBoolean(body.get("readOnlyMode").toString()));
        }
        if (body.containsKey("confluenceBaseUrl")) {
            String override = body.get("confluenceBaseUrl").toString();
            // SSRF guard: a non-empty override is forwarded with the caller's Authorization header,
            // so reject anything that isn't a safe http(s) URL (no loopback/link-local/metadata,
            // no embedded credentials). An empty value clears the override (use platform base URL).
            if (!override.isBlank() && !UrlSafety.isSafeBaseUrlForSet(override)) {
                return Response.status(Response.Status.BAD_REQUEST)
                        .entity(Map.of("error", "Invalid confluenceBaseUrl: must be an http(s) URL that does "
                                + "not resolve to a loopback, link-local, or cloud-metadata address, and must "
                                + "not contain embedded credentials"))
                        .type(MediaType.APPLICATION_JSON).build();
            }
            config.setConfluenceBaseUrlOverride(override);
        }
        if (body.containsKey("oauthClientId")) {
            config.setOAuthClientId(body.get("oauthClientId").toString());
        }
        if (body.containsKey("oauthClientSecret")) {
            String secret = body.get("oauthClientSecret").toString();
            if (!secret.isEmpty()) {
                config.setOAuthClientSecret(secret);
            }
        }

        return Response.noContent().build();
    }

    /**
     * True if the request is same-origin with this Confluence instance (or carries no
     * Origin/Referer, i.e. is not a cross-site browser request). Used as the CSRF gate.
     */
    private boolean isSameOriginRequest(HttpServletRequest request) {
        String baseHost = hostOf(platformBaseUrl());
        if (baseHost == null) {
            return true; // base URL unknown — don't hard-block legitimate admins
        }
        String origin = request.getHeader("Origin");
        if (origin != null && !origin.isBlank()) {
            return baseHost.equalsIgnoreCase(hostOf(origin));
        }
        String referer = request.getHeader("Referer");
        if (referer != null && !referer.isBlank()) {
            return baseHost.equalsIgnoreCase(hostOf(referer));
        }
        return true; // no Origin/Referer → not a browser cross-site request
    }

    @SuppressWarnings("deprecation")
    private String platformBaseUrl() {
        try {
            return applicationProperties.getBaseUrl();
        } catch (Exception e) {
            return null;
        }
    }

    private static String hostOf(String url) {
        if (url == null) {
            return null;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            return (host == null || host.isEmpty()) ? null : host;
        } catch (Exception e) {
            return null;
        }
    }
}
