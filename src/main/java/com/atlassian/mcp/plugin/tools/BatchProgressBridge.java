package com.atlassian.mcp.plugin.tools;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * Bridges {@link McpTool.ProgressCallback} onto the MCP SDK's {@link
 * McpSyncServerExchange#progressNotification(McpSchema.ProgressNotification)} channel.
 *
 * <p>When the client did not supply a {@code progressToken} (or the exchange is unavailable), the
 * returned callback is a no-op — batch tools still run, they just don't emit progress
 * notifications.
 */
public final class BatchProgressBridge {

  private BatchProgressBridge() {}

  public static McpTool.ProgressCallback bridge(
      McpSyncServerExchange exchange, Object progressToken) {
    if (exchange == null || progressToken == null) {
      return (current, total, message) -> {};
    }
    return (current, total, message) -> {
      try {
        exchange.progressNotification(
            new McpSchema.ProgressNotification(
                progressToken, (double) current, total > 0 ? (double) total : null, message, null));
      } catch (Exception ignored) {
        // Best-effort; never fail the batch over a notification error.
      }
    };
  }
}
