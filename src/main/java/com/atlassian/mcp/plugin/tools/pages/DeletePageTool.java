package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.util.List;
import java.util.Map;

public class DeletePageTool implements McpTool {
    private final ConfluenceRestClient client;

    public DeletePageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "delete_page"; }

    @Override
    public String description() {
        return "Delete an existing Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "The ID of the page to delete")
                ),
                "required", List.of("page_id")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }

        return client.delete("/rest/api/content/" + pageId, authHeader);
    }
}
