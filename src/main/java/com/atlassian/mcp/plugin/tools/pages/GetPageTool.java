package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

public class GetPageTool implements McpTool {
    private final ConfluenceRestClient client;

    public GetPageTool(ConfluenceRestClient client) {
        this.client = client;
    }

    @Override public String name() { return "get_page"; }

    @Override
    public String description() {
        return "Get content of a specific Confluence page by its ID, or by its title and space key.";
    }

    @Override
    public Map<String, Object> inputSchema() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "page_id", Map.of("type", "string", "description", "Confluence page ID (numeric ID, can be found in the page URL). For example, in the URL 'https://example.atlassian.net/wiki/spaces/TEAM/pages/123456789/Page+Title', the page ID is '123456789'. Provide this OR both 'title' and 'space_key'. If page_id is provided, title and space_key will be ignored."),
                        "title", Map.of("type", "string", "description", "The exact title of the Confluence page. Use this with 'space_key' if 'page_id' is not known."),
                        "space_key", Map.of("type", "string", "description", "The key of the Confluence space where the page resides (e.g., 'DEV', 'TEAM'). Required if using 'title'."),
                        "include_metadata", Map.of("type", "boolean", "description", "Whether to include page metadata such as creation date, last update, version, and labels.", "default", true),
                        "convert_to_markdown", Map.of("type", "boolean", "description", "Whether to convert page to markdown (true) or keep it in raw HTML format (false). Raw HTML can reveal macros (like dates) not visible in markdown, but CAUTION: using HTML significantly increases token usage in AI responses.", "default", true)
                ),
                "required", List.of()
        );
    }

    @Override public boolean isWriteTool() { return false; }

    @Override
    public String execute(Map<String, Object> args, String authHeader) throws McpToolException {
        String pageId = (String) args.get("page_id");
        String title = (String) args.get("title");
        String spaceKey = (String) args.get("space_key");

        String expand = "body.storage,version,space,ancestors,metadata.labels";

        if (pageId != null && !pageId.isBlank()) {
            return client.get("/rest/api/content/" + pageId + "?expand=" + encode(expand), authHeader);
        }

        if (title != null && !title.isBlank() && spaceKey != null && !spaceKey.isBlank()) {
            return client.get("/rest/api/content?title=" + encode(title)
                    + "&spaceKey=" + encode(spaceKey)
                    + "&expand=" + encode(expand), authHeader);
        }

        throw new McpToolException("Either 'page_id' or both 'title' and 'space_key' are required");
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static boolean getBoolean(Map<String, Object> args, String key, boolean defaultVal) {
        Object val = args.get(key);
        if (val instanceof Boolean b) return b;
        if (val instanceof String s) return "true".equalsIgnoreCase(s);
        return defaultVal;
    }
}
