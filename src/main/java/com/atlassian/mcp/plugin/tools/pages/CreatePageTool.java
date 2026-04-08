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
 * Mirrors upstream: confluence_mcp.create_page()
 * Returns: {message, page: {simplified page dict}}
 */
public class CreatePageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreatePageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "create_page"; }

    @Override
    public String description() {
        return "Create a new Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "space_key", Map.of("type", "string", "description", "The key of the space to create the page in (usually a short uppercase code like 'DEV', 'TEAM', or 'DOC')"),
                        "title", Map.of("type", "string", "description", "The title of the page"),
                        "content", Map.of("type", "string", "description", "The content of the page. Format depends on content_format parameter. Can be Markdown (default), wiki markup, or storage format"),
                        "parent_id", Map.of("type", "string", "description", "(Optional) parent page ID. If provided, this page will be created as a child of the specified page"),
                        "content_format", Map.of("type", "string", "description", "(Optional) The format of the content parameter. Options: 'markdown' (default), 'wiki', or 'storage'. Wiki format uses Confluence wiki markup syntax", "default", "markdown"),
                        "enable_heading_anchors", Map.of("type", "boolean", "description", "(Optional) Whether to enable automatic heading anchor generation. Only applies when content_format is 'markdown'", "default", false),
                        "emoji", Map.of("type", "string", "description", "(Optional) Page title emoji (icon shown in navigation). Can be any emoji character like '📝', '🚀', '📚'. Set to null/None to remove.")
                ),
                "required", List.of("space_key", "title", "content")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String spaceKey = (String) args.get("space_key");
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new McpToolException("'space_key' parameter is required");
        }
        String title = (String) args.get("title");
        if (title == null || title.isBlank()) {
            throw new McpToolException("'title' parameter is required");
        }
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        String parentId = (String) args.get("parent_id");
        String contentFormat = (String) args.getOrDefault("content_format", "markdown");

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

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("type", "page");
        requestBody.put("title", title);
        requestBody.put("space", Map.of("key", spaceKey));
        requestBody.put("body", Map.of(representation, Map.of(
                "value", finalBody,
                "representation", representation
        )));
        if (parentId != null && !parentId.isBlank()) {
            requestBody.put("ancestors", List.of(Map.of("id", parentId)));
        }
        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.postRaw("/rest/api/content", jsonBody, authHeader);

            // Transform to upstream format: {message, page}
            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Page created successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, false));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to create page: " + e.getMessage());
        }
    }
}
