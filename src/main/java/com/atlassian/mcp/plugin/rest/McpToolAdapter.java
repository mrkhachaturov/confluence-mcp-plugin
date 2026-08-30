package com.atlassian.mcp.plugin.rest;

import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts our internal {@link McpTool} interface to the MCP SDK's {@link
 * McpServerFeatures.SyncToolSpecification}: builds tool annotations, injects the JSON Schema
 * 2020-12 dialect, and dispatches {@code tools/call} into the tool, reading the auth header off the
 * per-request transport context.
 *
 * <p>No {@code outputSchema} / {@code structuredContent} — that is the deferred widget data layer
 * (spec §3, §6.4).
 */
public final class McpToolAdapter {

  private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

  /** Per MCP 2025-11-25 (SEP-1613), the spec defaults to JSON Schema 2020-12. */
  private static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

  private McpToolAdapter() {}

  /** Inject {@code "$schema": "<2020-12 URI>"} at the front of a tool's input schema if absent. */
  private static Map<String, Object> withSchemaDialect(Map<String, Object> raw) {
    if (raw == null || raw.containsKey("$schema")) return raw;
    Map<String, Object> copy = new LinkedHashMap<>(raw.size() + 1);
    copy.put("$schema", JSON_SCHEMA_2020_12);
    copy.putAll(raw);
    return copy;
  }

  /** Build a {@link McpServerFeatures.SyncToolSpecification} from an internal {@link McpTool}. */
  public static McpServerFeatures.SyncToolSpecification adapt(
      McpTool tool, com.atlassian.mcp.plugin.config.McpPluginConfig config) {
    McpSchema.ToolAnnotations annotations =
        McpSchema.ToolAnnotations.builder()
            .title(tool.title())
            .readOnlyHint(!tool.isWriteTool())
            .destructiveHint(tool.isDestructiveTool())
            .idempotentHint(tool.idempotentHint())
            .openWorldHint(tool.openWorldHint())
            .build();

    McpSchema.Tool schemaTool =
        McpSchema.Tool.builder(tool.name(), withSchemaDialect(tool.inputSchema()))
            .title(tool.title())
            .description(tool.description())
            .annotations(annotations)
            .build();

    return McpServerFeatures.SyncToolSpecification.builder()
        .tool(schemaTool)
        .callHandler((exchange, request) -> dispatch(tool, config, exchange, request))
        .build();
  }

  private static McpSchema.CallToolResult dispatch(
      McpTool tool,
      com.atlassian.mcp.plugin.config.McpPluginConfig config,
      McpSyncServerExchange exchange,
      McpSchema.CallToolRequest request) {
    // Call-time guard: the SDK sync server's tool list is frozen at filter init
    // (McpBootstrap.buildTransport -> ToolRegistry.toSpecifications). Re-check admin
    // config here so runtime toggles of readOnlyMode / disabledTools (via the admin page
    // or ConfigResource) block write/disabled tools immediately, without a plugin reload —
    // restoring the per-request enforcement the old McpResource provided.
    if (!config.isToolEnabled(tool.name())) {
      return McpSchema.CallToolResult.builder()
          .addTextContent("Error: tool '" + tool.name() + "' is disabled by the administrator")
          .isError(Boolean.TRUE)
          .build();
    }
    if (config.isReadOnlyMode() && tool.isWriteTool()) {
      return McpSchema.CallToolResult.builder()
          .addTextContent(
              "Error: server is in read-only mode; write tool '"
                  + tool.name()
                  + "' is not available")
          .isError(Boolean.TRUE)
          .build();
    }

    String authHeader = readContext(exchange, ConfluenceAuthContextExtractor.CTX_AUTH_HEADER);
    Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
    Object progressToken = extractProgressToken(request);

    try {
      String resultText = tool.executeWithSdkProgress(args, authHeader, exchange, progressToken);
      return McpSchema.CallToolResult.builder()
          .addTextContent(resultText)
          .isError(Boolean.FALSE)
          .build();
    } catch (McpToolException e) {
      log.debug("[MCP] tool '{}' failed: {}", tool.name(), e.getMessage());
      return McpSchema.CallToolResult.builder()
          .addTextContent("Error: " + e.getMessage())
          .isError(Boolean.TRUE)
          .build();
    } catch (RuntimeException e) {
      log.warn("[MCP] tool '{}' threw unexpectedly", tool.name(), e);
      return McpSchema.CallToolResult.builder()
          .addTextContent("Internal error: " + e.getMessage())
          .isError(Boolean.TRUE)
          .build();
    }
  }

  private static Object extractProgressToken(McpSchema.CallToolRequest request) {
    try {
      Map<String, Object> meta = request.meta();
      return meta == null ? null : meta.get("progressToken");
    } catch (Exception ignored) {
      return null;
    }
  }

  private static String readContext(McpSyncServerExchange exchange, String key) {
    try {
      Object v = exchange.transportContext().get(key);
      return v instanceof String s ? s : null;
    } catch (Exception ignored) {
      return null;
    }
  }
}
