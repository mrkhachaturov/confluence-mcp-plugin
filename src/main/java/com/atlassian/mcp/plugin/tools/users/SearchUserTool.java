package com.atlassian.mcp.plugin.tools.users;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.CqlSafety;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Mirrors upstream: confluence_mcp.search_user() Returns: [{display_name, email}, ...] */
public class SearchUserTool extends TypedTool<SearchUserTool.Args> {

  private static final int MAX_LIMIT = 50;

  public record Args(
      @ToolArg(
              value =
                  "Search query - a CQL query string for user search. Examples of CQL: - Basic user"
                      + " lookup by full name: 'user.fullname ~ \"First Last\"' Note: Special"
                      + " identifiers need proper quoting in CQL: personal space keys (e.g.,"
                      + " \"~username\"), reserved words, numeric IDs, and identifiers with special"
                      + " characters.",
              required = true)
          String query,
      @ToolArg(value = "Maximum number of results (1-50)", defaultValue = "10") int limit) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public SearchUserTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "search_user";
  }

  @Override
  public String description() {
    return "Search Confluence users using CQL (Cloud) or group member API (Server/DC).";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    int limit = Math.min(args.limit(), MAX_LIMIT);

    // CQL search works with both PAT and OAuth (3LO)
    String cql = "type=user AND user.fullname~\"" + CqlSafety.quote(args.query()) + "\"";
    String rawJson =
        client.getRaw(
            "/rest/api/search?cql=" + encode(cql) + "&limit=" + limit, context.authHeader());

    try {
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode output = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode result : results) {
          output.add(ResponseTransformer.simplifyUserNode(result));
        }
      }

      return mapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform user search results: " + e.getMessage());
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
