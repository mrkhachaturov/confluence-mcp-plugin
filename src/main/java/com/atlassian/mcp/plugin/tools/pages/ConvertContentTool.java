package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.atlassian.mcp.plugin.tools.ToolArg;
import com.atlassian.mcp.plugin.tools.TypedTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;

/**
 * Preview tool: converts Markdown or wiki markup to Confluence storage format without creating a
 * page. Returns: {content: {value, format}}
 */
public class ConvertContentTool extends TypedTool<ConvertContentTool.Args> {

  public record Args(
      @ToolArg(value = "Content to convert to Confluence storage format", required = true)
          String content,
      @ToolArg(
              value =
                  "Input format: 'markdown' (default) is converted locally, 'wiki' is converted by"
                      + " Confluence itself.",
              defaultValue = "markdown",
              allowed = {"markdown", "wiki"})
          String contentFormat) {}

  private final ConfluenceRestClient client;
  private final ObjectMapper mapper = new ObjectMapper();

  public ConvertContentTool(ConfluenceRestClient client) {
    super(Args.class);
    this.client = client;
  }

  @Override
  public String name() {
    return "convert_content";
  }

  @Override
  public String description() {
    return "Convert Markdown to Confluence storage format (XHTML) without creating or updating any page. "
        + "Use this to preview how Markdown will render in Confluence, debug formatting issues, "
        + "or validate content before writing. No page ID or space key required.\n\n"
        + "Supports all Markdown features: panels (> [!NOTE]), status badges ({status:Text|color}), "
        + "task lists, table of contents, expand sections, tables, code blocks, and more.";
  }

  @Override
  public boolean isWriteTool() {
    return false;
  }

  @Override
  protected String run(Args args, McpContext context) throws McpToolException {
    String storage =
        "wiki".equals(args.contentFormat())
            ? convertWiki(args.content(), context)
            : MarkdownToStorage.convert(args.content());

    try {
      ObjectNode contentNode = mapper.createObjectNode();
      contentNode.put("value", storage);
      contentNode.put("format", "storage");
      ObjectNode result = mapper.createObjectNode();
      result.set("content", contentNode);
      return mapper.writeValueAsString(result);
    } catch (Exception e) {
      throw new McpToolException("Failed to convert content: " + e.getMessage());
    }
  }

  /** Wiki markup is Confluence's own dialect, so Confluence is the only correct translator. */
  private String convertWiki(String wiki, McpContext context) throws McpToolException {
    try {
      String body = mapper.writeValueAsString(Map.of("value", wiki, "representation", "wiki"));
      String response =
          client.postRaw("/rest/api/contentbody/convert/storage", body, context.authHeader());
      return mapper.readTree(response).path("value").asText("");
    } catch (McpToolException e) {
      throw e;
    } catch (Exception e) {
      throw new McpToolException("Failed to convert wiki markup: " + e.getMessage());
    }
  }
}
