package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public class UploadAttachmentTool extends TypedTool<UploadAttachmentTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "The ID of the Confluence content (page or blog post) to attach the file to."
                      + " Page IDs can be found in the page URL or by using the search/get_page"
                      + " tools. Example: '123456789'",
              required = true)
          String contentId,
      @ToolArg(
              value =
                  "Full path to the file to upload. Can be absolute (e.g.,"
                      + " '/home/user/document.pdf' or 'C:\\Users\\name\\file.docx') or relative to"
                      + " the current working directory (e.g., './uploads/document.pdf'). If a file"
                      + " with the same name already exists, a new version will be created.",
              required = true)
          String filePath,
      @ToolArg(
              "(Optional) A comment describing this attachment or version. Visible in the"
                  + " attachment history. Example: 'Updated Q4 2024 figures'")
          String comment,
      @ToolArg(
              value =
                  "(Optional) Whether this is a minor edit. If true, watchers are not notified.",
              defaultValue = "false")
          boolean minorEdit) {}

  private final ConfluenceRestClient client;

  public UploadAttachmentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "upload_attachment";
  }

  @Override
  public String description() {
    return "Upload an attachment to Confluence content (page or blog post). If the attachment already exists (same filename), a new version is created. This is useful for: - Attaching documents, images, or files to a page - Updating existing attachments with new versions - Adding supporting materials to documentation";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String contentId = McpTool.resolvePageId(args.contentId());
    Path file = Paths.get(args.filePath());
    if (!Files.exists(file)) {
      throw new McpToolException("File not found: " + args.filePath());
    }

    return client.postMultipart(
        "/rest/api/content/" + contentId + "/child/attachment",
        file,
        args.comment(),
        args.minorEdit(),
        context.authHeader());
  }
}
