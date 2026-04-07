package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Base64;
import java.util.List;
import java.util.Map;

public class DownloadAttachmentTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final long MAX_BYTES = 50 * 1024 * 1024; // 50 MB

    public DownloadAttachmentTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "download_attachment"; }

    @Override
    public String description() {
        return "Download an attachment from Confluence as an embedded resource. Returns the attachment content as a base64-encoded embedded resource so that it is available over the MCP protocol without requiring filesystem access on the server. Files larger than 50 MB are not downloaded inline; a descriptive error message is returned instead.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "attachment_id", Map.of("type", "string", "description", "The ID of the attachment to download (e.g., 'att123456789'). Find attachment IDs using get_attachments tool. Example workflow: get_attachments(content_id) → use returned ID here.")
                ),
                "required", List.of("attachment_id")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String attachmentId = (String) args.get("attachment_id");
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new McpToolException("'attachment_id' parameter is required");
        }

        // Fetch attachment metadata to get download link
        String meta = client.get("/rest/api/content/" + attachmentId + "?expand=version", authHeader);
        try {
            JsonNode node = mapper.readTree(meta);
            String title = node.path("title").asText("unknown");

            // Download link is in _links.download (but ResponseTrimmer strips _links)
            // Use the standard download path pattern
            String downloadPath = "/rest/api/content/" + attachmentId + "/download";

            byte[] data = client.getBytes(downloadPath, authHeader);
            if (data.length > MAX_BYTES) {
                throw new McpToolException("Attachment '" + title + "' exceeds 50 MB limit (" + data.length + " bytes)");
            }

            String base64 = Base64.getEncoder().encodeToString(data);
            return "{\"filename\":\"" + title + "\",\"size\":" + data.length
                    + ",\"base64\":\"" + base64 + "\"}";
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to download attachment: " + e.getMessage());
        }
    }
}
