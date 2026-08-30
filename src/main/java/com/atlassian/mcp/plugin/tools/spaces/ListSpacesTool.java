package com.atlassian.mcp.plugin.tools.spaces;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

/** List available Confluence spaces. Returns: [{key, name, type, url}, ...] */
public class ListSpacesTool extends TypedTool<ListSpacesTool.Args> {

  public record Args(
      @ToolArg(value = "Maximum number of spaces to return", defaultValue = "25") int limit,
      @ToolArg(value = "Starting index for pagination", defaultValue = "0") int start,
      @ToolArg(
              value =
                  "Filter by space type: 'global' for team/project spaces, 'personal' for user"
                      + " spaces. Omit to return all types.",
              allowed = {"global", "personal"})
          String type) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public ListSpacesTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "list_spaces";
  }

  @Override
  public String description() {
    return "List available Confluence spaces. Returns space key, name, type, and URL for each space. "
        + "Use this to discover which spaces exist before creating pages or searching. "
        + "Supports filtering by space type (global or personal) and pagination.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    StringBuilder path = new StringBuilder("/rest/api/space?limit=");
    path.append(args.limit()).append("&start=").append(args.start());
    if (args.type() != null) {
      path.append("&type=").append(args.type());
    }

    try {
      String rawJson = client.getRaw(path.toString(), context.authHeader());
      String baseUrl = client.getBaseUrl();
      JsonNode results = mapper.readTree(rawJson).path("results");

      ArrayNode output = mapper.createArrayNode();
      if (results.isArray()) {
        for (JsonNode space : results) {
          output.add(ResponseTransformer.simplifySpaceNode(space, baseUrl));
        }
      }

      return mapper.writeValueAsString(output);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to list spaces: " + e.getMessage());
    }
  }
}
