package com.atlassian.mcp.plugin.tools.comments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/**
 * Mirrors upstream: confluence_mcp.get_comments() Returns: [{id, body, created, updated, author,
 * parent_comment_id, location}, ...]
 */
public class GetCommentsTool extends TypedTool<GetCommentsTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence page ID (numeric ID, can be parsed from URL, e.g. from"
                      + " 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title'"
                      + " -> '123456789')",
              required = true)
          String pageId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetCommentsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_comments";
  }

  @Override
  public String description() {
    return "Get comments for a specific Confluence page.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());

    // Upstream uses body.view (rendered HTML) — easier to convert to markdown
    String rawJson =
        client.getRaw(
            "/rest/api/content/"
                + pageId
                + "/child/comment?expand=body.view,version,ancestors&depth=all",
            context.authHeader());

    try {
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode output = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode comment : results) {
          output.add(ResponseTransformer.simplifyCommentNode(comment, true));
        }
      }

      return mapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform comments response: " + e.getMessage());
    }
  }
}
