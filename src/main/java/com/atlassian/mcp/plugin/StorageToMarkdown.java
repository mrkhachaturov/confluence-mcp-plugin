package com.atlassian.mcp.plugin;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;
import org.jsoup.select.Elements;

/**
 * Converts Confluence storage format (XHTML) to Markdown.
 * Mirrors upstream mcp-atlassian's process_html_content() pipeline:
 *   1. Parse storage format with jsoup (XML mode for ac:/ri: namespaces)
 *   2. Transform all Confluence-specific elements (ac:*, ri:*) into standard HTML
 *   3. Convert standard HTML to Markdown via flexmark-html2md-converter
 *
 * Upstream uses BeautifulSoup + markdownify (Python).
 * We use jsoup + FlexmarkHtmlConverter (Java).
 *
 * Full Confluence storage format support per:
 * https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html
 */
public final class StorageToMarkdown {

    private StorageToMarkdown() {}

    private static final FlexmarkHtmlConverter CONVERTER = FlexmarkHtmlConverter.builder().build();

    /**
     * Convert Confluence storage format content to Markdown.
     *
     * @param storage the Confluence storage format XHTML string
     * @return Markdown string
     */
    public static String convert(String storage) {
        if (storage == null || storage.isBlank()) {
            return "";
        }

        // 1. Parse as XML to preserve ac:/ri: namespaced elements
        Document doc = Jsoup.parse(storage, "", Parser.xmlParser());

        // 2. Transform Confluence elements to standard HTML (bottom-up to handle nesting)
        transformStructuredMacros(doc);
        transformImages(doc);
        transformLinks(doc);
        transformEmoticons(doc);
        transformTaskLists(doc);
        transformLayouts(doc);
        transformPlaceholders(doc);
        stripRemainingAcRi(doc);

        // 3. Convert clean HTML to Markdown
        String html = doc.html();
        String markdown = CONVERTER.convert(html);

        // Post-process: fix flexmark bracket escaping in task list checkboxes
        markdown = markdown.replace("\\[x\\]", "[x]");
        markdown = markdown.replace("\\[ \\]", "[ ]");
        markdown = markdown.replace("\\[x]", "[x]");
        markdown = markdown.replace("\\[ ]", "[ ]");

        // Clean up excessive blank lines
        markdown = markdown.replaceAll("\n{3,}", "\n\n");
        return markdown.trim();
    }

    // ── Structured Macros ────────────────────────────────────────────

    private static void transformStructuredMacros(Document doc) {
        // Process all ac:structured-macro elements
        for (Element macro : doc.select("ac|structured-macro")) {
            String name = macro.attr("ac:name");
            switch (name) {
                case "code":
                    transformCodeMacro(macro);
                    break;
                case "noformat":
                    transformNoformatMacro(macro);
                    break;
                case "info":
                case "note":
                case "warning":
                case "tip":
                    transformAlertMacro(macro, name);
                    break;
                case "panel":
                    transformPanelMacro(macro);
                    break;
                case "expand":
                    transformExpandMacro(macro);
                    break;
                case "toc":
                    macro.replaceWith(new TextNode("[TOC]"));
                    break;
                case "anchor":
                    transformAnchorMacro(macro);
                    break;
                case "jira":
                    transformJiraMacro(macro);
                    break;
                case "status":
                    transformStatusMacro(macro);
                    break;
                case "section":
                case "column":
                    // Layout macros — unwrap, keep content
                    unwrapBody(macro);
                    break;
                case "excerpt":
                case "excerpt-include":
                    // Excerpt — unwrap, keep content
                    unwrapBody(macro);
                    break;
                case "include":
                    transformIncludeMacro(macro);
                    break;
                case "children":
                case "page-tree":
                    macro.replaceWith(new TextNode("[Child pages]"));
                    break;
                case "recently-updated":
                    macro.replaceWith(new TextNode("[Recently updated content]"));
                    break;
                case "attachments":
                    macro.replaceWith(new TextNode("[Attachments]"));
                    break;
                case "blog-posts":
                    macro.replaceWith(new TextNode("[Blog posts]"));
                    break;
                case "content-by-label":
                    macro.replaceWith(new TextNode("[Content by label]"));
                    break;
                case "profile":
                    transformProfileMacro(macro);
                    break;
                default:
                    // Unknown macro — try to preserve body content
                    unwrapBody(macro);
                    break;
            }
        }
    }

    /** Code macro → <pre><code class="language-X">...</code></pre> */
    private static void transformCodeMacro(Element macro) {
        String language = getParam(macro, "language");
        String code = getPlainTextBody(macro);

        Element pre = new Element("pre");
        Element codeEl = new Element("code");
        if (language != null && !language.isBlank()) {
            codeEl.attr("class", "language-" + language);
        }
        codeEl.text(code);
        pre.appendChild(codeEl);
        macro.replaceWith(pre);
    }

    /** Noformat macro → <pre>...</pre> */
    private static void transformNoformatMacro(Element macro) {
        String text = getPlainTextBody(macro);
        Element pre = new Element("pre");
        pre.text(text);
        macro.replaceWith(pre);
    }

    /** Info/Note/Warning/Tip → <blockquote> with type label */
    private static void transformAlertMacro(Element macro, String type) {
        String title = getParam(macro, "title");
        String bodyHtml = getRichTextBody(macro);

        StringBuilder html = new StringBuilder();
        html.append("<blockquote>");
        html.append("<strong>").append(type.toUpperCase());
        if (title != null && !title.isBlank()) {
            html.append(": ").append(escapeHtml(title));
        }
        html.append("</strong><br/>");
        html.append(bodyHtml);
        html.append("</blockquote>");

        macro.replaceWith(parseFragment(html.toString()));
    }

    /** Panel → <blockquote> with optional title */
    private static void transformPanelMacro(Element macro) {
        String title = getParam(macro, "title");
        String bodyHtml = getRichTextBody(macro);

        StringBuilder html = new StringBuilder();
        html.append("<blockquote>");
        if (title != null && !title.isBlank()) {
            html.append("<strong>").append(escapeHtml(title)).append("</strong><br/>");
        }
        html.append(bodyHtml);
        html.append("</blockquote>");

        macro.replaceWith(parseFragment(html.toString()));
    }

    /** Expand → <details><summary>...</summary>...</details> */
    private static void transformExpandMacro(Element macro) {
        String title = getParam(macro, "title");
        if (title == null || title.isBlank()) title = "Click to expand...";
        String bodyHtml = getRichTextBody(macro);

        String html = "<details><summary>" + escapeHtml(title) + "</summary>"
                + bodyHtml + "</details>";
        macro.replaceWith(parseFragment(html));
    }

    /** Anchor → <a name="..."></a> (invisible anchor point) */
    private static void transformAnchorMacro(Element macro) {
        String anchorName = getParam(macro, "");
        if (anchorName == null || anchorName.isBlank()) {
            // Try first parameter regardless of name
            Element param = macro.selectFirst("ac|parameter");
            anchorName = param != null ? param.text() : "";
        }
        if (!anchorName.isBlank()) {
            Element a = new Element("a");
            a.attr("name", anchorName);
            macro.replaceWith(a);
        } else {
            macro.remove();
        }
    }

    /** Jira issue macro → **ISSUE-KEY** */
    private static void transformJiraMacro(Element macro) {
        String key = getParam(macro, "key");
        if (key != null && !key.isBlank()) {
            Element strong = new Element("strong");
            strong.text(key);
            macro.replaceWith(strong);
        } else {
            // Jira filter/JQL — just show as placeholder
            String jqlQuery = getParam(macro, "jqlQuery");
            if (jqlQuery != null) {
                macro.replaceWith(new TextNode("[Jira: " + jqlQuery + "]"));
            } else {
                macro.replaceWith(new TextNode("[Jira issues]"));
            }
        }
    }

    /** Status macro → **STATUS** colored label */
    private static void transformStatusMacro(Element macro) {
        String title = getParam(macro, "title");
        if (title == null || title.isBlank()) title = "STATUS";
        Element strong = new Element("strong");
        strong.text(title);
        macro.replaceWith(strong);
    }

    /** Include macro → [Included: page title] */
    private static void transformIncludeMacro(Element macro) {
        Element riPage = macro.selectFirst("ri|page");
        String pageTitle = riPage != null ? riPage.attr("ri:content-title") : "";
        if (pageTitle.isBlank()) {
            macro.replaceWith(new TextNode("[Included page]"));
        } else {
            macro.replaceWith(new TextNode("[Included: " + pageTitle + "]"));
        }
    }

    /** Profile macro → @DisplayName */
    private static void transformProfileMacro(Element macro) {
        Element riUser = macro.selectFirst("ri|user");
        if (riUser != null) {
            String username = riUser.attr("ri:username");
            if (username.isBlank()) username = riUser.attr("ri:userkey");
            if (username.isBlank()) username = riUser.attr("ri:account-id");
            if (username.isBlank()) username = "user";
            macro.replaceWith(new TextNode("@" + username));
        } else {
            macro.replaceWith(new TextNode("@user"));
        }
    }

    // ── Images ───────────────────────────────────────────────────────

    private static void transformImages(Document doc) {
        for (Element img : doc.select("ac|image")) {
            String width = img.attr("ac:width");
            String height = img.attr("ac:height");

            Element riAtt = img.selectFirst("ri|attachment");
            Element riUrl = img.selectFirst("ri|url");

            String src;
            String alt;
            if (riAtt != null) {
                String filename = riAtt.attr("ri:filename");
                src = filename;
                alt = filename;
            } else if (riUrl != null) {
                src = riUrl.attr("ri:value");
                alt = "image";
            } else {
                img.remove();
                continue;
            }

            Element imgTag = new Element("img");
            imgTag.attr("src", src);
            imgTag.attr("alt", alt);
            if (!width.isBlank()) imgTag.attr("width", width);
            if (!height.isBlank()) imgTag.attr("height", height);
            img.replaceWith(imgTag);
        }
    }

    // ── Links ────────────────────────────────────────────────────────

    private static void transformLinks(Document doc) {
        for (Element link : doc.select("ac|link")) {
            Element riPage = link.selectFirst("ri|page");
            Element riAtt = link.selectFirst("ri|attachment");
            Element riUser = link.selectFirst("ri|user");
            Element riSpace = link.selectFirst("ri|space");
            Element riShortcut = link.selectFirst("ri|shortcut");
            Element riBlogPost = link.selectFirst("ri|blog-post");

            // Get link body text (if any)
            String linkText = null;
            Element linkBody = link.selectFirst("ac|link-body, ac|plain-text-link-body");
            if (linkBody != null) {
                linkText = linkBody.text();
            }

            if (riUser != null) {
                // User mention → @username
                String username = riUser.attr("ri:username");
                if (username.isBlank()) username = riUser.attr("ri:userkey");
                if (username.isBlank()) username = riUser.attr("ri:account-id");
                if (username.isBlank()) username = "user";
                link.replaceWith(new TextNode("@" + username));
            } else if (riPage != null) {
                // Page link
                String pageTitle = riPage.attr("ri:content-title");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : pageTitle;
                String anchor = link.attr("ac:anchor");
                String href = "#";
                if (!anchor.isBlank()) href = "#" + anchor;
                Element a = new Element("a");
                a.attr("href", href);
                a.text(display.isBlank() ? "page" : display);
                link.replaceWith(a);
            } else if (riAtt != null) {
                // Attachment link
                String filename = riAtt.attr("ri:filename");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : filename;
                Element a = new Element("a");
                a.attr("href", filename);
                a.text(display);
                link.replaceWith(a);
            } else if (riSpace != null) {
                // Space link
                String spaceKey = riSpace.attr("ri:space-key");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : spaceKey;
                Element a = new Element("a");
                a.attr("href", "#");
                a.text(display);
                link.replaceWith(a);
            } else if (riShortcut != null) {
                // External shortcut link
                String shortcutValue = riShortcut.attr("ri:value");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : shortcutValue;
                Element a = new Element("a");
                a.attr("href", "#");
                a.text(display != null ? display : "link");
                link.replaceWith(a);
            } else if (riBlogPost != null) {
                // Blog post link
                String blogTitle = riBlogPost.attr("ri:content-title");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : blogTitle;
                Element a = new Element("a");
                a.attr("href", "#");
                a.text(display != null ? display : "blog post");
                link.replaceWith(a);
            } else {
                // Anchor-only link or unknown link type
                String anchor = link.attr("ac:anchor");
                String display = (linkText != null && !linkText.isBlank()) ? linkText : link.text();
                if (!anchor.isBlank() && !display.isBlank()) {
                    Element a = new Element("a");
                    a.attr("href", "#" + anchor);
                    a.text(display);
                    link.replaceWith(a);
                } else {
                    link.replaceWith(new TextNode(display.isBlank() ? "" : display));
                }
            }
        }
    }

    // ── Emoticons ────────────────────────────────────────────────────

    private static void transformEmoticons(Document doc) {
        for (Element emoticon : doc.select("ac|emoticon")) {
            String name = emoticon.attr("ac:name");
            String emoji = emoticon.attr("ac:emoji-fallback");
            if (!emoji.isBlank()) {
                emoticon.replaceWith(new TextNode(emoji));
            } else {
                // Map common emoticon names to Unicode
                String mapped = mapEmoticonName(name);
                emoticon.replaceWith(new TextNode(mapped));
            }
        }
    }

    private static String mapEmoticonName(String name) {
        if (name == null) return "";
        return switch (name) {
            case "smile" -> "\u263A\uFE0F";
            case "sad" -> "\u2639\uFE0F";
            case "cheeky" -> "\uD83D\uDE1C";
            case "laugh" -> "\uD83D\uDE02";
            case "wink" -> "\uD83D\uDE09";
            case "thumbs-up" -> "\uD83D\uDC4D";
            case "thumbs-down" -> "\uD83D\uDC4E";
            case "information" -> "\u2139\uFE0F";
            case "tick" -> "\u2705";
            case "cross" -> "\u274C";
            case "warning" -> "\u26A0\uFE0F";
            case "plus" -> "\u2795";
            case "minus" -> "\u2796";
            case "question" -> "\u2753";
            case "light-on" -> "\uD83D\uDCA1";
            case "light-off" -> "\uD83D\uDD26";
            case "yellow-star" -> "\u2B50";
            case "red-star" -> "\u2B50";
            case "green-star" -> "\u2B50";
            case "blue-star" -> "\u2B50";
            case "heart" -> "\u2764\uFE0F";
            case "broken-heart" -> "\uD83D\uDC94";
            default -> "(" + name + ")";
        };
    }

    // ── Task Lists ───────────────────────────────────────────────────

    private static void transformTaskLists(Document doc) {
        for (Element taskList : doc.select("ac|task-list")) {
            Element ul = new Element("ul");
            for (Element task : taskList.select("ac|task")) {
                Element status = task.selectFirst("ac|task-status");
                Element body = task.selectFirst("ac|task-body");
                boolean checked = status != null && "complete".equals(status.text().trim());
                String bodyHtml = body != null ? body.html() : "";

                Element li = new Element("li");
                // Use checkbox markers that flexmark converts to task list items
                String prefix = checked ? "[x] " : "[ ] ";
                li.prepend(prefix);
                li.append(bodyHtml);
                ul.appendChild(li);
            }
            taskList.replaceWith(ul);
        }
    }

    // ── Layouts ──────────────────────────────────────────────────────

    private static void transformLayouts(Document doc) {
        // ac:layout → just unwrap, keep the cell content
        for (Element layout : doc.select("ac|layout")) {
            // Collect all cell content
            StringBuilder content = new StringBuilder();
            for (Element cell : layout.select("ac|layout-cell")) {
                content.append(cell.html());
            }
            layout.replaceWith(parseFragment(content.toString()));
        }

        // Clean up any remaining layout elements
        for (Element el : doc.select("ac|layout-section, ac|layout-cell")) {
            el.unwrap();
        }
    }

    // ── Placeholders ─────────────────────────────────────────────────

    private static void transformPlaceholders(Document doc) {
        for (Element placeholder : doc.select("ac|placeholder")) {
            String type = placeholder.attr("ac:type");
            placeholder.replaceWith(new TextNode("[" + (type.isBlank() ? "placeholder" : type) + "]"));
        }
    }

    // ── Strip remaining ac:/ri: tags ─────────────────────────────────

    private static void stripRemainingAcRi(Document doc) {
        // Unwrap any remaining ac: or ri: elements — preserve their text content
        for (Element el : doc.select("*")) {
            String tagName = el.tagName();
            if (tagName.startsWith("ac:") || tagName.startsWith("ri:")) {
                el.unwrap();
            }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────

    /** Get a macro parameter by name. */
    private static String getParam(Element macro, String paramName) {
        for (Element param : macro.select("ac|parameter")) {
            String name = param.attr("ac:name");
            if (paramName.equals(name) || (paramName.isEmpty() && name.isEmpty())) {
                return param.text();
            }
        }
        return null;
    }

    /** Get the plain-text-body (CDATA) content of a macro. */
    private static String getPlainTextBody(Element macro) {
        Element body = macro.selectFirst("ac|plain-text-body");
        if (body == null) return "";
        // jsoup strips CDATA wrapper, returns the content
        return body.wholeText();
    }

    /** Get the rich-text-body HTML content of a macro. */
    private static String getRichTextBody(Element macro) {
        Element body = macro.selectFirst("ac|rich-text-body");
        if (body == null) return "";
        return body.html();
    }

    /** Unwrap a macro element, keeping only its body content (rich or plain text). */
    private static void unwrapBody(Element macro) {
        Element richBody = macro.selectFirst("ac|rich-text-body");
        if (richBody != null) {
            macro.replaceWith(parseFragment(richBody.html()));
        } else {
            Element plainBody = macro.selectFirst("ac|plain-text-body");
            if (plainBody != null) {
                Element pre = new Element("pre");
                pre.text(plainBody.wholeText());
                macro.replaceWith(pre);
            } else {
                macro.remove();
            }
        }
    }

    /** Parse an HTML fragment into a node that can replace another node. */
    private static Node parseFragment(String html) {
        Document fragment = Jsoup.parseBodyFragment(html);
        Element body = fragment.body();
        if (body.childNodeSize() == 1) {
            return body.childNode(0).clone();
        }
        // Multiple nodes — wrap in a span to return as single node
        Element wrapper = new Element("span");
        for (Node child : body.childNodes()) {
            wrapper.appendChild(child.clone());
        }
        return wrapper;
    }

    private static String escapeHtml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
