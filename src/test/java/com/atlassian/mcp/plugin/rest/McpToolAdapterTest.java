package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.McpTool;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.Map;
import org.junit.Test;

public class McpToolAdapterTest {

  /**
   * An unexpected exception's message is for the log, not the caller: here it names an internal
   * address, and the result must not.
   */
  @Test
  public void unexpectedExceptionDoesNotReachTheCaller() throws Exception {
    McpTool tool = tool();
    when(tool.executeWithSdkProgress(any(), any(), any(), any()))
        .thenThrow(new IllegalStateException("GET http://10.0.0.5:8090/rest/api/content failed"));

    McpSchema.CallToolResult result = call(tool);

    assertTrue(result.isError());
    String text = text(result);
    assertFalse("leaked: " + text, text.contains("10.0.0.5"));
    assertTrue(text.contains("get_page"));
  }

  /** A tool's own failure is written for the caller and still reaches it verbatim. */
  @Test
  public void toolExceptionMessageStillReachesTheCaller() throws Exception {
    McpTool tool = tool();
    when(tool.executeWithSdkProgress(any(), any(), any(), any()))
        .thenThrow(new McpToolException("Page 42 not found"));

    McpSchema.CallToolResult result = call(tool);

    assertTrue(result.isError());
    assertEquals("Error: Page 42 not found", text(result));
  }

  private static McpTool tool() {
    McpTool tool = mock(McpTool.class);
    when(tool.name()).thenReturn("get_page");
    when(tool.description()).thenReturn("Get a page");
    when(tool.inputSchema()).thenReturn(Map.of("type", "object"));
    return tool;
  }

  private static McpSchema.CallToolResult call(McpTool tool) {
    McpPluginConfig config = mock(McpPluginConfig.class);
    when(config.isToolEnabled("get_page")).thenReturn(true);
    var spec = McpToolAdapter.adapt(tool, config);
    return spec.callHandler()
        .apply(
            mock(McpSyncServerExchange.class),
            McpSchema.CallToolRequest.builder("get_page").arguments(Map.of()).build());
  }

  private static String text(McpSchema.CallToolResult result) {
    return ((McpSchema.TextContent) result.content().get(0)).text();
  }
}
