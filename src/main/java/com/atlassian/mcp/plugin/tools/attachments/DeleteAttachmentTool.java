package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;

public class DeleteAttachmentTool extends TypedTool<DeleteAttachmentTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "The ID of the attachment to delete. Attachment IDs can be found using the"
                      + " get_attachments tool. Example: 'att123456789'. **Warning**: This"
                      + " permanently deletes the attachment and all its versions.",
              required = true)
          String attachmentId) {}

  private final ConfluenceRestClient client;

  public DeleteAttachmentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "delete_attachment";
  }

  @Override
  public String description() {
    return "Permanently delete an attachment from Confluence. **Warning**: This action cannot be undone! The attachment and ALL its versions will be permanently deleted. Use this tool to: - Remove outdated or incorrect attachments - Clean up duplicate files - Delete sensitive information that was accidentally uploaded Best practices: - Verify the attachment ID before deletion using get_attachments - Consider downloading the attachment first as a backup - Check with content owners before deleting shared attachments";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  public boolean isDestructiveTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    return client.delete("/rest/api/content/" + args.attachmentId(), context.authHeader());
  }
}
