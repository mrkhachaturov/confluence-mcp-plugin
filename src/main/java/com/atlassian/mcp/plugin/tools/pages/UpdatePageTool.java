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
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "The ID of the page to update"),
                        "title", Map.of("type", "string", "description", "The new title of the page"),
                        "content", Map.of("type", "string", "description", "The new page content in Markdown. All rich features (panels, status badges, tasks, TOC, expand) work in Markdown — see tool description. Do NOT start with '# Title'."),
                        "is_minor_edit", Map.of("type", "boolean", "description", "Whether this is a minor edit", "default", false),
                        "version_comment", Map.of("type", "string", "description", "Optional comment for this version"),
                        "parent_id", Map.of("type", "string", "description", "Optional the new parent page ID"),
                        "content_format", Map.of("type", "string", "description", "(Optional) The format of the content parameter. Options: 'markdown' (default), 'wiki', or 'storage'. Wiki format uses Confluence wiki markup syntax", "default", "markdown"),
                        "enable_heading_anchors", Map.of("type", "boolean", "description", "(Optional) Whether to enable automatic heading anchor generation. Only applies when content_format is 'markdown'", "default", false),
                        "emoji", Map.of("type", "string", "description", "(Optional) Page title emoji (icon shown in navigation). Can be any emoji character like '📝', '🚀', '📚'. Set to null/None to remove.")
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

        // Fetch current page to get the version number
        int currentVersion;
        try {
            String current = client.getRaw("/rest/api/content/" + pageId + "?expand=version", authHeader);
            JsonNode parsed = mapper.readTree(current);
            currentVersion = parsed.path("version").path("number").asInt(0);
        } catch (Exception e) {
            throw new McpToolException("Failed to fetch current page version: " + e.getMessage());
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
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, false));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to update page: " + e.getMessage());
        }
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
