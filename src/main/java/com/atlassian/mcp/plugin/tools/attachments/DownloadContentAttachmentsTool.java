package com.atlassian.mcp.plugin.tools.attachments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Base64;

public class DownloadContentAttachmentsTool extends TypedTool<DownloadContentAttachmentsTool.Args> {

  private static final long MAX_BYTES = 50L * 1024 * 1024;

  public record Args(
      @ToolArg(
              value =
                  "The ID of the Confluence content (page or blog post) to download attachments"
                      + " from. Example: '123456789'",
              required = true)
          String contentId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public DownloadContentAttachmentsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "download_content_attachments";
  }

  @Override
  public String description() {
    return "Download all attachments for a Confluence content item as embedded resources. Returns attachment contents as base64-encoded embedded resources so that they are available over the MCP protocol without requiring filesystem access on the server. Files larger than 50 MB are skipped with an error entry in the summary.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  public boolean supportsProgress() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String contentId = McpTool.resolvePageId(args.contentId());

    String listJson =
        client.get(
            "/rest/api/content/" + contentId + "/child/attachment?limit=100", context.authHeader());
    try {
      JsonNode results = mapper.readTree(listJson).path("results");
      if (!results.isArray() || results.isEmpty()) {
        return "{\"message\":\"No attachments found\",\"attachments\":[]}";
      }

      ArrayNode attachments = mapper.createArrayNode();
      int done = 0;
      for (JsonNode att : results) {
        String attId = att.path("id").asText();
        String title = att.path("title").asText("unknown");
        ObjectNode entry = mapper.createObjectNode();
        entry.put("filename", title);
        try {
          byte[] data =
              client.getBytes("/rest/api/content/" + attId + "/download", context.authHeader());
          if (data.length > MAX_BYTES) {
            entry.put("error", "Exceeds 50 MB limit");
          } else {
            entry.put("size", data.length);
            entry.put("base64", Base64.getEncoder().encodeToString(data));
          }
        } catch (Exception e) {
          entry.put("error", e.getMessage());
        }
        attachments.add(entry);
        context.reportProgress(++done, results.size(), "downloaded " + title);
      }

      ObjectNode out = mapper.createObjectNode();
      out.put("count", attachments.size());
      out.set("attachments", attachments);
      return mapper.writeValueAsString(out);
    } catch (Exception e) {
      throw new McpToolException("Failed to process attachments: " + e.getMessage());
    }
  }
}
