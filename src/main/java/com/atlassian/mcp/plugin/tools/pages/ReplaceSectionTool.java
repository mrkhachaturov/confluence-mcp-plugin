package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.StorageToMarkdown;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.McpTool;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replace a section of a Confluence page identified by its heading. Returns: {message, page:
 * {simplified page dict}}
 */
public class ReplaceSectionTool extends TypedTool<ReplaceSectionTool.Args> {

  /** Matches markdown headings: # H1, ## H2, ### H3, etc. */
  private static final Pattern HEADING_PATTERN =
      Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

  public record Args(
      @ToolArg(value = "The ID of the page to edit", required = true) String pageId,
      @ToolArg(
              value = "The exact heading text to find (without the # prefix). Case-sensitive.",
              required = true)
          String heading,
      @ToolArg(
              value =
                  "New content for the section, in Markdown. This replaces everything under the"
                      + " heading until the next heading of equal or higher level.",
              required = true)
          String content,
      @ToolArg(
              "(Optional) Heading level to match (1-6). Use to disambiguate when the same text"
                  + " appears at different heading levels.")
          Integer headingLevel,
      @ToolArg(AppendToPageTool.EXPECTED_VERSION_DESCRIPTION) Integer expectedVersion) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public ReplaceSectionTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "replace_section";
  }

  @Override
  public String description() {
    return "Replace a specific section of a Confluence page identified by its heading text. "
        + "Only the content under the matched heading is replaced — the rest of the page is untouched. "
        + "The section spans from the heading to the next heading of equal or higher level (or end of page).\n\n"
        + "This is safer than update_page for large pages: you only touch the section you need to change, "
        + "reducing the risk of accidentally dropping other content.\n\n"
        + "Example: replace_section(page_id='123', heading='Meeting Notes', content='Updated notes here') "
        + "will find the '## Meeting Notes' section and replace everything under it until the next ## heading.";
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
    PageEdit.Current current = PageEdit.read(client, mapper, pageId, context);
    PageEdit.checkExpectedVersion(current, args.expectedVersion());

    String markdown = StorageToMarkdown.convert(current.body());
    int level = args.headingLevel() == null ? -1 : args.headingLevel();
    String updated = replaceSection(markdown, args.heading(), level, args.content());

    Map<String, Object> version = new LinkedHashMap<>();
    version.put("number", current.version() + 1);
    version.put("minorEdit", false);
    version.put("message", "Section '" + args.heading() + "' updated");

    Map<String, Object> requestBody = new LinkedHashMap<>();
    requestBody.put("id", pageId);
    requestBody.put("type", "page");
    requestBody.put("title", current.title());
    requestBody.put("version", version);
    requestBody.put(
        "body",
        Map.of(
            "storage",
            Map.of("value", MarkdownToStorage.convert(updated), "representation", "storage")));

    try {
      String rawJson =
          client.putRaw(
              "/rest/api/content/" + pageId,
              mapper.writeValueAsString(requestBody),
              context.authHeader());
      JsonNode raw = mapper.readTree(rawJson);
      ObjectNode result = mapper.createObjectNode();
      result.put("message", "Section '" + args.heading() + "' replaced successfully");
      result.set("page", ResponseTransformer.simplifyPageNode(raw, client.getBaseUrl(), false));
      return mapper.writeValueAsString(result);
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to replace section: " + e.getMessage());
    }
  }

  /**
   * Replace a section in markdown text identified by heading. A section spans from the heading to
   * the next heading of equal or higher level.
   */
  static String replaceSection(
      String markdown, String targetHeading, int targetLevel, String newContent)
      throws McpToolException {
    String[] lines = markdown.split("\n", -1);
    int sectionStart = -1;
    int sectionEnd = -1;
    int matchedLevel = -1;
    List<String> availableHeadings = new ArrayList<>();

    // Find the target heading
    for (int i = 0; i < lines.length; i++) {
      Matcher m = HEADING_PATTERN.matcher(lines[i]);
      if (m.matches()) {
        int level = m.group(1).length();
        String text = m.group(2).trim();
        availableHeadings.add(m.group(1) + " " + text);

        if (text.equals(targetHeading) && (targetLevel < 0 || targetLevel == level)) {
          if (sectionStart >= 0) {
            throw new McpToolException(
                "Multiple headings match '"
                    + targetHeading
                    + "'. "
                    + "Use heading_level to disambiguate. Available headings:\n"
                    + String.join("\n", availableHeadings));
          }
          sectionStart = i;
          matchedLevel = level;
        }
      }
    }

    if (sectionStart < 0) {
      throw new McpToolException(
          "Heading '"
              + targetHeading
              + "' not found. Available headings:\n"
              + String.join("\n", availableHeadings));
    }

    // Find end of section: next heading of equal or higher level
    for (int i = sectionStart + 1; i < lines.length; i++) {
      Matcher m = HEADING_PATTERN.matcher(lines[i]);
      if (m.matches()) {
        int level = m.group(1).length();
        if (level <= matchedLevel) {
          sectionEnd = i;
          break;
        }
      }
    }
    if (sectionEnd < 0) {
      sectionEnd = lines.length; // section goes to end of document
    }

    // Rebuild: before + heading + new content + after
    StringBuilder result = new StringBuilder();
    for (int i = 0; i < sectionStart; i++) {
      result.append(lines[i]).append("\n");
    }
    // Keep the heading line itself
    result.append(lines[sectionStart]).append("\n\n");
    // Insert new content
    result.append(newContent).append("\n\n");
    // Append everything after the section
    for (int i = sectionEnd; i < lines.length; i++) {
      result.append(lines[i]);
      if (i < lines.length - 1) result.append("\n");
    }

    return result.toString();
  }
}
