package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Mirrors upstream: confluence_mcp.get_page_history() Returns: {"page": {simplified page dict}} */
public class GetPageHistoryTool extends TypedTool<GetPageHistoryTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence page ID (numeric ID, can be found in the page URL). For example, in"
                      + " 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title',"
                      + " the page ID is '123456789'.",
              required = true)
          String pageId,
      @ToolArg(value = "The version number of the page to retrieve", required = true) int version,
      @ToolArg(
              value =
                  "Whether to convert page to markdown (true) or keep it in raw HTML format"
                      + " (false). Raw HTML can reveal macros (like dates) not visible in markdown,"
                      + " but CAUTION: using HTML significantly increases token usage in AI"
                      + " responses.",
              defaultValue = "true")
          boolean convertToMarkdown) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageHistoryTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page_history";
  }

  @Override
  public String description() {
    return "Get a historical version of a specific Confluence page.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    if (args.version() < 1) {
      throw new McpToolException("'version' must be >= 1");
    }
    String pageId = McpTool.resolvePageId(args.pageId());

    String rawJson =
        client.getRaw(
            "/rest/api/content/"
                + pageId
                + "?status=historical&version="
                + args.version()
                + "&expand=body.storage,version,space",
            context.authHeader());

    try {
      JsonNode root = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.set(
          "page",
          ResponseTransformer.simplifyPageNode(
              root, client.getBaseUrl(), args.convertToMarkdown()));
      return mapper.writeValueAsString(result);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform page history response: " + e.getMessage());
    }
  }
}
