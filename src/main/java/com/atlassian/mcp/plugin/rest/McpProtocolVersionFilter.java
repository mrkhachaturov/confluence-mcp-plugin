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
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enforces the MCP spec requirement (2025-06-18 transports §"Protocol Version Header"): a request
 * carrying an {@code MCP-Protocol-Version} header that is not a version this server supports MUST
 * be rejected with {@code 400 Bad Request}.
 *
 * <p>The MCP Java SDK transport does NOT validate this header (verified against {@code
 * HttpServletStreamableServerTransportProvider}), so it is enforced here as a discrete filter ahead
 * of the transport. A missing header is tolerated (the spec lets the server assume a default for
 * back-compat); only a <em>present-and-unsupported</em> value is rejected.
 *
 * <p>The supported set is the one the transport negotiates, {@code
 * HttpServletStreamableServerTransportProvider#protocolVersions()}. The provider only reports it
 * per instance, so the set is restated here from the SDK's {@link ProtocolVersions} constants and a
 * unit test holds the two equal.
 */
@UnrestrictedAccess
@Named("mcpProtocolVersionFilter")
public class McpProtocolVersionFilter implements Filter {

  static final List<String> SUPPORTED =
      List.of(
          ProtocolVersions.MCP_2025_03_26,
          ProtocolVersions.MCP_2025_06_18,
          ProtocolVersions.MCP_2025_11_25);

  private static final String REJECTION_BODY =
      SUPPORTED.stream()
          .map(v -> "\"" + v + "\"")
          .collect(
              Collectors.joining(
                  ",", "{\"error\":\"Unsupported MCP-Protocol-Version\",\"supported\":[", "]}"));

  @Override
  public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
      throws IOException, ServletException {
    HttpServletRequest httpReq = (HttpServletRequest) req;
    HttpServletResponse httpResp = (HttpServletResponse) resp;

    String version = httpReq.getHeader("MCP-Protocol-Version");
    if (version != null && !version.isBlank() && !SUPPORTED.contains(version.trim())) {
      httpResp.setContentType("application/json");
      httpResp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      httpResp.getWriter().write(REJECTION_BODY);
      return;
    }
    chain.doFilter(req, resp);
  }
}
