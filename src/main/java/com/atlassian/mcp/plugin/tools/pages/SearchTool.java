package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class SearchTool implements McpTool {
    private final ConfluenceRestClient client;

    public SearchTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "search"; }

    @Override
    public String description() {
        return "Search Confluence content using simple terms or CQL.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "query", Map.of("type", "string", "description", "Search query - can be either a simple text (e.g. 'project documentation') or a CQL query string. Simple queries use 'siteSearch' by default, to mimic the WebUI search, with an automatic fallback to 'text' search if not supported. Examples of CQL: - Basic search: 'type=page AND space=DEV' - Personal space search: 'space=\"~username\"' (note: personal space keys starting with ~ must be quoted) - Search by title: 'title~\"Meeting Notes\"' - Use siteSearch: 'siteSearch ~ \"important concept\"' - Use text search: 'text ~ \"important concept\"' - Recent content: 'created >= \"2023-01-01\"' - Content with specific label: 'label=documentation' - Recently modified content: 'lastModified > startOfMonth(\"-1M\")' - Content modified this year: 'creator = currentUser() AND lastModified > startOfYear()' - Content you contributed to recently: 'contributor = currentUser() AND lastModified > startOfWeek()' - Content watched by user: 'watcher = \"user@domain.com\" AND type = page' - Exact phrase in content: 'text ~ \"\\\"Urgent Review Required\\\"\" AND label = \"pending-approval\"' - Title wildcards: 'title ~ \"Minutes*\" AND (space = \"HR\" OR space = \"Marketing\")' Note: Special identifiers need proper quoting in CQL: personal space keys (e.g., \"~username\"), reserved words, numeric IDs, and identifiers with special characters."),
                        "limit", Map.of("type", "integer", "description", "Maximum number of results (1-50)", "default", 10),
                        "spaces_filter", Map.of("type", "string", "description", "(Optional) Comma-separated list of space keys to filter results by. Overrides the environment variable CONFLUENCE_SPACES_FILTER if provided. Use empty string to disable filtering.")
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
        String spacesFilter = (String) args.get("spaces_filter");

        // If it doesn't look like CQL, wrap as siteSearch
        String cql = searchQuery;
        if (!searchQuery.contains("=") && !searchQuery.contains("~")) {
            cql = "siteSearch ~ \"" + searchQuery.replace("\"", "\\\"") + "\"";
        }

        // Append space filter if provided
        if (spacesFilter != null && !spacesFilter.isBlank()) {
            String[] spaces = spacesFilter.split(",");
            StringBuilder spaceClause = new StringBuilder("space in (");
            for (int i = 0; i < spaces.length; i++) {
                if (i > 0) spaceClause.append(",");
                spaceClause.append("\"").append(spaces[i].trim()).append("\"");
            }
            spaceClause.append(")");
            cql = "(" + cql + ") AND " + spaceClause;
        }

        return client.get("/rest/api/search?cql=" + encode(cql)
                + "&limit=" + limit
                + "&expand=content.space,content.version", authHeader);
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
