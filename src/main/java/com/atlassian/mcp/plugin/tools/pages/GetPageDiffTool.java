package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_page_diff()
 * Returns: {page_id, from_version, to_version, diff}
 */
public class GetPageDiffTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetPageDiffTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page_diff"; }

    @Override
    public String description() {
        return "Get a unified diff between two versions of a Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be found in the page URL). For example, in 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title', the page ID is '123456789'."),
                        "from_version", Map.of("type", "integer", "description", "Source version number"),
                        "to_version", Map.of("type", "integer", "description", "Target version number")
                ),
                "required", List.of("page_id", "from_version", "to_version")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        int fromVersion = getInt(args, "from_version", 0);
        int toVersion = getInt(args, "to_version", 0);
        if (fromVersion < 1 || toVersion < 1) {
            throw new McpToolException("'from_version' and 'to_version' must be >= 1");
        }

        try {
            // Fetch both versions (raw — we need body.storage.value)
            String fromJson = client.getRaw("/rest/api/content/" + pageId
                    + "?status=historical&version=" + fromVersion
                    + "&expand=body.storage,version", authHeader);
            String toJson = client.getRaw("/rest/api/content/" + pageId
                    + "?status=historical&version=" + toVersion
                    + "&expand=body.storage,version", authHeader);

            JsonNode fromNode = mapper.readTree(fromJson);
            JsonNode toNode = mapper.readTree(toJson);

            String fromContent = fromNode.path("body").path("storage").path("value").asText("");
            String toContent = toNode.path("body").path("storage").path("value").asText("");

            // Simple line-based diff
            String[] fromLines = fromContent.split("\n");
            String[] toLines = toContent.split("\n");

            StringBuilder diff = new StringBuilder();
            diff.append("--- version ").append(fromVersion).append("\n");
            diff.append("+++ version ").append(toVersion).append("\n");

            int maxLines = Math.max(fromLines.length, toLines.length);
            for (int i = 0; i < maxLines; i++) {
                String fl = i < fromLines.length ? fromLines[i] : "";
                String tl = i < toLines.length ? toLines[i] : "";
                if (!fl.equals(tl)) {
                    if (i < fromLines.length) diff.append("- ").append(fl).append("\n");
                    if (i < toLines.length) diff.append("+ ").append(tl).append("\n");
                } else {
                    diff.append("  ").append(fl).append("\n");
                }
            }

            // Return as JSON object matching upstream format
            ObjectNode result = mapper.createObjectNode();
            result.put("page_id", pageId);
            result.put("from_version", fromVersion);
            result.put("to_version", toVersion);
            result.put("diff", diff.toString());
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to compute diff: " + e.getMessage());
        }
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
