package com.atlassian.mcp.plugin.tools;

import com.atlassian.mcp.plugin.McpToolException;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface McpTool {

  /** Tool name matching upstream mcp-atlassian. */
  String name();

  /** Human-readable description. */
  String description();

  /** JSON Schema for the tool's input parameters. */
  Map<String, Object> inputSchema();

  /** True if this tool modifies data (create, update, delete, transition). */
  boolean isWriteTool();

  /**
   * Optional: plugin key required for this tool to work. Return null if no specific plugin is
   * required (works on all Jira instances).
   */
  default String requiredPluginKey() {
    return null;
  }

  /**
   * Execute the tool with the given arguments.
   *
   * @param args parsed JSON arguments from the MCP client
   * @param authHeader the user's Authorization header (forwarded to Jira REST API)
   * @return JSON string result
   */
  String execute(Map<String, Object> args, String authHeader) throws McpToolException;

  // ── MCP SDK annotation hints (spec §6.4) — sane defaults so existing tools need no edits ──

  /** Display title for the tool annotation. Null = SDK omits it. */
  default String title() {
    return null;
  }

  /**
   * True only for tools that DELETE or OVERWRITE existing content. Overridden by delete_page,
   * delete_attachment, update_page, replace_section (spec §6.4). Additive writes (create_page,
   * add_comment, append_to_page, ...) stay false. A false hint lets a client skip confirmation on a
   * content-replacing write, so this is per-tool.
   */
  default boolean isDestructiveTool() {
    return false;
  }

  /** Idempotent unless the tool writes. */
  default boolean idempotentHint() {
    return !isWriteTool();
  }

  /** Every tool calls the Confluence REST API, so the world is "open". */
  default boolean openWorldHint() {
    return true;
  }

  /**
   * SDK-progress entry point. Defaults to {@link #execute(Map, String)}; batch tools may override
   * to emit progress via the exchange. {@code exchange} and {@code progressToken} are provided by
   * the SDK transport (spec §6.4).
   */
  default String executeWithSdkProgress(
      Map<String, Object> args,
      String authHeader,
      McpSyncServerExchange exchange,
      Object progressToken)
      throws McpToolException {
    return execute(args, authHeader);
  }

  /**
   * Whether this tool supports streaming execution with progress notifications. Override and return
   * true in batch tools that process multiple items.
   */
  default boolean supportsProgress() {
    return false;
  }

  /**
   * Execute the tool with progress reporting. Called instead of execute() when the client sends a
   * progressToken and supportsProgress() is true.
   *
   * @param args parsed JSON arguments
   * @param authHeader Authorization header
   * @param progress callback to report progress during execution
   * @return JSON string result (same as execute)
   */
  default String executeWithProgress(
      Map<String, Object> args, String authHeader, ProgressCallback progress)
      throws McpToolException {
    return execute(args, authHeader);
  }

  /**
   * Resolve a page_id parameter that may be a numeric ID or a Confluence URL. Extracts the page ID
   * from known URL patterns: - /pages/viewpage.action?pageId=123456 -
   * /spaces/KEY/pages/123456/Title - /wiki/spaces/KEY/pages/123456/Title Returns the input
   * unchanged if it's already a numeric string.
   */
  Pattern PAGE_ID_FROM_QUERY = Pattern.compile("[?&]pageId=(\\d+)");

  Pattern PAGE_ID_FROM_PATH = Pattern.compile("/pages/(\\d+)");

  static String resolvePageId(String input) throws McpToolException {
    if (input == null || input.isBlank()) return input;

    // Already a numeric ID
    if (input.matches("\\d+")) return input;

    // Try URL patterns
    if (input.startsWith("http://") || input.startsWith("https://")) {
      Matcher m = PAGE_ID_FROM_QUERY.matcher(input);
      if (m.find()) return m.group(1);

      m = PAGE_ID_FROM_PATH.matcher(input);
      if (m.find()) return m.group(1);

      // /display/KEY/Title format — can't resolve without API lookup
      throw new McpToolException(
          "Cannot extract page ID from URL: "
              + input
              + ". Use a URL with pageId= parameter or /pages/ID/ path, "
              + "or provide the numeric page ID directly.");
    }

    // Not a URL and not numeric — return as-is (Confluence will error if invalid)
    return input;
  }

  /** Callback for reporting progress during streaming execution. */
  @FunctionalInterface
  interface ProgressCallback {
    /**
     * @param current items processed so far
     * @param total total items (or -1 if unknown)
     * @param message human-readable status message
     */
    void report(int current, int total, String message);
  }
}
