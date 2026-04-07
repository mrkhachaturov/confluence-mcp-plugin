package com.atlassian.mcp.plugin.tools.labels;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.util.List;
import java.util.Map;

public class GetLabelsTool implements McpTool {
    private final ConfluenceRestClient client;

    public GetLabelsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_labels"; }

    @Override
    public String description() {
        return "Get labels for Confluence content (pages, blog posts, or attachments).";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence content ID (page, blog post, or attachment). For pages: numeric ID from URL (e.g., '123456789'). For attachments: ID with 'att' prefix (e.g., 'att123456789'). Works with any Confluence content type that supports labels.")
                ),
                "required", List.of("page_id")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }

        return client.get("/rest/api/content/" + pageId + "/label", authHeader);
    }
}
