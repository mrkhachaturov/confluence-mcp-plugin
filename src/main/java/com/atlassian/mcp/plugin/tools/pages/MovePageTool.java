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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reparents a Confluence page. Returns: {message, page: {simplified page dict}}
 *
 * <p>Data Center offers no REST route for the other two things a move can mean. {@code PUT
 * /rest/api/content/{id}/move/{position}/{targetId}} answers 404 — it belongs to Cloud — so
 * ordering a page among its siblings is not expressible, and naming a parent in another space is
 * refused outright with "Can't add a parent from another space". Both were verified against a live
 * 10.2.14 instance. A cross-space move has to be done in the Confluence UI.
 */
public class MovePageTool extends TypedTool<MovePageTool.Args> {

  public record Args(
      @ToolArg(value = "ID of the page to move", required = true) String pageId,
      @ToolArg(
              value =
                  "ID of the page to move it under. Must be in the same space: Confluence rejects a"
                      + " parent from another space.",
              required = true)
          String targetParentId) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public MovePageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "move_page";
  }

  @Override
  public String description() {
    return "Move a Confluence page under a different parent page in the same space. "
        + "Moving a page to another space is not available through Confluence Data Center's REST "
        + "API — do that in the Confluence UI.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());
    String parentId = McpTool.resolvePageId(args.targetParentId());
    PageEdit.Current current = PageEdit.read(client, mapper, pageId, context);

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("id", pageId);
    requestBody.put("type", "page");
    requestBody.put("title", current.title());
    requestBody.put("version", Map.of("number", current.version() + 1));
    requestBody.put("ancestors", List.of(Map.of("id", parentId)));
    requestBody.put(
        "body", Map.of("storage", Map.of("value", current.body(), "representation", "storage")));

    try {
      String rawJson =
          client.putRaw(
              "/rest/api/content/" + pageId,
              mapper.writeValueAsString(requestBody),
              context.authHeader());
      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("message", "Page moved successfully");
      result.set("page", ResponseTransformer.simplifyPageNode(raw, client.getBaseUrl(), false));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to move page: " + e.getMessage());
    }
  }
}
