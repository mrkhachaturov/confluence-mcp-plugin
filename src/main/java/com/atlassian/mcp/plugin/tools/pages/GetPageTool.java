package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_page()
 * Returns: {"page": {simplified page dict}} when include_metadata=true
 */
public class GetPageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetPageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page"; }

    @Override
    public String description() {
        return "Get content of a specific Confluence page by its ID, or by its title and space key. "
                + "By default, page content is converted from Confluence storage format to clean Markdown for easy reading. "
                + "The response includes full page URL, space info, author, version, and content.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be found in the page URL). For example, in the URL 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title', the page ID is '123456789'. Provide this OR both 'title' and 'space_key'. If page_id is provided, title and space_key will be ignored."),
                        "title", Map.of("type", "string", "description", "The exact title of the Confluence page. Use this with 'space_key' if 'page_id' is not known."),
                        "space_key", Map.of("type", "string", "description", "The key of the Confluence space where the page resides (e.g., 'DEV', 'TEAM'). Required if using 'title'."),
                        "include_metadata", Map.of("type", "boolean", "description", "Whether to include page metadata such as creation date, last update, version, and labels.", "default", true),
                        "convert_to_markdown", Map.of("type", "boolean", "description", "Whether to convert page to markdown (true) or keep it in raw HTML format (false). Raw HTML can reveal macros (like dates) not visible in markdown, but CAUTION: using HTML significantly increases token usage in AI responses.", "default", true)
                ),
                "required", List.of()
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        String title = (String) args.get("title");
        String spaceKey = (String) args.get("space_key");
        boolean convertToMarkdown = getBoolean(args, "convert_to_markdown", true);

        String expand = "body.storage,version,space,ancestors,children.attachment,metadata.labels";

        String rawJson;
        if (pageId != null && !pageId.isBlank()) {
            pageId = McpTool.resolvePageId(pageId);
            rawJson = client.getRaw("/rest/api/content/" + pageId + "?expand=" + encode(expand), authHeader);
        } else if (title != null && !title.isBlank() && spaceKey != null && !spaceKey.isBlank()) {
            rawJson = client.getRaw("/rest/api/content?title=" + encode(title)
                    + "&spaceKey=" + encode(spaceKey)
                    + "&expand=" + encode(expand), authHeader);
        } else {
            throw new McpToolException("Either 'page_id' or both 'title' and 'space_key' are required");
        }

        // Transform to upstream format: {"metadata": {simplified page dict}}
        try {
            String baseUrl = client.getBaseUrl();
            JsonNode root = mapper.readTree(rawJson);

            // If searched by title, result is in results array
            JsonNode pageNode = root;
            if (root.has("results")) {
                JsonNode results = root.path("results");
                if (!results.isArray() || results.size() == 0) {
                    throw new McpToolException("Page not found with title '" + title + "' in space '" + spaceKey + "'");
                }
                pageNode = results.get(0);
            }

            ObjectNode simplified = ResponseTransformer.simplifyPageNode(pageNode, baseUrl, convertToMarkdown);

            ObjectNode result = mapper.createObjectNode();
            result.set("page", simplified);
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to transform page response: " + e.getMessage());
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
