package com.atlassian.mcp.plugin.tools.pages;

import com.atlassian.mcp.plugin.ConfluenceRestClient;
import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared by the tools that edit part of a page rather than replacing it: each has to read the
 * current body, title and version before it can write a new one, and each offers the caller the
 * same optimistic-locking check against the version it last saw.
 */
final class PageEdit {

  record Current(String title, int version, String body) {}

  private PageEdit() {}

  static Current read(
      ConfluenceRestClient client, ObjectMapper mapper, String pageId, McpContext context)
      throws McpToolException {
    try {
      String json =
          client.getRaw(
              "/rest/api/content/" + pageId + "?expand=body.storage,version", context.authHeader());
      JsonNode parsed = mapper.readTree(json);
      return new Current(
          parsed.path("title").asText(""),
          parsed.path("version").path("number").asInt(0),
          parsed.path("body").path("storage").path("value").asText(""));
    } catch (Exception e) {
      throw new McpToolException("Failed to fetch current page: " + e.getMessage());
    }
  }

  static void checkExpectedVersion(Current current, Integer expectedVersion)
      throws McpToolException {
    if (expectedVersion == null || expectedVersion == current.version()) {
      return;
    }
    throw new McpToolException(
        "Page was modified since you last read it (current version: "
            + current.version()
            + ", expected: "
            + expectedVersion
            + "). Re-read the page with get_page before updating.");
  }
}
