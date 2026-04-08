package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_page_children()
 * Returns: {parent_id, count, limit_requested, start_requested, results: [{simplified page dict}, ...]}
 */
public class GetPageChildrenTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetPageChildrenTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page_children"; }

    @Override
    public String description() {
        return "Get child pages and folders of a specific Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "parent_id", Map.of("type", "string", "description", "The ID of the parent page whose children you want to retrieve"),
                        "expand", Map.of("type", "string", "description", "Fields to expand in the response (e.g., 'version', 'body.storage')", "default", "version"),
                        "limit", Map.of("type", "integer", "description", "Maximum number of child items to return (1-50)", "default", 25),
                        "include_content", Map.of("type", "boolean", "description", "Whether to include the page content in the response", "default", false),
                        "convert_to_markdown", Map.of("type", "boolean", "description", "Whether to convert page content to markdown (true) or keep it in raw HTML format (false). Only relevant if include_content is true.", "default", true),
                        "start", Map.of("type", "integer", "description", "Starting index for pagination (0-based)", "default", 0),
                        "include_folders", Map.of("type", "boolean", "description", "Whether to include child folders in addition to child pages", "default", true)
                ),
                "required", List.of("parent_id")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String parentId = (String) args.get("parent_id");
        if (parentId == null || parentId.isBlank()) {
            throw new McpToolException("'parent_id' parameter is required");
        }
        int limit = Math.min(getInt(args, "limit", 25), 50);
        boolean includeContent = getBoolean(args, "include_content", false);
        boolean convertToMarkdown = getBoolean(args, "convert_to_markdown", true);
        int start = getInt(args, "start", 0);

        String expand = includeContent ? "version,body.storage,space" : "version,space";

        String rawJson = client.getRaw("/rest/api/content/" + parentId + "/child/page"
                + "?expand=" + encode(expand)
                + "&limit=" + limit
                + "&start=" + start, authHeader);

        // Transform to upstream format
        try {
            String baseUrl = client.getBaseUrl();
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");
            ArrayNode childPages = mapper.createArrayNode();

            if (results.isArray()) {
                for (JsonNode child : results) {
                    childPages.add(ResponseTransformer.simplifyPageNode(child, baseUrl, convertToMarkdown));
                }
            }

            ObjectNode output = mapper.createObjectNode();
            output.put("parent_id", parentId);
            output.put("count", childPages.size());
            output.put("limit_requested", limit);
            output.put("start_requested", start);
            output.set("results", childPages);
            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform children response: " + e.getMessage());
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
