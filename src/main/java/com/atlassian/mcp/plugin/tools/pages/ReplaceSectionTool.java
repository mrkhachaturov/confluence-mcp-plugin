package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.MarkdownToStorage;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.ResponseTransformer;
import com.atlassian.mcp.plugin.StorageToMarkdown;
import com.atlassian.mcp.plugin.tools.McpTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Replace a section of a Confluence page identified by its heading.
 * Returns: {message, page: {simplified page dict}}
 */
public class ReplaceSectionTool implements McpTool {
    private final ConfluenceRestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Matches markdown headings: # H1, ## H2, ### H3, etc. */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);

    public ReplaceSectionTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "replace_section"; }

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
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.ofEntries(
                        Map.entry("page_id", Map.of("type", "string", "description", "The ID of the page to edit")),
                        Map.entry("heading", Map.of("type", "string", "description", "The exact heading text to find (without the # prefix). Case-sensitive.")),
                        Map.entry("content", Map.of("type", "string", "description", "New content for the section (in Markdown by default). This replaces everything under the heading until the next heading of equal or higher level.")),
                        Map.entry("content_format", Map.of("type", "string", "description", "(Optional) Format of the new content: 'markdown' (default) or 'storage'.", "default", "markdown")),
                        Map.entry("heading_level", Map.of("type", "integer", "description", "(Optional) Heading level to match (1-6). Use to disambiguate when the same text appears at different heading levels.")),
                        Map.entry("expected_version", Map.of("type", "integer", "description", "If provided, the update will fail if the page's current version doesn't match."))
                ),
                "required", List.of("page_id", "heading", "content")
        );
    }

    @Override public boolean isWriteTool() { return true; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        if (pageId == null || pageId.isBlank()) {
            throw new McpToolException("'page_id' parameter is required");
        }
        pageId = McpTool.resolvePageId(pageId);

        String heading = (String) args.get("heading");
        if (heading == null || heading.isBlank()) {
            throw new McpToolException("'heading' parameter is required");
        }
        String content = (String) args.get("content");
        if (content == null || content.isBlank()) {
            throw new McpToolException("'content' parameter is required");
        }
        int headingLevel = getInt(args, "heading_level", -1);

        // Fetch current page
        String currentTitle;
        int currentVersion;
        String storageBody;
        try {
            String current = client.getRaw(
                    "/rest/api/content/" + pageId + "?expand=body.storage,version", authHeader);
            JsonNode parsed = mapper.readTree(current);
            currentVersion = parsed.path("version").path("number").asInt(0);
            currentTitle = parsed.path("title").asText("");
            storageBody = parsed.path("body").path("storage").path("value").asText("");
        } catch (Exception e) {
            throw new McpToolException("Failed to fetch current page: " + e.getMessage());
        }

        // Optimistic locking
        int expectedVersion = getInt(args, "expected_version", -1);
        if (expectedVersion > 0 && expectedVersion != currentVersion) {
            throw new McpToolException(
                    "Page was modified since you last read it (current version: "
                    + currentVersion + ", expected: " + expectedVersion
                    + "). Re-read the page with get_page before updating.");
        }

        // Convert storage to markdown for section finding
        String markdown = StorageToMarkdown.convert(storageBody);

        // Find and replace the section
        String updatedMarkdown = replaceSection(markdown, heading, headingLevel, content);

        // Convert back to storage
        String updatedStorage = MarkdownToStorage.convert(updatedMarkdown);

        Map<String, Object> version = new HashMap<>();
        version.put("number", currentVersion + 1);
        version.put("minorEdit", false);
        version.put("message", "Section '" + heading + "' updated");

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("id", pageId);
        requestBody.put("type", "page");
        requestBody.put("title", currentTitle);
        requestBody.put("version", version);
        requestBody.put("body", Map.of("storage", Map.of(
                "value", updatedStorage,
                "representation", "storage"
        )));

        try {
            String jsonBody = mapper.writeValueAsString(requestBody);
            String rawJson = client.putRaw("/rest/api/content/" + pageId, jsonBody, authHeader);

            String baseUrl = client.getBaseUrl();
            JsonNode raw = mapper.readTree(rawJson);
            ObjectNode result = mapper.createObjectNode();
            result.put("message", "Section '" + heading + "' replaced successfully");
            result.set("page", ResponseTransformer.simplifyPageNode(raw, baseUrl, false));
            return mapper.writeValueAsString(result);
        } catch (McpToolException e) {
            throw e;
        } catch (Exception e) {
            throw new McpToolException("Failed to replace section: " + e.getMessage());
        }
    }

    /**
     * Replace a section in markdown text identified by heading.
     * A section spans from the heading to the next heading of equal or higher level.
     */
    static String replaceSection(String markdown, String targetHeading, int targetLevel,
                                  String newContent) throws McpToolException {
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
                                "Multiple headings match '" + targetHeading + "'. "
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
                    "Heading '" + targetHeading + "' not found. Available headings:\n"
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

    private static int getInt(Map<String, Object> args, String key, int defaultVal) {
        Object val = args.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s); } catch (NumberFormatException e) { return defaultVal; }
        }
        return defaultVal;
    }
}
