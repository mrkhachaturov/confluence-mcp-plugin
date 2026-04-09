package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/**
 * Preview tool: converts Markdown to Confluence storage format without creating a page.
 * Returns: {storage_format: "...", content_format: "storage"}
 */
public class ConvertContentTool implements McpTool {
    private final ObjectMapper mapper = new ObjectMapper();

    public ConvertContentTool(ConfluenceRestClient client) {
        // Client not needed — this is a pure conversion tool.
        // Constructor accepts it for consistency with other tools.
    }

    @Override public String name() { return "convert_content"; }

    @Override
    public String description() {
        return "Convert Markdown to Confluence storage format (XHTML) without creating or updating any page. "
                + "Use this to preview how Markdown will render in Confluence, debug formatting issues, "
                + "or validate content before writing. No page ID or space key required.\n\n"
                + "Supports all Markdown features: panels (> [!NOTE]), status badges ({status:Text|color}), "
                + "task lists, table of contents, expand sections, tables, code blocks, and more.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "content", Map.of("type", "string", "description",
                                "Markdown content to convert to Confluence storage format"),
                        "content_format", Map.of("type", "string", "description",
                                "(Optional) Input format: 'markdown' (default) or 'wiki'.",
                                "default", "markdown")
                ),
                "required", List.of("content")
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        String contentFormat = (String) args.getOrDefault("content_format", "markdown");

        try {
            String storageFormat;
            if ("markdown".equals(contentFormat)) {
                storageFormat = MarkdownToStorage.convert(content);
            } else {
                storageFormat = content;
            }

            ObjectNode contentNode = mapper.createObjectNode();
            contentNode.put("value", storageFormat);
            contentNode.put("format", "storage");

            ObjectNode result = mapper.createObjectNode();
            result.set("content", contentNode);
            return mapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new McpToolException("Failed to convert content: " + e.getMessage());
        }
    }
}
