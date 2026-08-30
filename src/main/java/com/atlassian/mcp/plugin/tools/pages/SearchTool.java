package com.atlassian.mcp.plugin.tools.pages;

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
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.StringJoiner;

/**
 * Mirrors upstream: confluence_mcp.search() Returns: [{id, title, type, url, space, author,
 * version, attachments, content, ancestors}, ...]
 */
public class SearchTool extends TypedTool<SearchTool.Args> {

  private static final int MAX_LIMIT = 50;
  private static final String EXPAND = "content.space,content.version,content.body.storage";

  public record Args(
      @ToolArg(
              value =
                  "Search query - can be either a simple text (e.g. 'project documentation') or a"
                      + " CQL query string. Simple queries use 'siteSearch' by default, to mimic"
                      + " the WebUI search, with an automatic fallback to 'text' search if not"
                      + " supported. Examples of CQL: - Basic search: 'type=page AND space=DEV' -"
                      + " Personal space search: 'space=\"~username\"' (note: personal space keys"
                      + " starting with ~ must be quoted) - Search by title: 'title~\"Meeting"
                      + " Notes\"' - Use siteSearch: 'siteSearch ~ \"important concept\"' - Use"
                      + " text search: 'text ~ \"important concept\"' - Recent content: 'created >="
                      + " \"2023-01-01\"' - Content with specific label: 'label=documentation' -"
                      + " Recently modified content: 'lastModified > startOfMonth(\"-1M\")' -"
                      + " Content modified this year: 'creator = currentUser() AND lastModified >"
                      + " startOfYear()' - Content you contributed to recently: 'contributor ="
                      + " currentUser() AND lastModified > startOfWeek()' - Content watched by"
                      + " user: 'watcher = \"user@domain.com\" AND type = page' - Exact phrase in"
                      + " content: 'text ~ \"\\\"Urgent Review Required\\\"\" AND label ="
                      + " \"pending-approval\"' - Title wildcards: 'title ~ \"Minutes*\" AND (space"
                      + " = \"HR\" OR space = \"Marketing\")' Note: Special identifiers need proper"
                      + " quoting in CQL: personal space keys (e.g., \"~username\"), reserved"
                      + " words, numeric IDs, and identifiers with special characters.",
              required = true)
          String query,
      @ToolArg(value = "Maximum number of results (1-50)", defaultValue = "10") int limit,
      @ToolArg(
              "(Optional) Space keys to restrict results to, e.g. ['DEV', 'TEAM']. Omit to search"
                  + " every space the caller can read.")
          List<String> spacesFilter) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public SearchTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "search";
  }

  @Override
  public String description() {
    return "Search Confluence content using simple terms or CQL.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    int limit = Math.min(args.limit(), MAX_LIMIT);

    // If it doesn't look like CQL, wrap as siteSearch (mirrors upstream)
    boolean isSimpleQuery = !args.query().contains("=") && !args.query().contains("~");
    String cql =
        isSimpleQuery ? "siteSearch ~ \"" + CqlSafety.quote(args.query()) + "\"" : args.query();
    cql = applySpaceFilter(cql, args.spacesFilter());

    String rawJson;
    try {
      rawJson = search(cql, limit, context);
    } catch (McpToolException e) {
      if (!isSimpleQuery) throw e;
      // Upstream fallback: siteSearch → text search
      String fallback = "text ~ \"" + CqlSafety.quote(args.query()) + "\"";
      rawJson = search(applySpaceFilter(fallback, args.spacesFilter()), limit, context);
    }

    try {
      String baseUrl = client.getBaseUrl();
      JsonNode results = mapper.readTree(rawJson).path("results");
      ArrayNode output = mapper.createArrayNode();

      if (results.isArray()) {
        for (JsonNode result : results) {
          JsonNode content = result.path("content");
          if (content.isMissingNode() || !content.has("id")) continue;

          ObjectNode page = ResponseTransformer.simplifyPageNode(content, baseUrl, true);

          String excerpt = result.path("excerpt").asText("");
          if (!excerpt.isBlank()) {
            excerpt = excerpt.replace("@@@hl@@@", "").replace("@@@endhl@@@", "");
            ObjectNode contentNode = mapper.createObjectNode();
            contentNode.put("value", excerpt);
            contentNode.put("format", "excerpt");
            page.set("content", contentNode);
          }

          output.add(page);
        }
      }

      return mapper.writeValueAsString(output);
    } catch (Exception e) {
      throw new McpToolException("Failed to transform search results: " + e.getMessage());
    }
  }

  private String search(String cql, int limit, McpContext context) throws McpToolException {
    return client.getRaw(
        "/rest/api/search?cql=" + encode(cql) + "&limit=" + limit + "&expand=" + encode(EXPAND),
        context.authHeader());
  }

  /**
   * Appends a validated {@code space in ("A","B")} clause. Each key must be a valid space key (see
   * {@link CqlSafety#isValidSpaceToken}); an invalid key is rejected rather than interpolated,
   * preventing CQL clause injection through {@code spaces_filter}.
   */
  private static String applySpaceFilter(String cql, List<String> spaceKeys)
      throws McpToolException {
    if (spaceKeys == null || spaceKeys.isEmpty()) {
      return cql;
    }
    StringJoiner keys = new StringJoiner(",", "space in (", ")");
    for (String key : spaceKeys) {
      if (!CqlSafety.isValidSpaceToken(key)) {
        throw new McpToolException("Invalid space key in spaces_filter: '" + key + "'");
      }
      keys.add("\"" + key + "\"");
    }
    return "(" + cql + ") AND " + keys;
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
