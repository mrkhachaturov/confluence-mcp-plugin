package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class UploadAttachmentsTool implements McpTool {
    private final ConfluenceRestClient client;

    public UploadAttachmentsTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "upload_attachments"; }

    @Override
    public String description() {
        return "Upload multiple attachments to Confluence content in a single operation. More efficient than calling upload_attachment multiple times. If files with the same names exist, new versions are created automatically. Useful for: - Bulk uploading documentation assets (diagrams, screenshots, etc.) - Adding multiple related files to a page at once - Batch updating existing attachments with new versions";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content_id", Map.of("type", "string", "description", "The ID of the Confluence content (page or blog post) to attach files to. Example: '123456789'. If uploading multiple files with the same names, new versions will be created automatically."),
                        "file_paths", Map.of("type", "string", "description", "Comma-separated list of file paths to upload. Can be absolute or relative paths. Examples: './file1.pdf,./file2.png' or 'C:\\docs\\report.docx,D:\\image.jpg'. All files uploaded with same comment/minor_edit settings."),
                        "comment", Map.of("type", "string", "description", "(Optional) Comment for all uploaded attachments. Visible in version history. Example: 'Q4 2024 batch upload'"),
                        "minor_edit", Map.of("type", "boolean", "description", "(Optional) Whether this is a minor edit. If true, watchers are not notified. Default is false.", "default", false)
                ),
                "required", List.of("content_id", "file_paths")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String contentId = (String) args.get("content_id");
        if (contentId == null || contentId.isBlank()) {
            throw new McpToolException("'content_id' parameter is required");
        }
        contentId = McpTool.resolvePageId(contentId);
        String filePaths = (String) args.get("file_paths");
        if (filePaths == null || filePaths.isBlank()) {
            throw new McpToolException("'file_paths' parameter is required");
        }
        String comment = (String) args.get("comment");
        boolean minorEdit = getBoolean(args, "minor_edit", false);

        String[] paths = filePaths.split(",");
        List<String> results = new ArrayList<>();
        String endpoint = "/rest/api/content/" + contentId + "/child/attachment";

        for (String p : paths) {
            String trimmed = p.trim();
            if (trimmed.isEmpty()) continue;
            Path file = Paths.get(trimmed);
            try {
                String result = client.postMultipart(endpoint, file, comment, minorEdit, authHeader);
                results.add(trimmed + ": OK");
            } catch (McpToolException e) {
                results.add(trimmed + ": ERROR - " + e.getMessage());
            }
        }

        return String.join("\n", results);
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
