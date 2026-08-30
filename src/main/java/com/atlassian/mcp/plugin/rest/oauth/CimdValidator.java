package com.atlassian.mcp.plugin.rest.oauth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Resolves and validates OAuth Client ID Metadata Documents (CIMD) per
 * draft-ietf-oauth-client-id-metadata-document-00 and MCP 2025-11-25 (SEP-991).
 *
 * <p>The client_id URL is attacker-controlled and fetched from inside the Confluence JVM by an
 * UNAUTHENTICATED authorize request, so this validator enforces (spec §6.5):
 *
 * <ul>
 *   <li><b>SSRF defense</b>: HTTPS-only, no redirects (client built with {@code Redirect.NEVER});
 *       host IPs are resolved and rejected if loopback/link-local/private/CGNAT/ULA/metadata BEFORE
 *       the fetch. NOTE: the JDK HttpClient re-resolves DNS at connect time, so this blocks static
 *       internal-IP DNS records but does NOT fully defeat an active DNS-rebinding attacker who
 *       flips the record between our check and the connect. Accepted residual risk (the /authorize
 *       entry point is rate-limited); full closure would require connection-level IP pinning. 8 KB
 *       body cap enforced while STREAMING; 5 s connect / 10 s request timeout.
 *   <li><b>Bounded cache</b>: hard entry cap, short positive TTL, bounded negative caching.
 * </ul>
 */
public final class CimdValidator {

  private static final Logger log = LoggerFactory.getLogger(CimdValidator.class);

  public static final int MAX_BODY_BYTES = 8 * 1024;
  private static final Duration POSITIVE_TTL = Duration.ofHours(1);
  private static final Duration NEGATIVE_TTL = Duration.ofMinutes(5);
  private static final int CACHE_MAX = 1000;

  private final HttpClient httpClient;
  private final ObjectMapper mapper;
  private final boolean enforceSsrfGuards;
  private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

  public CimdValidator() {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build(),
        new ObjectMapper(),
        true);
  }

  /** Test constructor — inject a stub client and disable IP guards for stub hosts if needed. */
  public CimdValidator(HttpClient httpClient, ObjectMapper mapper, boolean enforceSsrfGuards) {
    this.httpClient = httpClient;
    this.mapper = mapper;
    this.enforceSsrfGuards = enforceSsrfGuards;
  }

  /** True if client_id is a CIMD-style URL (HTTPS with a non-root path). */
  public static boolean isCimdClientId(String clientId) {
    if (clientId == null || !clientId.startsWith("https://")) {
      return false;
    }
    try {
      URI uri = URI.create(clientId);
      String path = uri.getPath();
      return uri.getHost() != null && path != null && !path.isEmpty() && !path.equals("/");
    } catch (IllegalArgumentException e) {
      return false;
    }
  }

  /** Fetch + validate the CIMD; cached (positive and negative). */
  public CimdMetadata resolve(String clientIdUrl) throws CimdException {
    if (!isCimdClientId(clientIdUrl)) {
      throw new CimdException("client_id is not a valid CIMD URL");
    }
    CacheEntry cached = cache.get(clientIdUrl);
    if (cached != null && cached.expiresAt.isAfter(Instant.now())) {
      if (cached.failure != null) {
        throw new CimdException("cached failure: " + cached.failure);
      }
      return cached.metadata;
    }
    try {
      CimdMetadata metadata = fetchAndValidate(clientIdUrl);
      putPositive(clientIdUrl, metadata);
      return metadata;
    } catch (CimdException e) {
      putNegative(clientIdUrl, e.getMessage());
      throw e;
    }
  }

  private CimdMetadata fetchAndValidate(String clientIdUrl) throws CimdException {
    URI uri;
    try {
      uri = new URI(clientIdUrl);
    } catch (URISyntaxException e) {
      throw new CimdException("Invalid CIMD URL: " + e.getMessage());
    }
    if (!"https".equalsIgnoreCase(uri.getScheme())) {
      throw new CimdException("CIMD URL must be https");
    }
    guardSsrf(uri.getHost());

    HttpRequest request =
        HttpRequest.newBuilder()
            .uri(uri)
            .header("Accept", "application/json")
            .timeout(Duration.ofSeconds(10))
            .GET()
            .build();

    HttpResponse<java.io.InputStream> response;
    try {
      response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
    } catch (IOException | InterruptedException e) {
      if (e instanceof InterruptedException) Thread.currentThread().interrupt();
      throw new CimdException("Failed to fetch CIMD: " + e.getMessage());
    }
    if (response.statusCode() != 200) {
      throw new CimdException("CIMD fetch returned HTTP " + response.statusCode());
    }
    byte[] body = readBounded(response.body(), MAX_BODY_BYTES);
    if (body == null) {
      throw new CimdException("CIMD exceeds " + MAX_BODY_BYTES + " byte cap");
    }

    JsonNode root;
    try {
      root = mapper.readTree(body);
    } catch (IOException e) {
      throw new CimdException("CIMD is not valid JSON: " + e.getMessage());
    }
    if (root == null || !root.isObject()) {
      throw new CimdException("CIMD must be a JSON object");
    }
    JsonNode docClientId = root.get("client_id");
    if (docClientId != null
        && docClientId.isTextual()
        && !clientIdUrl.equals(docClientId.asText())) {
      throw new CimdException("CIMD client_id does not match the fetched URL");
    }
    JsonNode redirectUrisNode = root.get("redirect_uris");
    if (redirectUrisNode == null || !redirectUrisNode.isArray() || redirectUrisNode.size() == 0) {
      throw new CimdException("CIMD redirect_uris is required and must be a non-empty array");
    }
    List<String> redirectUris = new ArrayList<>();
    Iterator<JsonNode> it = redirectUrisNode.elements();
    while (it.hasNext()) {
      JsonNode n = it.next();
      if (!n.isTextual()) {
        throw new CimdException("CIMD redirect_uris entries must be strings");
      }
      String uriStr = n.asText();
      if (!isAllowedRedirectUri(uriStr)) {
        throw new CimdException("CIMD redirect_uri must be https:// or http://localhost|127.0.0.1");
      }
      redirectUris.add(uriStr);
    }
    return new CimdMetadata(
        clientIdUrl,
        textOrNull(root, "client_name"),
        Collections.unmodifiableList(redirectUris),
        textOrNull(root, "scope"),
        textOrNull(root, "token_endpoint_auth_method"));
  }

  /**
   * SSRF host guard: resolve all A/AAAA records and reject if ANY is loopback, link-local,
   * site-local (RFC 1918), CGNAT (100.64.0.0/10), unique-local (fc00::/7), or the cloud-metadata IP
   * (169.254.169.254). This rejects static internal-IP DNS records. It does NOT bind the address
   * the HttpClient later connects to (the JDK re-resolves DNS at connect time), so an active
   * DNS-rebinding attacker who flips the record after this check is an accepted residual risk;
   * closing it fully would require connection-level IP pinning.
   */
  private void guardSsrf(String host) throws CimdException {
    if (!enforceSsrfGuards) return;
    if (host == null || host.isEmpty()) {
      throw new CimdException("CIMD URL has no host");
    }
    InetAddress[] addrs;
    try {
      addrs = InetAddress.getAllByName(host);
    } catch (UnknownHostException e) {
      throw new CimdException("CIMD host does not resolve: " + host);
    }
    for (InetAddress a : addrs) {
      if (a.isLoopbackAddress()
          || a.isLinkLocalAddress()
          || a.isSiteLocalAddress()
          || a.isAnyLocalAddress()
          || isUniqueLocalOrMetadata(a)) {
        throw new CimdException("CIMD host resolves to a blocked address: " + a.getHostAddress());
      }
    }
  }

  private static boolean isUniqueLocalOrMetadata(InetAddress a) {
    byte[] b = a.getAddress();
    if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
      return true; // fc00::/7 unique-local
    }
    return b.length == 4
        && (
        // 169.254.169.254 cloud-metadata IP
        ((b[0] & 0xFF) == 169
                && (b[1] & 0xFF) == 254
                && (b[2] & 0xFF) == 169
                && (b[3] & 0xFF) == 254)
            // 100.64.0.0/10 CGNAT (RFC 6598)
            || ((b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 0x40));
  }

  /** Reads up to {@code max} bytes from the response stream; null if the body exceeds the cap. */
  private static byte[] readBounded(java.io.InputStream in, int max) {
    if (in == null) return new byte[0];
    try (java.io.InputStream stream = in) {
      java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
      byte[] chunk = new byte[2048];
      long total = 0;
      int n;
      while ((n = stream.read(chunk)) != -1) {
        total += n;
        if (total > max) return null;
        buf.write(chunk, 0, n);
      }
      return buf.toByteArray();
    } catch (IOException e) {
      return new byte[0];
    }
  }

  /**
   * Allowed redirect URIs: https for any host, OR http ONLY for the exact loopback hosts (localhost
   * / 127.0.0.1 / [::1]). Host-exact-matched so http://localhost.evil.example is rejected.
   */
  // Public so OAuthServlet's Dynamic Client Registration path reuses the same redirect_uri
  // policy as CIMD (https for any host, or http only for exact loopback; no embedded creds).
  public static boolean isAllowedRedirectUri(String uri) {
    if (uri == null || uri.isEmpty()) return false;
    URI u;
    try {
      u = new URI(uri);
    } catch (URISyntaxException e) {
      return false;
    }
    if (u.getUserInfo() != null) return false; // reject embedded credentials
    String scheme = u.getScheme();
    String host = u.getHost();
    if (scheme == null || host == null) return false;
    if ("https".equalsIgnoreCase(scheme)) return true;
    if ("http".equalsIgnoreCase(scheme)) {
      return host.equalsIgnoreCase("localhost")
          || host.equals("127.0.0.1")
          || host.equals("[::1]")
          || host.equals("::1");
    }
    return false;
  }

  private static String textOrNull(JsonNode root, String field) {
    JsonNode n = root.get(field);
    return (n != null && n.isTextual()) ? n.asText() : null;
  }

  private void putPositive(String url, CimdMetadata metadata) {
    evictIfFull();
    Instant now = Instant.now();
    cache.put(url, new CacheEntry(metadata, null, now.plus(POSITIVE_TTL)));
  }

  private void putNegative(String url, String failure) {
    evictIfFull();
    Instant now = Instant.now();
    cache.put(
        url, new CacheEntry(null, failure == null ? "error" : failure, now.plus(NEGATIVE_TTL)));
  }

  private void evictIfFull() {
    if (cache.size() < CACHE_MAX) return;
    Instant now = Instant.now();
    cache.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
    if (cache.size() >= CACHE_MAX) {
      cache.entrySet().stream()
          .min(Map.Entry.comparingByValue((a, b) -> a.expiresAt.compareTo(b.expiresAt)))
          .ifPresent(e -> cache.remove(e.getKey()));
    }
  }

  /** Visible for testing. */
  public void clearCache() {
    cache.clear();
  }

  public int cacheSize() {
    return cache.size();
  }

  public static final class CimdMetadata {
    public final String clientId;
    public final String clientName;
    public final List<String> redirectUris;
    public final String scope;
    public final String tokenEndpointAuthMethod;

    public CimdMetadata(
        String clientId,
        String clientName,
        List<String> redirectUris,
        String scope,
        String tokenEndpointAuthMethod) {
      this.clientId = clientId;
      this.clientName = clientName;
      this.redirectUris = redirectUris;
      this.scope = scope;
      this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
    }
  }

  public static final class CimdException extends Exception {
    public CimdException(String message) {
      super(message);
    }
  }

  private static final class CacheEntry implements Comparable<CacheEntry> {
    final CimdMetadata metadata;
    final String failure;
    final Instant expiresAt;

    CacheEntry(CimdMetadata metadata, String failure, Instant expiresAt) {
      this.metadata = metadata;
      this.failure = failure;
      this.expiresAt = expiresAt;
    }

    @Override
    public int compareTo(CacheEntry o) {
      return this.expiresAt.compareTo(o.expiresAt);
    }
  }
}
