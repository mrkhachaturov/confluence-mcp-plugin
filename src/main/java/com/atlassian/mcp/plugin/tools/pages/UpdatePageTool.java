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
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.update_page() Returns: {message, page: {simplified page dict}}
 */
public class UpdatePageTool extends TypedTool<UpdatePageTool.Args> {

  public record Args(
      @ToolArg(value = "The ID of the page to update", required = true) String pageId,
      @ToolArg(
              value =
                  "The new title of the page. To give the page an icon, begin the title with an"
                      + " emoji — Confluence renders it in the page tree, the page header and"
                      + " search results, e.g. '\uD83D\uDE80 Release 1.3'.",
              required = true)
          String title,
      @ToolArg(
              value =
                  "The new page content in Markdown. All rich features (panels, status badges,"
                      + " tasks, TOC, expand) work in Markdown — see tool description. Do NOT start"
                      + " with '# Title'.",
              required = true)
          String content,
      @ToolArg(value = "Whether this is a minor edit", defaultValue = "false") boolean isMinorEdit,
      @ToolArg("Optional comment for this version") String versionComment,
      @ToolArg("Optional the new parent page ID") String parentId,
      @ToolArg(
              value = CreatePageTool.CONTENT_FORMAT_DESCRIPTION,
              defaultValue = "markdown",
              allowed = {"markdown", "wiki", "storage"})
          String contentFormat,
      @ToolArg(
              "If provided, the update will fail if the page's current version doesn't match this"
                  + " value. Use the version number from get_page to prevent overwriting concurrent"
                  + " changes.")
          Integer expectedVersion,
      @ToolArg(
              value =
                  "If true, return the page content converted to Markdown instead of storage"
                      + " format.",
              defaultValue = "false")
          boolean returnMarkdown) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public UpdatePageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "update_page";
  }

  @Override
  public String description() {
    return "Update an existing Confluence page. Same Markdown features as create_page — all auto-converted "
        + "to native Confluence elements:\n"
        + "- Callout panels: > [!NOTE], > [!TIP], > [!IMPORTANT], > [!WARNING] (with optional |title:...)\n"
        + "- Status badges: {status:Text|color} (green, red, yellow, blue, grey)\n"
        + "- Task lists: - [x] / - [ ] → native checkboxes\n"
        + "- Table of contents: {toc}\n"
        + "- Expandable sections: <details><summary>Title</summary>content</details>\n"
        + "- All standard Markdown: headings, tables, code blocks, links, images, lists, bold, italic\n\n"
        + "IMPORTANT: This replaces the entire page content. Read the page first (get_page) if you need "
        + "to preserve existing content. Do NOT start with '# Title' — Confluence shows the title separately.";
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

    int currentVersion;
    try {
      String current =
          client.getRaw("/rest/api/content/" + pageId + "?expand=version", context.authHeader());
      currentVersion = mapper.readTree(current).path("version").path("number").asInt(0);
    } catch (Exception e) {
      throw new McpToolException("Failed to fetch current page version: " + e.getMessage());
    }

    if (args.expectedVersion() != null && args.expectedVersion() != currentVersion) {
      throw new McpToolException(
          "Page was modified since you last read it (current version: "
              + currentVersion
              + ", expected: "
              + args.expectedVersion()
              + "). Re-read the page with get_page before updating.");
    }

    String representation = "wiki".equals(args.contentFormat()) ? "wiki" : "storage";
    String body =
        "markdown".equals(args.contentFormat())
            ? MarkdownToStorage.convert(args.content())
            : args.content();

    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", currentVersion + 1);
    version.put("minorEdit", args.isMinorEdit());
    if (args.versionComment() != null) {
      version.put("message", args.versionComment());
    }

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("id", pageId);
    requestBody.put("type", "page");
    requestBody.put("title", args.title());
    requestBody.put("version", version);
    requestBody.put(
        "body", Map.of(representation, Map.of("value", body, "representation", representation)));
    if (args.parentId() != null) {
      requestBody.put("ancestors", List.of(Map.of("id", args.parentId())));
    }

    try {
      String jsonBody = mapper.writeValueAsString(requestBody);
      String rawJson = client.putRaw("/rest/api/content/" + pageId, jsonBody, context.authHeader());

      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("message", "Page updated successfully");
      result.set(
          "page",
          ResponseTransformer.simplifyPageNode(raw, client.getBaseUrl(), args.returnMarkdown()));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to update page: " + e.getMessage());
    }
  }
}
