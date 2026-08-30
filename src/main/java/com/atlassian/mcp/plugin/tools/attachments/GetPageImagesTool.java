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
import java.util.Set;

public class GetPageImagesTool extends TypedTool<GetPageImagesTool.Args> {

  private static final long MAX_BYTES = 50L * 1024 * 1024;
  private static final Set<String> IMAGE_EXTENSIONS =
      Set.of("png", "jpg", "jpeg", "gif", "webp", "svg", "bmp");

  public record Args(
      @ToolArg(
              value =
                  "The ID of the Confluence page or blog post to retrieve images from. Example:"
                      + " '123456789'",
              required = true)
          String contentId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageImagesTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page_images";
  }

  @Override
  public String description() {
    return "Get all images attached to a Confluence page as inline image content. Filters attachments to images only (PNG, JPEG, GIF, WebP, SVG, BMP) and returns them as base64-encoded ImageContent that clients can render directly. Non-image attachments are excluded. Files with ambiguous MIME types (application/octet-stream) are detected by filename extension as a fallback. Images larger than 50 MB are skipped with an error entry in the summary.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String contentId = McpTool.resolvePageId(args.contentId());

    String listJson =
        client.get(
            "/rest/api/content/" + contentId + "/child/attachment?limit=100&expand=version",
            context.authHeader());
    try {
      JsonNode results = mapper.readTree(listJson).path("results");
      if (!results.isArray() || results.isEmpty()) {
        return "{\"message\":\"No attachments found\",\"images\":[]}";
      }

      ArrayNode images = mapper.createArrayNode();
      for (JsonNode att : results) {
        String title = att.path("title").asText("");
        String mediaType = att.path("metadata").path("mediaType").asText("");

        boolean isImage = mediaType.startsWith("image/");
        if (!isImage) {
          int dot = title.lastIndexOf('.');
          if (dot >= 0) {
            String ext = title.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
            isImage = IMAGE_EXTENSIONS.contains(ext);
            if (isImage && mediaType.isEmpty()) {
              mediaType = "image/" + (ext.equals("jpg") ? "jpeg" : ext);
            }
          }
        }
        if (!isImage) continue;

        String attId = att.path("id").asText();
        ObjectNode entry = mapper.createObjectNode();
        entry.put("filename", title);
        try {
          byte[] data =
              client.getBytes("/rest/api/content/" + attId + "/download", context.authHeader());
          if (data.length > MAX_BYTES) {
            entry.put("error", "Exceeds 50 MB limit");
          } else {
            entry.put("mediaType", mediaType);
            entry.put("size", data.length);
            entry.put("base64", Base64.getEncoder().encodeToString(data));
          }
        } catch (Exception e) {
          entry.put("error", e.getMessage());
        }
        images.add(entry);
      }

      ObjectNode out = mapper.createObjectNode();
      out.put("count", images.size());
      out.set("images", images);
      return mapper.writeValueAsString(out);
    } catch (Exception e) {
      throw new McpToolException("Failed to process images: " + e.getMessage());
    }
  }
}
