package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class GetAttachmentsTool implements McpTool {
    private final ConfluenceRestClient client;

    public GetAttachmentsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_attachments"; }

    @Override
    public String description() {
        return "List all attachments for a Confluence content item (page or blog post). Returns metadata about attachments including: - Attachment ID, title, and file type - File size and download URL - Creation/modification dates - Version information **Important**: Confluence API returns 'application/octet-stream' as the media type for most binary files (PNG, JPG, PDF) instead of specific types like 'image/png'. For filtering by file type, using the 'filename' parameter is more reliable (e.g., filename='*.png' pattern matching if supported, or exact filename). Useful for: - Discovering what files are attached to a page - Getting attachment IDs for download operations - Checking if a specific file exists - Listing images/documents for processing";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content_id", Map.of("type", "string", "description", "The ID of the Confluence content (page or blog post) to list attachments for. Example: '123456789'"),
                        "start", Map.of("type", "integer", "description", "(Optional) Starting index for pagination. Use 0 for the first page. To get the next page, add the 'limit' value to 'start'. Default: 0", "default", 0),
                        "limit", Map.of("type", "integer", "description", "(Optional) Maximum number of attachments to return per request (1-100). Use pagination (start/limit) for large attachment lists. Default: 50", "default", 50),
                        "filename", Map.of("type", "string", "description", "(Optional) Filter results to only attachments matching this filename. Exact match only. Example: 'report.pdf'"),
                        "media_type", Map.of("type", "string", "description", "(Optional) Filter by MIME type. **Note**: Confluence API returns 'application/octet-stream' for most binary files (PNG, JPG, PDF) instead of specific MIME types like 'image/png'. For more reliable filtering, use the 'filename' parameter. Examples: 'application/octet-stream' (binary files), 'application/pdf', 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' (for .docx)")
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
        int start = getInt(args, "start", 0);
        int limit = Math.min(getInt(args, "limit", 50), 100);
        String filename = (String) args.get("filename");
        String mediaType = (String) args.get("media_type");

        StringBuilder query = new StringBuilder();
        String sep = "?";
        query.append(sep).append("start=").append(start);
        sep = "&";
        query.append(sep).append("limit=").append(limit);
        sep = "&";
        if (filename != null && !filename.isBlank()) {
            query.append(sep).append("filename=").append(encode(filename));
            sep = "&";
        }
        if (mediaType != null && !mediaType.isBlank()) {
            query.append(sep).append("mediaType=").append(encode(mediaType));
            sep = "&";
        }

        return client.get("/rest/api/content/" + contentId + "/child/attachment" + query, authHeader);
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
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
