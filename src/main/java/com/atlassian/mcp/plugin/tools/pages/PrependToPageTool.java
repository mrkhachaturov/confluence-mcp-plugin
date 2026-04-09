package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Prepend content to the beginning of an existing Confluence page.
 * Returns: {message, page: {simplified page dict}}
 */
public class PrependToPageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public PrependToPageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "prepend_to_page"; }

    @Override
    public String description() {
        return "Prepend content to the beginning of an existing Confluence page without touching existing content. "
                + "The new content is added before the page's current content. "
                + "Supports the same Markdown features as create_page (panels, status badges, task lists, etc.).\n\n"
                + "Use cases: adding changelog entries, alerts, or announcements at the top of a page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "The ID of the page to prepend content to"),
                        "content", Map.of("type", "string", "description", "Content to prepend (in Markdown by default). Added before the existing page content."),
                        "content_format", Map.of("type", "string", "description", "(Optional) Format of the content: 'markdown' (default), 'wiki', or 'storage'.", "default", "markdown"),
                        "expected_version", Map.of("type", "integer", "description", "If provided, the update will fail if the page's current version doesn't match. Prevents overwriting concurrent changes.")
                ),
                "required", List.of("page_id", "content")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        pageId = McpTool.resolvePageId(pageId);

        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        String contentFormat = (String) args.getOrDefault("content_format", "markdown");

        // Fetch current page (need body + version + title)
        String currentTitle;
        int currentVersion;
        String existingBody;
        try {
            String current = client.getRaw(
                    "/rest/api/content/" + pageId + "?expand=body.storage,version", authHeader);
            JsonNode parsed = mapper.readTree(current);
            currentVersion = parsed.path("version").path("number").asInt(0);
            currentTitle = parsed.path("title").asText("");
            existingBody = parsed.path("body").path("storage").path("value").asText("");
        } catch (Exception e) {
            throw new McpToolException("Failed to fetch current page: " + e.getMessage());
        }

        // Optimistic locking
        int expectedVersion = getInt(args, "expected_version", -1);
        if (expectedVersion > 0 && expectedVersion != currentVersion) {
            throw new McpToolException(
                    "Page was modified since you last read it (current version: "
                    + currentVersion + ", expected: " + expectedVersion
                    + "). Re-read the page with get_page before updating.");
        }

        // Convert new content to storage format
        String newStorage;
        if ("markdown".equals(contentFormat)) {
            newStorage = MarkdownToStorage.convert(content);
        } else {
            newStorage = content;
        }

        // Prepend: new content + existing body
        String combinedBody = newStorage + "\n" + existingBody;

        Map<String, Object> version = new HashMap<>();
        version.put("number", currentVersion + 1);
        version.put("minorEdit", false);
        version.put("message", "Content prepended");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", pageId);
        requestBody.put("type", "page");
        requestBody.put("title", currentTitle);
        requestBody.put("version", version);
        requestBody.put("body", Map.of("storage", Map.of(
                "value", combinedBody,
                "representation", "storage"
        )));

        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.putRaw("/rest/api/content/" + pageId, jsonBody, authHeader);

            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Content prepended successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, false));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to prepend content: " + e.getMessage());
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
