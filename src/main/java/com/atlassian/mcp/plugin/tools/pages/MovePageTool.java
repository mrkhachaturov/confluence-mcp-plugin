package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.move_page()
 * Returns: {message, page: {simplified page dict}}
 */
public class MovePageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public MovePageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "move_page"; }

    @Override
    public String description() {
        return "Move a Confluence page to a new parent or space.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "ID of the page to move"),
                        "target_parent_id", Map.of("type", "string", "description", "Target parent page ID. If omitted with target_space_key, moves to space root."),
                        "target_space_key", Map.of("type", "string", "description", "Target space key for cross-space moves"),
                        "position", Map.of("type", "string", "description", "Position: 'append' (default, move as child of target), 'above' (move before target as sibling), or 'below' (move after target as sibling)", "default", "append")
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
        pageId = McpTool.resolvePageId(pageId);
        String targetParentId = (String) args.get("target_parent_id");
        String targetSpaceKey = (String) args.get("target_space_key");
        String position = (String) args.getOrDefault("position", "append");

        if (targetParentId == null && targetSpaceKey == null) {
            throw new McpToolException("At least one of 'target_parent_id' or 'target_space_key' is required");
        }

        // Confluence move API: PUT /rest/api/content/{id}/move/{position}/{targetId}
        String targetId = targetParentId != null ? targetParentId : "0";
        String path = "/rest/api/content/" + pageId + "/move/" + position + "/" + targetId;
        String rawJson = client.putRaw(path, "{}", authHeader);

        // Transform to upstream format: {message, page}
        try {
            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Page moved successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, false));
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new McpToolException("Failed to move page: " + e.getMessage());
        }
    }
}
