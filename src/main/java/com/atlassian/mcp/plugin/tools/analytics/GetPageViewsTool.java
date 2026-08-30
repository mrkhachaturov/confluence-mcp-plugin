package com.atlassian.mcp.plugin.tools.analytics;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mirrors upstream: confluence_mcp.get_page_views() Returns: {page_id, page_title, total_views,
 * unique_viewers, last_viewed} Note: Cloud-only API. Server/DC does not support the Analytics API.
 */
public class GetPageViewsTool extends TypedTool<GetPageViewsTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence page ID (numeric ID, can be found in the page URL). For example, in"
                      + " 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title',"
                      + " the page ID is '123456789'.",
              required = true)
          String pageId,
      @ToolArg(value = "Whether to fetch and include the page title", defaultValue = "true")
          boolean includeTitle) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageViewsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page_views";
  }

  @Override
  public String description() {
    return "Get view statistics for a Confluence page. Note: This tool is only available for Confluence Cloud. Server/Data Center instances do not support the Analytics API.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());
    String rawJson =
        client.getRaw("/rest/api/analytics/content/" + pageId + "/views", context.authHeader());

    try {
      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("page_id", pageId);

      if (args.includeTitle()) {
        try {
          String pageJson =
              client.getRaw("/rest/api/content/" + pageId + "?expand=", context.authHeader());
          result.put("page_title", mapper.readTree(pageJson).path("title").asText(""));
        } catch (Exception e) {
          result.put("page_title", "");
        }
      }

      result.put("total_views", raw.path("count").asLong(0));
      result.put("last_viewed", raw.path("lastSeen").asText(""));

      return mapper.writeValueAsString(result);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform page views response: " + e.getMessage());
    }
  }
}
