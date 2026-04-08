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
 *   1. Pre-process Markdown extensions (GitHub alerts, status labels, etc.)
 *   2. Parse Markdown into AST via flexmark-java
 *   3. Render to HTML
 *   4. Post-process to native Confluence elements (task lists, panels, etc.)
 *
 * Supported Markdown extensions beyond standard GFM:
 *   - GitHub alerts:  > [!NOTE], > [!WARNING], > [!TIP], > [!IMPORTANT], > [!CAUTION]
 *   - Status labels:  {status:IN PROGRESS} or {status:DONE|green}
 *   - Expand/details: <details><summary>Title</summary>content</details>
 *   - Task lists:     - [x] done, - [ ] todo
 *   - Mentions:       @username (preserved as-is for readability)
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

        List<com.vladsch.flexmark.util.misc.Extension> extensions = List.of(
                TablesExtension.create(),
                StrikethroughExtension.create(),
                TaskListExtension.create(),
                AutolinkExtension.create()
        );

        PARSER = Parser.builder(options)
                .extensions(extensions)
                .build();

        RENDERER = HtmlRenderer.builder(options)
                .extensions(extensions)
                .build();
    }

    // ── GitHub Alert Patterns ────────────────────────────────────────
    // Matches: > [!NOTE], > [!WARNING], > [!TIP], > [!IMPORTANT], > [!CAUTION]
    // With optional title: > [!NOTE|title:Custom Title]
    private static final Pattern GITHUB_ALERT = Pattern.compile(
            "^(> *\\[!(NOTE|WARNING|TIP|IMPORTANT|CAUTION)(?:\\|title:([^\\]]*))?)\\]\\s*$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Status Label Pattern ─────────────────────────────────────────
    // Matches: {status:IN PROGRESS} or {status:DONE|green} or {status:BLOCKED|red}
    private static final Pattern STATUS_LABEL = Pattern.compile(
            "\\{status:([^|}]+)(?:\\|([^}]+))?\\}");

    // ── Table of Contents ────────────────────────────────────────────
    // Matches: {toc} or [TOC] on its own line
    private static final Pattern TOC_MARKER = Pattern.compile(
            "^\\{toc\\}$|^\\[TOC\\]$",
            Pattern.MULTILINE | Pattern.CASE_INSENSITIVE);

    // ── Confluence Expand/Details ─────────────────────────────────────
    private static final Pattern DETAILS_BLOCK = Pattern.compile(
            "<details>\\s*<summary>(.*?)</summary>(.*?)</details>",
            Pattern.DOTALL);

    // ── Task List Patterns ───────────────────────────────────────────
    private static final Pattern TASK_LIST_ITEM = Pattern.compile(
            "<li class=\"task-list-item\">\\s*<input type=\"checkbox\"([^>]*)(?:/>|>)\\s*(.*?)</li>",
            Pattern.DOTALL);
    private static final Pattern TASK_LIST_UL = Pattern.compile(
            "<ul>\\s*((?:<li class=\"task-list-item\">.*?</li>\\s*)+)</ul>",
            Pattern.DOTALL);

    private static int taskIdCounter = 1;

    /** Placeholder prefix for protecting Confluence XML from flexmark escaping. */
    private static final String PLACEHOLDER_PREFIX = "\u0000CFMACRO";

    /**
     * Convert Markdown content to Confluence storage format (XHTML).
     */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }

        // Protected macros storage — filled by pre-processing, restored after flexmark
        List<String> protectedMacros = new ArrayList<>();

        // 1. Pre-process: convert Markdown extensions to Confluence XML,
        //    then protect the XML from flexmark escaping via placeholders
        String processed = markdown;
        processed = convertGitHubAlerts(processed, protectedMacros);
        processed = convertStatusLabels(processed, protectedMacros);
        processed = convertTocMarkers(processed, protectedMacros);

        // 2. Parse Markdown → HTML (flexmark won't touch placeholders)
        Node document = PARSER.parse(processed);
        String html = RENDERER.render(document).trim();

        // 3. Post-process HTML → native Confluence elements
        html = convertTaskLists(html);
        html = convertDetailsToExpand(html);

        // 4. Restore protected macros
        for (int i = 0; i < protectedMacros.size(); i++) {
            String placeholder = PLACEHOLDER_PREFIX + i + "\u0000";
            html = html.replace(placeholder, protectedMacros.get(i));
            // Also handle HTML-escaped version (flexmark might wrap in <p> and escape)
            String escaped = placeholder.replace("\u0000", "");
            html = html.replace(escaped, protectedMacros.get(i));
        }

        // Clean up any <p> tags wrapping block-level macros
        html = html.replace("<p><ac:structured-macro", "<ac:structured-macro");
        html = html.replace("</ac:structured-macro></p>", "</ac:structured-macro>");
        html = html.replace("<ac:structured-macro ac:name=\"toc\"/></p>", "<ac:structured-macro ac:name=\"toc\"/>");
        html = html.replace("<p><ac:structured-macro ac:name=\"toc\"/>", "<ac:structured-macro ac:name=\"toc\"/>");

        return html;
    }

    /** Store a Confluence XML macro and return a placeholder string. */
    private static String protect(String macroXml, List<String> store) {
        int index = store.size();
        store.add(macroXml);
        return PLACEHOLDER_PREFIX + index + "\u0000";
    }

    // ── Pre-processing (Markdown → Markdown) ─────────────────────────

    /**
     * Convert GitHub-style alerts to Confluence panel macros.
     *
     * Input:
     *   > [!NOTE]
     *   > This is important info.
     *   > Second line.
     *
     * Output (Confluence storage format injected directly):
     *   <ac:structured-macro ac:name="info">
     *     <ac:rich-text-body><p>This is important info. Second line.</p></ac:rich-text-body>
     *   </ac:structured-macro>
     */
    private static String convertGitHubAlerts(String markdown, List<String> protectedMacros) {
        String[] lines = markdown.split("\n");
        StringBuilder result = new StringBuilder();
        int i = 0;

        while (i < lines.length) {
            Matcher m = GITHUB_ALERT.matcher(lines[i]);
            if (m.matches()) {
                String alertType = m.group(2).toUpperCase();
                String customTitle = m.group(3);
                String macroName = mapAlertToMacro(alertType);

                // Collect all continuation lines (lines starting with >)
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

                // Convert body markdown to HTML (recursive for nested formatting)
                String bodyContent = body.toString().trim();
                String bodyHtml;
                if (bodyContent.isEmpty()) {
                    bodyHtml = "";
                } else {
                    Node bodyDoc = PARSER.parse(bodyContent);
                    bodyHtml = RENDERER.render(bodyDoc).trim();
                }

                // Build Confluence panel macro and protect from flexmark
                StringBuilder macro = new StringBuilder();
                macro.append("<ac:structured-macro ac:name=\"").append(macroName).append("\">");
                if (customTitle != null && !customTitle.isBlank()) {
                    macro.append("<ac:parameter ac:name=\"title\">")
                            .append(escapeXml(customTitle.trim()))
                            .append("</ac:parameter>");
                }
                macro.append("<ac:rich-text-body>").append(bodyHtml).append("</ac:rich-text-body>");
                macro.append("</ac:structured-macro>");
                result.append(protect(macro.toString(), protectedMacros)).append("\n");
            } else {
                result.append(lines[i]).append("\n");
                i++;
            }
        }

        return result.toString();
    }

    /** Map GitHub alert type to Confluence macro name. */
    private static String mapAlertToMacro(String alertType) {
        return switch (alertType) {
            case "NOTE" -> "info";
            case "TIP" -> "tip";
            case "IMPORTANT" -> "note";
            case "WARNING" -> "warning";
            case "CAUTION" -> "warning";
            default -> "info";
        };
    }

    /**
     * Convert {status:TEXT} and {status:TEXT|color} to Confluence status macro.
     *
     * Input:  The task is {status:IN PROGRESS|yellow} right now.
     * Output: The task is <ac:structured-macro ac:name="status">
     *           <ac:parameter ac:name="title">IN PROGRESS</ac:parameter>
     *           <ac:parameter ac:name="colour">Yellow</ac:parameter>
     *         </ac:structured-macro> right now.
     */
    private static String convertStatusLabels(String markdown, List<String> protectedMacros) {
        Matcher m = STATUS_LABEL.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String title = m.group(1).trim();
            String color = m.group(2);

            StringBuilder macro = new StringBuilder();
            macro.append("<ac:structured-macro ac:name=\"status\">");
            macro.append("<ac:parameter ac:name=\"title\">").append(escapeXml(title)).append("</ac:parameter>");
            if (color != null && !color.isBlank()) {
                String normalized = color.trim().substring(0, 1).toUpperCase() + color.trim().substring(1).toLowerCase();
                macro.append("<ac:parameter ac:name=\"colour\">").append(normalized).append("</ac:parameter>");
            }
            macro.append("</ac:structured-macro>");
            m.appendReplacement(sb, Matcher.quoteReplacement(protect(macro.toString(), protectedMacros)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Convert {toc} or [TOC] markers to Confluence TOC macro.
     */
    private static String convertTocMarkers(String markdown, List<String> protectedMacros) {
        Matcher m = TOC_MARKER.matcher(markdown);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(
                    protect("<ac:structured-macro ac:name=\"toc\"/>", protectedMacros)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    // ── Post-processing (HTML → Confluence XHTML) ────────────────────

    /**
     * Convert HTML <details>/<summary> to Confluence expand macro.
     */
    private static String convertDetailsToExpand(String html) {
        Matcher m = DETAILS_BLOCK.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String title = m.group(1).trim();
            String body = m.group(2).trim();

            StringBuilder macro = new StringBuilder();
            macro.append("<ac:structured-macro ac:name=\"expand\">");
            macro.append("<ac:parameter ac:name=\"title\">").append(title).append("</ac:parameter>");
            macro.append("<ac:rich-text-body>").append(body).append("</ac:rich-text-body>");
            macro.append("</ac:structured-macro>");
            m.appendReplacement(sb, Matcher.quoteReplacement(macro.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * Convert flexmark's HTML task list items to Confluence native ac:task-list.
     */
    private static String convertTaskLists(String html) {
        Matcher ulMatcher = TASK_LIST_UL.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (ulMatcher.find()) {
            String items = ulMatcher.group(1);
            StringBuilder taskList = new StringBuilder("<ac:task-list>");

            Matcher itemMatcher = TASK_LIST_ITEM.matcher(items);
            while (itemMatcher.find()) {
                String attrs = itemMatcher.group(1);
                String body = itemMatcher.group(2).trim();
                boolean checked = attrs.contains("checked");
                String status = checked ? "complete" : "incomplete";

                taskList.append("<ac:task>")
                        .append("<ac:task-id>").append(taskIdCounter++).append("</ac:task-id>")
                        .append("<ac:task-status>").append(status).append("</ac:task-status>")
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
