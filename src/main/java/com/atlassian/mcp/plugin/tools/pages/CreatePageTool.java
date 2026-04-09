package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mirrors upstream: confluence_mcp.create_page()
 * Returns: {message, page: {simplified page dict}}
 */
public class CreatePageTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public CreatePageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "create_page"; }

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
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("space_key", Map.of("type", "string", "description", "The key of the space to create the page in (usually a short uppercase code like 'DEV', 'TEAM', or 'DOC')")),
                        Map.entry("title", Map.of("type", "string", "description", "The title of the page")),
                        Map.entry("content", Map.of("type", "string", "description", "Page content in Markdown. All features described in the tool description work in Markdown — panels, status badges, task lists, expandable sections are all auto-converted to native Confluence elements. Do NOT start with '# Title' — Confluence displays the title separately.")),
                        Map.entry("parent_id", Map.of("type", "string", "description", "(Optional) parent page ID. If provided, this page will be created as a child of the specified page")),
                        Map.entry("content_format", Map.of("type", "string", "description", "(Optional) Content format. Use 'markdown' (default). Only use 'storage' if you need advanced Confluence macros not covered by Markdown.", "default", "markdown")),
                        Map.entry("emoji", Map.of("type", "string", "description", "(Optional) Page title emoji (icon shown in navigation). Can be any emoji character.")),
                        Map.entry("return_markdown", Map.of("type", "boolean", "description", "If true, return the page content converted to Markdown instead of storage format.", "default", false)),
                        Map.entry("labels", Map.of("type", "array", "description", "List of labels to apply to the page after creation. Example: ['policy', 'hr', 'q3-2026']", "items", Map.of("type", "string"))),
                        Map.entry("initial_comment", Map.of("type", "string", "description", "(Optional) A comment to add to the page after creation, in Markdown format."))
                ),
                "required", List.of("space_key", "title", "content")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String spaceKey = (String) args.get("space_key");
        if (spaceKey == null || spaceKey.isBlank()) {
            throw new McpToolException("'space_key' parameter is required");
        }
        String title = (String) args.get("title");
        if (title == null || title.isBlank()) {
            throw new McpToolException("'title' parameter is required");
        }
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        String parentId = (String) args.get("parent_id");
        String contentFormat = (String) args.getOrDefault("content_format", "markdown");
        boolean returnMarkdown = getBoolean(args, "return_markdown", false);

        // Convert content to storage format (mirrors upstream's markdown_to_confluence_storage)
        String finalBody;
        String representation;
        if ("markdown".equals(contentFormat)) {
            finalBody = MarkdownToStorage.convert(content);
            representation = "storage";
        } else if ("wiki".equals(contentFormat)) {
            finalBody = content;
            representation = "wiki";
        } else {
            finalBody = content;
            representation = "storage";
        }

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("type", "page");
        requestBody.put("title", title);
        requestBody.put("space", Map.of("key", spaceKey));
        requestBody.put("body", Map.of(representation, Map.of(
                "value", finalBody,
                "representation", representation
        )));
        if (parentId != null && !parentId.isBlank()) {
            requestBody.put("ancestors", List.of(Map.of("id", parentId)));
        }
        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.postRaw("/rest/api/content", jsonBody, authHeader);

            // Transform to upstream format: {message, page}
            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Page created successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, returnMarkdown));

            String createdPageId = raw.path("id").asText();

            // Best-effort: add labels if provided
            Object labelsObj = args.get("labels");
            if (labelsObj instanceof List<?> labelsList && !labelsList.isEmpty()) {
                try {
                    List<Map<String, String>> labelPayload = new ArrayList<>();
                    for (Object l : labelsList) {
                        labelPayload.add(Map.of("prefix", "global", "name", l.toString()));
                    }
                    String labelJson = mapper.writeValueAsString(labelPayload);
                    client.postRaw("/rest/api/content/" + createdPageId + "/label", labelJson, authHeader);

                    ArrayNode labelsAdded = mapper.createArrayNode();
                    for (Object l : labelsList) {
                        labelsAdded.add(l.toString());
                    }
                    result.set("labels_added", labelsAdded);
                } catch (Exception labelErr) {
                    result.put("labels_error", "Failed to add labels: " + labelErr.getMessage());
                }
            }

            // Best-effort: add initial comment if provided
            String initialComment = (String) args.get("initial_comment");
            if (initialComment != null && !initialComment.isBlank()) {
                try {
                    Map<String, Object> commentBody = new HashMap<>();
                    commentBody.put("type", "comment");
                    commentBody.put("container", Map.of("id", createdPageId, "type", "page"));
                    commentBody.put("body", Map.of("storage", Map.of(
                            "value", MarkdownToStorage.convert(initialComment),
                            "representation", "storage"
                    )));
                    String commentJson = mapper.writeValueAsString(commentBody);
                    client.postRaw("/rest/api/content", commentJson, authHeader);
                    result.put("comment_added", true);
                } catch (Exception commentErr) {
                    result.put("comment_error", "Failed to add comment: " + commentErr.getMessage());
                }
            }

            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to create page: " + e.getMessage());
        }
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
