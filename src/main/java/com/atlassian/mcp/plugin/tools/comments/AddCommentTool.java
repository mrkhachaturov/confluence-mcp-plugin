package com.atlassian.mcp.plugin.tools.comments;

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
 * Mirrors upstream: confluence_mcp.add_comment()
 * Returns: {success, message, comment: {simplified comment dict}}
 */
public class AddCommentTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AddCommentTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "add_comment"; }

    @Override
    public String description() {
        return "Add a comment to a Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "The ID of the page to add a comment to"),
                        "body", Map.of("type", "string", "description", "The comment content in Markdown format")
                ),
                "required", List.of("page_id", "body")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        String body = (String) args.get("body");
        if (body == null || body.isBlank()) {
            throw new McpToolException("'body' parameter is required");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("type", "comment");
        requestBody.put("container", Map.of("id", pageId, "type", "page"));
        requestBody.put("body", Map.of("storage", Map.of(
                "value", MarkdownToStorage.convert(body),
                "representation", "storage"
        )));
        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.postRaw("/rest/api/content", jsonBody, authHeader);

            // Transform to upstream format: {success, message, comment}
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("success", true);
            result.put("message", "Comment added successfully");
            result.set("comment", ResponseTransformer.simplifyCommentNode(raw, true));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to add comment: " + e.getMessage());
        }
    }
}
