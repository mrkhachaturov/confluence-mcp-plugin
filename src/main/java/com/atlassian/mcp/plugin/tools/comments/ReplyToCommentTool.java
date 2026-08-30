package com.atlassian.mcp.plugin.tools.comments;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.reply_to_comment() Returns: {success, message, comment:
 * {simplified comment dict}}
 */
public class ReplyToCommentTool extends TypedTool<ReplyToCommentTool.Args> {

  public record Args(
      @ToolArg(value = "The ID of the parent comment to reply to", required = true)
          String commentId,
      @ToolArg(value = "The reply content in Markdown format", required = true) String body) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public ReplyToCommentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "reply_to_comment";
  }

  @Override
  public String description() {
    return "Reply to an existing comment thread on a Confluence page. Reply body is written in Markdown and "
        + "automatically converted to Confluence format.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    Map<String, Object> requestBody = new HashMap<>();
    requestBody.put("type", "comment");
    requestBody.put("ancestors", List.of(Map.of("id", args.commentId())));
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
      result.put("message", "Reply added successfully");
      result.set("comment", ResponseTransformer.simplifyCommentNode(raw, true));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to reply to comment: " + e.getMessage());
    }
  }
}
