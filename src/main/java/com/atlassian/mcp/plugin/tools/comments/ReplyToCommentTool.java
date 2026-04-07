package com.atlassian.mcp.plugin.tools.comments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ReplyToCommentTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReplyToCommentTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "reply_to_comment"; }

    @Override
    public String description() {
        return "Reply to an existing comment thread on a Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "comment_id", Map.of("type", "string", "description", "The ID of the parent comment to reply to"),
                        "body", Map.of("type", "string", "description", "The reply content in Markdown format")
                ),
                "required", List.of("comment_id", "body")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String commentId = (String) args.get("comment_id");
        if (commentId == null || commentId.isBlank()) {
            throw new McpToolException("'comment_id' parameter is required");
        }
        String body = (String) args.get("body");
        if (body == null || body.isBlank()) {
            throw new McpToolException("'body' parameter is required");
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("type", "comment");
        requestBody.put("ancestors", List.of(Map.of("id", commentId)));
        requestBody.put("body", Map.of("storage", Map.of(
                "value", body,
                "representation", "storage"
        )));
        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            return client.post("/rest/api/content", jsonBody, authHeader);
        } catch (Exception e) {
            throw new McpToolException("Failed to serialize request: " + e.getMessage());
        }
    }
}
