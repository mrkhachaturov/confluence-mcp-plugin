package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_page_history()
 * Returns: {"page": {simplified page dict}}
 */
public class GetPageHistoryTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetPageHistoryTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page_history"; }

    @Override
    public String description() {
        return "Get a historical version of a specific Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be found in the page URL). For example, in 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title', the page ID is '123456789'."),
                        "version", Map.of("type", "integer", "description", "The version number of the page to retrieve"),
                        "convert_to_markdown", Map.of("type", "boolean", "description", "Whether to convert page to markdown (true) or keep it in raw HTML format (false). Raw HTML can reveal macros (like dates) not visible in markdown, but CAUTION: using HTML significantly increases token usage in AI responses.", "default", true)
                ),
                "required", List.of("page_id", "version")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        pageId = McpTool.resolvePageId(pageId);
        int version = getInt(args, "version", 0);
        boolean convertToMarkdown = getBoolean(args, "convert_to_markdown", true);

        String rawJson = client.getRaw("/rest/api/content/" + pageId
                + "?status=historical&version=" + version
                + "&expand=body.storage,version,space", authHeader);

        // Transform to upstream format
        try {
            String baseUrl = client.getBaseUrl();
            JsonNode root = mapper.readTree(rawJson);
            ObjectNode simplified = ResponseTransformer.simplifyPageNode(root, baseUrl, convertToMarkdown);
            ObjectNode result = mapper.createObjectNode();
            result.set("page", simplified);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform page history response: " + e.getMessage());
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

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
