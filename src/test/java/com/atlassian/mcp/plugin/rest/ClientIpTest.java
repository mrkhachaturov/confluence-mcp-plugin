package com.atlassian.mcp.plugin.rest;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.Test;

public class ClientIpTest {

    @Test
    public void honoursXffOnlyFromLoopbackPeer() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("127.0.0.1");
        when(req.getHeader("X-Forwarded-For")).thenReturn("203.0.113.7, 10.0.0.1");
        assertEquals("203.0.113.7", ClientIp.resolve(req));
    }

    @Test
    public void ignoresSpoofedXffFromNonLoopbackPeer() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("203.0.113.9");
        when(req.getHeader("X-Forwarded-For")).thenReturn("1.2.3.4");
        assertEquals("real socket address wins over a spoofed XFF",
                "203.0.113.9", ClientIp.resolve(req));
    }

    @Test
    public void usesRemoteAddrWhenNoXff() {
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRemoteAddr()).thenReturn("198.51.100.2");
        when(req.getHeader("X-Forwarded-For")).thenReturn(null);
        assertEquals("198.51.100.2", ClientIp.resolve(req));
    }
}
