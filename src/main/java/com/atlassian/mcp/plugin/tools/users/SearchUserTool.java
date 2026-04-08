package com.atlassian.mcp.plugin.tools.users;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.search_user()
 * Returns: [{display_name, email}, ...]
 */
public class SearchUserTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public SearchUserTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "search_user"; }

    @Override
    public String description() {
        return "Search Confluence users using CQL (Cloud) or group member API (Server/DC).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query - a CQL query string for user search. Examples of CQL: - Basic user lookup by full name: 'user.fullname ~ \"First Last\"' Note: Special identifiers need proper quoting in CQL: personal space keys (e.g., \"~username\"), reserved words, numeric IDs, and identifiers with special characters."),
                        "limit", Map.of("type", "integer", "description", "Maximum number of results (1-50)", "default", 10),
                        "group_name", Map.of("type", "string", "description", "Group to search within on Server/DC instances (default: 'confluence-users'). Ignored on Cloud.", "default", "confluence-users")
                ),
                "required", List.of("query")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String searchQuery = (String) args.get("query");
        if (searchQuery == null || searchQuery.isBlank()) {
            throw new McpToolException("'query' parameter is required");
        }
        int limit = Math.min(getInt(args, "limit", 10), 50);

        // Use CQL search — works with both PAT and OAuth (3LO)
        String cql = "type=user AND user.fullname~\"" + searchQuery.replace("\"", "\\\"") + "\"";
        String rawJson = client.getRaw("/rest/api/search?cql=" + encode(cql) + "&limit=" + limit, authHeader);

        // Transform to upstream format: flat list of simplified user dicts
        try {
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");
            ArrayNode output = mapper.createArrayNode();

            if (results.isArray()) {
                for (JsonNode result : results) {
                    output.add(ResponseTransformer.simplifyUserNode(result));
                }
            }

            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform user search results: " + e.getMessage());
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
}
