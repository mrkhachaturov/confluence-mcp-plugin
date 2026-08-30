package com.atlassian.mcp.plugin.config;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSRF-hardening checks for the admin-configurable {@code confluenceBaseUrl} override.
 *
 * <p>The override is forwarded to {@link com.atlassian.mcp.plugin.ConfluenceRestClient}, which
 * appends every tool's path AND the caller's own {@code Authorization} header. An unvalidated
 * override therefore turns the whole plugin into an authenticated request proxy to an arbitrary
 * host (credential exfiltration + internal SSRF). These checks bound that risk; the admin REST is
 * also CSRF-protected so the override can no longer be set by a forged cross-site request.
 *
 * <p>Note: RFC 1918 / private addresses are intentionally ALLOWED — a legitimate internal
 * Confluence base URL is commonly a private address. Loopback, link-local, any-local, and the
 * cloud-metadata IP (169.254.169.254) are always rejected.
 */
public final class UrlSafety {

  private static final Logger log = LoggerFactory.getLogger(UrlSafety.class);

  private UrlSafety() {}

  /**
   * Read-time, no-DNS guard. http/https only, must have a host, no embedded credentials, and the
   * host must not be a loopback/metadata literal. Cheap enough to run on every request.
   */
  public static boolean isSyntacticallySafeBaseUrl(String url) {
    if (url == null || url.isBlank()) {
      return false;
    }
    URI u;
    try {
      u = new URI(url.trim());
    } catch (Exception e) {
      return false;
    }
    String scheme = u.getScheme();
    String host = u.getHost();
    if (scheme == null || host == null) {
      return false;
    }
    if (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https")) {
      return false;
    }
    if (u.getUserInfo() != null) {
      return false; // reject embedded credentials (user:pass@host)
    }
    String h = host.toLowerCase();
    if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1")) {
      return false;
    }
    return !h.equals("169.254.169.254");
  }

  /**
   * Admin set-time guard: syntactic check plus DNS resolution that rejects loopback, link-local,
   * any-local, and the cloud-metadata address. Returns false if the host does not resolve (an admin
   * can correct it and retry).
   */
  public static boolean isSafeBaseUrlForSet(String url) {
    if (!isSyntacticallySafeBaseUrl(url)) {
      return false;
    }
    try {
      URI u = new URI(url.trim());
      InetAddress[] addrs = InetAddress.getAllByName(u.getHost());
      for (InetAddress a : addrs) {
        if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isAnyLocalAddress()) {
          return false;
        }
        byte[] b = a.getAddress();
        if (b.length == 4
            && (b[0] & 0xFF) == 169
            && (b[1] & 0xFF) == 254
            && (b[2] & 0xFF) == 169
            && (b[3] & 0xFF) == 254) {
          return false; // 169.254.169.254 cloud-metadata
        }
      }
      return true;
    } catch (UnknownHostException e) {
      return false;
    } catch (Exception e) {
      log.warn("[MCP-SEC] base URL override validation error", e);
      return false;
    }
  }
}
