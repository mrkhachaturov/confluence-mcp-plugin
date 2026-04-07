package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class GetPageImagesTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private static final long MAX_BYTES = 50 * 1024 * 1024;
    private static final Set<String> IMAGE_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "svg", "bmp"
    );

    public GetPageImagesTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page_images"; }

    @Override
    public String description() {
        return "Get all images attached to a Confluence page as inline image content. Filters attachments to images only (PNG, JPEG, GIF, WebP, SVG, BMP) and returns them as base64-encoded ImageContent that clients can render directly. Non-image attachments are excluded. Files with ambiguous MIME types (application/octet-stream) are detected by filename extension as a fallback. Images larger than 50 MB are skipped with an error entry in the summary.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content_id", Map.of("type", "string", "description", "The ID of the Confluence page or blog post to retrieve images from. Example: '123456789'")
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

        String listJson = client.get("/rest/api/content/" + contentId
                + "/child/attachment?limit=100&expand=version", authHeader);
        try {
            JsonNode root = mapper.readTree(listJson);
            JsonNode results = root.path("results");
            if (!results.isArray() || results.isEmpty()) {
                return "{\"message\":\"No attachments found\",\"images\":[]}";
            }

            List<String> images = new ArrayList<>();
            for (JsonNode att : results) {
                String title = att.path("title").asText("");
                String mediaType = att.path("metadata").path("mediaType").asText("");

                // Check if this is an image
                boolean isImage = mediaType.startsWith("image/");
                if (!isImage) {
                    // Fallback: check file extension
                    int dot = title.lastIndexOf('.');
                    if (dot >= 0) {
                        String ext = title.substring(dot + 1).toLowerCase();
                        isImage = IMAGE_EXTENSIONS.contains(ext);
                        if (isImage && mediaType.isEmpty()) {
                            mediaType = "image/" + (ext.equals("jpg") ? "jpeg" : ext);
                        }
                    }
                }
                if (!isImage) continue;

                String attId = att.path("id").asText();
                try {
                    byte[] data = client.getBytes("/rest/api/content/" + attId + "/download", authHeader);
                    if (data.length > MAX_BYTES) {
                        images.add("{\"filename\":\"" + title + "\",\"error\":\"Exceeds 50 MB limit\"}");
                        continue;
                    }
                    String base64 = Base64.getEncoder().encodeToString(data);
                    images.add("{\"filename\":\"" + title + "\",\"mediaType\":\"" + mediaType
                            + "\",\"size\":" + data.length + ",\"base64\":\"" + base64 + "\"}");
                } catch (Exception e) {
                    images.add("{\"filename\":\"" + title + "\",\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
                }
            }

            return "{\"count\":" + images.size() + ",\"images\":[" + String.join(",", images) + "]}";
        } catch (Exception e) {
            throw new McpToolException("Failed to process images: " + e.getMessage());
        }
    }
}
