package com.atlassian.mcp.plugin.e2e;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

/**
 * End-to-end tests for the Confluence MCP plugin running on a live Confluence instance.
 *
 * <p>All MCP protocol concerns (JSON-RPC framing, session ids, SSE wrapping, Accept negotiation,
 * MCP-Protocol-Version) are owned by the official MCP Java SDK ({@link McpSyncClient}). These tests
 * drive the SDK sync client rather than raw JSON-RPC HTTP POSTs — so they assert real plugin
 * behaviour the SDK cannot own:
 *
 * <ul>
 *   <li>Server identity, capabilities, and the 28-tool registry contents
 *   <li>Tool annotations (read-only / destructive hints) per spec §6.4
 *   <li>Live Confluence data returned by read tools + the page-CRUD lifecycle
 *   <li>HTTP-level security the SDK hides: 401-not-302 auth routing, 1 MiB body cap, per-IP
 *       anonymous rate limiting, CIMD SSRF rejection, OIDC discovery
 *   <li>Offline CIMD cache bound is covered separately in {@code CimdValidatorTest}
 * </ul>
 *
 * <p>Required env vars (skipped cleanly when absent):
 *
 * <pre>
 *   CONFLUENCE_URL          — e.g. https://bkm.astrateam.net
 *   CONFLUENCE_PAT_RKADMIN  — PAT for an admin user with MCP access
 * </pre>
 *
 * <p>Optional:
 *
 * <pre>
 *   CONFLUENCE_SPACE_KEY    — space key for page CRUD tests (default: TEST)
 * </pre>
 *
 * <p>Run: {@code just e2e} <br>
 * Or: {@code source .credentials/confluence.env && atlas-mvn test -Dtest=McpEndpointE2ETest}
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class McpEndpointE2ETest {

  // --- environment --------------------------------------------------------

  private static final String CONFLUENCE_URL = System.getenv("CONFLUENCE_URL");
  private static final String CONFLUENCE_PAT = System.getenv("CONFLUENCE_PAT_RKADMIN");
  private static final String SPACE_KEY =
      System.getenv().getOrDefault("CONFLUENCE_SPACE_KEY", "TEST");

  /** MCP servlet path (see atlassian-plugin.xml). */
  private static final String MCP_ENDPOINT = "/plugins/servlet/mcp";

  /** Per-call timeout — must remain well below the surefire fork timeout. */
  private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Raw HTTP client for HTTP-level security assertions the SDK hides. */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

  /** Shared SDK client — initialize() is the expensive bit so we do it once. */
  private static McpSyncClient client;

  /** Captured at @BeforeClass for the capabilities assertions. */
  private static InitializeResult initResult;

  /** Page ID created during CRUD lifecycle test, cleaned up at end. */
  private static String createdPageId;

  /** All 28 tool names (23 upstream + 5 ergonomics tools). */
  private static final Set<String> ALL_UPSTREAM_TOOLS =
      Set.of(
          "search",
          "get_page",
          "get_page_children",
          "create_page",
          "update_page",
          "delete_page",
          "move_page",
          "get_page_history",
          "get_page_diff",
          "get_comments",
          "add_comment",
          "reply_to_comment",
          "get_labels",
          "add_label",
          "search_user",
          "get_page_views",
          "upload_attachment",
          "upload_attachments",
          "get_attachments",
          "download_attachment",
          "download_content_attachments",
          "delete_attachment",
          "get_page_images",
          "list_spaces",
          "append_to_page",
          "prepend_to_page",
          "convert_content",
          "replace_section");

  // --- lifecycle ----------------------------------------------------------

  @BeforeClass
  public static void setUp() {
    Assume.assumeTrue(
        "CONFLUENCE_URL not set — skipping e2e tests",
        CONFLUENCE_URL != null && !CONFLUENCE_URL.isEmpty());
    Assume.assumeTrue(
        "CONFLUENCE_PAT_RKADMIN not set — skipping e2e tests",
        CONFLUENCE_PAT != null && !CONFLUENCE_PAT.isEmpty());

    client = newClient();
    InitializeResult init = client.initialize();
    assertNotNull("initialize returned null", init);
    assertNotNull("server info missing", init.serverInfo());
    initResult = init;
  }

  @AfterClass
  public static void tearDown() {
    if (createdPageId != null && client != null) {
      try {
        call("delete_page", Map.of("page_id", createdPageId));
      } catch (Exception ignored) {
        /* best-effort cleanup */
      }
    }
    if (client != null) {
      try {
        client.close();
      } catch (Exception ignored) {
        /* best-effort */
      }
    }
  }

  // ========================================================================
  // 1 — Protocol: server identity + ping
  // ========================================================================

  @Test
  public void t01_initializeReturnsServerInfo() {
    InitializeResult init = client.getCurrentInitializationResult();
    assertNotNull("no initialization result cached on client", init);

    McpSchema.Implementation serverInfo = init.serverInfo();
    assertEquals(
        "server identifies as something other than confluence-mcp-plugin",
        "confluence-mcp-plugin",
        serverInfo.name());
    assertNotNull("server reported null version", serverInfo.version());
    assertFalse("server reported empty version", serverInfo.version().isEmpty());
  }

  @Test
  public void t02_pingSucceeds() {
    // Throws on failure; reaching the assertion means the round-trip succeeded.
    client.ping();
    assertTrue(true);
  }

  // ========================================================================
  // 2 — Capabilities: tools(listChanged=false) + logging, NOT resources/completions
  // ========================================================================

  @Test
  public void t03_capabilitiesDeclareToolsAndLoggingOnly() {
    McpSchema.ServerCapabilities caps = initResult.capabilities();
    assertNotNull("server capabilities missing", caps);

    assertNotNull("tools capability must be advertised", caps.tools());
    // listChanged is false in McpBootstrap (.tools(false)).
    assertFalse(
        "tools.listChanged should be false", Boolean.TRUE.equals(caps.tools().listChanged()));

    assertNotNull("logging capability must be advertised", caps.logging());

    assertTrue("resources capability must NOT be advertised", caps.resources() == null);
    assertTrue("completions capability must NOT be advertised", caps.completions() == null);
  }

  // ========================================================================
  // 3 — tools/list parity: exactly 28, all in upstream set, schemas valid
  // ========================================================================

  @Test
  public void t10_toolsListReturns28() {
    ListToolsResult result = client.listTools();
    List<Tool> tools = result.tools();
    assertNotNull("tools/list returned null tools", tools);
    assertEquals("expected exactly 28 tools", 28, tools.size());
  }

  @Test
  public void t11_toolsListCoversUpstreamTools() {
    List<Tool> tools = client.listTools().tools();

    Map<String, Tool> byName = new HashMap<>();
    for (Tool t : tools) {
      byName.put(t.name(), t);
      assertTrue(
          "unexpected tool not in upstream set: " + t.name(),
          ALL_UPSTREAM_TOOLS.contains(t.name()));
    }

    for (String core :
        List.of("search", "get_page", "create_page", "add_comment", "get_labels", "search_user")) {
      assertNotNull("core tool missing: " + core, byName.get(core));
    }
  }

  @Test
  public void t12_eachToolHasSchemaAndDescription() {
    for (Tool tool : client.listTools().tools()) {
      String name = tool.name();
      assertNotNull(name + " has null description", tool.description());
      assertFalse(name + " has empty description", tool.description().isEmpty());

      assertNotNull(name + " has null inputSchema", tool.inputSchema());

      // The 2020-12 $schema URI is emitted on every generated tool schema.
      JsonNode schemaJson = MAPPER.valueToTree(tool.inputSchema());
      assertEquals(
          name + " inputSchema.type should be 'object'",
          "object",
          schemaJson.path("type").asText());
      assertEquals(
          name + " inputSchema missing 2020-12 $schema",
          "https://json-schema.org/draft/2020-12/schema",
          schemaJson.path("$schema").asText());
    }
  }

  @Test
  public void t13_toolAnnotationsAreCorrect() {
    Map<String, Tool> byName = new HashMap<>();
    for (Tool t : client.listTools().tools()) byName.put(t.name(), t);

    assertTrue("search must be read-only", byName.get("search").annotations().readOnlyHint());
    assertTrue(
        "update_page must be destructive",
        byName.get("update_page").annotations().destructiveHint());
    assertTrue(
        "replace_section must be destructive",
        byName.get("replace_section").annotations().destructiveHint());
    assertTrue(
        "delete_page must be destructive",
        byName.get("delete_page").annotations().destructiveHint());
    assertFalse(
        "append_to_page must NOT be destructive",
        byName.get("append_to_page").annotations().destructiveHint());
    assertFalse(
        "create_page must NOT be destructive",
        byName.get("create_page").annotations().destructiveHint());
  }

  // ========================================================================
  // 4 — Read tools return live Confluence data
  // ========================================================================

  @Test
  public void t20_search() {
    CallToolResult result = call("search", Map.of("query", "type=page", "limit", 5));
    assertNotErrored("search", result);
    assertNotNull("search should have content text", firstTextOrEmpty(result));
  }

  @Test
  public void t21_searchUser() {
    CallToolResult result = call("search_user", Map.of("query", "rkadmin"));
    assertNotErrored("search_user", result);
    assertNotNull("search_user should have content text", firstTextOrEmpty(result));
  }

  @Test
  public void t22_listSpaces() throws Exception {
    CallToolResult result = call("list_spaces", Map.of("limit", 5));
    assertNotErrored("list_spaces", result);

    JsonNode parsed = MAPPER.readTree(firstText(result));
    assertTrue("list_spaces should return array", parsed.isArray());
    assertTrue("should have at least 1 space", parsed.size() >= 1);

    JsonNode first = parsed.get(0);
    assertTrue("space should have key", first.has("key"));
    assertTrue("space should have name", first.has("name"));
    assertTrue("space should have type", first.has("type"));
    assertTrue("space should have url", first.has("url"));
    assertTrue("URL should be a full URL", first.path("url").asText().startsWith("http"));
  }

  @Test
  public void t23_convertContent() throws Exception {
    CallToolResult result =
        call(
            "convert_content",
            Map.of("content", "## Hello\n\nSome **bold** text.\n\n> [!NOTE]\n> Important info"));
    assertNotErrored("convert_content", result);

    JsonNode parsed = MAPPER.readTree(firstText(result));
    assertTrue("should have content wrapper", parsed.has("content"));
    String storage = parsed.path("content").path("value").asText();
    assertTrue("should contain h2 tag", storage.contains("<h2"));
    assertTrue("should contain strong tag", storage.contains("<strong>"));
  }

  // ========================================================================
  // 5 — Response format: whitelist transformation, no internal fields leak
  // ========================================================================

  @Test
  public void t30_responseFormatMatchesUpstream() throws Exception {
    CallToolResult result = call("search", Map.of("query", "type=page", "limit", 3));
    assertNotErrored("search", result);
    String raw = firstText(result);

    assertFalse("should not contain _links", raw.contains("\"_links\""));
    assertFalse("should not contain _expandable", raw.contains("\"_expandable\""));
    assertFalse("should not contain profilePicture", raw.contains("\"profilePicture\""));

    JsonNode parsed = MAPPER.readTree(raw);
    assertTrue("search should return array", parsed.isArray());
    if (parsed.size() > 0) {
      JsonNode first = parsed.get(0);
      assertTrue("each result should have url", first.has("url"));
      assertTrue("URL should be a full URL", first.path("url").asText().startsWith("http"));
      assertTrue("each result should have id", first.has("id"));
      assertTrue("each result should have title", first.has("title"));
    }
  }

  // ========================================================================
  // 6 — Page CRUD lifecycle
  // ========================================================================

  @Test
  public void t40_createPage() throws Exception {
    CallToolResult result =
        call(
            "create_page",
            Map.of(
                "space_key",
                SPACE_KEY,
                "title",
                "[E2E Test] Auto-created " + System.currentTimeMillis(),
                "content",
                "This page was created by the E2E test suite.",
                "content_format",
                "storage"));
    assertNotErrored("create_page", result);

    JsonNode parsed = MAPPER.readTree(firstText(result));
    JsonNode pageNode = parsed.path("page");
    createdPageId = pageNode.has("id") ? pageNode.path("id").asText() : null;
    assertNotNull("should return created page ID", createdPageId);
    assertTrue("should have success message", parsed.has("message"));
    assertTrue("page should have url", pageNode.has("url"));
  }

  @Test
  public void t41_getCreatedPage() throws Exception {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result = call("get_page", Map.of("page_id", createdPageId));
    assertNotErrored("get_page", result);

    String text = firstText(result);
    assertTrue("response should contain page ID", text.contains(createdPageId));

    JsonNode parsed = MAPPER.readTree(text);
    assertTrue("should have page wrapper", parsed.has("page"));
    JsonNode metadata = parsed.path("page");
    assertTrue("metadata should have url", metadata.has("url"));
    assertTrue("URL should be a full URL", metadata.path("url").asText().startsWith("http"));
    assertTrue("metadata should have content", metadata.has("content"));
  }

  @Test
  public void t42_addComment() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result =
        call(
            "add_comment",
            Map.of(
                "page_id", createdPageId, "body", "E2E test comment — verifying add_comment tool"));
    assertNotErrored("add_comment", result);
  }

  @Test
  public void t43_getLabels() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result = call("get_labels", Map.of("page_id", createdPageId));
    assertNotErrored("get_labels", result);
  }

  @Test
  public void t44_addLabel() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result = call("add_label", Map.of("page_id", createdPageId, "name", "e2etest"));
    assertNotErrored("add_label", result);
  }

  @Test
  public void t45_getPageDiffMarkdownFormat() throws Exception {
    Assume.assumeTrue("no page created", createdPageId != null);

    // Update the page to create version 2.
    call(
        "update_page",
        Map.of(
            "page_id", createdPageId,
            "title", "[E2E Test] Updated Page",
            "content", "Updated content for diff test.",
            "content_format", "storage"));

    CallToolResult result =
        call(
            "get_page_diff",
            Map.of(
                "page_id", createdPageId,
                "from_version", 1,
                "to_version", 2));
    assertNotErrored("get_page_diff", result);

    JsonNode parsed = MAPPER.readTree(firstText(result));
    assertTrue("should have diff field", parsed.has("diff"));
    String diff = parsed.path("diff").asText();
    assertFalse("diff should not contain ac:macro-id", diff.contains("ac:macro-id"));
    assertFalse("diff should not contain ac:task-id", diff.contains("ac:task-id"));
  }

  @Test
  public void t46_updatePageOptimisticLocking() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result =
        call(
            "update_page",
            Map.of(
                "page_id",
                createdPageId,
                "title",
                "[E2E Test] Should Fail",
                "content",
                "This should not succeed.",
                "expected_version",
                999));
    assertTrue("should error on version mismatch", Boolean.TRUE.equals(result.isError()));
    String text = firstTextOrEmpty(result);
    assertTrue(
        "error should mention version mismatch", text.contains("modified since you last read it"));
  }

  @Test
  public void t47_appendAndPrependToPage() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult appendResult =
        call(
            "append_to_page",
            Map.of("page_id", createdPageId, "content", "**Appended section** content."));
    assertNotErrored("append_to_page", appendResult);
    assertTrue("should have success message", firstTextOrEmpty(appendResult).contains("appended"));

    CallToolResult prependResult =
        call(
            "prepend_to_page",
            Map.of("page_id", createdPageId, "content", "**Prepended alert** at top."));
    assertNotErrored("prepend_to_page", prependResult);
    assertTrue(
        "should have success message", firstTextOrEmpty(prependResult).contains("prepended"));
  }

  @Test
  public void t47b_createPageWithLabels() throws Exception {
    CallToolResult result =
        call(
            "create_page",
            Map.of(
                "space_key",
                SPACE_KEY,
                "title",
                "[E2E Test] Page with labels " + System.currentTimeMillis(),
                "content",
                "Page created with labels.",
                "content_format",
                "storage",
                "labels",
                List.of("e2etest", "automated")));
    assertNotErrored("create_page", result);

    JsonNode parsed = MAPPER.readTree(firstText(result));
    assertTrue("should have labels_added", parsed.has("labels_added"));

    String pageId = parsed.path("page").path("id").asText();
    call("delete_page", Map.of("page_id", pageId));
  }

  @Test
  public void t48_deleteCreatedPage() {
    Assume.assumeTrue("no page created", createdPageId != null);

    CallToolResult result = call("delete_page", Map.of("page_id", createdPageId));
    assertNotErrored("delete_page", result);
    createdPageId = null;
  }

  // ========================================================================
  // 7 — Error handling
  // ========================================================================

  @Test
  public void t60_missingRequiredParamReturnsError() {
    try {
      CallToolResult result = call("get_page", Map.of());
      assertTrue("should error on missing required param", Boolean.TRUE.equals(result.isError()));
    } catch (RuntimeException e) {
      // SDK client-side schema validation may raise instead — equally valid.
      assertTrue(true);
    }
  }

  @Test
  public void t61_invalidPageIdReturnsError() {
    CallToolResult result = call("get_page", Map.of("page_id", "999999999"));
    assertTrue("should error on invalid page ID", Boolean.TRUE.equals(result.isError()));
  }

  @Test
  public void t62_unknownToolReturnsError() {
    try {
      CallToolResult result = call("nonexistent_tool", Map.of());
      assertTrue("should error on unknown tool", Boolean.TRUE.equals(result.isError()));
    } catch (RuntimeException e) {
      String msg = String.valueOf(e.getMessage()).toLowerCase();
      assertTrue(
          "SDK exception should reference the unknown tool",
          msg.contains("nonexistent_tool") || msg.contains("unknown") || msg.contains("not found"));
    }
  }

  // ========================================================================
  // 8 — Security acceptance (raw HTTP — asserts HTTP-level behaviour the SDK hides)
  // ========================================================================

  @Test
  public void t70_unauthenticatedReturnsJson401NotLoginRedirect() throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

    assertEquals(
        "unauthenticated POST must be 401, not a 302 login redirect", 401, resp.statusCode());
    String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse("");
    assertFalse("401 must carry WWW-Authenticate", wwwAuth.isEmpty());
    // The advertised scope must be exactly the token registered on the Confluence Application
    // Link (WRITE, which already grants read). Advertising "read write" makes clients request
    // a "read" token Confluence rejects with invalid_scope — the bug this guards against.
    assertTrue(
        "WWW-Authenticate must advertise scope=\"WRITE\", was: " + wwwAuth,
        wwwAuth.contains("scope=\"WRITE\""));
    assertFalse(
        "WWW-Authenticate must not advertise the unregistered 'read' scope, was: " + wwwAuth,
        wwwAuth.toLowerCase().contains("read"));
    assertFalse(
        "must not be a login redirect HTML page", resp.body().toLowerCase().contains("<html"));
  }

  @Test
  public void t71_invalidPatReturnsJson401NotLoginRedirect() throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Authorization", "Bearer not-a-real-pat-token")
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

    assertEquals("invalid-PAT POST must be 401, not a 302 login redirect", 401, resp.statusCode());
    String wwwAuth = resp.headers().firstValue("WWW-Authenticate").orElse("");
    assertFalse("401 must carry WWW-Authenticate", wwwAuth.isEmpty());
    assertTrue(
        "WWW-Authenticate must advertise scope=\"WRITE\", was: " + wwwAuth,
        wwwAuth.contains("scope=\"WRITE\""));
    assertFalse(
        "WWW-Authenticate must not advertise the unregistered 'read' scope, was: " + wwwAuth,
        wwwAuth.toLowerCase().contains("read"));
    assertFalse(
        "must not be a login redirect HTML page", resp.body().toLowerCase().contains("<html"));
  }

  @Test
  public void t72_oversizedFixedLengthBodyReturns413() throws Exception {
    String big = oversizedJson();
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Authorization", "Bearer " + CONFLUENCE_PAT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(big))
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals("oversized fixed-length body must be rejected with 413", 413, resp.statusCode());
  }

  @Test
  public void t73_oversizedChunkedBodyReturns413() throws Exception {
    // No Content-Length: a streaming BodyPublisher forces chunked transfer encoding.
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Authorization", "Bearer " + CONFLUENCE_PAT)
            .header("Content-Type", "application/json")
            .POST(streamingOversizedPublisher())
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals("oversized chunked body must be rejected with 413", 413, resp.statusCode());
  }

  @Test
  public void t74_oversizedNoContentLengthBodyReturns413() throws Exception {
    // ofInputStream() produces an unknown-length (chunked) body too — distinct
    // BodyPublisher path from the streaming publisher above.
    byte[] payload = oversizedJson().getBytes(StandardCharsets.UTF_8);
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Authorization", "Bearer " + CONFLUENCE_PAT)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofInputStream(
                    () -> new java.io.ByteArrayInputStream(payload)))
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
    assertEquals(
        "oversized no-Content-Length body must be rejected with 413", 413, resp.statusCode());
  }

  @Test
  public void t75_anonymousRateLimitEventually429() throws Exception {
    boolean saw429 = false;
    HttpResponse<String> last = null;
    // Per-IP anonymous limit is 120/min; cap the burst so we don't loop forever.
    for (int i = 0; i < 150; i++) {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
              .header("Authorization", "Bearer invalid-burst-token")
              .header("Content-Type", "application/json")
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
              .timeout(REQUEST_TIMEOUT)
              .build();
      last = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      if (last.statusCode() == 429) {
        saw429 = true;
        break;
      }
    }
    assertTrue("anonymous burst must eventually be rate-limited with 429", saw429);
    assertTrue(
        "429 must carry RateLimit-Limit header",
        last.headers().firstValue("RateLimit-Limit").isPresent());
    assertTrue(
        "429 must carry RateLimit-Remaining header",
        last.headers().firstValue("RateLimit-Remaining").isPresent());
  }

  @Test
  public void t76_cimdSsrfAuthorizeReturnsInvalidClient() throws Exception {
    for (String host : List.of("localhost", "10.0.0.1", "169.254.169.254")) {
      String clientId = "https://" + host + "/.well-known/oauth-client";
      String url =
          CONFLUENCE_URL
              + "/plugins/servlet/mcp-oauth/authorize"
              + "?client_id="
              + enc(clientId)
              + "&redirect_uri="
              + enc("http://localhost:9999/cb")
              + "&response_type=code"
              + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM"
              + "&code_challenge_method=S256"
              + "&state=xyz";
      HttpRequest req =
          HttpRequest.newBuilder().uri(URI.create(url)).timeout(REQUEST_TIMEOUT).GET().build();
      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

      assertEquals("CIMD SSRF to " + host + " must be rejected with 400", 400, resp.statusCode());
      assertTrue(
          "CIMD SSRF to " + host + " must be invalid_client, body=" + resp.body(),
          resp.body().contains("invalid_client"));
    }
  }

  @Test
  public void t77_oidcWellKnownReturnsIssuerJson() throws Exception {
    for (String path :
        List.of(
            "/.well-known/openid-configuration",
            "/plugins/servlet/mcp-oauth/openid-configuration")) {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(CONFLUENCE_URL + path))
              .timeout(REQUEST_TIMEOUT)
              .GET()
              .build();
      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

      assertEquals(path + " must return 200 (not 404/redirect)", 200, resp.statusCode());
      JsonNode json = MAPPER.readTree(resp.body());
      assertTrue(path + " must advertise issuer", json.has("issuer"));
      assertFalse(path + " issuer must not be empty", json.path("issuer").asText().isEmpty());
    }
  }

  /**
   * Every OAuth/OIDC discovery document must advertise exactly the scope token(s) registered on the
   * Confluence Application Link — only {@code WRITE} (which already grants read). Advertising
   * {@code READ} as a separately requestable scope makes MCP clients request a token Confluence's
   * OAuth provider rejects with {@code invalid_scope}. This is the regression guard for the bug
   * where the consent flow failed because the plugin advertised {@code ["WRITE","READ"]} / {@code
   * scope="read write"} against a WRITE-only Application Link.
   */
  @Test
  public void t78_discoveryAdvertisesOnlyRegisteredWriteScope() throws Exception {
    for (String path :
        List.of(
            "/plugins/servlet/mcp-oauth/metadata",
            "/.well-known/oauth-authorization-server",
            "/.well-known/openid-configuration",
            "/plugins/servlet/mcp-oauth/openid-configuration")) {
      HttpRequest req =
          HttpRequest.newBuilder()
              .uri(URI.create(CONFLUENCE_URL + path))
              .timeout(REQUEST_TIMEOUT)
              .GET()
              .build();
      HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
      assertEquals(path + " must return 200", 200, resp.statusCode());

      JsonNode scopes = MAPPER.readTree(resp.body()).path("scopes_supported");
      assertTrue(path + " must advertise scopes_supported, body=" + resp.body(), scopes.isArray());
      assertEquals(path + " must advertise exactly one scope, was: " + scopes, 1, scopes.size());
      assertEquals(
          path + " must advertise only the registered WRITE scope, was: " + scopes,
          "WRITE",
          scopes.get(0).asText());
    }
  }

  @Test
  public void t79_unsupportedProtocolVersionReturns400() throws Exception {
    // Authenticated request carrying a bogus MCP-Protocol-Version must be rejected by
    // McpProtocolVersionFilter (weight 560) before the transport. The SDK does NOT validate
    // this header, so a 400 here proves the plugin filter fired.
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
            .header("Authorization", "Bearer " + CONFLUENCE_PAT)
            .header("Content-Type", "application/json")
            .header("MCP-Protocol-Version", "1999-01-01")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
            .timeout(REQUEST_TIMEOUT)
            .build();
    HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());

    assertEquals("unsupported MCP-Protocol-Version must be 400", 400, resp.statusCode());
    assertTrue(
        "body must identify the protocol-version filter, was: " + resp.body(),
        resp.body().contains("Unsupported MCP-Protocol-Version"));
  }

  @Test
  public void t80_dcrRejectsNonHttpsRedirectUri() throws Exception {
    String badBody =
        "{\"client_name\":\"e2e-bad\",\"redirect_uris\":[\"http://attacker.example/cb\"]}";
    HttpResponse<String> bad = postRegister(badBody);
    Assume.assumeTrue(
        "OAuth not configured on this instance — DCR returns 404", bad.statusCode() != 404);

    assertEquals(
        "non-https/non-loopback redirect_uri must be rejected with 400, body=" + bad.body(),
        400,
        bad.statusCode());
    assertTrue(
        "rejection must be invalid_redirect_uri, body=" + bad.body(),
        bad.body().contains("invalid_redirect_uri"));

    // Control: a valid https redirect_uri must still register successfully (201).
    String goodBody =
        "{\"client_name\":\"e2e-good\",\"redirect_uris\":[\"https://good.example/cb\"]}";
    HttpResponse<String> good = postRegister(goodBody);
    assertEquals(
        "valid https redirect_uri must register, body=" + good.body(), 201, good.statusCode());
  }

  private static HttpResponse<String> postRegister(String json) throws Exception {
    HttpRequest req =
        HttpRequest.newBuilder()
            .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/register"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .timeout(REQUEST_TIMEOUT)
            .build();
    return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
  }

  // =======================================================================
  // helpers
  // =======================================================================

  private static McpSyncClient newClient() {
    return newClientWithToken(CONFLUENCE_PAT);
  }

  private static McpSyncClient newClientWithToken(String token) {
    McpClientTransport transport =
        HttpClientStreamableHttpTransport.builder(CONFLUENCE_URL)
            .endpoint(MCP_ENDPOINT)
            .connectTimeout(Duration.ofSeconds(5))
            .openConnectionOnStartup(false)
            .httpRequestCustomizer(
                (builder, method, uri, body, ctx) ->
                    builder.header("Authorization", "Bearer " + token))
            .build();

    return McpClient.sync(transport)
        .requestTimeout(REQUEST_TIMEOUT)
        .initializationTimeout(REQUEST_TIMEOUT)
        .clientInfo(new McpSchema.Implementation("confluence-mcp-e2e", "1.0"))
        .build();
  }

  private static CallToolResult call(String name, Map<String, Object> args) {
    return client.callTool(new CallToolRequest(name, args));
  }

  private static void assertNotErrored(String toolName, CallToolResult result) {
    if (Boolean.TRUE.equals(result.isError())) {
      fail(toolName + " returned isError=true. content=" + truncate(firstTextOrEmpty(result), 400));
    }
  }

  private static String firstText(CallToolResult result) {
    return firstTextOpt(result)
        .orElseThrow(() -> new AssertionError("CallToolResult had no text content block"));
  }

  private static String firstTextOrEmpty(CallToolResult result) {
    return firstTextOpt(result).orElse("");
  }

  private static Optional<String> firstTextOpt(CallToolResult result) {
    if (result == null || result.content() == null) {
      return Optional.empty();
    }
    for (Content c : result.content()) {
      if (c instanceof TextContent) {
        return Optional.ofNullable(((TextContent) c).text());
      }
    }
    return Optional.empty();
  }

  private static String truncate(String s, int n) {
    if (s == null) return "<null>";
    return s.length() <= n ? s : s.substring(0, n) + "…[" + s.length() + " chars total]";
  }

  private static String enc(String s) {
    return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
  }

  /** A JSON document well over the 1 MiB body cap. */
  private static String oversizedJson() {
    int padLen = 1_200_000;
    StringBuilder sb = new StringBuilder(padLen + 64);
    sb.append("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\"");
    for (int i = 0; i < padLen; i++) sb.append('a');
    sb.append("\"}}");
    return sb.toString();
  }

  /**
   * Streaming publisher with unknown length → chunked transfer encoding (no Content-Length header),
   * emitting more than the 1 MiB cap.
   */
  private static HttpRequest.BodyPublisher streamingOversizedPublisher() {
    return HttpRequest.BodyPublishers.ofByteArrays(
        () ->
            new java.util.Iterator<byte[]>() {
              int remaining = 1_300_000;
              final byte[] chunk = new byte[16_384];

              {
                java.util.Arrays.fill(chunk, (byte) 'a');
              }

              @Override
              public boolean hasNext() {
                return remaining > 0;
              }

              @Override
              public byte[] next() {
                int n = Math.min(chunk.length, remaining);
                remaining -= n;
                if (n == chunk.length) return chunk;
                byte[] tail = new byte[n];
                System.arraycopy(chunk, 0, tail, 0, n);
                return tail;
              }
            });
  }
}
