# MCP Java SDK Migration — Implementation Plan

> **For agentic workers:** Use subagent-driven-development (recommended) or executing-plans skill to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Spec:** docs/rkstack/specs/2026-05-30-mcp-sdk-migration-design.md

**Goal:** Replace the hand-rolled JSON-RPC/MCP layer with the official MCP Java SDK (`io.modelcontextprotocol.sdk:2.0.0-M3`), mounted as an async servlet-filter at `/plugins/servlet/mcp`, mirroring the sibling `jira-mcp-plugin` on the identical `platform-public-api:8.3.16` platform — while preserving all 28 tools, their behavior, and upstream parity.

**Architecture:** The SDK's `HttpServletStreamableServerTransportProvider` + `McpSyncServer` are built by a new `McpBootstrap` and owned by a `McpTransportFilter` (the only filter type that can be async on the Atlassian framework). A six-filter security chain (body-size, rate-limit, access-control, session-binding, security-headers, transport) replaces the inline security that lived in `McpResource`. Tools are adapted to `SyncToolSpecification` by a new `McpToolAdapter`. OAuth gains OIDC discovery + CIMD. The JAX-RS `McpResource` and `JsonRpcHandler` are deleted.

**Tech Stack:** Java 21 (Temurin), Confluence 10.2.11 / Jakarta EE 10 / Tomcat 10.1, Atlassian SDK (`atlas-mvn`), Spring Scanner 6.0.2, MCP Java SDK 2.0.0-M3, Jackson 2.21.2 (BOM), `just` task runner, `mise` tool versions.

**Reference implementation:** `../jira-mcp-plugin/src/main/java/com/atlassian/mcp/plugin/` — every new class below is ported from there and adapted to Confluence APIs (`AuthenticatedUserThreadLocal`/`ConfluenceUser`/`UserAccessor` instead of SAL `UserManager`/`GroupManager`; `ComponentLocator` instead of Jira `ComponentAccessor`).

---

## Key Commands (from CLAUDE.md + justfile — reference in every task)

| Purpose | Command |
|---------|---------|
| Build (compile + JAR) | `just build` (= `atlas-package`) |
| Unit tests (excludes e2e) | `just test` (= `atlas-mvn test -Dtest="!*E2E*"`) |
| E2E vs live Confluence | `just e2e` (= `atlas-mvn test -Dtest="McpEndpointE2ETest" -DfailIfNoTests=false`) |
| Build + deploy + e2e | `just deploy-and-test` |
| Deploy JAR to UPM | `just deploy` |
| Clean | `just clean` |

**Build prerequisite (non-interactive shells):** `export JAVA_HOME="$(mise where java)"` (Temurin 21) before `atlas-mvn`/`just` — mise shims may be inactive. Env vars (`.credentials/confluence.env`) auto-load via mise.

**Deployment prerequisite for the e2e gate (Task 5):** the live Confluence 10.2.11 JVM must run with `-Datlassian.plugins.filter.async.default=true` (spec §6.1, §10). Fallback flag if needed: `-Datlassian.plugins.filter.force.async.dispatcher=true`.

**Branch:** all work stays on `feature/platform-10.2.11-jakarta-sdk`. Merge to `main` only when `just e2e` is green (spec invariants).

---

## File Structure

| Action | File | Responsibility |
|--------|------|----------------|
| Modify | `pom.xml` | SDK deps, OSGi embedding, slf4j pin, version bump |
| Modify | `tools/McpTool.java` | SDK-aware default methods |
| Modify | `tools/pages/{DeletePageTool,UpdatePageTool,ReplaceSectionTool}.java`, `tools/attachments/DeleteAttachmentTool.java` | `isDestructiveTool()` overrides |
| Create | `rest/McpToolAdapter.java` | `McpTool` → `SyncToolSpecification` |
| Create | `rest/ConfluenceAuthContextExtractor.java` | request → SDK transport context |
| Modify | `tools/ToolRegistry.java` | `toSpecifications()` |
| Create | `rest/McpBootstrap.java` | builds SDK transport + sync server |
| Create | `rest/McpTransportFilter.java` | async servlet-filter owning the endpoint |
| Create | `rest/ConfluenceIconConstants.java` | server-level logo data URI |
| Create | `rest/BodySizeLimitFilter.java` | 1 MB cap on **actual bytes** |
| Create | `rest/RateLimitFilter.java` | 120/min per-user / per-IP + `RateLimit-*` |
| Create | `rest/AccessControlFilter.java` | auth + allowlist + `WWW-Authenticate` |
| Create | `rest/SessionBindingFilter.java` | bind `MCP-Session-Id` to user |
| Create | `rest/SecurityHeadersFilter.java` | `nosniff` / `no-store` / frame options |
| Create | `rest/BufferedRequestWrapper.java` | re-readable request body |
| Create | `rest/CapturingResponseWrapper.java` | capture session id + status |
| Modify | `rest/RateLimiter.java` | add `Snapshot` + `snapshot()` |
| Modify | `rest/OAuthAnonymousFilter.java` | `/plugins/servlet/mcp` passthrough, OIDC well-known, protected-resource URL |
| Modify | `rest/OAuthServlet.java` | OIDC discovery route + CIMD wiring |
| Create | `rest/oauth/CimdValidator.java` | CIMD fetch + SSRF defenses + bounded cache |
| Modify | `resources/atlassian-plugin.xml` | drop REST module, add 6 filters |
| Delete | `JsonRpcHandler.java`, `rest/McpResource.java` | replaced by SDK |
| Modify | `src/test/.../e2e/McpEndpointE2ETest.java` | rewrite vs SDK sync client |
| Modify | `CLAUDE.md`, `README`, `docs/HANDOFF-jakarta-mcp-sdk-migration.md` | docs |

---

## Task 1: SDK dependencies + OSGi embedding

**Spec:** §6.6. **Files:** Modify `pom.xml`. No code wired yet — the build must still succeed.

- [ ] **Step 1: Bump the plugin version (cache-busting + release tracking)**

In [pom.xml](pom.xml), change:

```xml
    <version>1.1.2</version>
```
to:
```xml
    <version>1.2.0</version>
```

- [ ] **Step 2: Add the MCP SDK + slf4j dependencies**

In [pom.xml](pom.xml), insert these into `<dependencies>` immediately after the `atlassian-plugins-api` block (before the `jsoup` dependency at line ~105):

```xml
        <!-- MCP Java SDK — embedded via OSGi <Private-Package> (see build instructions).
             2.0.0-M3 is the newest published release on Maven Central. -->
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-core</artifactId>
            <version>2.0.0-M3</version>
        </dependency>
        <dependency>
            <groupId>io.modelcontextprotocol.sdk</groupId>
            <artifactId>mcp-json-jackson2</artifactId>
            <version>2.0.0-M3</version>
            <exclusions>
                <!-- The SDK pulls jackson-dataformat-yaml 2.18.3; Confluence ships 2.21.2.
                     We never parse YAML, so exclude it to avoid version skew (spec §5 row 7). -->
                <exclusion>
                    <groupId>com.fasterxml.jackson.dataformat</groupId>
                    <artifactId>jackson-dataformat-yaml</artifactId>
                </exclusion>
            </exclusions>
        </dependency>
        <!-- Pin slf4j-api as provided so the plugin uses Confluence's slf4j (avoids OSGi
             split-package / log loss). The SDK depends on slf4j transitively. -->
        <dependency>
            <groupId>org.slf4j</groupId>
            <artifactId>slf4j-api</artifactId>
            <scope>provided</scope>
        </dependency>
```

- [ ] **Step 3: Embed SDK packages + widen imports in the OSGi manifest**

In [pom.xml](pom.xml), replace the `<instructions>` block (lines ~176-189) with:

```xml
                    <instructions>
                        <Atlassian-Plugin-Key>${atlassian.plugin.key}</Atlassian-Plugin-Key>
                        <Export-Package>
                            com.atlassian.mcp.plugin.api,
                        </Export-Package>
                        <!-- Embed the MCP SDK and its transitives into the bundle.
                             Confluence does not export these packages, so they must ship inside. -->
                        <Private-Package>
                            io.modelcontextprotocol.*,
                            com.networknt.schema.*,
                            com.networknt.*,
                            reactor.*,
                            org.reactivestreams.*,
                            com.ethlo.time.*
                        </Private-Package>
                        <Import-Package>
                            jakarta.inject*;resolution:="optional",
                            jakarta.servlet*;resolution:="optional",
                            jakarta.ws.rs*;resolution:="optional",
                            <!-- SDK transitives Confluence does not export — optional so resolution
                                 does not fail at bundle start (spec §6.6). -->
                            com.fasterxml.jackson.dataformat.yaml;resolution:="optional",
                            io.micrometer.*;resolution:="optional",
                            reactor.blockhound.*;resolution:="optional",
                            org.graalvm.polyglot;resolution:="optional",
                            org.jcodings.*;resolution:="optional",
                            org.joni.*;resolution:="optional",
                            javax.annotation.*;resolution:="optional",
                            *
                        </Import-Package>
                        <DynamicImport-Package>*</DynamicImport-Package>
                        <Spring-Context>*</Spring-Context>
                    </instructions>
```

- [ ] **Step 4: Build to verify dependency resolution + OSGi packaging**

Run: `export JAVA_HOME="$(mise where java)" && just build`
Expected: `BUILD SUCCESS`. The generated JAR (`target/confluence-mcp-plugin-1.2.0.jar`) now contains the embedded `io/modelcontextprotocol/` classes. Nothing is wired into the runtime yet.

- [ ] **Step 5: Sanity-check the embedded packages landed in the JAR**

Run: `unzip -l target/confluence-mcp-plugin-1.2.0.jar | grep -E "io/modelcontextprotocol|com/networknt|reactor/core" | head`
Expected: non-empty — class files for `io/modelcontextprotocol/...`, `com/networknt/...`, `reactor/...` are present.

- [ ] **Step 6: Commit**

```bash
git add pom.xml
git commit -m "feat(mcp-sdk): add MCP Java SDK 2.0.0-M3 deps + OSGi embedding"
```

---

## Task 2: Tool adapter + SDK-aware `McpTool` + `ToolRegistry.toSpecifications()`

**Spec:** §6.4, §9 step 2. Purely additive — the old `McpResource`/`JsonRpcHandler` still serve traffic and the build stays green. **Files:** Modify `tools/McpTool.java`, four tool classes, `tools/ToolRegistry.java`; Create `rest/ConfluenceAuthContextExtractor.java`, `rest/McpToolAdapter.java`.

- [ ] **Step 1: Add SDK-aware default methods to `McpTool`**

In [tools/McpTool.java](src/main/java/com/atlassian/mcp/plugin/tools/McpTool.java), add these imports at the top (after the existing `import java.util.Map;`):

```java
import io.modelcontextprotocol.server.McpSyncServerExchange;
```

Then add these default methods inside the interface, immediately after `execute(...)` (line ~36):

```java
    // ── MCP SDK annotation hints (spec §6.4) — sane defaults so existing tools need no edits ──

    /** Display title for the tool annotation. Null = SDK omits it. */
    default String title() {
        return null;
    }

    /**
     * True only for tools that DELETE or OVERWRITE existing content. Overridden by
     * delete_page, delete_attachment, update_page, replace_section (spec §6.4). Additive
     * writes (create_page, add_comment, append_to_page, ...) stay false. A false hint lets
     * a client skip confirmation on a content-replacing write, so this is per-tool.
     */
    default boolean isDestructiveTool() {
        return false;
    }

    /** Idempotent unless the tool writes. */
    default boolean idempotentHint() {
        return !isWriteTool();
    }

    /** Every tool calls the Confluence REST API, so the world is "open". */
    default boolean openWorldHint() {
        return true;
    }

    /**
     * SDK-progress entry point. Defaults to {@link #execute(Map, String)}; batch tools
     * may override to emit progress via the exchange. {@code exchange} and
     * {@code progressToken} are provided by the SDK transport (spec §6.4).
     */
    default String executeWithSdkProgress(Map<String, Object> args, String authHeader,
                                          McpSyncServerExchange exchange, Object progressToken)
            throws McpToolException {
        return execute(args, authHeader);
    }
```

- [ ] **Step 2: Build to verify the interface compiles**

Run: `export JAVA_HOME="$(mise where java)" && just build`
Expected: `BUILD SUCCESS` — all 28 tools inherit the defaults; none break.

- [ ] **Step 3: Override `isDestructiveTool()` on the four content-destroying tools**

In each file below, add this method inside the class body (next to the existing `isWriteTool()` override):

```java
    @Override
    public boolean isDestructiveTool() {
        return true;
    }
```

Files:
- [tools/pages/DeletePageTool.java](src/main/java/com/atlassian/mcp/plugin/tools/pages/DeletePageTool.java)
- [tools/pages/UpdatePageTool.java](src/main/java/com/atlassian/mcp/plugin/tools/pages/UpdatePageTool.java)
- [tools/pages/ReplaceSectionTool.java](src/main/java/com/atlassian/mcp/plugin/tools/pages/ReplaceSectionTool.java)
- [tools/attachments/DeleteAttachmentTool.java](src/main/java/com/atlassian/mcp/plugin/tools/attachments/DeleteAttachmentTool.java)

Leave `move_page`, `create_page`, `add_comment`, `reply_to_comment`, `add_label`, `append_to_page`, `prepend_to_page` non-destructive (spec §6.4).

- [ ] **Step 4: Create `ConfluenceAuthContextExtractor`**

This feeds the authenticated Confluence user + Authorization header into the SDK transport context. Confluence-native: `AuthenticatedUserThreadLocal` (not SAL `UserManager`, spec §5 row 2). Create `src/main/java/com/atlassian/mcp/plugin/rest/ConfluenceAuthContextExtractor.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * Pulls the Authorization header and the resolved Confluence user off each request and
 * stashes them into the per-request {@link McpTransportContext}. Tool dispatch in
 * {@link McpToolAdapter} reads these via {@code exchange.transportContext().get(key)}.
 *
 * <p>Confluence-native: the authenticated principal comes from
 * {@link AuthenticatedUserThreadLocal} (Confluence's auth filters populate it before our
 * non-before-login filter chain runs), not SAL {@code UserManager} (spec §5 row 2).
 * Session-user binding enforcement lives in {@link SessionBindingFilter}, not here.
 */
@Named("confluenceAuthContextExtractor")
public class ConfluenceAuthContextExtractor implements McpTransportContextExtractor<HttpServletRequest> {

    public static final String CTX_AUTH_HEADER = "authHeader";
    public static final String CTX_CONFLUENCE_USER = "confluenceUser";
    public static final String CTX_CONFLUENCE_USER_KEY = "confluenceUserKey";

    @Override
    public McpTransportContext extract(HttpServletRequest request) {
        Map<String, Object> ctx = new HashMap<>(4);
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isEmpty()) {
            ctx.put(CTX_AUTH_HEADER, authHeader);
        }
        try {
            ConfluenceUser user = AuthenticatedUserThreadLocal.get();
            if (user != null) {
                ctx.put(CTX_CONFLUENCE_USER, user.getName());
                if (user.getKey() != null) {
                    ctx.put(CTX_CONFLUENCE_USER_KEY, user.getKey().getStringValue());
                }
            }
        } catch (Exception ignored) {
            // Best-effort: never throw out of the extractor.
        }
        return McpTransportContext.create(ctx);
    }
}
```

- [ ] **Step 5: Create `McpToolAdapter`**

Confluence variant — no `outputSchema` / `structuredContent` / UI binding (all deferred, spec §3 out-of-scope, §6.4). Create `src/main/java/com/atlassian/mcp/plugin/rest/McpToolAdapter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.mcp.plugin.McpToolException;
import com.atlassian.mcp.plugin.tools.McpTool;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Adapts our internal {@link McpTool} interface to the MCP SDK's
 * {@link McpServerFeatures.SyncToolSpecification}: builds tool annotations, injects the
 * JSON Schema 2020-12 dialect, and dispatches {@code tools/call} into the tool, reading the
 * auth header off the per-request transport context.
 *
 * <p>No {@code outputSchema} / {@code structuredContent} — that is the deferred widget data
 * layer (spec §3, §6.4).
 */
public final class McpToolAdapter {

    private static final Logger log = LoggerFactory.getLogger(McpToolAdapter.class);

    /** Per MCP 2025-11-25 (SEP-1613), the spec defaults to JSON Schema 2020-12. */
    private static final String JSON_SCHEMA_2020_12 = "https://json-schema.org/draft/2020-12/schema";

    private McpToolAdapter() {}

    /** Inject {@code "$schema": "<2020-12 URI>"} at the front of a tool's input schema if absent. */
    private static Map<String, Object> withSchemaDialect(Map<String, Object> raw) {
        if (raw == null || raw.containsKey("$schema")) return raw;
        Map<String, Object> copy = new LinkedHashMap<>(raw.size() + 1);
        copy.put("$schema", JSON_SCHEMA_2020_12);
        copy.putAll(raw);
        return copy;
    }

    /** Build a {@link McpServerFeatures.SyncToolSpecification} from an internal {@link McpTool}. */
    public static McpServerFeatures.SyncToolSpecification adapt(McpTool tool) {
        McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
                .title(tool.title())
                .readOnlyHint(!tool.isWriteTool())
                .destructiveHint(tool.isDestructiveTool())
                .idempotentHint(tool.idempotentHint())
                .openWorldHint(tool.openWorldHint())
                .build();

        McpSchema.Tool schemaTool = McpSchema.Tool.builder()
                .name(tool.name())
                .title(tool.title())
                .description(tool.description())
                .inputSchema(withSchemaDialect(tool.inputSchema()))
                .annotations(annotations)
                .build();

        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(schemaTool)
                .callHandler((exchange, request) -> dispatch(tool, exchange, request))
                .build();
    }

    private static McpSchema.CallToolResult dispatch(McpTool tool,
                                                     McpSyncServerExchange exchange,
                                                     McpSchema.CallToolRequest request) {
        String authHeader = readContext(exchange, ConfluenceAuthContextExtractor.CTX_AUTH_HEADER);
        Map<String, Object> args = request.arguments() != null ? request.arguments() : Map.of();
        Object progressToken = extractProgressToken(request);

        try {
            String resultText = tool.executeWithSdkProgress(args, authHeader, exchange, progressToken);
            return McpSchema.CallToolResult.builder()
                    .addTextContent(resultText)
                    .isError(Boolean.FALSE)
                    .build();
        } catch (McpToolException e) {
            log.debug("[MCP] tool '{}' failed: {}", tool.name(), e.getMessage());
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Error: " + e.getMessage())
                    .isError(Boolean.TRUE)
                    .build();
        } catch (RuntimeException e) {
            log.warn("[MCP] tool '{}' threw unexpectedly", tool.name(), e);
            return McpSchema.CallToolResult.builder()
                    .addTextContent("Internal error: " + e.getMessage())
                    .isError(Boolean.TRUE)
                    .build();
        }
    }

    private static Object extractProgressToken(McpSchema.CallToolRequest request) {
        try {
            Map<String, Object> meta = request.meta();
            return meta == null ? null : meta.get("progressToken");
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String readContext(McpSyncServerExchange exchange, String key) {
        try {
            Object v = exchange.transportContext().get(key);
            return v instanceof String s ? s : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}
```

- [ ] **Step 6: Add `toSpecifications()` to `ToolRegistry`**

In [tools/ToolRegistry.java](src/main/java/com/atlassian/mcp/plugin/tools/ToolRegistry.java), add imports:

```java
import com.atlassian.mcp.plugin.rest.McpToolAdapter;
import io.modelcontextprotocol.server.McpServerFeatures;
import java.util.List;
```

Then add this method after `listTools(...)` (line ~97). It applies the SAME three filters `listTools` uses — capability gate, admin-disabled list, read-only-hides-write — and adapts the survivors (spec §6.4):

```java
    /**
     * Adapts the currently-visible tools to MCP SDK {@code SyncToolSpecification}s for
     * registration on the sync server. Applies the same three filters as {@link #listTools}:
     * capability gate, admin-disabled list, and read-only-hides-write (spec §6.4).
     */
    public List<McpServerFeatures.SyncToolSpecification> toSpecifications() {
        return allTools.values().stream()
                .filter(this::isCapabilityMet)
                .filter(t -> config.isToolEnabled(t.name()))
                .filter(t -> !config.isReadOnlyMode() || !t.isWriteTool())
                .map(McpToolAdapter::adapt)
                .collect(Collectors.toList());
    }
```

- [ ] **Step 7: Build to verify the additive layer compiles**

Run: `export JAVA_HOME="$(mise where java)" && just build`
Expected: `BUILD SUCCESS`. The old endpoint still serves; the new adapter is dormant until Task 3 wires `McpBootstrap`.

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/atlassian/mcp/plugin/tools/McpTool.java \
        src/main/java/com/atlassian/mcp/plugin/tools/pages/DeletePageTool.java \
        src/main/java/com/atlassian/mcp/plugin/tools/pages/UpdatePageTool.java \
        src/main/java/com/atlassian/mcp/plugin/tools/pages/ReplaceSectionTool.java \
        src/main/java/com/atlassian/mcp/plugin/tools/attachments/DeleteAttachmentTool.java \
        src/main/java/com/atlassian/mcp/plugin/tools/ToolRegistry.java \
        src/main/java/com/atlassian/mcp/plugin/rest/ConfluenceAuthContextExtractor.java \
        src/main/java/com/atlassian/mcp/plugin/rest/McpToolAdapter.java
git commit -m "feat(mcp-sdk): adapt tools to SyncToolSpecification + SDK-aware McpTool hints"
```

---

## Task 3: Transport + bootstrap + security chain (ONE atomic commit)

**Spec:** §4.1, §6.1, §6.2, §6.3, §7, §9 step 3. The endpoint must NEVER be reachable without its replacement controls, so every change below lands in a **single commit**. No intermediate commit exposes `/plugins/servlet/mcp` anonymously without body-cap, rate-limit, access-control, and session-binding. All build steps verify compilation; runtime behavior is verified at the e2e gate (Task 5).

**Files (all in this one commit):** Create `BodySizeLimitFilter`, `RateLimitFilter`, `AccessControlFilter`, `SessionBindingFilter`, `SecurityHeadersFilter`, `BufferedRequestWrapper`, `CapturingResponseWrapper`, `McpBootstrap`, `McpTransportFilter`, `ConfluenceIconConstants`. Modify `RateLimiter`, `OAuthAnonymousFilter`, `atlassian-plugin.xml`. Delete `JsonRpcHandler`, `McpResource`.

- [ ] **Step 1: Add `Snapshot` + `snapshot()` to the existing `RateLimiter`**

Confluence already has [rest/RateLimiter.java](src/main/java/com/atlassian/mcp/plugin/rest/RateLimiter.java) (used by `McpResource`) but it lacks the read-only snapshot the new `RateLimitFilter` needs for `RateLimit-*` headers. Add the `Snapshot` inner class and `snapshot()` method, ported from Jira. Insert the `Snapshot` class right after the class's field declarations (after `currentBucket`), and add the `snapshot()` method after `isAllowed(...)`:

```java
    /**
     * Snapshot of current rate-limit state for a bucket key + endpoint, used to emit
     * RateLimit-* response headers per draft-ietf-httpapi-ratelimit-headers-09.
     */
    public static final class Snapshot {
        public final int limit;
        public final int remaining;
        public final long resetSeconds;

        public Snapshot(int limit, int remaining, long resetSeconds) {
            this.limit = limit;
            this.remaining = remaining;
            this.resetSeconds = resetSeconds;
        }
    }

    /**
     * Read-only inspection of the current bucket state. Does NOT consume a slot.
     * {@code resetSeconds} is the time until the current one-minute window rolls over.
     */
    public Snapshot snapshot(String ip, String endpoint, int maxPerMin) {
        long now = System.currentTimeMillis();
        long bucket = now / BUCKET_MS;
        long resetSeconds = Math.max(0L, ((bucket + 1) * BUCKET_MS - now + 999) / 1000);
        if (bucket != currentBucket.get()) {
            return new Snapshot(maxPerMin, maxPerMin, resetSeconds);
        }
        String key = ip + ":" + endpoint;
        AtomicInteger counter = counters.get(key);
        int used = counter == null ? 0 : counter.get();
        int remaining = Math.max(0, maxPerMin - used);
        return new Snapshot(maxPerMin, remaining, resetSeconds);
    }
```

> Note: the existing field names are `BUCKET_MS`, `currentBucket`, `counters` — confirm they match before pasting (they do in the current file). If `BUCKET_MS` is named differently, adjust.

- [ ] **Step 2: Create `BufferedRequestWrapper` (re-readable body, bounded constructor)**

The spec upgrades body-size enforcement to **actual bytes read** (§6.3), so this wrapper gains a `byte[]`-accepting constructor used by `BodySizeLimitFilter`. Create `src/main/java/com/atlassian/mcp/plugin/rest/BufferedRequestWrapper.java`:

```java
package com.atlassian.mcp.plugin.rest;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Caches the request body so it can be read more than once. {@link BodySizeLimitFilter}
 * constructs it from an already-bounded byte[]; {@link SessionBindingFilter} uses the
 * read-from-request constructor to inspect {@code "method":"initialize"} before the SDK
 * servlet reads the body downstream.
 */
public final class BufferedRequestWrapper extends HttpServletRequestWrapper {
    private final byte[] body;

    public BufferedRequestWrapper(HttpServletRequest request) throws IOException {
        super(request);
        this.body = request.getInputStream().readAllBytes();
    }

    /** Construct from a body the caller already read (bounded), avoiding a second read. */
    public BufferedRequestWrapper(HttpServletRequest request, byte[] body) {
        super(request);
        this.body = body;
    }

    public byte[] body() {
        return body;
    }

    @Override
    public ServletInputStream getInputStream() {
        final ByteArrayInputStream bais = new ByteArrayInputStream(body);
        return new ServletInputStream() {
            @Override public boolean isFinished() { return bais.available() == 0; }
            @Override public boolean isReady()    { return true; }
            @Override public void setReadListener(ReadListener listener) {}
            @Override public int read() { return bais.read(); }
        };
    }

    @Override
    public BufferedReader getReader() {
        return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
    }

    @Override
    public int getContentLength() {
        return body.length;
    }

    @Override
    public long getContentLengthLong() {
        return body.length;
    }
}
```

- [ ] **Step 3: Create `BodySizeLimitFilter` (caps on ACTUAL bytes, not Content-Length)**

Stronger than Jira's Content-Length check (spec §6.3): the endpoint is anonymously reachable, so the cap must hold for chunked transfers and missing/lying `Content-Length`. Create `src/main/java/com/atlassian/mcp/plugin/rest/BodySizeLimitFilter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Caps the MCP POST body at 1 MiB, enforced on the ACTUAL bytes read — not on a trusted
 * {@code Content-Length} header (spec §6.3). A fixed-length header over the cap is rejected
 * as a fast path; otherwise the stream is drained up to the cap + 1 byte and, if more
 * remains, the request is rejected with 413. The already-read bytes are re-wrapped so the
 * SDK transport downstream reads the body intact.
 */
@Named("mcpBodySizeLimitFilter")
public class BodySizeLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(BodySizeLimitFilter.class);
    private static final long MAX_BYTES = 1024L * 1024L;

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        // Fast path: a fixed Content-Length over the cap is rejected without reading.
        long declared = httpReq.getContentLengthLong();
        if (declared > MAX_BYTES) {
            reject(httpResp, declared);
            return;
        }

        // Only POSTs carry a JSON-RPC body worth capping; GET/DELETE pass through untouched.
        if (!"POST".equalsIgnoreCase(httpReq.getMethod())) {
            chain.doFilter(req, resp);
            return;
        }

        byte[] body = readBounded(httpReq, MAX_BYTES);
        if (body == null) {
            reject(httpResp, -1);
            return;
        }
        chain.doFilter(new BufferedRequestWrapper(httpReq, body), resp);
    }

    /** Reads up to {@code max} bytes; returns null if the stream exceeds the cap. */
    private static byte[] readBounded(HttpServletRequest req, long max) throws IOException {
        ServletInputStream in = req.getInputStream();
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] chunk = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(chunk)) != -1) {
            total += n;
            if (total > max) {
                return null; // over the cap — abort before buffering more
            }
            buf.write(chunk, 0, n);
        }
        return buf.toByteArray();
    }

    private static void reject(HttpServletResponse resp, long size) throws IOException {
        log.warn("[MCP-SEC] body too large (declared/actual {} bytes, max {})", size, MAX_BYTES);
        resp.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE, "Request body too large");
    }
}
```

- [ ] **Step 4: Create `RateLimitFilter` (per-user / per-IP, `RateLimit-*` headers)**

Confluence-adapted: resolve the user from `AuthenticatedUserThreadLocal` (not SAL `UserManager`); fall back to client IP for unauthenticated/unresolved requests so the anonymous-reachable path (§4.1) has a real bucket. Create `src/main/java/com/atlassian/mcp/plugin/rest/RateLimitFilter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 120 calls/min, keyed per authenticated user (and per client IP for unauthenticated or
 * unresolved-user requests). Runs before {@link AccessControlFilter}, and §4.1 deliberately
 * lets unauthenticated traffic reach the chain, so the anonymous case gets a real per-IP
 * bucket rather than a shared null key (spec §6.3). Over-limit returns 429 with
 * {@code RateLimit-*} + {@code Retry-After} headers.
 */
@Named("mcpRateLimitFilter")
public class RateLimitFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final int LIMIT_PER_MIN = 120;
    private static final String ENDPOINT = "mcp";

    private final RateLimiter rateLimiter;

    @Inject
    public RateLimitFilter(RateLimiter rateLimiter) {
        this.rateLimiter = rateLimiter;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        String bucket = resolveBucket(httpReq);
        if (!rateLimiter.isAllowed(bucket, ENDPOINT, LIMIT_PER_MIN)) {
            log.warn("[MCP-SEC] rate limit exceeded for bucket={}", bucket);
            RateLimiter.Snapshot snap = rateLimiter.snapshot(bucket, ENDPOINT, LIMIT_PER_MIN);
            setRateLimitHeaders(httpResp, snap);
            httpResp.setHeader("Retry-After", Long.toString(Math.max(1L, snap.resetSeconds)));
            httpResp.sendError(429, "Rate limit exceeded");
            return;
        }
        RateLimiter.Snapshot snap = rateLimiter.snapshot(bucket, ENDPOINT, LIMIT_PER_MIN);
        setRateLimitHeaders(httpResp, snap);
        chain.doFilter(req, resp);
    }

    private static String resolveBucket(HttpServletRequest req) {
        try {
            ConfluenceUser user = AuthenticatedUserThreadLocal.get();
            if (user != null && user.getKey() != null) {
                return "u:" + user.getKey().getStringValue();
            }
        } catch (Exception ignored) {
            // fall through to IP bucket
        }
        return "ip:" + clientIp(req);
    }

    private static void setRateLimitHeaders(HttpServletResponse resp, RateLimiter.Snapshot snap) {
        resp.setHeader("RateLimit-Limit", Integer.toString(snap.limit));
        resp.setHeader("RateLimit-Remaining", Integer.toString(snap.remaining));
        resp.setHeader("RateLimit-Reset", Long.toString(snap.resetSeconds));
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
```

- [ ] **Step 5: Create `AccessControlFilter`**

Ports `McpResource.checkAuth` + `isAccessAllowed` into a filter, Confluence-native (`AuthenticatedUserThreadLocal` + `UserAccessor.hasMembership`, spec §5 rows 2-3). Create `src/main/java/com/atlassian/mcp/plugin/rest/AccessControlFilter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.confluence.user.UserAccessor;
import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import jakarta.inject.Inject;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the admin-config access policy (ports {@code McpResource.checkAuth} +
 * {@code isAccessAllowed}):
 * <ul>
 *   <li>503 if MCP is globally disabled.</li>
 *   <li>401 + {@code WWW-Authenticate} (JSON body) if no authenticated user — never a
 *       Confluence login redirect (spec §4.1).</li>
 *   <li>403 if the user is not in {@code allowedUsers} nor any {@code allowedGroups}.</li>
 * </ul>
 */
@Named("mcpAccessControlFilter")
public class AccessControlFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(AccessControlFilter.class);

    private final McpPluginConfig config;
    private final UserAccessor userAccessor;
    private final ApplicationProperties applicationProperties;

    @Inject
    public AccessControlFilter(McpPluginConfig config,
                               @ComponentImport UserAccessor userAccessor,
                               @ComponentImport ApplicationProperties applicationProperties) {
        this.config = config;
        this.userAccessor = userAccessor;
        this.applicationProperties = applicationProperties;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        if (!config.isEnabled()) {
            httpResp.setContentType("application/json");
            httpResp.setStatus(503);
            httpResp.getWriter().write("{\"error\":\"MCP server is disabled\"}");
            return;
        }

        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null) {
            log.warn("[MCP-SEC] unauthenticated request from {}", clientIp(httpReq));
            httpResp.setContentType("application/json");
            httpResp.setStatus(401);
            String challenge = "Bearer realm=\"confluence-mcp\", scope=\"read write\"";
            if (config.isOAuthEnabled()) {
                String resourceMetadata = getConfluenceBaseUrl()
                        + "/plugins/servlet/mcp-oauth/protected-resource";
                challenge = challenge + ", resource_metadata=\"" + resourceMetadata + "\"";
            }
            httpResp.setHeader("WWW-Authenticate", challenge);
            httpResp.getWriter().write("{\"error\":\"Authentication required\"}");
            return;
        }

        String username = user.getName();
        String userKey = user.getKey() == null ? null : user.getKey().getStringValue();
        if (!isAccessAllowed(username, userKey)) {
            log.warn("[MCP-SEC] user '{}' not allowed", username);
            httpResp.setHeader("WWW-Authenticate", "Bearer realm=\"confluence-mcp\"");
            httpResp.sendError(HttpServletResponse.SC_FORBIDDEN, "User not allowed");
            return;
        }
        chain.doFilter(req, resp);
    }

    /** Ported from McpResource.isAccessAllowed. */
    private boolean isAccessAllowed(String username, String userKey) {
        if (userKey != null && config.isUserAllowed(userKey)) return true;
        if (username != null && config.isUserAllowed(username)) return true;
        Set<String> allowedGroups = config.getAllowedGroups();
        if (!allowedGroups.isEmpty() && username != null) {
            for (String groupName : allowedGroups) {
                if (userAccessor.hasMembership(groupName, username)) return true;
            }
        }
        return false;
    }

    private String getConfluenceBaseUrl() {
        String override = config.getConfluenceBaseUrlOverride();
        if (override != null && !override.isEmpty()) return override;
        try {
            return applicationProperties.getBaseUrl().toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static String clientIp(HttpServletRequest req) {
        String xff = req.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isEmpty()) {
            return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
```

- [ ] **Step 6: Create `CapturingResponseWrapper`**

Verbatim port from Jira (no Confluence-specific changes). Create `src/main/java/com/atlassian/mcp/plugin/rest/CapturingResponseWrapper.java` with the exact contents of [`../jira-mcp-plugin/.../rest/CapturingResponseWrapper.java`](../jira-mcp-plugin/src/main/java/com/atlassian/mcp/plugin/rest/CapturingResponseWrapper.java):

```java
package com.atlassian.mcp.plugin.rest;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpServletResponseWrapper;

/**
 * Captures the {@code MCP-Session-Id} header (and final status code) as the SDK writes them,
 * so {@link SessionBindingFilter} can record the binding post-initialize.
 */
public final class CapturingResponseWrapper extends HttpServletResponseWrapper {
    private String mcpSessionId;
    private int statusCode = HttpServletResponse.SC_OK;

    public CapturingResponseWrapper(HttpServletResponse response) {
        super(response);
    }

    @Override
    public void setHeader(String name, String value) {
        if ("MCP-Session-Id".equalsIgnoreCase(name)) {
            this.mcpSessionId = value;
        }
        super.setHeader(name, value);
    }

    @Override
    public void addHeader(String name, String value) {
        if ("MCP-Session-Id".equalsIgnoreCase(name)) {
            this.mcpSessionId = value;
        }
        super.addHeader(name, value);
    }

    @Override
    public void setStatus(int sc) {
        this.statusCode = sc;
        super.setStatus(sc);
    }

    @Override
    public void sendError(int sc) throws java.io.IOException {
        this.statusCode = sc;
        super.sendError(sc);
    }

    @Override
    public void sendError(int sc, String msg) throws java.io.IOException {
        this.statusCode = sc;
        super.sendError(sc, msg);
    }

    @Override
    public int getStatus() {
        return statusCode;
    }

    public String capturedSessionId() {
        return mcpSessionId;
    }
}
```

- [ ] **Step 7: Create `SessionBindingFilter`**

Confluence-adapted from Jira (resolve username via `AuthenticatedUserThreadLocal`). Create `src/main/java/com/atlassian/mcp/plugin/rest/SessionBindingFilter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Enforces the security invariant: an {@code MCP-Session-Id} issued to one Confluence user
 * may not be used by another. Cross-user replay returns 403; unknown/expired sessions on
 * non-initialize POSTs return 401. Captures the session id from the {@code initialize}
 * response and binds it to the authenticated user; DELETE removes the binding on success.
 */
@Named("mcpSessionBindingFilter")
public class SessionBindingFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(SessionBindingFilter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_BINDINGS = 200;
    private static final long TTL_MILLIS = 4L * 60 * 60 * 1000; // 4 hours

    private static final ConcurrentHashMap<String, SessionBinding> BINDINGS = new ConcurrentHashMap<>();

    private record SessionBinding(String username, long createdAtMillis) {}

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;

        String currentUser = resolveUsername();
        if (currentUser == null) {
            // AccessControlFilter ran before us and should have rejected anonymous already.
            httpResp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized");
            return;
        }

        String incomingSid = httpReq.getHeader("MCP-Session-Id");
        boolean isPost = "POST".equalsIgnoreCase(httpReq.getMethod());

        BufferedRequestWrapper buffered = isPost ? new BufferedRequestWrapper(httpReq) : null;
        boolean isInitialize = isPost && looksLikeInitialize(buffered);

        if (!isInitialize && incomingSid != null) {
            SessionBinding b = BINDINGS.get(incomingSid);
            if (b == null || expired(b)) {
                if (b != null) BINDINGS.remove(incomingSid);
                log.warn("[MCP-SEC] unknown/expired session id from user={}", currentUser);
                httpResp.sendError(HttpServletResponse.SC_UNAUTHORIZED, "session unknown or expired");
                return;
            }
            if (!b.username().equals(currentUser)) {
                log.warn("[MCP-SEC] session-user binding violation: bound to {} but request from {}",
                        b.username(), currentUser);
                httpResp.sendError(HttpServletResponse.SC_FORBIDDEN, "session bound to a different user");
                return;
            }
        }

        if (isInitialize) {
            CapturingResponseWrapper wrapped = new CapturingResponseWrapper(httpResp);
            chain.doFilter(buffered, wrapped);
            String issuedSid = wrapped.capturedSessionId();
            if (issuedSid != null && wrapped.getStatus() < 400) {
                evictIfFull();
                BINDINGS.put(issuedSid, new SessionBinding(currentUser, System.currentTimeMillis()));
                log.debug("[MCP-SEC] bound session {} to user {}", issuedSid, currentUser);
            }
            return;
        }

        if ("DELETE".equalsIgnoreCase(httpReq.getMethod()) && incomingSid != null) {
            chain.doFilter(req, resp);
            if (httpResp.getStatus() < 400) {
                BINDINGS.remove(incomingSid);
            }
            return;
        }

        chain.doFilter(buffered != null ? buffered : req, resp);
    }

    private boolean looksLikeInitialize(BufferedRequestWrapper buffered) {
        if (buffered == null) return false;
        try {
            JsonNode node = JSON.readTree(buffered.body());
            return node != null && "initialize".equals(node.path("method").asText(null));
        } catch (IOException e) {
            return false; // malformed body — let the SDK return 400
        }
    }

    private boolean expired(SessionBinding b) {
        return System.currentTimeMillis() - b.createdAtMillis() > TTL_MILLIS;
    }

    private void evictIfFull() {
        if (BINDINGS.size() < MAX_BINDINGS) return;
        BINDINGS.entrySet().removeIf(e -> expired(e.getValue()));
        if (BINDINGS.size() >= MAX_BINDINGS) {
            BINDINGS.entrySet().stream()
                    .min((a, b) -> Long.compare(a.getValue().createdAtMillis(), b.getValue().createdAtMillis()))
                    .ifPresent(e -> BINDINGS.remove(e.getKey()));
        }
    }

    private String resolveUsername() {
        try {
            ConfluenceUser user = AuthenticatedUserThreadLocal.get();
            return user == null ? null : user.getName();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 8: Create `SecurityHeadersFilter`**

Verbatim port from Jira. Create `src/main/java/com/atlassian/mcp/plugin/rest/SecurityHeadersFilter.java`:

```java
package com.atlassian.mcp.plugin.rest;

import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Adds security headers to every MCP response. */
@Named("mcpSecurityHeadersFilter")
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServletResponse httpResp = (HttpServletResponse) resp;
        httpResp.setHeader("X-Content-Type-Options", "nosniff");
        httpResp.setHeader("Cache-Control", "no-store");
        httpResp.setHeader("X-Frame-Options", "DENY");
        chain.doFilter(req, resp);
    }
}
```

- [ ] **Step 9: Create `ConfluenceIconConstants`**

Server-level logo for the `Implementation` builder (spec §6.1). Create `src/main/java/com/atlassian/mcp/plugin/rest/ConfluenceIconConstants.java`:

```java
package com.atlassian.mcp.plugin.rest;

/** Server-level icon (Confluence wordmark) as an SVG data URI, used in the MCP server identity. */
public final class ConfluenceIconConstants {

    private ConfluenceIconConstants() {}

    /** Minimal Confluence-blue mark; replace with the official wordmark SVG if/when licensed. */
    public static final String CONFLUENCE_LOGO_DATA_URI =
            "data:image/svg+xml;base64,"
            + "PHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAzMiAzMiI+"
            + "PHJlY3Qgd2lkdGg9IjMyIiBoZWlnaHQ9IjMyIiByeD0iNiIgZmlsbD0iIzE3NkJGRiIvPjx0ZXh0IHg9"
            + "IjE2IiB5PSIyMSIgZm9udC1mYW1pbHk9IkFyaWFsIiBmb250LXNpemU9IjE2IiBmaWxsPSIjZmZmIiB0"
            + "ZXh0LWFuY2hvcj0ibWlkZGxlIj5DPC90ZXh0Pjwvc3ZnPg==";
}
```

> The data URI above encodes a simple rounded blue square with a "C". If the official Confluence wordmark SVG is available under license, base64-encode it and substitute here — the value just needs to be a valid `image/svg+xml` data URI.

- [ ] **Step 10: Create `McpBootstrap`**

Confluence variant — capabilities `tools(false).logging()` only (no resources/completions, spec §3, §6.1). Uses `ConfluenceAuthContextExtractor` + the SDK origin validator (§6.2). Create `src/main/java/com/atlassian/mcp/plugin/rest/McpBootstrap.java`:

```java
package com.atlassian.mcp.plugin.rest;

import com.atlassian.mcp.plugin.config.McpPluginConfig;
import com.atlassian.mcp.plugin.tools.ToolRegistry;
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import com.atlassian.sal.api.ApplicationProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;
import io.modelcontextprotocol.json.schema.JsonSchemaValidator;
import io.modelcontextprotocol.json.schema.jackson2.DefaultJsonSchemaValidator;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.http.HttpServlet;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds (lazily, once) the MCP SDK transport + sync server, holding the configured
 * {@link HttpServlet} for {@link McpTransportFilter} to delegate into. Replaces the
 * hand-rolled {@code JsonRpcHandler} dispatch and {@code McpResource} JAX-RS endpoint.
 *
 * <p>Final-form per spec §2: SDK 2.0.0-M3 builder APIs, correct capabilities
 * ({@code tools(false).logging()} — no false listChanged, no resources/completions),
 * full server identity, and the SDK origin validator (§6.2) in place of a hand-rolled filter.
 */
@Named("mcpBootstrap")
public class McpBootstrap {

    private static final Logger log = LoggerFactory.getLogger(McpBootstrap.class);

    private static final String SERVER_NAME = "confluence-mcp-plugin";
    private static final String SERVER_VERSION = "1.2.0";
    private static final String SERVER_TITLE = "Confluence MCP Server";
    private static final String SERVER_DESCRIPTION =
            "Connect AI agents to Confluence Data Center — 28 tools across pages, comments, "
            + "labels, attachments, spaces, and users.";
    private static final String SERVER_WEBSITE = "https://github.com/mrkhachaturov/confluence-mcp-plugin";
    private static final String SERVER_INSTRUCTIONS =
            "This server exposes tools for Confluence Data Center.\n"
            + "- To find content: use `search` with a CQL query.\n"
            + "- To read a page: use `get_page` with a numeric page id or a Confluence page URL.\n"
            + "- To create or edit pages: `create_page`, `update_page`, `append_to_page`, "
            + "`prepend_to_page`, `replace_section` — content is Markdown by default and is "
            + "converted to Confluence storage format (panels, status badges, task lists, TOC, "
            + "expand sections are supported).\n"
            + "- Write tools are hidden when the admin enables read-only mode.";

    private static final String LOGO_DATA_URI = ConfluenceIconConstants.CONFLUENCE_LOGO_DATA_URI;

    private final ToolRegistry toolRegistry;
    private final McpPluginConfig config;
    private final ConfluenceAuthContextExtractor authExtractor;
    private final ApplicationProperties applicationProperties;

    private volatile HttpServletStreamableServerTransportProvider transport;
    private volatile McpSyncServer server;

    @Inject
    public McpBootstrap(ToolRegistry toolRegistry,
                        McpPluginConfig config,
                        ConfluenceAuthContextExtractor authExtractor,
                        @ComponentImport ApplicationProperties applicationProperties) {
        this.toolRegistry = toolRegistry;
        this.config = config;
        this.authExtractor = authExtractor;
        this.applicationProperties = applicationProperties;
    }

    /** Build (idempotent). Returns the configured servlet for the transport filter to delegate into. */
    public synchronized HttpServlet buildTransport() {
        if (transport != null) {
            return transport;
        }

        ObjectMapper mapper = new ObjectMapper();
        McpJsonMapper jsonMapper = new JacksonMcpJsonMapper(mapper);
        JsonSchemaValidator schemaValidator = new DefaultJsonSchemaValidator(mapper);

        this.transport = HttpServletStreamableServerTransportProvider.builder()
                .jsonMapper(jsonMapper)
                .mcpEndpoint("/plugins/servlet/mcp")
                .contextExtractor(authExtractor)
                .securityValidator(buildSecurityValidator())
                .build();

        var serverInfo = McpSchema.Implementation.builder(SERVER_NAME, SERVER_VERSION)
                .title(SERVER_TITLE)
                .description(SERVER_DESCRIPTION)
                .websiteUrl(SERVER_WEBSITE)
                .icons(List.of(McpSchema.Icon.builder(LOGO_DATA_URI)
                        .mimeType("image/svg+xml")
                        .sizes(List.of("any"))
                        .build()))
                .build();

        this.server = McpServer.sync(transport)
                .jsonMapper(jsonMapper)
                .jsonSchemaValidator(schemaValidator)
                .serverInfo(serverInfo)
                .instructions(SERVER_INSTRUCTIONS)
                // We never emit notifications/tools/list_changed → tools(false). Declare `logging`
                // so the SDK auto-wires logging/setLevel. No resources/completions (deferred, §3).
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(false)
                        .logging()
                        .build())
                .tools(toolRegistry.toSpecifications())
                .build();

        log.info("[MCP] SDK transport built ({} tools)", toolRegistry.toSpecifications().size());
        return transport;
    }

    /**
     * SDK-side Origin allowlist (spec §6.2): Confluence's own base URL (with a :* port variant),
     * known MCP client hosts, and loopback. Missing Origin (non-browser clients) is allowed;
     * a mismatch returns 403.
     */
    private DefaultServerTransportSecurityValidator buildSecurityValidator() {
        var builder = DefaultServerTransportSecurityValidator.builder()
                .allowedOrigin("https://claude.ai")
                .allowedOrigin("https://claude.com")
                .allowedOrigin("https://chatgpt.com")
                .allowedOrigin("https://chat.openai.com")
                .allowedOrigin("http://localhost")
                .allowedOrigin("http://localhost:*")
                .allowedOrigin("https://localhost")
                .allowedOrigin("https://localhost:*")
                .allowedOrigin("http://127.0.0.1")
                .allowedOrigin("http://127.0.0.1:*")
                .allowedOrigin("https://127.0.0.1")
                .allowedOrigin("https://127.0.0.1:*")
                .allowedOrigin("http://[::1]")
                .allowedOrigin("http://[::1]:*")
                .allowedOrigin("https://[::1]")
                .allowedOrigin("https://[::1]:*");

        String baseUrl = resolveConfluenceBaseUrl();
        if (baseUrl != null && !baseUrl.isEmpty()) {
            String normalized = normalizeOrigin(baseUrl);
            if (normalized != null) {
                builder.allowedOrigin(normalized);
                builder.allowedOrigin(normalized + ":*");
            }
        }
        return builder.build();
    }

    private String resolveConfluenceBaseUrl() {
        try {
            String override = config.getConfluenceBaseUrlOverride();
            if (override != null && !override.isEmpty()) {
                return override;
            }
            return applicationProperties.getBaseUrl().toString();
        } catch (Exception e) {
            log.warn("[MCP] could not resolve Confluence base URL for Origin allowlist", e);
            return null;
        }
    }

    private static String normalizeOrigin(String url) {
        try {
            URI u = URI.create(url);
            String scheme = u.getScheme();
            String host = u.getHost();
            int port = u.getPort();
            if (scheme == null || host == null) {
                return null;
            }
            return port == -1 ? scheme + "://" + host : scheme + "://" + host + ":" + port;
        } catch (Exception e) {
            return null;
        }
    }

    public synchronized void close() {
        if (server != null) {
            try {
                server.close();
            } catch (Exception e) {
                log.warn("[MCP] error closing SDK server", e);
            }
            server = null;
        }
        transport = null;
    }
}
```

> **Verify SDK method names while implementing:** `McpServer.sync(...)` builder methods (`.jsonMapper`, `.jsonSchemaValidator`, `.serverInfo`, `.instructions`, `.capabilities`, `.tools`) and `ServerCapabilities.builder().tools(false).logging()` are taken from the Jira `McpBootstrap` which targets the same 2.0.0-M3 (the Jira file additionally calls `.resources(false,false)`/`.completions()` — omit those here). If any builder signature differs, cross-check `.upstream/java-sdk/mcp-core/src/main/java/io/modelcontextprotocol/server/`.

- [ ] **Step 11: Create `McpTransportFilter`**

Verbatim port from Jira (no Confluence-specific changes — only the `McpBootstrap` it injects differs). Create `src/main/java/com/atlassian/mcp/plugin/rest/McpTransportFilter.java` with the full contents of [`../jira-mcp-plugin/.../rest/McpTransportFilter.java`](../jira-mcp-plugin/src/main/java/com/atlassian/mcp/plugin/rest/McpTransportFilter.java) — including the inner `FilterBackedServletConfig`. The class is reproduced here so it can be created without cross-referencing:

```java
package com.atlassian.mcp.plugin.rest;

import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.FilterConfig;
import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Enumeration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Filter-based transport for the MCP SDK servlet. Atlassian {@code <servlet>} modules
 * hardcode {@code asyncSupported=false}; {@code <servlet-filter>} modules honor the JVM flag
 * {@code atlassian.plugins.filter.async.default=true} (verified on Confluence 10.2.11 —
 * spec §5). This filter owns {@code /plugins/servlet/mcp}, calls the SDK transport servlet's
 * {@code service(...)} directly, and never invokes {@code chain.doFilter()} — it IS the endpoint.
 * The SDK transport calls {@code req.startAsync()} for non-initialize requests; with the flag
 * set, the chain is async-supported and the call succeeds.
 */
@Named("mcpTransportFilter")
public class McpTransportFilter implements Filter {

    private static final Logger log = LoggerFactory.getLogger(McpTransportFilter.class);

    private final McpBootstrap bootstrap;
    private volatile HttpServlet delegate;

    @Inject
    public McpTransportFilter(McpBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    @Override
    public void init(FilterConfig filterConfig) throws ServletException {
        HttpServlet d = bootstrap.buildTransport();
        d.init(new FilterBackedServletConfig(filterConfig));
        this.delegate = d;
        log.info("[MCP] McpTransportFilter initialized — delegate={}", d.getClass().getName());
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, ServletException {
        HttpServlet d = delegate;
        if (d == null) {
            throw new ServletException("McpTransportFilter not initialized");
        }
        HttpServletRequest httpReq = (HttpServletRequest) req;
        HttpServletResponse httpResp = (HttpServletResponse) resp;
        if (log.isDebugEnabled()) {
            log.debug("[MCP] doFilter uri={} dispatcher={} asyncSupported={}",
                    httpReq.getRequestURI(), httpReq.getDispatcherType(), httpReq.isAsyncSupported());
        }
        // Do NOT call chain.doFilter(). This filter IS the endpoint.
        d.service(httpReq, httpResp);
    }

    @Override
    public void destroy() {
        HttpServlet d = delegate;
        if (d != null) {
            try {
                d.destroy();
            } catch (Exception e) {
                log.warn("[MCP] error destroying SDK transport", e);
            }
        }
        bootstrap.close();
        delegate = null;
    }

    /** Synthetic ServletConfig wrapping a FilterConfig — enough for the SDK servlet's init(). */
    private static final class FilterBackedServletConfig implements ServletConfig {
        private final FilterConfig filterConfig;

        FilterBackedServletConfig(FilterConfig filterConfig) {
            this.filterConfig = filterConfig;
        }

        @Override public String getServletName() { return "mcp-transport"; }
        @Override public ServletContext getServletContext() { return filterConfig.getServletContext(); }
        @Override public String getInitParameter(String name) { return filterConfig.getInitParameter(name); }
        @Override public Enumeration<String> getInitParameterNames() { return filterConfig.getInitParameterNames(); }
    }
}
```

- [ ] **Step 12: Extend `OAuthAnonymousFilter` to pass `/plugins/servlet/mcp` through the login layer**

In [rest/OAuthAnonymousFilter.java](src/main/java/com/atlassian/mcp/plugin/rest/OAuthAnonymousFilter.java), the request to `/plugins/servlet/mcp` must reach the MCP filter chain (where `AccessControlFilter` returns a JSON 401) instead of a Confluence login redirect (spec §4.1). Replace the `/rest/mcp/1.0` trailing-slash redirect block (lines ~41-49) with a clean passthrough for the new endpoint. Change:

```java
        // Redirect /rest/mcp/1.0 → /rest/mcp/1.0/ (Claude sends without trailing slash;
        // without this, Confluence's login filter intercepts before JAX-RS can match)
        if (uri.endsWith("/rest/mcp/1.0")) {
            String query = req.getQueryString();
            String target = uri + "/" + (query != null ? "?" + query : "");
            resp.setStatus(307); // preserve method (POST)
            resp.setHeader("Location", target);
            return;
        }

        // Everything else (mcp-oauth servlet) — pass through
        chain.doFilter(request, response);
```
to:
```java
        // /plugins/servlet/mcp — let it pass Confluence's login layer so it reaches the MCP
        // filter chain, where AccessControlFilter returns a JSON 401 + WWW-Authenticate rather
        // than an HTML login redirect (spec §4.1). This is reachability, NOT authorization —
        // real auth still happens at AccessControlFilter.
        // Everything else (mcp-oauth servlet, /plugins/servlet/mcp) — pass through.
        chain.doFilter(request, response);
```

> The `@UnrestrictedAccess` annotation on the filter + the url-pattern added in Step 14 are what actually grant anonymous reachability; the `doFilter` body just needs to fall through to `chain.doFilter` for `/plugins/servlet/mcp`. (OIDC well-known + protected-resource URL changes to this filter come in Task 4 — keep them separate.)

- [ ] **Step 13: Delete the obsolete endpoint classes AND their orphaned unit test**

`JsonRpcHandlerTest.java` instantiates `JsonRpcHandler` directly; leaving it would break `just test` at Step 16 (the SDK adapter replaces its coverage, which the new e2e suite re-exercises against the live server in Task 5). Delete all three:

```bash
git rm src/main/java/com/atlassian/mcp/plugin/JsonRpcHandler.java
git rm src/main/java/com/atlassian/mcp/plugin/rest/McpResource.java
git rm src/test/java/com/atlassian/mcp/plugin/JsonRpcHandlerTest.java
```

> Confirm no other references remain. Run `grep -rn "JsonRpcHandler\|McpResource" src/main src/test` — expect **zero** hits (the only `JsonRpcHandler` reference outside these files was `McpResource`, also deleted). Also check `grep -rn "getSseMetrics" src/main src/test`; if any caller (e.g. an admin metrics panel) references `McpResource.getSseMetrics()`, remove that caller in this same commit.

- [ ] **Step 14: Swap the plugin descriptor — REST module out, filter chain in**

In [resources/atlassian-plugin.xml](src/main/resources/atlassian-plugin.xml):

(a) Delete the MCP REST module (lines ~18-22):
```xml
    <!-- MCP REST endpoint -->
    <rest key="mcp-rest" path="/mcp" version="1.0">
        <description>MCP Streamable HTTP endpoint</description>
        <package>com.atlassian.mcp.plugin.rest</package>
    </rest>
```

(b) Update the anonymous filter's url-patterns (lines ~49-56) — drop `/rest/mcp/1.0`, add `/plugins/servlet/mcp`:
```xml
    <!-- OAuth + MCP: bypass Confluence login filter for the OAuth servlet, well-known, and
         the MCP transport endpoint (anonymous reachability only — real auth at AccessControlFilter) -->
    <servlet-filter key="mcp-oauth-anon-filter" name="MCP OAuth Anonymous Filter"
                    class="com.atlassian.mcp.plugin.rest.OAuthAnonymousFilter"
                    location="before-login" weight="1">
        <url-pattern>/plugins/servlet/mcp-oauth/*</url-pattern>
        <url-pattern>/.well-known/*</url-pattern>
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
```

(c) Add the six-filter security chain, ascending by weight, all scoped to `/plugins/servlet/mcp`. These run at default location (AFTER Confluence authentication, so `AuthenticatedUserThreadLocal` is populated) — NOT `before-login`. Insert after the anon-filter block (spec §6.3):
```xml
    <!-- MCP security chain (spec §6.3) — ascending weight, default location (post-auth).
         Origin is handled by the SDK validator inside the transport, so it is not a filter. -->
    <servlet-filter key="mcp-body-size-filter" name="MCP Body Size Limit"
                    class="com.atlassian.mcp.plugin.rest.BodySizeLimitFilter" weight="200">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
    <servlet-filter key="mcp-rate-limit-filter" name="MCP Rate Limit"
                    class="com.atlassian.mcp.plugin.rest.RateLimitFilter" weight="300">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
    <servlet-filter key="mcp-access-control-filter" name="MCP Access Control"
                    class="com.atlassian.mcp.plugin.rest.AccessControlFilter" weight="400">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
    <servlet-filter key="mcp-session-binding-filter" name="MCP Session Binding"
                    class="com.atlassian.mcp.plugin.rest.SessionBindingFilter" weight="500">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
    <servlet-filter key="mcp-security-headers-filter" name="MCP Security Headers"
                    class="com.atlassian.mcp.plugin.rest.SecurityHeadersFilter" weight="550">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
    <servlet-filter key="mcp-transport-filter" name="MCP Transport"
                    class="com.atlassian.mcp.plugin.rest.McpTransportFilter" weight="600">
        <url-pattern>/plugins/servlet/mcp</url-pattern>
        <dispatcher>REQUEST</dispatcher>
    </servlet-filter>
```

- [ ] **Step 15: Build to verify the whole atomic change compiles**

Run: `export JAVA_HOME="$(mise where java)" && just build`
Expected: `BUILD SUCCESS`. Compilation now reflects the deleted classes and the new filter chain. (Runtime behavior — async transport, anonymous reachability, session binding — is verified at the e2e gate in Task 5, which requires the JVM flag on the live instance.)

- [ ] **Step 16: Unit tests still pass**

First confirm no dangling references: `grep -rn "JsonRpcHandler\|McpResource" src/main src/test` → zero hits.
Then run: `export JAVA_HOME="$(mise where java)" && just test`
Expected: remaining non-e2e unit tests (`ConfluenceRestClientTest`) pass; the deleted `JsonRpcHandlerTest` no longer compiles against missing classes.

- [ ] **Step 17: Commit (single atomic commit — the whole transport+security swap)**

```bash
git add -A
git commit -m "feat(mcp-sdk): replace JSON-RPC endpoint with SDK transport + security filter chain

Atomic swap (spec §9 step 3): SDK streamable transport mounted as an async
servlet-filter at /plugins/servlet/mcp, fronted by the body-size/rate-limit/
access-control/session-binding/security-headers chain and the SDK origin
validator. Deletes JsonRpcHandler + McpResource. No intermediate state exposes
the endpoint without its replacement controls."
```

---

## Task 4: OAuth discovery — OIDC + CIMD + protected-resource URL

**Spec:** §6.5, §9 step 4. **Files:** Create `rest/oauth/CimdValidator.java`; Modify `rest/OAuthServlet.java`, `rest/OAuthAnonymousFilter.java`.

- [ ] **Step 1: Create `CimdValidator` with SSRF defenses + bounded negative cache**

Ported from Jira and **hardened** per spec §6.5 (Jira's version lacks IP-range SSRF checks and negative caching — this is a "do it right" upgrade). Create `src/main/java/com/atlassian/mcp/plugin/rest/oauth/CimdValidator.java`:

```java
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
 * <ul>
 *   <li><b>SSRF defense</b>: HTTPS-only, no redirects (client built with
 *       {@code Redirect.NEVER}), host IPs re-resolved and re-checked immediately before the
 *       fetch and rejected if loopback/link-local/private/ULA/metadata — the spec's accepted
 *       alternative to socket-level pinning for defeating DNS rebinding; 8 KB body cap enforced
 *       while STREAMING (never buffers an oversized body); 5 s connect / 10 s request timeout.</li>
 *   <li><b>Bounded cache</b>: hard entry cap, short positive TTL, bounded negative caching of
 *       failures so a flood of unique URLs neither grows the heap nor re-hammers outbound hosts.</li>
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
        this(HttpClient.newBuilder()
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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        // Stream the body and stop reading once the cap is exceeded — never buffer an
        // arbitrarily large response into the heap (spec §6.5: bounded fetch size).
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
        if (docClientId != null && docClientId.isTextual() && !clientIdUrl.equals(docClientId.asText())) {
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
     * site-local (RFC 1918), unique-local (fc00::/7), or the cloud-metadata IP (169.254.169.254).
     * Called immediately before {@code httpClient.send(...)} in {@link #fetchAndValidate}, so it
     * is a re-resolve-and-check right before connection — the spec's stated alternative to
     * socket pinning — and combined with the client's {@code Redirect.NEVER} policy closes the
     * common DNS-rebinding and redirect-pivot vectors.
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
            if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                    || a.isAnyLocalAddress() || isUniqueLocalOrMetadata(a)) {
                throw new CimdException("CIMD host resolves to a blocked address: " + a.getHostAddress());
            }
        }
    }

    private static boolean isUniqueLocalOrMetadata(InetAddress a) {
        byte[] b = a.getAddress();
        if (b.length == 16 && (b[0] & 0xFE) == 0xFC) {
            return true; // fc00::/7 unique-local
        }
        // 169.254.169.254 cloud metadata (also covered by link-local, kept explicit)
        return b.length == 4 && (b[0] & 0xFF) == 169 && (b[1] & 0xFF) == 254
                && (b[2] & 0xFF) == 169 && (b[3] & 0xFF) == 254;
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
                if (total > max) return null; // over the cap — stop before buffering more
                buf.write(chunk, 0, n);
            }
            return buf.toByteArray();
        } catch (IOException e) {
            return new byte[0]; // empty → JSON parse fails with a clear error downstream
        }
    }

    /**
     * Allowed redirect URIs: https for any host, OR http ONLY for the exact loopback hosts
     * (localhost / 127.0.0.1 / [::1]). Parsed with {@link URI} and host-exact-matched — a
     * naive {@code startsWith("http://localhost")} would wrongly accept
     * {@code http://localhost.evil.example/cb}, leaking plaintext auth codes to an attacker
     * domain from the unauthenticated CIMD flow.
     */
    // Package-private (not private) so CimdValidatorTest can assert host-exact matching directly.
    static boolean isAllowedRedirectUri(String uri) {
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
        cache.put(url, new CacheEntry(null, failure == null ? "error" : failure, now.plus(NEGATIVE_TTL)));
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
    public void clearCache() { cache.clear(); }
    public int cacheSize() { return cache.size(); }

    public static final class CimdMetadata {
        public final String clientId;
        public final String clientName;
        public final List<String> redirectUris;
        public final String scope;
        public final String tokenEndpointAuthMethod;

        public CimdMetadata(String clientId, String clientName, List<String> redirectUris,
                            String scope, String tokenEndpointAuthMethod) {
            this.clientId = clientId;
            this.clientName = clientName;
            this.redirectUris = redirectUris;
            this.scope = scope;
            this.tokenEndpointAuthMethod = tokenEndpointAuthMethod;
        }
    }

    public static final class CimdException extends Exception {
        public CimdException(String message) { super(message); }
    }

    private static final class CacheEntry implements Comparable<CacheEntry> {
        final CimdMetadata metadata; // null on negative entries
        final String failure;        // null on positive entries
        final Instant expiresAt;

        CacheEntry(CimdMetadata metadata, String failure, Instant expiresAt) {
            this.metadata = metadata;
            this.failure = failure;
            this.expiresAt = expiresAt;
        }

        @Override public int compareTo(CacheEntry o) { return this.expiresAt.compareTo(o.expiresAt); }
    }
}
```

- [ ] **Step 2: Write a failing unit test for the CIMD SSRF guard + cache bound**

Create `src/test/java/com/atlassian/mcp/plugin/rest/oauth/CimdValidatorTest.java`:

```java
package com.atlassian.mcp.plugin.rest.oauth;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import static org.junit.Assert.*;

public class CimdValidatorTest {

    private final CimdValidator validator =
            new CimdValidator(java.net.http.HttpClient.newHttpClient(), new ObjectMapper(), true);

    @Test
    public void rejectsLocalhostClientId() {
        try {
            validator.resolve("https://localhost/.well-known/client");
            fail("expected CimdException for localhost");
        } catch (CimdValidator.CimdException e) {
            assertTrue(e.getMessage().toLowerCase().contains("blocked")
                    || e.getMessage().toLowerCase().contains("resolve"));
        }
    }

    @Test
    public void rejectsMetadataIpClientId() {
        try {
            validator.resolve("https://169.254.169.254/latest/meta-data");
            fail("expected CimdException for metadata IP");
        } catch (CimdValidator.CimdException e) {
            assertTrue(e.getMessage().toLowerCase().contains("blocked"));
        }
    }

    @Test
    public void rejectsNonCimdClientId() {
        try {
            validator.resolve("not-a-url");
            fail("expected CimdException");
        } catch (CimdValidator.CimdException e) {
            assertTrue(e.getMessage().contains("not a valid CIMD URL"));
        }
    }

    @Test
    public void redirectUriHostMustMatchExactly() {
        // https for any host is fine
        assertTrue(CimdValidator.isAllowedRedirectUri("https://app.example.com/cb"));
        // http only for exact loopback hosts
        assertTrue(CimdValidator.isAllowedRedirectUri("http://localhost:1234/cb"));
        assertTrue(CimdValidator.isAllowedRedirectUri("http://127.0.0.1/cb"));
        // the startsWith() bug: these LOOK loopback but are attacker domains — must be rejected
        assertFalse(CimdValidator.isAllowedRedirectUri("http://localhost.evil.example/cb"));
        assertFalse(CimdValidator.isAllowedRedirectUri("http://127.0.0.1.evil.example/cb"));
        // embedded credentials and plain http to a real host are rejected
        assertFalse(CimdValidator.isAllowedRedirectUri("http://user@localhost/cb"));
        assertFalse(CimdValidator.isAllowedRedirectUri("http://example.com/cb"));
    }
}
```

- [ ] **Step 3: Run the test to verify it passes**

Run: `export JAVA_HOME="$(mise where java)" && atlas-mvn test -Dtest=CimdValidatorTest`
Expected: PASS — localhost and metadata IPs are blocked by the SSRF guard; non-URLs are rejected. (These resolve locally without network egress.)

- [ ] **Step 4: Add the OIDC discovery route to `OAuthServlet`**

In [rest/OAuthServlet.java](src/main/java/com/atlassian/mcp/plugin/rest/OAuthServlet.java) `doGet`, add an `/openid-configuration` branch alongside `/metadata` (spec §6.5). Insert this `else if` immediately after the `/metadata` block (after line ~119):

```java
        } else if (path.equals("/openid-configuration") || path.equals("/openid-configuration/")) {
            if (!rateLimiter.isAllowed(ip, "oauth-metadata", RATE_METADATA)) {
                sendRateLimited(resp);
                return;
            }
            addSecurityHeaders(resp);
            resp.setContentType("application/json");
            String base = getOAuthBase();
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("issuer", base);
            meta.put("authorization_endpoint", base + "/authorize");
            meta.put("token_endpoint", base + "/token");
            meta.put("registration_endpoint", base + "/register");
            meta.put("response_types_supported", List.of("code"));
            meta.put("grant_types_supported", List.of("authorization_code", "refresh_token"));
            meta.put("token_endpoint_auth_methods_supported", List.of("none"));
            meta.put("code_challenge_methods_supported", List.of("S256"));
            meta.put("scopes_supported", List.of("WRITE", "READ"));
            // We do not issue ID tokens; this advertises the same authorization server, with CIMD.
            meta.put("client_id_metadata_document_supported", true);
            mapper.writeValue(resp.getWriter(), meta);
```

Also add `client_id_metadata_document_supported` to the existing `/metadata` response for consistency — in the `/metadata` block, after the `scopes_supported` line (line ~118), add:
```java
            meta.put("client_id_metadata_document_supported", true);
```

- [ ] **Step 5: Wire CIMD into `handleAuthorize` + fix the protected-resource URL**

In [rest/OAuthServlet.java](src/main/java/com/atlassian/mcp/plugin/rest/OAuthServlet.java):

(a) Add a `CimdValidator` field + import:
```java
import com.atlassian.mcp.plugin.rest.oauth.CimdValidator;
```
```java
    private final CimdValidator cimdValidator = new CimdValidator();
```

(b) Fix the protected-resource resource URL — change line ~92 from:
```java
            meta.put("resource", getBaseUrl() + "/rest/mcp/1.0/");
```
to:
```java
            meta.put("resource", getBaseUrl() + "/plugins/servlet/mcp");
```

(c) In `handleAuthorize`, the current code (lines 235-251) looks up a DCR-registered client and validates `redirect_uri` against `client.redirectUris`. A CIMD `client_id` is an HTTPS URL not present in the DCR `OAuthStateStore`, so it would fail at "Unknown client_id". Replace those 17 lines (from `OAuthStateStore.RegisteredClient client = stateStore.getClient(clientId);` through the end of the redirect_uri-mismatch `if` block at line 251) so both DCR and CIMD produce an allowed-redirect-URI list. Change:

```java
        OAuthStateStore.RegisteredClient client = stateStore.getClient(clientId);
        if (client == null) {
            resp.setStatus(400);
            resp.getWriter().write("Unknown client_id");
            return;
        }

        // Validate redirect_uri against registered URIs (prevents open redirect / token theft)
        if (redirectUri == null || redirectUri.isEmpty()
                || client.redirectUris.isEmpty()
                || !client.redirectUris.contains(redirectUri)) {
            log.warn("[MCP-SEC] redirect_uri mismatch for client {} from {}", clientId, getClientIp(req));
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"invalid_request\",\"error_description\":\"redirect_uri does not match registered URIs\"}");
            return;
        }
```
to:
```java
        // Resolve the set of allowed redirect URIs from either a DCR-registered client OR a
        // CIMD client_id (an HTTPS URL whose metadata document lists its redirect_uris, §6.5).
        java.util.List<String> allowedRedirectUris;
        if (CimdValidator.isCimdClientId(clientId)) {
            try {
                CimdValidator.CimdMetadata md = cimdValidator.resolve(clientId);
                allowedRedirectUris = md.redirectUris;
            } catch (CimdValidator.CimdException e) {
                log.warn("[MCP-SEC] CIMD validation failed from {}: {}", getClientIp(req), e.getMessage());
                resp.setStatus(400);
                resp.setContentType("application/json");
                resp.getWriter().write("{\"error\":\"invalid_client\",\"error_description\":\""
                        + e.getMessage().replace("\"", "'") + "\"}");
                return;
            }
        } else {
            OAuthStateStore.RegisteredClient client = stateStore.getClient(clientId);
            if (client == null) {
                resp.setStatus(400);
                resp.getWriter().write("Unknown client_id");
                return;
            }
            allowedRedirectUris = client.redirectUris;
        }

        // Validate redirect_uri against the allowed list (prevents open redirect / token theft)
        if (redirectUri == null || redirectUri.isEmpty()
                || allowedRedirectUris == null || allowedRedirectUris.isEmpty()
                || !allowedRedirectUris.contains(redirectUri)) {
            log.warn("[MCP-SEC] redirect_uri mismatch for client {} from {}", clientId, getClientIp(req));
            resp.setStatus(400);
            resp.setContentType("application/json");
            resp.getWriter().write("{\"error\":\"invalid_request\",\"error_description\":\"redirect_uri does not match registered URIs\"}");
            return;
        }
```

The downstream `createPendingAuth(...)` + redirect-to-Confluence logic (lines 253-271) is unchanged — it already keys off `redirectUri`/`clientId`, which now carry the CIMD-validated values.

- [ ] **Step 6: Serve `/.well-known/openid-configuration` from the anonymous filter + fix protected-resource URL**

In [rest/OAuthAnonymousFilter.java](src/main/java/com/atlassian/mcp/plugin/rest/OAuthAnonymousFilter.java) `handleWellKnown` — servlets can't serve at the context root, so the filter must serve the OIDC well-known path too (spec §6.5). 

(a) Broaden the well-known matcher in `doFilter` — change:
```java
        if (uri.contains("/.well-known/oauth-")) {
            handleWellKnown(uri, resp);
            return;
        }
```
to:
```java
        if (uri.contains("/.well-known/oauth-") || uri.contains("/.well-known/openid-configuration")) {
            handleWellKnown(uri, resp);
            return;
        }
```

(b) In `handleWellKnown`, fix the protected-resource URL and add the OIDC branch. Change the protected-resource line from:
```java
            resp.getWriter().write("{\"resource\":\"" + baseUrl + "/rest/mcp/1.0/\","
                    + "\"authorization_servers\":[\"" + oauthBase + "\"]}");
```
to:
```java
            resp.getWriter().write("{\"resource\":\"" + baseUrl + "/plugins/servlet/mcp\","
                    + "\"authorization_servers\":[\"" + oauthBase + "\"]}");
```
And add an OIDC branch — insert before the final `else { 404 }`:
```java
        } else if (uri.contains("openid-configuration")) {
            resp.getWriter().write("{\"issuer\":\"" + oauthBase + "\","
                    + "\"authorization_endpoint\":\"" + oauthBase + "/authorize\","
                    + "\"token_endpoint\":\"" + oauthBase + "/token\","
                    + "\"registration_endpoint\":\"" + oauthBase + "/register\","
                    + "\"response_types_supported\":[\"code\"],"
                    + "\"grant_types_supported\":[\"authorization_code\",\"refresh_token\"],"
                    + "\"token_endpoint_auth_methods_supported\":[\"none\"],"
                    + "\"code_challenge_methods_supported\":[\"S256\"],"
                    + "\"scopes_supported\":[\"WRITE\",\"READ\"],"
                    + "\"client_id_metadata_document_supported\":true}");
```

> Note `handleWellKnown`'s `if/else if` currently keys on substrings: `oauth-protected-resource`, then `oauth-authorization-server`. The new `openid-configuration` branch must be placed so it doesn't get shadowed — put it as its own `else if` keyed on `openid-configuration`.

- [ ] **Step 7: Build + unit tests**

Run: `export JAVA_HOME="$(mise where java)" && just build && just test`
Expected: `BUILD SUCCESS`; `CimdValidatorTest` passes.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "feat(mcp-sdk): OAuth OIDC discovery + CIMD (SSRF-guarded, bounded cache) + protected-resource URL"
```

---

## Task 5: E2E rewrite against the SDK sync client + security acceptance tests

**Spec:** §4.1, §6.3, §6.5, §8, §9 step 5. **Files:** Modify `src/test/java/.../e2e/McpEndpointE2ETest.java`. This is the acceptance gate — requires the live 10.2.11 instance with the JVM flag set.

- [ ] **Step 1: Add the SDK client test dependency**

The e2e test uses the SDK's sync client (`io.modelcontextprotocol.client.*`), already on the compile classpath via `mcp-core` (Task 1). No new dependency needed — confirm `import io.modelcontextprotocol.client.McpClient;` resolves. If the client transport (`HttpClientStreamableHttpTransport`) is in a separate artifact, add it `test`-scoped; the Jira e2e imports it from `mcp-core`, so it should already resolve.

- [ ] **Step 2: Rewrite the client setup to use the SDK sync client**

Replace the raw `HttpClient`-based helpers at the bottom of [e2e/McpEndpointE2ETest.java](src/test/java/com/atlassian/mcp/plugin/e2e/McpEndpointE2ETest.java) with SDK client construction, mirroring Jira's rewrite ([`../jira-mcp-plugin/.../e2e/McpEndpointE2ETest.java`](../jira-mcp-plugin/src/test/java/com/atlassian/mcp/plugin/e2e/McpEndpointE2ETest.java) lines ~569-592). Add imports:

```java
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.ListToolsResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;
```

Add `import java.time.Duration;` (already imported in the current file — confirm), then the endpoint constant, timeout, and client factory. These method names are taken verbatim from Jira's working 2.0.0-M3 e2e (`../jira-mcp-plugin/.../e2e/McpEndpointE2ETest.java` lines 574-589) and verified against `.upstream/java-sdk/` — the auth header is set via `.httpRequestCustomizer((builder, method, uri, body, ctx) -> ...)`, NOT a `.customizeRequest(...)` method (which does not exist in this SDK):

```java
    private static final String MCP_ENDPOINT = "/plugins/servlet/mcp";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static McpSyncClient client;

    /** Build a fresh SDK sync client wired to $CONFLUENCE_URL with the admin PAT. */
    private static McpSyncClient newClient() {
        return newClientWithToken(CONFLUENCE_PAT);
    }

    private static McpSyncClient newClientWithToken(String token) {
        McpClientTransport transport = HttpClientStreamableHttpTransport
                .builder(CONFLUENCE_URL)
                .endpoint(MCP_ENDPOINT)
                .connectTimeout(Duration.ofSeconds(5))
                .openConnectionOnStartup(false)
                .httpRequestCustomizer((builder, method, uri, body, ctx) ->
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
```

In `@BeforeClass`, after the `Assume` guards, initialize + connect the client:
```java
        client = newClient();
        InitializeResult init = client.initialize();
        assertNotNull("initialize returned null", init);
```

> **Method-name caveat:** the transport builder methods (`.builder(url)`, `.endpoint(...)`, `.connectTimeout(...)`, `.openConnectionOnStartup(...)`, `.httpRequestCustomizer(...)`) and client builder methods (`.requestTimeout`, `.initializationTimeout`, `.clientInfo`) are copied from Jira's working 2.0.0-M3 e2e and confirmed against `.upstream/java-sdk/.../HttpClientStreamableHttpTransport.java`. The `httpRequestCustomizer` lambda signature is `(HttpRequest.Builder, String method, URI, Object body, McpTransportContext)`. If anything mismatches at compile time, the Jira file is the source of truth — do not invent signatures.

- [ ] **Step 3: Port the protocol / tools-list / read-tool / CRUD tests to the SDK client**

Convert each existing `@Test` from raw JSON-RPC POSTs to SDK calls, preserving coverage (spec §8):
- **Protocol:** `client.initialize()` returns server info; `client.ping()` succeeds.
- **Tools list parity:** `ListToolsResult r = client.listTools(); assertEquals(28, r.tools().size());` and assert tool names match the registry.
- **Schema validation:** every `Tool.inputSchema()` is non-null and contains `"$schema"` = 2020-12.
- **Read tools:** `call("search", Map.of("query","type=page", "limit", 5))`, `call("search_user", ...)`, `call("get_spaces", ...)` return non-error results.
- **Page CRUD lifecycle:** `create_page` → `get_page` → `add_comment` → `add_label` → `delete_page`, asserting upstream-format fields on the responses (mirror the current lifecycle test's assertions, but read results from `CallToolResult.content()` text).

Mirror the Jira e2e's structure for each (`../jira-mcp-plugin/.../e2e/McpEndpointE2ETest.java`).

- [ ] **Step 4: Add the compliance-item assertions**

New tests (spec §8):
- **Tool annotations correct:** for `search`, `Tool.annotations().readOnlyHint()` is true; for `update_page`, `replace_section`, `delete_page`, `destructiveHint()` is true; for `append_to_page`, `create_page`, `destructiveHint()` is false.
- **Capabilities correct:** `InitializeResult.capabilities()` declares `tools` (with `listChanged=false`) and `logging`, and does NOT declare `resources` or `completions`.

```java
    @Test
    public void toolAnnotationsAreCorrect() {
        Map<String, Tool> byName = new HashMap<>();
        for (Tool t : client.listTools().tools()) byName.put(t.name(), t);
        assertTrue(byName.get("search").annotations().readOnlyHint());
        assertTrue(byName.get("update_page").annotations().destructiveHint());
        assertTrue(byName.get("replace_section").annotations().destructiveHint());
        assertTrue(byName.get("delete_page").annotations().destructiveHint());
        assertFalse(byName.get("append_to_page").annotations().destructiveHint());
        assertFalse(byName.get("create_page").annotations().destructiveHint());
    }
```

- [ ] **Step 5: Add the security acceptance tests (mandatory — spec §8 a–f)**

Keep these as raw `HttpClient` calls (they assert HTTP-level behavior the SDK client hides). Reuse the existing `HttpClient HTTP` field:
- **(a) Auth routing (§4.1):** an unauthenticated POST and an invalid-PAT POST to `/plugins/servlet/mcp` return a JSON body with status 401 and a `WWW-Authenticate` header — NOT a 302 redirect to a login page. A valid-PAT POST returns a JSON/MCP response.
```java
    @Test
    public void unauthenticatedReturnsJson401NotLoginRedirect() throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(CONFLUENCE_URL + MCP_ENDPOINT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}"))
                .build();
        HttpResponse<String> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        assertEquals(401, resp.statusCode());
        assertTrue(resp.headers().firstValue("WWW-Authenticate").isPresent());
        assertFalse("must not be a login redirect", resp.body().toLowerCase().contains("<html"));
    }
```
- **(b) Body cap (§6.3):** an oversized fixed-length body, an oversized chunked body, and an oversized no-`Content-Length` body each return 413. (Build a >1 MB JSON payload; for chunked, omit `Content-Length` via a streaming `BodyPublisher`.)
- **(c) Anonymous rate limit (§6.3):** >120 rapid unauthenticated/invalid-token requests eventually return 429 with `RateLimit-*` headers (per-IP bucket).
- **(d) CIMD SSRF (§6.5):** an authorize request with `client_id=https://localhost/...`, `=https://10.0.0.1/...`, and `=https://169.254.169.254/...` each return a 400 invalid_client — not a fetch attempt. (Hits `/plugins/servlet/mcp-oauth/authorize`.)
- **(e) CIMD cache bound (§6.5):** covered by a `CimdValidator` unit assertion that many distinct URLs keep `cacheSize() <= 1000`; add it to `CimdValidatorTest`:
```java
    @Test
    public void cacheStaysBounded() {
        for (int i = 0; i < 5000; i++) {
            try { validator.resolve("https://blocked.invalid.test/" + i); }
            catch (CimdValidator.CimdException ignored) {}
        }
        assertTrue("cache must stay bounded", validator.cacheSize() <= 1000);
    }
```
- **(f) OIDC well-known (§6.5):** both `GET /.well-known/openid-configuration` and `GET /plugins/servlet/mcp-oauth/openid-configuration` return the discovery document (200, JSON with `issuer`), not a 404 or login redirect.

- [ ] **Step 6: Run unit tests (CIMD bound test runs offline)**

Run: `export JAVA_HOME="$(mise where java)" && atlas-mvn test -Dtest=CimdValidatorTest`
Expected: PASS, including `cacheStaysBounded`.

- [ ] **Step 7: Deploy to the live instance and run the e2e gate**

Ensure the live Confluence 10.2.11 JVM has `-Datlassian.plugins.filter.async.default=true` (spec §6.1, §10). Then:

Run: `export JAVA_HOME="$(mise where java)" && just deploy-and-test`
Expected: deploy succeeds (plugin enabled in UPM); `just e2e` → all tests green, including the async transport path (proves the JVM flag works), the auth-routing tests (proves anonymous reachability returns JSON 401), and the compliance assertions.

> If the async transport fails (e2e hangs or 500s on a non-initialize call), the JVM flag is not set — add it (or the fallback `atlassian.plugins.filter.force.async.dispatcher=true`) and restart Confluence. This is the one runtime risk called out in spec §10.

- [ ] **Step 8: Commit**

```bash
git add -A
git commit -m "test(mcp-sdk): rewrite e2e against SDK sync client + security acceptance tests"
```

---

## Task 6: Documentation

**Spec:** §9 step 6. **Files:** Modify `CLAUDE.md`, `README` (if present), `docs/HANDOFF-jakarta-mcp-sdk-migration.md`.

- [ ] **Step 1: Update `CLAUDE.md`**

In [CLAUDE.md](CLAUDE.md):
- Change the MCP endpoint from `POST /rest/mcp/1.0/` to `POST /plugins/servlet/mcp` everywhere (the "Key Identifiers" table, "MCP Protocol — Streamable HTTP" section, and the architecture table).
- Replace the "MCP endpoint | JAX-RS at `/rest/mcp/1.0/`" architecture row with: "MCP endpoint | MCP Java SDK streamable transport, mounted as an async `<servlet-filter>` at `/plugins/servlet/mcp`".
- Add a "Hard-Won Lesson": **Async transport needs a JVM flag** — the SDK transport calls `request.startAsync()`, which only works on `<servlet-filter>` modules with `-Datlassian.plugins.filter.async.default=true` set on the Confluence JVM (verified in `atlassian-plugins-servlet-9.0.0-m002`).
- Add a "Hard-Won Lesson": **Security is a filter chain, not inline** — body-size/rate-limit/access-control/session-binding/security-headers are discrete `<servlet-filter>` modules by ascending weight; Origin is validated by the SDK's `DefaultServerTransportSecurityValidator` inside the transport.
- Update the "Tools — 28 Total" interface note: tools are adapted to SDK `SyncToolSpecification` via `McpToolAdapter`; `ToolRegistry.toSpecifications()` is the registration entry point.

- [ ] **Step 2: Update the admin UI endpoint (user-facing — ships a live config snippet)**

The admin page builds a copy-paste MCP client config pointing at the old REST route. In [src/main/resources/js/admin.js](src/main/resources/js/admin.js) line ~185, change:
```javascript
            var mcpUrl = baseUrl + "/rest/mcp/1.0/";
```
to:
```javascript
            var mcpUrl = baseUrl + "/plugins/servlet/mcp";
```
(The version bump to 1.2.0 in Task 1 busts the web-resource cache, so browsers load the updated JS.)

- [ ] **Step 3: Update the README (if present)**

Run: `ls README* 2>/dev/null`. If a README exists, update the endpoint URL and any client-configuration snippet to `/plugins/servlet/mcp`, and note the OIDC/CIMD discovery support.

- [ ] **Step 4: Mark the HANDOFF SDK-migration section done**

In [docs/HANDOFF-jakarta-mcp-sdk-migration.md](docs/HANDOFF-jakarta-mcp-sdk-migration.md), add a "DONE" note under "Step 0 / Steps 1–6" pointing at this plan and the commits, and noting that the design spec (`docs/rkstack/specs/2026-05-30-mcp-sdk-migration-design.md`) supersedes the SDK-migration portion.

- [ ] **Step 5: Verify no live source still points at the deleted endpoint**

Run: `grep -rn "/rest/mcp/1.0" src/main src/test README* CLAUDE.md 2>/dev/null`
Expected: **zero** hits. (Historical references in `docs/` plan/spec/HANDOFF files are fine — they document the migration.) If any `src/` or doc-config hit remains, fix it before committing.

- [ ] **Step 6: Commit**

```bash
git add CLAUDE.md docs/HANDOFF-jakarta-mcp-sdk-migration.md src/main/resources/js/admin.js README* 2>/dev/null
git commit -m "docs(mcp-sdk): endpoint URL, async JVM flag, filter-chain architecture; fix admin UI endpoint"
```

- [ ] **Step 7: Final acceptance — merge gate**

Confirm `just e2e` is green against the live instance (Task 5 step 7) and Step 5's stale-endpoint grep is clean. Per the spec invariant, only now is the branch eligible to merge to `main`. Do NOT merge as part of this plan unless the user explicitly asks.

---

## Self-Review notes (for the implementer)

- **Atomicity (Task 3):** every file in Task 3 lands in ONE commit. Do not push a green build mid-task that exposes `/plugins/servlet/mcp` without the filter chain (spec §9 step 3, the round-2 Codex finding).
- **Confluence ≠ Jira auth:** all filters resolve the user via `AuthenticatedUserThreadLocal`/`ConfluenceUser` and groups via `UserAccessor.hasMembership` — NOT SAL `UserManager`/`GroupManager` (spec §5). The `@ComponentImport` beans are `UserAccessor` + `ApplicationProperties`.
- **Filter location:** the six security filters run at DEFAULT location (post-auth, so the thread-local is populated). Only `OAuthAnonymousFilter` is `before-login`.
- **SDK method names:** `McpBootstrap`, `McpTransportFilter`, `McpToolAdapter`, and the e2e client are ported from Jira's working 2.0.0-M3 code. If any builder signature mismatches, the Jira file + `.upstream/java-sdk/` are the source of truth — do not invent signatures.
- **Deferred (do NOT add):** resources/resource-templates, completions, MCP Apps widgets, `outputSchema`/`structuredContent`. The Confluence `McpBootstrap` capabilities are `tools(false).logging()` only.
