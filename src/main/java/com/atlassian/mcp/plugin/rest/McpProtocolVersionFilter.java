package com.atlassian.mcp.plugin.rest;

import com.atlassian.annotations.security.UnrestrictedAccess;
import io.modelcontextprotocol.spec.ProtocolVersions;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

/**
 * Enforces the MCP spec requirement (2025-06-18 transports §"Protocol Version Header"): a request
 * carrying an {@code MCP-Protocol-Version} header that is not a version this server supports MUST
 * be rejected with {@code 400 Bad Request}.
 *
 * <p>The MCP Java SDK transport does NOT validate this header (verified against
 * {@code HttpServletStreamableServerTransportProvider}), so it is enforced here as a discrete
 * filter ahead of the transport. A missing header is tolerated (the spec lets the server assume a
 * default for back-compat); only a <em>present-and-unsupported</em> value is rejected. The
 * supported set is taken from the SDK's own {@link ProtocolVersions} constants so it cannot drift.
 */
@UnrestrictedAccess
@Named("mcpProtocolVersionFilter")
public class McpProtocolVersionFilter implements Filter {

    private static final Set<String> SUPPORTED = Set.of(
            ProtocolVersions.MCP_2024_11_05,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18,
            ProtocolVersions.MCP_2025_11_25);

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        String version = httpReq.getHeader("MCP-Protocol-Version");
        if (version != null && !version.isBlank() && !SUPPORTED.contains(version.trim())) {
            httpResp.setContentType("application/json");
            httpResp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
            httpResp.getWriter().write(
                    "{\"error\":\"Unsupported MCP-Protocol-Version\",\"supported\":["
                    + "\"2024-11-05\",\"2025-03-26\",\"2025-06-18\",\"2025-11-25\"]}");
            return;
        }
        chain.doFilter(req, resp);
    }
}
