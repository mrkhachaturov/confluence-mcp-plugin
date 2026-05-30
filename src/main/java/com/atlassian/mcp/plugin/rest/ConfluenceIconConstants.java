package com.atlassian.mcp.plugin.rest;

/** Server-level icon (Confluence wordmark) as an SVG data URI, used in the MCP server identity. */
public final class ConfluenceIconConstants {

    private ConfluenceIconConstants() {}

    /** Minimal Confluence-blue mark; replace with the official wordmark SVG if/when licensed. */
    public static final String CONFLUENCE_LOGO_DATA_URI =
            "data:image/svg+xml;base64,"
            + "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAzMiAzMiI+"
            + "PHJlY3Qgd2lkdGg9IjMyIiBoZWlnaHQ9IjMyIiByeD0iNiIgZmlsbD0iIzE3NkJGRiIvPjx0ZXh0IHg9"
            + "IjE2IiB5PSIyMSIgZm9udC1mYW1pbHk9IkFyaWFsIiBmb250LXNpemU9IjE2IiBmaWxsPSIjZmZmIiB0"
            + "ZXh0LWFuY2hvcj0ibWlkZGxlIj5DPC90ZXh0Pjwvc3ZnPg==";
}
