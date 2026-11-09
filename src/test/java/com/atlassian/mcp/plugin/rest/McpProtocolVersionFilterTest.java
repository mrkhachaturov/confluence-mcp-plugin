package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Set;
import org.junit.Test;

public class McpProtocolVersionFilterTest {

  private final McpProtocolVersionFilter filter = new McpProtocolVersionFilter();

  @Test
  public void missingHeaderPassesThrough() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("MCP-Protocol-Version")).thenReturn(null);

    filter.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(400);
  }

  @Test
  public void supportedVersionPassesThrough() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("MCP-Protocol-Version")).thenReturn("2025-06-18");

    filter.doFilter(req, resp, chain);

    verify(chain).doFilter(req, resp);
    verify(resp, never()).setStatus(400);
  }

  @Test
  public void unsupportedVersionReturns400AndStopsChain() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("MCP-Protocol-Version")).thenReturn("1999-01-01");
    StringWriter sw = new StringWriter();
    when(resp.getWriter()).thenReturn(new PrintWriter(sw));

    filter.doFilter(req, resp, chain);

    verify(resp).setStatus(400);
    verify(chain, never()).doFilter(any(), any());
    assertTrue(
        "body must explain the rejection",
        sw.toString().contains("Unsupported MCP-Protocol-Version"));
    assertTrue("body must list what is supported", sw.toString().contains("\"2025-11-25\""));
  }

  /** The streamable transport stopped negotiating 2024-11-05, so its header is now refused. */
  @Test
  public void versionTheTransportNoLongerNegotiatesReturns400() throws Exception {
    HttpServletRequest req = mock(HttpServletRequest.class);
    HttpServletResponse resp = mock(HttpServletResponse.class);
    FilterChain chain = mock(FilterChain.class);
    when(req.getHeader("MCP-Protocol-Version")).thenReturn("2024-11-05");
    when(resp.getWriter()).thenReturn(new PrintWriter(new StringWriter()));

    filter.doFilter(req, resp, chain);

    verify(resp).setStatus(400);
    verify(chain, never()).doFilter(any(), any());
  }

  /**
   * The filter accepts exactly the versions the transport negotiates. Accepting more lets a client
   * carry a header for a version no session can have; accepting fewer refuses a valid client.
   */
  @Test
  public void supportedSetMatchesTheTransport() {
    PluginSchedulers.install();
    var transport =
        HttpServletStreamableServerTransportProvider.builder()
            .jsonMapper(new JacksonMcpJsonMapper(new ObjectMapper()))
            .build();
    try {
      assertEquals(
          Set.copyOf(transport.protocolVersions()), Set.copyOf(McpProtocolVersionFilter.SUPPORTED));
    } finally {
      transport.closeGracefully().block();
      PluginSchedulers.shutdown();
    }
  }
}
