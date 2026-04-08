package com.atlassian.mcp.plugin;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts Markdown to Confluence storage format (XHTML).
 *
 * Pipeline:
 *   1. Pre-process: convert extended Markdown syntax to Confluence XML macros
 *   2. Parse with flexmark (Confluence XML passes through untouched)
 *   3. Post-process: convert flexmark task list HTML to native ac:task-list
 *
 * Flexmark is configured with two key settings:
 *   - Parser.HTML_ALLOW_NAME_SPACE = true  -> recognizes ac and ri namespaced tags as valid HTML
 *   - Parser.HTML_BLOCK_TAGS += Confluence tags -> Type 6 block detection
 *
 * This means all <ac:structured-macro>, <ac:parameter>, etc. pass through
 * flexmark as raw HTML -- no placeholders, no escaping, no post-processing needed.
 *
 * Supported extensions beyond standard GFM:
 *   - GitHub alerts:    > [!NOTE], > [!TIP], > [!IMPORTANT], > [!WARNING], > [!CAUTION]
 *   - Status labels:    {status:Text} or {status:Text|color}
 *   - Table of contents: {toc} or [TOC]
 *   - Task lists:       - [x] done, - [ ] todo -> native ac:task-list
 *   - Expand sections:  <details><summary>Title</summary>content</details>
 */
public final class MarkdownToStorage {

    private MarkdownToStorage() {}

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
        options.set(HtmlRenderer.RENDER_HEADER_ID, false);
        options.set(HtmlRenderer.HTML_BLOCK_OPEN_TAG_EOL, false);
        options.set(HtmlRenderer.HTML_BLOCK_CLOSE_TAG_EOL, false);

        // Enable XML namespace support: ac:* and ri:* tags are recognized as valid
        // HTML in both block and inline contexts. Without this, flexmark escapes them.
        // See: Parsing.java:354-399 (OPENTAG/CLOSETAG patterns conditional on this flag)
        options.set(Parser.HTML_ALLOW_NAME_SPACE, true);

        // Register Confluence tags as block-level HTML (Type 6 detection).
        // Type 6 is more reliable than Type 7 for multi-line macro blocks.
        // See: HtmlBlockParser.java:79, Parser.java:118-183
        List<String> blockTags = new ArrayList<>(Parser.HTML_BLOCK_TAGS.getDefaultValue());
        blockTags.add("ac:structured-macro");
        blockTags.add("ac:rich-text-body");
        blockTags.add("ac:plain-text-body");
        blockTags.add("ac:parameter");
        blockTags.add("ac:task-list");
        blockTags.add("ac:task");
        blockTags.add("ac:task-body");
        blockTags.add("ac:task-status");
        blockTags.add("ac:task-id");
        blockTags.add("ac:image");
        blockTags.add("ac:link");
        blockTags.add("ac:link-body");
        blockTags.add("ac:emoticon");
        blockTags.add("ac:layout");
        blockTags.add("ac:layout-section");
        blockTags.add("ac:layout-cell");
        blockTags.add("ac:placeholder");
        blockTags.add("ri:page");
        blockTags.add("ri:attachment");
        blockTags.add("ri:url");
        blockTags.add("ri:user");
        blockTags.add("ri:space");
        options.set(Parser.HTML_BLOCK_TAGS, blockTags);

        List<com.vladsch.flexmark.util.misc.Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
        );

        PARSER = Parser.builder(options).extensions(extensions).build();
        RENDERER = HtmlRenderer.builder(options).extensions(extensions).build();
    }

    // ── Patterns ─────────────────────────────────────────────────────

    /** GitHub alerts: > [!NOTE], > [!WARNING], etc. with optional title */
    private static final Pattern GITHUB_ALERT = Pattern.compile(
            "^> *\\[!(NOTE|WARNING|TIP|IMPORTANT|CAUTION)(?:\\|title:([^\\]]*))?\\]\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** Status labels: {status:Text} or {status:Text|color} */
    private static final Pattern STATUS_LABEL = Pattern.compile(
            "\\{status:([^|}]+)(?:\\|([^}]+))?\\}");

    /** Table of contents: {toc} or [TOC] on its own line */
    private static final Pattern TOC_MARKER = Pattern.compile(
            "^(?:\\{toc\\}|\\[TOC\\])$", Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    /** HTML details/summary -> Confluence expand macro */
    private static final Pattern DETAILS_BLOCK = Pattern.compile(
            "<details>\\s*<summary>(.*?)</summary>(.*?)</details>", Pattern.DOTALL);

    /** Flexmark task list items in HTML output */
    private static final Pattern TASK_LIST_ITEM = Pattern.compile(
            "<li class=\"task-list-item\">\\s*<input type=\"checkbox\"([^>]*)(?:/>|>)\\s*(.*?)</li>",
            Pattern.DOTALL);
    private static final Pattern TASK_LIST_UL = Pattern.compile(
            "<ul>\\s*((?:<li class=\"task-list-item\">.*?</li>\\s*)+)</ul>", Pattern.DOTALL);

    private static int taskIdCounter = 1;

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Convert Markdown content to Confluence storage format (XHTML).
     */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) return "";

        // 1. Pre-process: convert extended syntax to Confluence XML.
        //    The XML passes through flexmark untouched because:
        //    - HTML_ALLOW_NAME_SPACE = true (inline ac:* recognized)
        //    - HTML_BLOCK_TAGS includes ac:* (block ac:* recognized)
        String processed = convertGitHubAlerts(markdown);
        processed = convertStatusLabels(processed);
        processed = convertTocMarkers(processed);

        // 2. Parse Markdown -> HTML
        Node document = PARSER.parse(processed);
        String html = RENDERER.render(document).trim();

        // 3. Post-process: convert flexmark output to native Confluence elements
        html = convertTaskLists(html);
        html = convertDetailsToExpand(html);

        return html;
    }

    // ── Pre-processing (Markdown -> Markdown with embedded Confluence XML) ──

    /**
     * Convert GitHub-style alerts to Confluence panel macros.
     *
     * Input:                              Output:
     * > [!NOTE]                           <ac:structured-macro ac:name="info">
     * > This is important.                  <ac:rich-text-body><p>This is important.</p></ac:rich-text-body>
     *                                     </ac:structured-macro>
     */
    private static String convertGitHubAlerts(String markdown) {
        String[] lines = markdown.split("\n");
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < lines.length) {
            Matcher m = GITHUB_ALERT.matcher(lines[i]);
            if (m.matches()) {
                String alertType = m.group(1).toUpperCase();
                String customTitle = m.group(2);
                String macroName = mapAlertToMacro(alertType);

                // Collect continuation lines (starting with >)
                StringBuilder body = new StringBuilder();
                i++;
                while (i < lines.length) {
                    String line = lines[i];
                    if (line.startsWith("> ")) {
                        body.append(line.substring(2)).append("\n");
                        i++;
                    } else if (line.equals(">")) {
                        body.append("\n");
                        i++;
                    } else {
                        break;
                    }
                }

                // Convert body to HTML (nested formatting like **bold**, lists, etc.)
                String bodyHtml = "";
                String bodyContent = body.toString().trim();
                if (!bodyContent.isEmpty()) {
                    bodyHtml = RENDERER.render(PARSER.parse(bodyContent)).trim();
                }

                // Emit Confluence panel macro -- flexmark passes it through as-is
                result.append("\n<ac:structured-macro ac:name=\"").append(macroName).append("\">");
                if (customTitle != null && !customTitle.isBlank()) {
                    result.append("<ac:parameter ac:name=\"title\">")
                            .append(escapeXml(customTitle.trim()))
                            .append("</ac:parameter>");
                }
                result.append("<ac:rich-text-body>").append(bodyHtml).append("</ac:rich-text-body>");
                result.append("</ac:structured-macro>\n\n");
            } else {
                result.append(lines[i]).append("\n");
                i++;
            }
        }

        return result.toString();
    }

    private static String mapAlertToMacro(String alertType) {
        return switch (alertType) {
            case "NOTE" -> "info";
            case "TIP" -> "tip";
            case "IMPORTANT" -> "note";
            case "WARNING", "CAUTION" -> "warning";
            default -> "info";
        };
    }

    /**
     * Convert {status:Text|color} to inline Confluence status macro.
     * Inline XML works because HTML_ALLOW_NAME_SPACE = true.
     */
    private static String convertStatusLabels(String markdown) {
        Matcher m = STATUS_LABEL.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String title = m.group(1).trim();
            String color = m.group(2);

            StringBuilder macro = new StringBuilder();
            macro.append("<ac:structured-macro ac:name=\"status\">");
            macro.append("<ac:parameter ac:name=\"title\">").append(escapeXml(title)).append("</ac:parameter>");
            if (color != null && !color.isBlank()) {
                String normalized = color.trim().substring(0, 1).toUpperCase()
                        + color.trim().substring(1).toLowerCase();
                macro.append("<ac:parameter ac:name=\"colour\">").append(normalized).append("</ac:parameter>");
            }
            macro.append("</ac:structured-macro>");
            m.appendReplacement(sb, Matcher.quoteReplacement(macro.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Convert {toc} or [TOC] to Confluence TOC macro. */
    private static String convertTocMarkers(String markdown) {
        return TOC_MARKER.matcher(markdown).replaceAll("\n<ac:structured-macro ac:name=\"toc\"/>\n");
    }

    // ── Post-processing (HTML -> Confluence XHTML) ────────────────────

    /** Convert <details><summary>Title</summary>content</details> -> expand macro. */
    private static String convertDetailsToExpand(String html) {
        Matcher m = DETAILS_BLOCK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String title = m.group(1).trim();
            String body = m.group(2).trim();
            String macro = "<ac:structured-macro ac:name=\"expand\">"
                    + "<ac:parameter ac:name=\"title\">" + title + "</ac:parameter>"
                    + "<ac:rich-text-body>" + body + "</ac:rich-text-body>"
                    + "</ac:structured-macro>";
            m.appendReplacement(sb, Matcher.quoteReplacement(macro));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Convert flexmark task list HTML to native Confluence ac:task-list. */
    private static String convertTaskLists(String html) {
        Matcher ulMatcher = TASK_LIST_UL.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (ulMatcher.find()) {
            String items = ulMatcher.group(1);
            StringBuilder taskList = new StringBuilder("<ac:task-list>");

            Matcher itemMatcher = TASK_LIST_ITEM.matcher(items);
            while (itemMatcher.find()) {
                boolean checked = itemMatcher.group(1).contains("checked");
                String body = itemMatcher.group(2).trim();
                taskList.append("<ac:task>")
                        .append("<ac:task-id>").append(taskIdCounter++).append("</ac:task-id>")
                        .append("<ac:task-status>").append(checked ? "complete" : "incomplete").append("</ac:task-status>")
                        .append("<ac:task-body>").append(body).append("</ac:task-body>")
                        .append("</ac:task>");
            }

            taskList.append("</ac:task-list>");
            ulMatcher.appendReplacement(sb, Matcher.quoteReplacement(taskList.toString()));
        }
        ulMatcher.appendTail(sb);
        return sb.toString();
    }

    private static String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
