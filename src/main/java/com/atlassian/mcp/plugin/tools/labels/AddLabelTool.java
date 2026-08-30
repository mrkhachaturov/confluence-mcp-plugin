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
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.add_label() Returns: updated list of all labels [{id, name,
 * prefix, label}, ...]
 */
public class AddLabelTool extends TypedTool<AddLabelTool.Args> {

  public record Args(
      @ToolArg(
              value =
                  "Confluence content ID to label. For pages/blogs: numeric ID (e.g.,"
                      + " '123456789'). For attachments: ID with 'att' prefix (e.g.,"
                      + " 'att123456789'). Use get_attachments to find attachment IDs.",
              required = true)
          String pageId,
      @ToolArg(
              value =
                  "Label name to add (lowercase, no spaces). Examples: 'draft', 'reviewed',"
                      + " 'confidential', 'v1.0'. Labels help organize and categorize content.",
              required = true)
          String name) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public AddLabelTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "add_label";
  }

  @Override
  public String description() {
    return "Add label to Confluence content (pages, blog posts, or attachments). Useful for: - Categorizing attachments (e.g., 'screenshot', 'diagram', 'legal-doc') - Tracking status (e.g., 'approved', 'needs-review', 'archived') - Filtering content by topic or version";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());

    // The Confluence label API expects an array of label objects.
    List<Map<String, String>> labels = List.of(Map.of("prefix", "global", "name", args.name()));
    try {
      String jsonBody = mapper.writeValueAsString(labels);
      client.post("/rest/api/content/" + pageId + "/label", jsonBody, context.authHeader());

      String rawJson =
          client.getRaw("/rest/api/content/" + pageId + "/label", context.authHeader());
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode output = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode label : results) {
          output.add(ResponseTransformer.simplifyLabelNode(label));
        }
      }

      return mapper.writeValueAsString(output);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to add label: " + e.getMessage());
    }
  }
}
