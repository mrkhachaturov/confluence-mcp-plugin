package com.atlassian.mcp.plugin.tools.analytics;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.util.List;
import java.util.Map;

public class GetPageViewsTool implements McpTool {
    private final ConfluenceRestClient client;

    public GetPageViewsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page_views"; }

    @Override
    public String description() {
        return "Get view statistics for a Confluence page. Note: This tool is only available for Confluence Cloud. Server/Data Center instances do not support the Analytics API.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be found in the page URL). For example, in 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title', the page ID is '123456789'."),
                        "include_title", Map.of("type", "boolean", "description", "Whether to fetch and include the page title", "default", true)
                ),
                "required", List.of("page_id")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        boolean includeTitle = getBoolean(args, "include_title", true);

        return client.get("/rest/api/analytics/content/" + pageId + "/views", authHeader);
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
