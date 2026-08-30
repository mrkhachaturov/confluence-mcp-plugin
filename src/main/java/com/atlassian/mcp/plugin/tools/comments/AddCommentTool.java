package com.atlassian.mcp.plugin.tools.comments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.add_comment() Returns: {success, message, comment: {simplified
 * comment dict}}
 */
public class AddCommentTool extends TypedTool<AddCommentTool.Args> {

  public record Args(
      @ToolArg(value = "The ID of the page to add a comment to", required = true) String pageId,
      @ToolArg(value = "The comment content in Markdown format", required = true) String body) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public AddCommentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "add_comment";
  }

  @Override
  public String description() {
    return "Add a comment to a Confluence page. Comment body is written in Markdown and automatically converted "
        + "to Confluence format. Supports bold, italic, code, links, lists, and other standard Markdown formatting.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());

    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("type", "comment");
    requestBody.put("container", Map.of("id", pageId, "type", "page"));
    requestBody.put(
        "body",
        Map.of(
            "storage",
            Map.of("value", MarkdownToStorage.convert(args.body()), "representation", "storage")));
    try {
      String jsonBody = mapper.writeValueAsString(requestBody);
      String rawJson = client.postRaw("/rest/api/content", jsonBody, context.authHeader());

      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("message", "Comment added successfully");
      result.set("comment", ResponseTransformer.simplifyCommentNode(raw, true));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to add comment: " + e.getMessage());
    }
  }
}
