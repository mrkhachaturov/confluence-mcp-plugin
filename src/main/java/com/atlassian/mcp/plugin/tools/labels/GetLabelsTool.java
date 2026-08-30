package com.atlassian.mcp.plugin.tools.labels;

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

/** Mirrors upstream: confluence_mcp.get_labels() Returns: [{id, name, prefix, label}, ...] */
public class GetLabelsTool extends TypedTool<GetLabelsTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence content ID (page, blog post, or attachment). For pages: numeric ID"
                      + " from URL (e.g., '123456789'). For attachments: ID with 'att' prefix"
                      + " (e.g., 'att123456789'). Works with any Confluence content type that"
                      + " supports labels.",
              required = true)
          String pageId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetLabelsTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_labels";
  }

  @Override
  public String description() {
    return "Get labels for Confluence content (pages, blog posts, or attachments).";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());
    String rawJson = client.getRaw("/rest/api/content/" + pageId + "/label", context.authHeader());

    try {
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode output = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode label : results) {
          output.add(ResponseTransformer.simplifyLabelNode(label));
        }
      }

      return mapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform labels response: " + e.getMessage());
    }
  }
}
