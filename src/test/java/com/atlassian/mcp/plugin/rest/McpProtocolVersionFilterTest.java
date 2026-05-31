package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
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
        assertTrue("body must explain the rejection",
                sw.toString().contains("Unsupported MCP-Protocol-Version"));
    }
}
