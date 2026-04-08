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
 * Mirrors upstream: confluence_mcp.add_label()
 * Returns: updated list of all labels [{id, name, prefix, label}, ...]
 */
public class AddLabelTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public AddLabelTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "add_label"; }

    @Override
    public String description() {
        return "Add label to Confluence content (pages, blog posts, or attachments). Useful for: - Categorizing attachments (e.g., 'screenshot', 'diagram', 'legal-doc') - Tracking status (e.g., 'approved', 'needs-review', 'archived') - Filtering content by topic or version";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence content ID to label. For pages/blogs: numeric ID (e.g., '123456789'). For attachments: ID with 'att' prefix (e.g., 'att123456789'). Use get_attachments to find attachment IDs."),
                        "name", Map.of("type", "string", "description", "Label name to add (lowercase, no spaces). Examples: 'draft', 'reviewed', 'confidential', 'v1.0'. Labels help organize and categorize content.")
                ),
                "required", List.of("page_id", "name")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        String name = (String) args.get("name");
        if (name == null || name.isBlank()) {
            throw new McpToolException("'name' parameter is required");
        }

        // Confluence label API expects an array of label objects
        List<Map<String, String>> labels = List.of(Map.of("prefix", "global", "name", name));
        try {
            String jsonBody = mapper.writeValueAsString(labels);
            client.post("/rest/api/content/" + pageId + "/label", jsonBody, authHeader);

            // Upstream refreshes label list after adding — return updated labels
            String rawJson = client.getRaw("/rest/api/content/" + pageId + "/label", authHeader);
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");
            ArrayNode output = mapper.createArrayNode();

            if (results.isArray()) {
                for (JsonNode label : results) {
                    output.add(ResponseTransformer.simplifyLabelNode(label));
                }
            }

            return mapper.writeValueAsString(output);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to add label: " + e.getMessage());
        }
    }
}
