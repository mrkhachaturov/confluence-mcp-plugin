package com.atlassian.mcp.plugin.tools.labels;

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
 * Mirrors upstream: confluence_mcp.get_labels()
 * Returns: [{id, name, prefix, label}, ...]
 */
public class GetLabelsTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

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
        pageId = McpTool.resolvePageId(pageId);

        String rawJson = client.getRaw("/rest/api/content/" + pageId + "/label", authHeader);

        // Transform to upstream format: flat list of simplified label dicts
        try {
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");
            ArrayNode output = mapper.createArrayNode();

            if (results.isArray()) {
                for (JsonNode label : results) {
                    output.add(ResponseTransformer.simplifyLabelNode(label));
                }
            }

            return mapper.writeValueAsString(output);
        } catch (Exception e) {
            throw new McpToolException("Failed to transform labels response: " + e.getMessage());
        }
    }
}
