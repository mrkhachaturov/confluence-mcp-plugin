package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Mirrors upstream: confluence_mcp.delete_page() Returns: {success: true, message: "Page {id}
 * deleted successfully"}
 */
public class DeletePageTool extends TypedTool<DeletePageTool.Args> {

  public record Args(
      @ToolArg(value = "The ID of the page to delete", required = true) String pageId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public DeletePageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "delete_page";
  }

  @Override
  public String description() {
    return "Delete an existing Confluence page.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  public boolean isDestructiveTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());
    client.delete("/rest/api/content/" + pageId, context.authHeader());

    try {
      ObjectNode result = mapper.createObjectNode();
      result.put("success", true);
      result.put("message", "Page " + pageId + " deleted successfully");
      return mapper.writeValueAsString(result);
    } catch (Exception e) {
      throw new McpToolException("Failed to serialize response: " + e.getMessage());
    }
  }
}
