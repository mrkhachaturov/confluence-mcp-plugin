package com.atlassian.mcp.plugin.tools.spaces;

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
 * List available Confluence spaces.
 * Returns: [{key, name, type, url}, ...]
 */
public class ListSpacesTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public ListSpacesTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "list_spaces"; }

    @Override
    public String description() {
        return "List available Confluence spaces. Returns space key, name, type, and URL for each space. "
                + "Use this to discover which spaces exist before creating pages or searching. "
                + "Supports filtering by space type (global or personal) and pagination.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "limit", Map.of("type", "integer", "description",
                                "Maximum number of spaces to return (default: 25)", "default", 25),
                        "start", Map.of("type", "integer", "description",
                                "Starting index for pagination (default: 0)", "default", 0),
                        "type", Map.of("type", "string", "description",
                                "Filter by space type: 'global' for team/project spaces, 'personal' for user spaces. Omit to return all types.")
                ),
                "required", List.of()
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        int limit = getInt(args, "limit", 25);
        int start = getInt(args, "start", 0);
        String type = (String) args.get("type");

        StringBuilder path = new StringBuilder("/rest/api/space?limit=");
        path.append(limit).append("&start=").append(start);
        if (type != null && !type.isBlank()) {
            path.append("&type=").append(type);
        }

        try {
            String rawJson = client.getRaw(path.toString(), authHeader);
            String baseUrl = client.getBaseUrl();
            JsonNode root = mapper.readTree(rawJson);
            JsonNode results = root.path("results");

            ArrayNode output = mapper.createArrayNode();
            if (results.isArray()) {
                for (JsonNode space : results) {
                    output.add(ResponseTransformer.simplifySpaceNode(space, baseUrl));
                }
            }

            return mapper.writeValueAsString(output);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to list spaces: " + e.getMessage());
        }
    }

    private static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }
}
