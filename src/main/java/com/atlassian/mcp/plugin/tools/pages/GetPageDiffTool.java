package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.StorageToMarkdown;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mirrors upstream: confluence_mcp.get_page_diff() Returns: {page_id, from_version, to_version,
 * diff}
 */
public class GetPageDiffTool extends TypedTool<GetPageDiffTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence page ID (numeric ID, can be found in the page URL). For example, in"
                      + " 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title',"
                      + " the page ID is '123456789'.",
              required = true)
          String pageId,
      @ToolArg(value = "Source version number", required = true) int fromVersion,
      @ToolArg(value = "Target version number", required = true) int toVersion) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageDiffTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page_diff";
  }

  @Override
  public String description() {
    return "Get a unified diff between two versions of a Confluence page.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    if (args.fromVersion() < 1 || args.toVersion() < 1) {
      throw new McpToolException("'from_version' and 'to_version' must be >= 1");
    }
    String pageId = McpTool.resolvePageId(args.pageId());

    try {
      String[] fromLines = markdownAt(pageId, args.fromVersion(), context).split("\n");
      String[] toLines = markdownAt(pageId, args.toVersion(), context).split("\n");

      StringBuilder diff = new StringBuilder();
      diff.append("--- version ").append(args.fromVersion()).append("\n");
      diff.append("+++ version ").append(args.toVersion()).append("\n");

      int maxLines = Math.max(fromLines.length, toLines.length);
      for (int i = 0; i < maxLines; i++) {
        String fl = i < fromLines.length ? fromLines[i] : "";
        String tl = i < toLines.length ? toLines[i] : "";
        if (fl.equals(tl)) {
          diff.append("  ").append(fl).append("\n");
          continue;
        }
        if (i < fromLines.length) diff.append("- ").append(fl).append("\n");
        if (i < toLines.length) diff.append("+ ").append(tl).append("\n");
      }

      ObjectNode result = mapper.createObjectNode();
      result.put("page_id", pageId);
      result.put("from_version", args.fromVersion());
      result.put("to_version", args.toVersion());
      result.put("diff", diff.toString());
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to compute diff: " + e.getMessage());
    }
  }

  private String markdownAt(String pageId, int version, McpContext context) throws Exception {
    String json =
        client.getRaw(
            "/rest/api/content/"
                + pageId
                + "?status=historical&version="
                + version
                + "&expand=body.storage,version",
            context.authHeader());
    JsonNode node = mapper.readTree(json);
    return StorageToMarkdown.convert(node.path("body").path("storage").path("value").asText(""));
  }
}
