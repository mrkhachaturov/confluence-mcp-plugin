package com.atlassian.mcp.plugin.admin;

import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolRegistry;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.*;
import java.util.stream.Collectors;

@Path("/")
public class ConfigResource {

    private final McpPluginConfig config;
    private final ToolRegistry toolRegistry;
    private final PermissionManager permissionManager;

    @Inject
    public ConfigResource(McpPluginConfig config, ToolRegistry toolRegistry, PermissionManager permissionManager) {
        this.config = config;
        this.toolRegistry = toolRegistry;
        this.permissionManager = permissionManager;
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
    public Response putConfig(Map<String, Object> body) {
        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null || !permissionManager.isConfluenceAdministrator(user)) {
            return Response.status(Response.Status.FORBIDDEN).build();
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
        if (body.containsKey("disabledTools")) {
            config.setDisabledTools(body.get("disabledTools").toString());
        }
        if (body.containsKey("readOnlyMode")) {
            config.setReadOnlyMode(Boolean.parseBoolean(body.get("readOnlyMode").toString()));
        }
        if (body.containsKey("confluenceBaseUrl")) {
            config.setConfluenceBaseUrlOverride(body.get("confluenceBaseUrl").toString());
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
}
