package com.atlassian.mcp.plugin.tools.pages;

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Append content to the end of an existing Confluence page. Returns: {message, page: {simplified
 * page dict}}
 */
public class AppendToPageTool extends TypedTool<AppendToPageTool.Args> {

  /**
   * Merging happens in storage format, so wiki markup has no place here — it would be concatenated
   * into the body and stored as if it were storage.
   */
  static final String CONTENT_FORMAT_DESCRIPTION =
      "Format of the content: 'markdown' (default) is converted to storage format, 'storage' is"
          + " merged as-is.";

  static final String EXPECTED_VERSION_DESCRIPTION =
      "If provided, the update will fail if the page's current version doesn't match. Prevents"
          + " overwriting concurrent changes.";

  public record Args(
      @ToolArg(value = "The ID of the page to append content to", required = true) String pageId,
      @ToolArg(value = "Content to append. Added after the existing page content.", required = true)
          String content,
      @ToolArg(
              value = CONTENT_FORMAT_DESCRIPTION,
              defaultValue = "markdown",
              allowed = {"markdown", "storage"})
          String contentFormat,
      @ToolArg(EXPECTED_VERSION_DESCRIPTION) Integer expectedVersion) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public AppendToPageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "append_to_page";
  }

  @Override
  public String description() {
    return "Append content to the end of an existing Confluence page without touching existing content. "
        + "The new content is added after the page's current content. "
        + "Supports the same Markdown features as create_page (panels, status badges, task lists, etc.).\n\n"
        + "Use cases: adding meeting notes, appending status updates, adding new sections to existing pages.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String pageId = McpTool.resolvePageId(args.pageId());
    PageEdit.Current current = PageEdit.read(client, mapper, pageId, context);
    PageEdit.checkExpectedVersion(current, args.expectedVersion());

    String addition =
        "markdown".equals(args.contentFormat())
            ? MarkdownToStorage.convert(args.content())
            : args.content();

    return write(pageId, current, current.body() + "\n" + addition, context);
  }

  private String write(String pageId, PageEdit.Current current, String body, McpContext context)
      throws McpToolException {
    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", current.version() + 1);
    version.put("minorEdit", false);
    version.put("message", "Content appended");

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("id", pageId);
    requestBody.put("type", "page");
    requestBody.put("title", current.title());
    requestBody.put("version", version);
    requestBody.put("body", Map.of("storage", Map.of("value", body, "representation", "storage")));

    try {
      String rawJson =
          client.putRaw(
              "/rest/api/content/" + pageId,
              mapper.writeValueAsString(requestBody),
              context.authHeader());
      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("message", "Content appended successfully");
      result.set("page", ResponseTransformer.simplifyPageNode(raw, client.getBaseUrl(), false));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to append content: " + e.getMessage());
    }
  }
}
