package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;

public class DownloadAttachmentTool extends TypedTool<DownloadAttachmentTool.Args> {

  private static final long MAX_BYTES = 50L * 1024 * 1024;

  public record Args(
      @ToolArg(
              value =
                  "The ID of the attachment to download (e.g., 'att123456789'). Find attachment IDs"
                      + " using get_attachments tool. Example workflow: get_attachments(content_id)"
                      + " → use returned ID here.",
              required = true)
          String attachmentId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public DownloadAttachmentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "download_attachment";
  }

  @Override
  public String description() {
    return "Download an attachment from Confluence as an embedded resource. Returns the attachment content as a base64-encoded embedded resource so that it is available over the MCP protocol without requiring filesystem access on the server. Files larger than 50 MB are not downloaded inline; a descriptive error message is returned instead.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String meta =
        client.get(
            "/rest/api/content/" + args.attachmentId() + "?expand=version", context.authHeader());
    try {
      JsonNode node = mapper.readTree(meta);
      String title = node.path("title").asText("unknown");

      byte[] data =
          client.getBytes(
              "/rest/api/content/" + args.attachmentId() + "/download", context.authHeader());
      if (data.length > MAX_BYTES) {
        throw new McpToolException(
            "Attachment '" + title + "' exceeds 50 MB limit (" + data.length + " bytes)");
      }

      ObjectNode out = mapper.createObjectNode();
      out.put("filename", title);
      out.put("size", data.length);
      out.put("base64", Base64.getEncoder().encodeToString(data));
      return mapper.writeValueAsString(out);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to download attachment: " + e.getMessage());
    }
  }
}
