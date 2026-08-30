package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class GetAttachmentsTool extends TypedTool<GetAttachmentsTool.Args> {

  private static final int MAX_LIMIT = 100;

  public record Args(
      @ToolArg(
              value =
                  "The ID of the Confluence content (page or blog post) to list attachments for."
                      + " Example: '123456789'",
              required = true)
          String contentId,
      @ToolArg(
              value =
                  "(Optional) Starting index for pagination. Use 0 for the first page. To get the"
                      + " next page, add the 'limit' value to 'start'.",
              defaultValue = "0")
          int start,
      @ToolArg(
              value =
                  "(Optional) Maximum number of attachments to return per request (1-100). Use"
                      + " pagination (start/limit) for large attachment lists.",
              defaultValue = "50")
          int limit,
      @ToolArg(
              "(Optional) Filter results to only attachments matching this filename. Exact match"
                  + " only. Example: 'report.pdf'")
          String filename,
      @ToolArg(
              "(Optional) Filter by MIME type. **Note**: Confluence API returns"
                  + " 'application/octet-stream' for most binary files (PNG, JPG, PDF) instead of"
                  + " specific MIME types like 'image/png'. For more reliable filtering, use the"
                  + " 'filename' parameter. Examples: 'application/octet-stream' (binary files),"
                  + " 'application/pdf',"
                  + " 'application/vnd.openxmlformats-officedocument.wordprocessingml.document'"
                  + " (for .docx)")
          String mediaType) {}

  private final ConfluenceRestClient client;

  public GetAttachmentsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_attachments";
  }

  @Override
  public String description() {
    return "List all attachments for a Confluence content item (page or blog post). Returns metadata about attachments including: - Attachment ID, title, and file type - File size and download URL - Creation/modification dates - Version information **Important**: Confluence API returns 'application/octet-stream' as the media type for most binary files (PNG, JPG, PDF) instead of specific types like 'image/png'. For filtering by file type, using the 'filename' parameter is more reliable (e.g., filename='*.png' pattern matching if supported, or exact filename). Useful for: - Discovering what files are attached to a page - Getting attachment IDs for download operations - Checking if a specific file exists - Listing images/documents for processing";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String contentId = McpTool.resolvePageId(args.contentId());

    StringBuilder query = new StringBuilder("?start=").append(args.start());
    query.append("&limit=").append(Math.min(args.limit(), MAX_LIMIT));
    if (args.filename() != null) {
      query.append("&filename=").append(encode(args.filename()));
    }
    if (args.mediaType() != null) {
      query.append("&mediaType=").append(encode(args.mediaType()));
    }

    return client.get(
        "/rest/api/content/" + contentId + "/child/attachment" + query, context.authHeader());
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
