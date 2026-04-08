package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.delete_page()
 * Returns: {success: true, message: "Page {id} deleted successfully"}
 */
public class DeletePageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

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

        client.delete("/rest/api/content/" + pageId, authHeader);

        // Return upstream format: {success, message}
        try {
            ObjectNode result = mapper.createObjectNode();
            result.put("success", true);
            result.put("message", "Page " + pageId + " deleted successfully");
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new McpToolException("Failed to serialize response: " + e.getMessage());
        }
    }
}
