package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.ToolRegistry;
import com.atlassian.sal.api.ApplicationProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpError;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * The plugin is disabled and enabled inside a running Confluence, so every thread the transport
 * starts must end when the plugin stops. A thread that outlives the plugin keeps its class loader
 * alive and, being a non-daemon thread, can hold up Confluence's own shutdown.
 */
public class McpBootstrapTransportTest {

  private static final String SWEEPER_THREAD = "streamable-http-server-transport-session-sweeper";

  @Test
  public void stoppingThePluginEndsTheSessionSweeper() throws Exception {
    Set<Thread> before = sweeperThreads();
    McpBootstrap bootstrap = bootstrap();

    HttpServlet transport = bootstrap.buildTransport();
    assertFalse("the sweeper should be running", newThreads(before).isEmpty());

    // What McpTransportFilter.destroy() does when the plugin is disabled.
    transport.destroy();
    bootstrap.close();

    long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!newThreads(before).isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(50);
    }
    assertTrue("sweeper threads still alive: " + newThreads(before), newThreads(before).isEmpty());
  }

  /** A refused request's body is the JSON-RPC error, not the serialized exception behind it. */
  @Test
  public void refusedRequestBodyCarriesNoStackTrace() throws Exception {
    McpBootstrap bootstrap = bootstrap();
    var transport = (HttpServletStreamableServerTransportProvider) bootstrap.buildTransport();
    try {
      HttpServletResponse resp = mock(HttpServletResponse.class);
      StringWriter body = new StringWriter();
      when(resp.getWriter()).thenReturn(new PrintWriter(body));

      transport.responseError(
          resp,
          415,
          McpError.builder(McpSchema.ErrorCodes.INVALID_REQUEST).message("Unsupported").build());

      JsonNode json = new ObjectMapper().readTree(body.toString());
      assertTrue(body.toString(), json.has("jsonRpcError"));
      for (String leaked : List.of("stackTrace", "cause", "suppressed")) {
        assertFalse("body leaks '" + leaked + "': " + body, json.has(leaked));
      }
    } finally {
      transport.destroy();
      bootstrap.close();
    }
  }

  private static McpBootstrap bootstrap() {
    ToolRegistry registry = mock(ToolRegistry.class);
    when(registry.toSpecifications()).thenReturn(List.of());
    McpPluginConfig config = mock(McpPluginConfig.class);
    when(config.getConfluenceBaseUrlOverride()).thenReturn("https://confluence.example.com");
    return new McpBootstrap(
        registry,
        config,
        mock(ConfluenceAuthContextExtractor.class),
        mock(ApplicationProperties.class));
  }

  private static Set<Thread> sweeperThreads() {
    return Thread.getAllStackTraces().keySet().stream()
        .filter(Thread::isAlive)
        .filter(t -> t.getName().startsWith(SWEEPER_THREAD))
        .collect(Collectors.toSet());
  }

  private static List<String> newThreads(Set<Thread> before) {
    return sweeperThreads().stream()
        .filter(t -> !before.contains(t))
        .map(t -> t.getName() + (t.isDaemon() ? " (daemon)" : " (non-daemon)"))
        .toList();
  }
}
