package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.util.List;
import java.util.Map;

public class DeleteAttachmentTool implements McpTool {
    private final ConfluenceRestClient client;

    public DeleteAttachmentTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "delete_attachment"; }

    @Override
    public String description() {
        return "Permanently delete an attachment from Confluence. **Warning**: This action cannot be undone! The attachment and ALL its versions will be permanently deleted. Use this tool to: - Remove outdated or incorrect attachments - Clean up duplicate files - Delete sensitive information that was accidentally uploaded Best practices: - Verify the attachment ID before deletion using get_attachments - Consider downloading the attachment first as a backup - Check with content owners before deleting shared attachments";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "attachment_id", Map.of("type", "string", "description", "The ID of the attachment to delete. Attachment IDs can be found using the get_attachments tool. Example: 'att123456789'. **Warning**: This permanently deletes the attachment and all its versions.")
                ),
                "required", List.of("attachment_id")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String attachmentId = (String) args.get("attachment_id");
        if (attachmentId == null || attachmentId.isBlank()) {
            throw new McpToolException("'attachment_id' parameter is required");
        }

        return client.delete("/rest/api/content/" + attachmentId, authHeader);
    }
}
