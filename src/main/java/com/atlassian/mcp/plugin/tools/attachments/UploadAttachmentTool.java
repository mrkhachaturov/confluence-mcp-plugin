package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

public class UploadAttachmentTool implements McpTool {
    private final ConfluenceRestClient client;

    public UploadAttachmentTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "upload_attachment"; }

    @Override
    public String description() {
        return "Upload an attachment to Confluence content (page or blog post). If the attachment already exists (same filename), a new version is created. This is useful for: - Attaching documents, images, or files to a page - Updating existing attachments with new versions - Adding supporting materials to documentation";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content_id", Map.of("type", "string", "description", "The ID of the Confluence content (page or blog post) to attach the file to. Page IDs can be found in the page URL or by using the search/get_page tools. Example: '123456789'"),
                        "file_path", Map.of("type", "string", "description", "Full path to the file to upload. Can be absolute (e.g., '/home/user/document.pdf' or 'C:\\Users\\name\\file.docx') or relative to the current working directory (e.g., './uploads/document.pdf'). If a file with the same name already exists, a new version will be created."),
                        "comment", Map.of("type", "string", "description", "(Optional) A comment describing this attachment or version. Visible in the attachment history. Example: 'Updated Q4 2024 figures'"),
                        "minor_edit", Map.of("type", "boolean", "description", "(Optional) Whether this is a minor edit. If true, watchers are not notified. Default is false.", "default", false)
                ),
                "required", List.of("content_id", "file_path")
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
        String filePath = (String) args.get("file_path");
        if (filePath == null || filePath.isBlank()) {
            throw new McpToolException("'file_path' parameter is required");
        }
        String comment = (String) args.get("comment");
        boolean minorEdit = getBoolean(args, "minor_edit", false);

        Path file = Paths.get(filePath);
        if (!java.nio.file.Files.exists(file)) {
            throw new McpToolException("File not found: " + filePath);
        }

        return client.postMultipart(
                "/rest/api/content/" + contentId + "/child/attachment",
                file, comment, minorEdit, authHeader);
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
