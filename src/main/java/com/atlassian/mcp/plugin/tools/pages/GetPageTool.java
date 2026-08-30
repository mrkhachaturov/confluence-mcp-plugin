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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Mirrors upstream: confluence_mcp.get_page() Returns: {"page": {simplified page dict}} when
 * include_metadata=true
 */
public class GetPageTool extends TypedTool<GetPageTool.Args> {

  private static final String EXPAND =
      "body.storage,version,space,ancestors,children.attachment,metadata.labels";

  /** Dropped from the response when the caller asks for content without metadata. */
  private static final List<String> METADATA_FIELDS =
      List.of("created", "updated", "author", "version", "attachments", "ancestors", "labels");

  public record Args(
      @ToolArg(
              "Confluence page ID (numeric ID, can be found in the page URL). For example, in the"
                  + " URL"
                  + " 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title',"
                  + " the page ID is '123456789'. Provide this OR both 'title' and 'space_key'. If"
                  + " page_id is provided, title and space_key will be ignored.")
          String pageId,
      @ToolArg(
              "The exact title of the Confluence page. Use this with 'space_key' if 'page_id' is"
                  + " not known.")
          String title,
      @ToolArg(
              "The key of the Confluence space where the page resides (e.g., 'DEV', 'TEAM')."
                  + " Required if using 'title'.")
          String spaceKey,
      @ToolArg(
              value =
                  "Whether to include page metadata such as creation date, last update, version,"
                      + " and labels.",
              defaultValue = "true")
          boolean includeMetadata,
      @ToolArg(
              value =
                  "Whether to convert page to markdown (true) or keep it in raw HTML format"
                      + " (false). Raw HTML can reveal macros (like dates) not visible in markdown,"
                      + " but CAUTION: using HTML significantly increases token usage in AI"
                      + " responses.",
              defaultValue = "true")
          boolean convertToMarkdown) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public GetPageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "get_page";
  }

  @Override
  public String description() {
    return "Get content of a specific Confluence page by its ID, or by its title and space key. "
        + "By default, page content is converted from Confluence storage format to clean Markdown for easy reading. "
        + "The response includes full page URL, space info, author, version, and content.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String rawJson;
    if (args.pageId() != null) {
      String pageId = McpTool.resolvePageId(args.pageId());
      rawJson =
          client.getRaw(
              "/rest/api/content/" + pageId + "?expand=" + encode(EXPAND), context.authHeader());
    } else if (args.title() != null && args.spaceKey() != null) {
      rawJson =
          client.getRaw(
              "/rest/api/content?title="
                  + encode(args.title())
                  + "&spaceKey="
                  + encode(args.spaceKey())
                  + "&expand="
                  + encode(EXPAND),
              context.authHeader());
    } else {
      throw new McpToolException("Either 'page_id' or both 'title' and 'space_key' are required");
    }

    try {
      String baseUrl = client.getBaseUrl();
      JsonNode root = mapper.readTree(rawJson);

      JsonNode pageNode = root;
      if (root.has("results")) {
        JsonNode results = root.path("results");
        if (!results.isArray() || results.isEmpty()) {
          throw new McpToolException(
              "Page not found with title '"
                  + args.title()
                  + "' in space '"
                  + args.spaceKey()
                  + "'");
        }
        pageNode = results.get(0);
      }

      ObjectNode simplified =
          ResponseTransformer.simplifyPageNode(pageNode, baseUrl, args.convertToMarkdown());
      if (!args.includeMetadata()) {
        simplified.remove(METADATA_FIELDS);
      }

      ObjectNode result = mapper.createObjectNode();
      result.set("page", simplified);
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to transform page response: " + e.getMessage());
    }
  }

  private static String encode(String s) {
    return URLEncoder.encode(s, StandardCharsets.UTF_8);
  }
}
