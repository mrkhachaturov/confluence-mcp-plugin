package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class UploadAttachmentsTool extends TypedTool<UploadAttachmentsTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "The ID of the Confluence content (page or blog post) to attach files to."
                      + " Example: '123456789'. If uploading multiple files with the same names,"
                      + " new versions will be created automatically.",
              required = true)
          String contentId,
      @ToolArg(
              value =
                  "File paths to upload. Each may be absolute or relative to the current working"
                      + " directory. All files are uploaded with the same comment and minor_edit"
                      + " settings.",
              required = true)
          List<String> filePaths,
      @ToolArg(
              "(Optional) Comment for all uploaded attachments. Visible in version history."
                  + " Example: 'Q4 2024 batch upload'")
          String comment,
      @ToolArg(
              value =
                  "(Optional) Whether this is a minor edit. If true, watchers are not notified.",
              defaultValue = "false")
          boolean minorEdit) {}

  private final ConfluenceRestClient client;

  public UploadAttachmentsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "upload_attachments";
  }

  @Override
  public String description() {
    return "Upload multiple attachments to Confluence content in a single operation. More efficient than calling upload_attachment multiple times. If files with the same names exist, new versions are created automatically. Useful for: - Bulk uploading documentation assets (diagrams, screenshots, etc.) - Adding multiple related files to a page at once - Batch updating existing attachments with new versions";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    if (args.filePaths().isEmpty()) {
      throw new McpToolException("'file_paths' must name at least one file");
    }
    String contentId = McpTool.resolvePageId(args.contentId());
    String endpoint = "/rest/api/content/" + contentId + "/child/attachment";

    List<String> results = new ArrayList<>();
    int done = 0;
    for (String filePath : args.filePaths()) {
      Path file = Paths.get(filePath);
      try {
        client.postMultipart(
            endpoint, file, args.comment(), args.minorEdit(), context.authHeader());
        results.add(filePath + ": OK");
      } catch (McpToolException e) {
        results.add(filePath + ": ERROR - " + e.getMessage());
      }
      context.reportProgress(++done, args.filePaths().size(), "uploaded " + filePath);
    }

    return String.join("\n", results);
  }
}
