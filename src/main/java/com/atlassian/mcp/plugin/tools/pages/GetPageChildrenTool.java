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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Mirrors upstream: confluence_mcp.get_page_children() Returns: {parent_id, count, limit_requested,
 * start_requested, results: [{simplified page dict}, ...]}
 */
public class GetPageChildrenTool extends TypedTool<GetPageChildrenTool.Args> {

  private static final int MAX_LIMIT = 50;

  public record Args(
      @ToolArg(
              value = "The ID of the parent page whose children you want to retrieve",
              required = true)
          String parentId,
      @ToolArg(value = "Maximum number of child pages to return (1-50)", defaultValue = "25")
          int limit,
      @ToolArg(
              value = "Whether to include the page content in the response",
              defaultValue = "false")
          boolean includeContent,
      @ToolArg(
              value =
                  "Whether to convert page content to markdown (true) or keep it in raw HTML format"
                      + " (false). Only relevant if include_content is true.",
              defaultValue = "true")
          boolean convertToMarkdown,
      @ToolArg(value = "Starting index for pagination (0-based)", defaultValue = "0") int start) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageChildrenTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page_children";
  }

  @Override
  public String description() {
    return "Get child pages of a specific Confluence page.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String parentId = McpTool.resolvePageId(args.parentId());
    int limit = Math.min(args.limit(), MAX_LIMIT);
    String expand = args.includeContent() ? "version,body.storage,space" : "version,space";

    String rawJson =
        client.getRaw(
            "/rest/api/content/"
                + parentId
                + "/child/page?expand="
                + encode(expand)
                + "&limit="
                + limit
                + "&start="
                + args.start(),
            context.authHeader());

    try {
      String baseUrl = client.getBaseUrl();
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode childPages = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode child : results) {
          childPages.add(
              ResponseTransformer.simplifyPageNode(child, baseUrl, args.convertToMarkdown()));
        }
      }

      ObjectNode output = mapper.createObjectNode();
      output.put("parent_id", parentId);
      output.put("count", childPages.size());
      output.put("limit_requested", limit);
      output.put("start_requested", args.start());
      output.set("results", childPages);
      return mapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform children response: " + e.getMessage());
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
