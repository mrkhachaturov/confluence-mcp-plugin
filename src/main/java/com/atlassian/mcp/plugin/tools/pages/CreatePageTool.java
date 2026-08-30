package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.create_page() Returns: {message, page: {simplified page dict}}
 */
public class CreatePageTool extends TypedTool<CreatePageTool.Args> {

  static final String CONTENT_FORMAT_DESCRIPTION =
      "Content format. 'markdown' (default) is converted to Confluence storage format; 'storage'"
          + " and 'wiki' are sent through untouched. Use 'storage' only for macros Markdown cannot"
          + " express.";

  public record Args(
      @ToolArg(
              value =
                  "The key of the space to create the page in (usually a short uppercase code like"
                      + " 'DEV', 'TEAM', or 'DOC')",
              required = true)
          String spaceKey,
      @ToolArg(value = "The title of the page", required = true) String title,
      @ToolArg(
              value =
                  "Page content in Markdown. All features described in the tool description work in"
                      + " Markdown — panels, status badges, task lists, expandable sections are all"
                      + " auto-converted to native Confluence elements. Do NOT start with '# Title'"
                      + " — Confluence displays the title separately.",
              required = true)
          String content,
      @ToolArg(
              "(Optional) parent page ID. If provided, this page will be created as a child of the"
                  + " specified page")
          String parentId,
      @ToolArg(
              value = CONTENT_FORMAT_DESCRIPTION,
              defaultValue = "markdown",
              allowed = {"markdown", "wiki", "storage"})
          String contentFormat,
      @ToolArg(
              value =
                  "If true, return the page content converted to Markdown instead of storage"
                      + " format.",
              defaultValue = "false")
          boolean returnMarkdown,
      @ToolArg("Labels to apply to the page after creation. Example: ['policy', 'hr', 'q3-2026']")
          List<String> labels,
      @ToolArg("(Optional) A comment to add to the page after creation, in Markdown format.")
          String initialComment) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public CreatePageTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "create_page";
  }

  @Override
  public String description() {
    return "Create a new Confluence page. Write content in Markdown — it is automatically converted "
        + "to rich Confluence format with native panels, status badges, task lists, and more.\n\n"
        + "IMPORTANT: Do NOT start content with '# Title' — Confluence displays the page title separately.\n\n"
        + "Supported Markdown features (all auto-converted to native Confluence elements):\n\n"
        + "FORMATTING: headings (## H2, ### H3), **bold**, *italic*, ~~strikethrough~~, `inline code`, "
        + "```code blocks``` (with language), tables, links, images, blockquotes, numbered/bulleted lists, horizontal rules (---)\n\n"
        + "TASK LISTS (native Confluence checkboxes):\n"
        + "  - [x] Completed item\n"
        + "  - [ ] Pending item\n\n"
        + "CALLOUT PANELS (colored Confluence panels for highlighting key information):\n"
        + "  > [!NOTE]                    → blue info panel (context, background, references)\n"
        + "  > [!TIP]                     → green tip panel (advice, recommendations, best practices)\n"
        + "  > [!IMPORTANT]               → yellow panel (key decisions, requirements, deadlines)\n"
        + "  > [!WARNING]                 → red panel (risks, compliance issues, critical constraints)\n"
        + "  > [!NOTE|title:Custom Title]  → panel with custom title\n"
        + "  Panels support nested formatting: bold, lists, links, code inside them.\n\n"
        + "STATUS BADGES (colored inline labels): {status:Text|color}\n"
        + "  Colors: green, red, yellow, blue, grey\n"
        + "  Examples: {status:Approved|green}, {status:Draft|grey}, {status:Pending Review|yellow}, "
        + "{status:Urgent|red}, {status:On Hold|blue}, {status:Final|green}, {status:Rejected|red}\n\n"
        + "TABLE OF CONTENTS: Place {toc} or [TOC] on its own line to insert an auto-generated table of contents.\n\n"
        + "EXPANDABLE SECTIONS (click-to-expand blocks for supplementary details):\n"
        + "  <details><summary>Section title</summary>Hidden content revealed on click</details>\n\n"
        + "NOT SUPPORTED in Markdown (use content_format='storage' if needed): "
        + "@user mentions, date pickers, Jira issue links, page layout columns, page includes.\n\n"
        + "Use these features to create professional pages: procedures, policies, meeting notes, "
        + "project plans, status reports, approval memos, training materials, and any business documentation.";
  }

  @Override
  public boolean isWriteTool() {
    return true;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String representation = "wiki".equals(args.contentFormat()) ? "wiki" : "storage";
    String body =
        "markdown".equals(args.contentFormat())
            ? MarkdownToStorage.convert(args.content())
            : args.content();

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("type", "page");
    requestBody.put("title", args.title());
    requestBody.put("space", Map.of("key", args.spaceKey()));
    requestBody.put(
        "body", Map.of(representation, Map.of("value", body, "representation", representation)));
    if (args.parentId() != null) {
      requestBody.put("ancestors", List.of(Map.of("id", args.parentId())));
    }

    try {
      String jsonBody = mapper.writeValueAsString(requestBody);
      String rawJson = client.postRaw("/rest/api/content", jsonBody, context.authHeader());

      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("message", "Page created successfully");
      result.set(
          "page",
          ResponseTransformer.simplifyPageNode(raw, client.getBaseUrl(), args.returnMarkdown()));

      String createdPageId = raw.path("id").asText();
      addLabels(createdPageId, args.labels(), result, context);
      addInitialComment(createdPageId, args.initialComment(), result, context);

      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to create page: " + e.getMessage());
    }
  }

  /**
   * The page exists by now, so a labelling failure is reported beside it rather than replacing it.
   */
  private void addLabels(
      String pageId, List<String> labels, ObjectNode result, McpContext context) {
    if (labels == null || labels.isEmpty()) return;
    try {
      List<Map<String, String>> payload = new ArrayList<>();
      ArrayNode added = mapper.createArrayNode();
      for (String label : labels) {
        payload.add(Map.of("prefix", "global", "name", label));
        added.add(label);
      }
      client.postRaw(
          "/rest/api/content/" + pageId + "/label",
          mapper.writeValueAsString(payload),
          context.authHeader());
      result.set("labels_added", added);
    } catch (Exception e) {
      result.put("labels_error", "Failed to add labels: " + e.getMessage());
    }
  }

  private void addInitialComment(
      String pageId, String comment, ObjectNode result, McpContext context) {
    if (comment == null) return;
    try {
      Map<String, Object> commentBody = new LinkedHashMap<>();
      commentBody.put("type", "comment");
      commentBody.put("container", Map.of("id", pageId, "type", "page"));
      commentBody.put(
          "body",
          Map.of(
              "storage",
              Map.of("value", MarkdownToStorage.convert(comment), "representation", "storage")));
      client.postRaw(
          "/rest/api/content", mapper.writeValueAsString(commentBody), context.authHeader());
      result.put("comment_added", true);
    } catch (Exception e) {
      result.put("comment_error", "Failed to add comment: " + e.getMessage());
    }
  }
}
