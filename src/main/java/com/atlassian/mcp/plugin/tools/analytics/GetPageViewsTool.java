package com.atlassian.mcp.plugin.tools.analytics;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_page_views()
 * Returns: {page_id, page_title, total_views, unique_viewers, last_viewed}
 * Note: Cloud-only API. Server/DC does not support the Analytics API.
 */
public class GetPageViewsTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

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

        String rawJson = client.getRaw("/rest/api/analytics/content/" + pageId + "/views", authHeader);

        // Transform to upstream format: {page_id, page_title, total_views, unique_viewers, last_viewed}
        try {
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("page_id", pageId);

            if (includeTitle) {
                // Fetch page title separately
                try {
                    String pageJson = client.getRaw("/rest/api/content/" + pageId + "?expand=", authHeader);
                    JsonNode pageNode = mapper.readTree(pageJson);
                    result.put("page_title", pageNode.path("title").asText(""));
                } catch (Exception e) {
                    result.put("page_title", "");
                }
            }

            result.put("total_views", raw.path("count").asLong(0));
            result.put("last_viewed", raw.path("lastSeen").asText(""));

            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform page views response: " + e.getMessage());
        }
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
