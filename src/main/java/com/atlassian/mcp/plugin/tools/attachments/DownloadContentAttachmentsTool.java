package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public class DownloadContentAttachmentsTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final long MAX_BYTES = 50 * 1024 * 1024;

    public DownloadContentAttachmentsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "download_content_attachments"; }

    @Override
    public String description() {
        return "Download all attachments for a Confluence content item as embedded resources. Returns attachment contents as base64-encoded embedded resources so that they are available over the MCP protocol without requiring filesystem access on the server. Files larger than 50 MB are skipped with an error entry in the summary.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content_id", Map.of("type", "string", "description", "The ID of the Confluence content (page or blog post) to download attachments from. Example: '123456789'")
                ),
                "required", List.of("content_id")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String contentId = (String) args.get("content_id");
        if (contentId == null || contentId.isBlank()) {
            throw new McpToolException("'content_id' parameter is required");
        }
        contentId = McpTool.resolvePageId(contentId);

        String listJson = client.get("/rest/api/content/" + contentId + "/child/attachment?limit=100", authHeader);
        try {
            JsonNode root = mapper.readTree(listJson);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "{\"message\":\"No attachments found\",\"attachments\":[]}";
            }

            com.fasterxml.jackson.databind.node.ArrayNode attachments = mapper.createArrayNode();
            for (JsonNode att : results) {
                String attId = att.path("id").asText();
                String title = att.path("title").asText("unknown");
                com.fasterxml.jackson.databind.node.ObjectNode entry = mapper.createObjectNode();
                entry.put("filename", title);
                try {
                    String downloadPath = "/rest/api/content/" + attId + "/download";
                    byte[] data = client.getBytes(downloadPath, authHeader);
                    if (data.length > MAX_BYTES) {
                        entry.put("error", "Exceeds 50 MB limit");
                    } else {
                        entry.put("size", data.length);
                        entry.put("base64", Base64.getEncoder().encodeToString(data));
                    }
                } catch (Exception e) {
                    entry.put("error", e.getMessage());
                }
                attachments.add(entry);
            }

            com.fasterxml.jackson.databind.node.ObjectNode out = mapper.createObjectNode();
            out.put("count", attachments.size());
            out.set("attachments", attachments);
            return mapper.writeValueAsString(out);
        } catch (Exception e) {
            throw new McpToolException("Failed to process attachments: " + e.getMessage());
        }
    }
}
