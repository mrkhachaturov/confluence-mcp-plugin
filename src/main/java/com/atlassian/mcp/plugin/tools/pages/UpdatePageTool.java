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
 * Mirrors upstream: confluence_mcp.update_page()
 * Returns: {message, page: {simplified page dict}}
 */
public class UpdatePageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public UpdatePageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "update_page"; }

    @Override
    public String description() {
        return "Update an existing Confluence page. Same Markdown features as create_page — all auto-converted "
                + "to native Confluence elements:\n"
                + "- Callout panels: > [!NOTE], > [!TIP], > [!IMPORTANT], > [!WARNING] (with optional |title:...)\n"
                + "- Status badges: {status:Text|color} (green, red, yellow, blue, grey)\n"
                + "- Task lists: - [x] / - [ ] → native checkboxes\n"
                + "- Table of contents: {toc}\n"
                + "- Expandable sections: <details><summary>Title</summary>content</details>\n"
                + "- All standard Markdown: headings, tables, code blocks, links, images, lists, bold, italic\n\n"
                + "IMPORTANT: This replaces the entire page content. Read the page first (get_page) if you need "
                + "to preserve existing content. Do NOT start with '# Title' — Confluence shows the title separately.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("page_id", Map.of("type", "string", "description", "The ID of the page to update")),
                        Map.entry("title", Map.of("type", "string", "description", "The new title of the page")),
                        Map.entry("content", Map.of("type", "string", "description", "The new page content in Markdown. All rich features (panels, status badges, tasks, TOC, expand) work in Markdown — see tool description. Do NOT start with '# Title'.")),
                        Map.entry("is_minor_edit", Map.of("type", "boolean", "description", "Whether this is a minor edit", "default", false)),
                        Map.entry("version_comment", Map.of("type", "string", "description", "Optional comment for this version")),
                        Map.entry("parent_id", Map.of("type", "string", "description", "Optional the new parent page ID")),
                        Map.entry("content_format", Map.of("type", "string", "description", "(Optional) The format of the content parameter. Options: 'markdown' (default), 'wiki', or 'storage'.", "default", "markdown")),
                        Map.entry("emoji", Map.of("type", "string", "description", "(Optional) Page title emoji (icon shown in navigation). Can be any emoji character.")),
                        Map.entry("expected_version", Map.of("type", "integer", "description", "If provided, the update will fail if the page's current version doesn't match this value. Use the version number from get_page to prevent overwriting concurrent changes.")),
                        Map.entry("return_markdown", Map.of("type", "boolean", "description", "If true, return the page content converted to Markdown instead of storage format.", "default", false))
                ),
                "required", List.of("page_id", "title", "content")
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
        String title = (String) args.get("title");
        if (title == null || title.isBlank()) {
            throw new McpToolException("'title' parameter is required");
        }
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        boolean isMinorEdit = getBoolean(args, "is_minor_edit", false);
        String versionComment = (String) args.get("version_comment");
        String parentId = (String) args.get("parent_id");
        String contentFormat = (String) args.getOrDefault("content_format", "markdown");
        boolean returnMarkdown = getBoolean(args, "return_markdown", false);

        // Fetch current page to get the version number
        int currentVersion;
        try {
            String current = client.getRaw("/rest/api/content/" + pageId + "?expand=version", authHeader);
            JsonNode parsed = mapper.readTree(current);
            currentVersion = parsed.path("version").path("number").asInt(0);
        } catch (Exception e) {
            throw new McpToolException("Failed to fetch current page version: " + e.getMessage());
        }

        // Optimistic locking: check expected version if provided
        int expectedVersion = getInt(args, "expected_version", -1);
        if (expectedVersion > 0 && expectedVersion != currentVersion) {
            throw new McpToolException(
                    "Page was modified since you last read it (current version: "
                    + currentVersion + ", expected: " + expectedVersion
                    + "). Re-read the page with get_page before updating.");
        }

        // Convert content to storage format (mirrors upstream's markdown_to_confluence_storage)
        String finalBody;
        String representation;
        if ("markdown".equals(contentFormat)) {
            finalBody = MarkdownToStorage.convert(content);
            representation = "storage";
        } else if ("wiki".equals(contentFormat)) {
            finalBody = content;
            representation = "wiki";
        } else {
            finalBody = content;
            representation = "storage";
        }

        Map<String, Object> version = new HashMap<>();
        version.put("number", currentVersion + 1);
        version.put("minorEdit", isMinorEdit);
        if (versionComment != null && !versionComment.isBlank()) {
            version.put("message", versionComment);
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", pageId);
        requestBody.put("type", "page");
        requestBody.put("title", title);
        requestBody.put("version", version);
        requestBody.put("body", Map.of(representation, Map.of(
                "value", finalBody,
                "representation", representation
        )));
        if (parentId != null && !parentId.isBlank()) {
            requestBody.put("ancestors", List.of(Map.of("id", parentId)));
        }
        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.putRaw("/rest/api/content/" + pageId, jsonBody, authHeader);

            // Transform to upstream format: {message, page}
            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Page updated successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, returnMarkdown));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to update page: " + e.getMessage());
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
