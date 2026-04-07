package com.atlassian.mcp.plugin;

import com.vladsch.flexmark.ext.autolink.AutolinkExtension;
import com.vladsch.flexmark.ext.gfm.strikethrough.StrikethroughExtension;
import com.vladsch.flexmark.ext.gfm.tasklist.TaskListExtension;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import com.vladsch.flexmark.util.ast.Node;
import com.vladsch.flexmark.util.data.MutableDataSet;

import java.util.List;

/**
 * Converts Markdown to Confluence storage format (XHTML).
 * Mirrors upstream mcp-atlassian's markdown_to_confluence_storage() pipeline:
 *   1. Parse Markdown into AST (flexmark-java, equivalent to upstream's md2conf)
 *   2. Render to HTML (which is valid Confluence storage format)
 *
 * Confluence storage format accepts standard XHTML tags (p, h1-h6, table, ul, ol,
 * code, pre, a, img, em, strong, etc.) — the HTML output from flexmark is directly
 * usable as storage format content.
 */
public final class MarkdownToStorage {

    private MarkdownToStorage() {}

    private static final Parser PARSER;
    private static final HtmlRenderer RENDERER;

    static {
        MutableDataSet options = new MutableDataSet();
        options.set(HtmlRenderer.GENERATE_HEADER_ID, true);
        options.set(HtmlRenderer.RENDER_HEADER_ID, false);
        // Confluence storage format uses self-closing tags for br, hr, img
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

    /**
     * Convert Markdown content to Confluence storage format (XHTML).
     *
     * @param markdown the Markdown text
     * @return Confluence storage format XHTML string
     */
    public static String convert(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        Node document = PARSER.parse(markdown);
        return RENDERER.render(document).trim();
    }
}
