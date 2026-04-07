package com.atlassian.mcp.plugin.tools.users;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class SearchUserTool implements McpTool {
    private final ConfluenceRestClient client;

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
        String groupName = (String) args.getOrDefault("group_name", "confluence-users");

        // Use CQL search — works with both PAT and OAuth (3LO)
        // /rest/api/group/*/member and /rest/api/user are blocked for 3LO tokens
        String cql = "type=user AND user.fullname~\"" + searchQuery.replace("\"", "\\\"") + "\"";
        return client.get("/rest/api/search?cql=" + encode(cql) + "&limit=" + limit, authHeader);
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
