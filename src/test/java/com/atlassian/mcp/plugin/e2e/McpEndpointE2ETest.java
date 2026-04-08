package com.atlassian.mcp.plugin.e2e;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

import static org.junit.Assert.*;

/**
 * End-to-end tests for the MCP endpoint running on a live Confluence instance.
 *
 * Requires environment variables:
 *   CONFLUENCE_URL          — e.g. https://bkm.astrateam.net
 *   CONFLUENCE_PAT_RKADMIN  — PAT for an admin user with MCP access
 *
 * Optional:
 *   CONFLUENCE_SPACE_KEY    — space key for page CRUD tests (default: TES)
 *
 * Skipped automatically when env vars are not set.
 *
 * Run: just e2e
 *  Or: source .credentials/confluence.env && atlas-mvn test -Dtest=McpEndpointE2ETest
 */
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
public class McpEndpointE2ETest {

    private static final String CONFLUENCE_URL = System.getenv("CONFLUENCE_URL");
    private static final String CONFLUENCE_PAT = System.getenv("CONFLUENCE_PAT_RKADMIN");
    private static final String SPACE_KEY = System.getenv().getOrDefault("CONFLUENCE_SPACE_KEY", "TEST");

    private static final String MCP_ENDPOINT = "/rest/mcp/1.0/";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** All 23 upstream tool names (source of truth). */
    private static final Set<String> ALL_UPSTREAM_TOOLS = Set.of(
            "search", "get_page", "get_page_children", "create_page", "update_page",
            "delete_page", "move_page", "get_page_history", "get_page_diff",
            "get_comments", "add_comment", "reply_to_comment",
            "get_labels", "add_label",
            "search_user",
            "get_page_views",
            "upload_attachment", "upload_attachments", "get_attachments",
            "download_attachment", "download_content_attachments",
            "delete_attachment", "get_page_images"
    );

    /** Page ID created during CRUD lifecycle test, cleaned up at end. */
    private static String createdPageId;

    @BeforeClass
    public static void checkEnvironment() {
        Assume.assumeTrue("CONFLUENCE_URL not set — skipping e2e tests", CONFLUENCE_URL != null);
        Assume.assumeTrue("CONFLUENCE_PAT_RKADMIN not set — skipping e2e tests", CONFLUENCE_PAT != null);
    }

    // ── Protocol Tests ───────────────────────────────────────────────

    @Test
    public void t01_initialize() throws Exception {
        JsonNode result = mcpCall("initialize", MAPPER.createObjectNode());

        assertTrue("Should have result", result.has("result"));
        JsonNode serverInfo = result.path("result").path("serverInfo");
        assertEquals("confluence-mcp", serverInfo.path("name").asText());
        assertTrue("Should have capabilities.tools", result.path("result").has("capabilities"));
    }

    @Test
    public void t02_ping() throws Exception {
        JsonNode result = mcpCall("ping", MAPPER.createObjectNode());
        assertFalse("ping should not error", result.has("error"));
    }

    @Test
    public void t03_invalidMethod_returnsError() throws Exception {
        JsonNode result = mcpCall("nonexistent/method", MAPPER.createObjectNode());
        assertTrue("Should return error for invalid method", result.has("error"));
    }

    // ── Tools List Tests ─────────────────────────────────────────────

    @Test
    public void t10_toolsList_returnsTools() throws Exception {
        JsonNode result = mcpCall("tools/list", MAPPER.createObjectNode());

        assertTrue("Should have result", result.has("result"));
        JsonNode tools = result.path("result").path("tools");
        assertTrue("tools should be array", tools.isArray());
        assertTrue("Should have at least 20 tools", tools.size() >= 20);

        System.out.println("[e2e] tools/list returned " + tools.size() + " tools");
    }

    @Test
    public void t11_toolsList_coversUpstreamTools() throws Exception {
        JsonNode tools = mcpCall("tools/list", MAPPER.createObjectNode())
                .path("result").path("tools");

        Set<String> visibleNames = new HashSet<>();
        tools.forEach(t -> visibleNames.add(t.path("name").asText()));

        // Every visible tool must be in the upstream set
        for (String name : visibleNames) {
            assertTrue("Unexpected tool not in upstream: " + name,
                    ALL_UPSTREAM_TOOLS.contains(name));
        }

        // At minimum, core tools must be present
        for (String core : List.of("search", "get_page", "create_page",
                "add_comment", "get_labels", "search_user")) {
            assertTrue("Core tool missing: " + core, visibleNames.contains(core));
        }
    }

    @Test
    public void t12_toolsList_eachToolHasSchemaAndDescription() throws Exception {
        JsonNode tools = mcpCall("tools/list", MAPPER.createObjectNode())
                .path("result").path("tools");

        for (JsonNode tool : tools) {
            String name = tool.path("name").asText();
            assertTrue(name + " missing description", tool.has("description"));
            assertFalse(name + " has empty description",
                    tool.path("description").asText().isBlank());
            assertTrue(name + " missing inputSchema", tool.has("inputSchema"));
            assertEquals(name + " inputSchema.type should be 'object'",
                    "object", tool.path("inputSchema").path("type").asText());
        }
    }

    // ── Read Tool Tests ──────────────────────────────────────────────

    @Test
    public void t20_search() throws Exception {
        JsonNode result = callTool("search", Map.of("query", "type=page", "limit", 5));

        assertFalse("Should not error", isError(result));
        String text = getContentText(result);
        assertNotNull("Should have content text", text);

        System.out.println("[e2e] search returned content");
    }

    @Test
    public void t21_searchUser() throws Exception {
        JsonNode result = callTool("search_user", Map.of("query", "rkadmin"));

        assertFalse("Should not error", isError(result));
        String text = getContentText(result);
        assertNotNull("Should have content text", text);
    }

    // ── Response Trimming Tests ──────────────────────────────────────

    @Test
    public void t30_responseFormat_matchesUpstream() throws Exception {
        JsonNode result = callTool("search", Map.of("query", "type=page", "limit", 3));
        String raw = getContentText(result);
        assertNotNull(raw);

        // Whitelist approach: no internal Confluence fields leak
        assertFalse("Should not contain _links", raw.contains("\"_links\""));
        assertFalse("Should not contain _expandable", raw.contains("\"_expandable\""));
        assertFalse("Should not contain profilePicture", raw.contains("\"profilePicture\""));

        // Should be a flat list of simplified page dicts with full URLs
        JsonNode parsed = MAPPER.readTree(raw);
        assertTrue("Search should return array", parsed.isArray());
        if (parsed.size() > 0) {
            JsonNode first = parsed.get(0);
            assertTrue("Each result should have url", first.has("url"));
            assertTrue("URL should be full URL",
                    first.path("url").asText().startsWith("http"));
            assertTrue("Each result should have id", first.has("id"));
            assertTrue("Each result should have title", first.has("title"));
        }
    }

    // ── Page CRUD Lifecycle Test ─────────────────────────────────────

    @Test
    public void t40_createPage() throws Exception {
        JsonNode result = callTool("create_page", Map.of(
                "space_key", SPACE_KEY,
                "title", "[E2E Test] Auto-created by McpEndpointE2ETest " + System.currentTimeMillis(),
                "content", "This page was created by the E2E test suite.",
                "content_format", "storage"
        ));

        assertFalse("Create should not error: " + getContentText(result), isError(result));
        String text = getContentText(result);
        JsonNode parsed = MAPPER.readTree(text);

        // Response format: {"message": "...", "page": {"id": "...", ...}}
        JsonNode pageNode = parsed.path("page");
        createdPageId = pageNode.has("id") ? pageNode.path("id").asText() : null;
        assertNotNull("Should return created page ID", createdPageId);
        assertTrue("Should have success message", parsed.has("message"));
        assertTrue("Page should have url", pageNode.has("url"));

        System.out.println("[e2e] Created page: " + createdPageId);
    }

    @Test
    public void t41_getCreatedPage() throws Exception {
        Assume.assumeTrue("No page created", createdPageId != null);

        JsonNode result = callTool("get_page", Map.of("page_id", createdPageId));

        assertFalse("Get should not error", isError(result));
        String text = getContentText(result);
        assertTrue("Response should contain page ID",
                text.contains(createdPageId));

        // Verify upstream response format: {"metadata": {"id", "title", "url", ...}}
        JsonNode parsed = MAPPER.readTree(text);
        assertTrue("Should have metadata wrapper", parsed.has("metadata"));
        JsonNode metadata = parsed.path("metadata");
        assertTrue("Metadata should have url", metadata.has("url"));
        assertTrue("URL should be full URL",
                metadata.path("url").asText().startsWith("http"));
        assertTrue("Metadata should have content", metadata.has("content"));
    }

    @Test
    public void t42_addComment() throws Exception {
        Assume.assumeTrue("No page created", createdPageId != null);

        JsonNode result = callTool("add_comment", Map.of(
                "page_id", createdPageId,
                "body", "E2E test comment — verifying add_comment tool"
        ));

        assertFalse("Add comment should not error: " + getContentText(result), isError(result));
    }

    @Test
    public void t43_getLabels() throws Exception {
        Assume.assumeTrue("No page created", createdPageId != null);

        JsonNode result = callTool("get_labels", Map.of("page_id", createdPageId));
        assertFalse("Get labels should not error", isError(result));
    }

    @Test
    public void t44_addLabel() throws Exception {
        Assume.assumeTrue("No page created", createdPageId != null);

        JsonNode result = callTool("add_label", Map.of(
                "page_id", createdPageId,
                "name", "e2etest"
        ));
        assertFalse("Add label should not error: " + getContentText(result), isError(result));
    }

    @Test
    public void t48_deleteCreatedPage() throws Exception {
        Assume.assumeTrue("No page created", createdPageId != null);

        JsonNode result = callTool("delete_page", Map.of("page_id", createdPageId));
        assertFalse("Delete should not error: " + getContentText(result), isError(result));

        System.out.println("[e2e] Deleted page: " + createdPageId);
        createdPageId = null;
    }

    // ── Error Handling Tests ─────────────────────────────────────────

    @Test
    public void t60_missingRequiredParam_returnsError() throws Exception {
        // Call get_page without page_id
        JsonNode result = callTool("get_page", Map.of());
        assertTrue("Should error on missing required param", isError(result));
    }

    @Test
    public void t61_invalidPageId_returnsError() throws Exception {
        JsonNode result = callTool("get_page", Map.of("page_id", "999999999"));
        assertTrue("Should error on invalid page ID", isError(result));
    }

    @Test
    public void t62_unknownTool_returnsError() throws Exception {
        JsonNode result = callTool("nonexistent_tool", Map.of());
        assertTrue("Should error on unknown tool", isError(result));
    }

    // ── Streamable HTTP Transport Tests ─────────────────────────────

    @Test
    public void t80_streamableHttp_initializeReturnsSessionId() throws Exception {
        HttpResponse<String> response = streamablePost(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                null);

        assertEquals("Should return 200", 200, response.statusCode());

        String contentType = response.headers().firstValue("Content-Type").orElse("");
        assertTrue("Should return JSON for single response, got: " + contentType,
                contentType.contains("application/json"));

        String sessionId = response.headers().firstValue("MCP-Session-Id").orElse(null);
        assertNotNull("Should return MCP-Session-Id header", sessionId);

        JsonNode parsed = MAPPER.readTree(response.body());
        assertEquals("confluence-mcp", parsed.path("result").path("serverInfo").path("name").asText());

        System.out.println("[e2e] Streamable HTTP initialize: OK, session " + sessionId.substring(0, 8) + "...");
    }

    @Test
    public void t81_streamableHttp_toolCallWithSession() throws Exception {
        HttpResponse<String> initResp = streamablePost(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                null);
        String sessionId = initResp.headers().firstValue("MCP-Session-Id").orElse(null);
        Assume.assumeTrue("No session ID", sessionId != null);

        HttpResponse<String> toolResp = streamablePost(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"search\",\"arguments\":{\"query\":\"type=page\",\"limit\":\"3\"}}}",
                sessionId);

        assertEquals("Should return 200", 200, toolResp.statusCode());
        JsonNode parsed = MAPPER.readTree(toolResp.body());
        assertFalse("Should not be error", parsed.path("result").path("isError").asBoolean(false));

        System.out.println("[e2e] Streamable HTTP tool call: OK");
    }

    @Test
    public void t82_streamableHttp_deleteSession() throws Exception {
        HttpResponse<String> initResp = streamablePost(
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                null);
        String sessionId = initResp.headers().firstValue("MCP-Session-Id").orElse(null);
        Assume.assumeTrue("No session ID", sessionId != null);

        HttpRequest deleteReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
                .header("Authorization", "Bearer " + CONFLUENCE_PAT)
                .header("MCP-Session-Id", sessionId)
                .DELETE()
                .timeout(Duration.ofSeconds(10))
                .build();
        HttpResponse<String> deleteResp = HTTP.send(deleteReq, HttpResponse.BodyHandlers.ofString());
        assertEquals("Delete should return 200", 200, deleteResp.statusCode());

        HttpResponse<String> staleResp = streamablePost(
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                sessionId);
        assertEquals("Stale session should return 404", 404, staleResp.statusCode());

        System.out.println("[e2e] Streamable HTTP session delete: OK");
    }

    @Test
    public void t83_oauth_refreshTokenGrantType() throws Exception {
        // 1. Metadata must advertise refresh_token grant type
        HttpRequest metaReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/metadata"))
                .GET().timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> metaResp = HTTP.send(metaReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, metaResp.statusCode());
        JsonNode metaJson = MAPPER.readTree(metaResp.body());
        JsonNode grantTypes = metaJson.path("grant_types_supported");
        assertTrue("Should be array", grantTypes.isArray());
        List<String> grants = new ArrayList<>();
        grantTypes.forEach(n -> grants.add(n.asText()));
        assertTrue("Must include authorization_code", grants.contains("authorization_code"));
        assertTrue("Must include refresh_token", grants.contains("refresh_token"));

        // 2. Register a DCR client for token endpoint tests
        String regBody = "{\"client_name\":\"Refresh Test\",\"redirect_uris\":[\"http://localhost:9999/cb\"]}";
        HttpRequest regReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/register"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(regBody))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> regResp = HTTP.send(regReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(201, regResp.statusCode());
        String clientId = MAPPER.readTree(regResp.body()).path("client_id").asText();

        // 3. refresh_token grant with missing refresh_token → 400 invalid_request
        String missingBody = "grant_type=refresh_token&client_id=" + clientId;
        HttpRequest missingReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(missingBody))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> missingResp = HTTP.send(missingReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, missingResp.statusCode());
        assertTrue("Should be invalid_request", missingResp.body().contains("invalid_request"));

        // 4. refresh_token grant with bogus token → 400 invalid_grant
        String bogusBody = "grant_type=refresh_token&client_id=" + clientId
                + "&refresh_token=bogus-token-12345";
        HttpRequest bogusReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(bogusBody))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> bogusResp = HTTP.send(bogusReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, bogusResp.statusCode());
        assertTrue("Should be invalid_grant", bogusResp.body().contains("invalid_grant"));

        // 5. unsupported grant type → 400 unsupported_grant_type
        String badGrantBody = "grant_type=client_credentials&client_id=" + clientId;
        HttpRequest badGrantReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + "/plugins/servlet/mcp-oauth/token"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(badGrantBody))
                .timeout(Duration.ofSeconds(10)).build();
        HttpResponse<String> badGrantResp = HTTP.send(badGrantReq, HttpResponse.BodyHandlers.ofString());
        assertEquals(400, badGrantResp.statusCode());
        assertTrue("Should be unsupported_grant_type", badGrantResp.body().contains("unsupported_grant_type"));

        System.out.println("[e2e] OAuth: refresh_token grant type + error paths OK");
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private JsonNode mcpCall(String method, JsonNode params) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("jsonrpc", "2.0");
        request.put("id", 1);
        request.put("method", method);
        request.put("params", params);

        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
                .header("Authorization", "Bearer " + CONFLUENCE_PAT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(request)))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = HTTP.send(httpReq, HttpResponse.BodyHandlers.ofString());
        return MAPPER.readTree(response.body());
    }

    private JsonNode callTool(String toolName, Map<String, Object> arguments) throws Exception {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("name", toolName);
        params.put("arguments", arguments);

        return mcpCall("tools/call", MAPPER.valueToTree(params));
    }

    private HttpResponse<String> streamablePost(String body, String sessionId) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
                .header("Authorization", "Bearer " + CONFLUENCE_PAT)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .timeout(Duration.ofSeconds(30));

        if (sessionId != null) {
            builder.header("MCP-Session-Id", sessionId);
            builder.header("MCP-Protocol-Version", "2025-06-18");
        }

        return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static boolean isError(JsonNode response) {
        if (response.has("error")) return true;
        return response.path("result").path("isError").asBoolean(false);
    }

    private static String getContentText(JsonNode response) {
        JsonNode content = response.path("result").path("content");
        if (content.isArray() && content.size() > 0) {
            return content.get(0).path("text").asText(null);
        }
        return null;
    }
}
