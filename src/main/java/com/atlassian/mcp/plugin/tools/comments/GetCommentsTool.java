package com.atlassian.mcp.plugin.tools.comments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.get_comments()
 * Returns: [{id, body, created, updated, author, parent_comment_id, location}, ...]
 */
public class GetCommentsTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public GetCommentsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_comments"; }

    @Override
    public String description() {
        return "Get comments for a specific Confluence page.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be parsed from URL, e.g. from 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title' -> '123456789')")
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
        pageId = McpTool.resolvePageId(pageId);

        // Upstream uses body.view (rendered HTML) — easier to convert to markdown
        String rawJson = client.getRaw("/rest/api/content/" + pageId
                + "/child/comment?expand=body.view,version,ancestors&depth=all", authHeader);

        // Transform to upstream format: flat list of simplified comment dicts
        try {
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");
            ArrayNode output = mapper.createArrayNode();

            if (results.isArray()) {
                for (JsonNode comment : results) {
                    output.add(ResponseTransformer.simplifyCommentNode(comment, true));
                }
            }

            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform comments response: " + e.getMessage());
        }
    }
}
