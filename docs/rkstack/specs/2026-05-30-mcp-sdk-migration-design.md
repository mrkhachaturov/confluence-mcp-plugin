# Design — MCP Java SDK migration for confluence-mcp-plugin

**Date:** 2026-05-30
**Branch:** `feature/platform-10.2.11-jakarta-sdk` (all work stays here; merge to `main` only when `just e2e` is green)
**Status:** Approved design — ready for implementation plan
**Supersedes the SDK-migration portion of:** `docs/HANDOFF-jakarta-mcp-sdk-migration.md`

---

## 1. Goal

Replace the hand-rolled JSON-RPC / MCP layer with the official **MCP Java SDK** (`io.modelcontextprotocol.sdk`, version `2.0.0-M3`), mirroring the sibling `jira-mcp-plugin`, which already completed this migration on the identical platform (`platform-public-api:8.3.16`).

The 28 tools — their names, parameters, descriptions, and behavior — do not change. `ConfluenceRestClient`, `ResponseTransformer`, `StorageToMarkdown`, `MarkdownToStorage`, the admin UI, and the tool `execute()` bodies are untouched except where the tool interface gains SDK-aware methods. Upstream parity with `mcp-atlassian` is preserved.

This is a transport-and-protocol swap, plus the spec-compliance polish that is cheap to do at the same time. It is **not** a behavior change for the tools.

---

## 2. Guiding principle — do it right the first time

The Jira plugin reached its current state in two passes: a core SDK swap (branch `feature/jakarta-jira-11`, on SDK `2.0.0-M2`), then a separate v1.4.0 spec-compliance sprint that **reworked** several pieces of the first pass — origin validation, the SDK version, server capabilities, server identity, the JSON-Schema dialect.

We are not Jira. We have the benefit of hindsight and a single migration window. So wherever Jira later went back and rewrote code from its first pass, **we write the final form once.** We do not reproduce a throwaway intermediate just to match the commit-by-commit history.

This principle has a boundary: it applies only to code we are already touching in the swap and would otherwise rewrite. It does **not** pull in net-new features that merely *append* to that code. Net-new features are deferred unless explicitly listed as in-scope below.

---

## 3. Scope

The agreed scope is **"SDK migration + MCP spec compliance, minus UI widgets and resources."**

### In scope

**Core swap (the transport replacement):**
- Add the MCP SDK dependencies and embed them via OSGi.
- New `McpBootstrap` builds the SDK transport + `McpSyncServer`.
- New `McpTransportFilter` mounts the transport as an async servlet-filter at `/plugins/servlet/mcp`.
- New `ConfluenceAuthContextExtractor` feeds the authenticated user + Authorization header into the SDK transport context.
- New `McpToolAdapter` turns each `McpTool` into an SDK `SyncToolSpecification`; `ToolRegistry` gains `toSpecifications()`.
- The six-filter security chain (body size, rate limit, access control, session binding, security headers, transport) replaces the inline security logic currently living in `McpResource`.
- Delete `JsonRpcHandler` and `McpResource`; remove the `/rest/mcp/1.0` REST module.
- Rewrite the e2e suite against the SDK sync client.

**Spec-compliance items folded in (final form from the start — see §2):**
- **SDK `2.0.0-M3` with builder APIs** (not M2 canonical constructors).
- **Correct server capabilities:** `tools(false)`, `logging`. No false `listChanged`. No `resources` and no `completions` capability (we expose neither — see "Out of scope").
- **Server identity:** full `Implementation` builder — title, description, websiteUrl, icon — plus model-facing `instructions`. Confluence-authored text and logo.
- **Tool annotations:** `title`, `readOnlyHint`, `destructiveHint`, `idempotentHint`, `openWorldHint`.
- **JSON Schema 2020-12 dialect** injected into each tool's input schema.
- **RateLimit-\* response headers** (`RateLimit-Limit/Remaining/Reset`).
- **WWW-Authenticate scope challenges** on 401/403.
- **OAuth discovery:** OpenID Connect Discovery metadata, Client ID Metadata Documents (CIMD), and the corrected protected-resource URL pointing at `/plugins/servlet/mcp`.

### Out of scope (deferred to a later spec)

- **MCP Apps UI widgets** — `ui://` resources, the JS widget bundle, `McpUiAppCapabilities`, tool `visibility`, and the data layer that backs them: **`structuredContent` and `outputSchema`**. Without a widget to render them, structured payloads have no consumer, so the whole layer waits.
- **MCP resources and resource templates** — `ResourceRegistry`, `confluence://page/{id}`, the `resources/*` handlers, and the `resources` server capability.
- **completion/complete (argument autocompletion)** — deferred *because it is coupled to the above*. In MCP, a completion reference is only a `PromptReference` or a `ResourceReference` (a resource-template URI); the SDK's `CompleteReference` has no "tool argument" variant. Completing `space_key` would therefore require a resource template such as `confluence://space/{spaceKey}` (or a prompt) to attach to — exactly the resource layer we are deferring. So `CompletionRegistry`, the `.completions()` capability, and any `space_key` autocompletion move to the later spec alongside resources/templates.

These are additive. Deferring them forces no rework of the code we write now.

---

## 4. Endpoint URL change (breaking)

The public MCP endpoint moves from `/rest/mcp/1.0/` to **`/plugins/servlet/mcp`**, matching Jira.

The SDK's streamable transport calls `request.startAsync()`. On the Atlassian plugin framework only `<servlet-filter>` modules can be async-capable (see §6.1), and they live under `/plugins/servlet/*`. Keeping the old `/rest/*` path would mean fighting the JAX-RS module for ownership of the URL. The plugin is pre-1.x with no widely pinned external clients, so the clean move is worth the one-time URL change. OAuth metadata and docs are updated to advertise the new URL.

### 4.1 Anonymous reachability (Confluence-specific — do not skip)

The current endpoint gets anonymous access through `@UnrestrictedAccess` on the JAX-RS `McpResource` plus the `before-login` `OAuthAnonymousFilter` (CLAUDE.md: *"Anonymous REST access in Confluence 10: use `@UnrestrictedAccess` combined with a `before-login` servlet filter for full anonymous access"*). The new design deletes that JAX-RS resource, so the annotation is gone. Without a replacement, Confluence's login layer would intercept an unauthenticated request to `/plugins/servlet/mcp` and return an **HTML login redirect** — which breaks MCP discovery and the OAuth challenge flow, because clients expect a JSON body with a `WWW-Authenticate` header (RFC 9728), not a 302 to a login page.

This is a real divergence from Jira: Jira does **not** route `/plugins/servlet/mcp` through its before-login filter, relying on platform default servlet behavior. Confluence's documented constraint says we must be explicit. Therefore:

- The `before-login` `OAuthAnonymousFilter` (which carries `@UnrestrictedAccess`) is extended to cover `/plugins/servlet/mcp` (in addition to `/plugins/servlet/mcp-oauth/*` and `/.well-known/*`). It does not authenticate — it only lets the request pass Confluence's login layer so it reaches the MCP filter chain, where `AccessControlFilter` performs the real auth check and returns a proper `401` + `WWW-Authenticate` (or `403`) as a JSON response.
- Real authentication still happens via PAT / OAuth Bearer token at `AccessControlFilter` (§6.3); anonymous reachability is *not* anonymous authorization.
- **Acceptance tests (added to the e2e gate):** an unauthenticated request, an invalid-PAT request, and a valid-PAT/OAuth request to `/plugins/servlet/mcp` each return a JSON/MCP response (with `WWW-Authenticate` on the 401), never a Confluence login-page redirect.

---

## 5. Platform parity — Confluence vs. Jira (verified)

The platform BOM is shared (`platform-public-api:8.3.16`), but product APIs differ. Each mechanism below was checked against the Confluence source and the Confluence 10.2.11 framework jars before being written into this design.

| # | Mechanism | Verdict | Confluence approach |
|---|-----------|---------|---------------------|
| 1 | Async servlet-filter | **Same** (verified by bytecode) | `ServletFilterModuleDescriptor.getDefaultAsyncSupported()` in `atlassian-plugins-servlet-9.0.0-m002` reads `Boolean.getBoolean("atlassian.plugins.filter.async.default")`. Mirror Jira's `McpTransportFilter`; same JVM flag. |
| 2 | Auth context | **Different** | `AuthenticatedUserThreadLocal.get()` → `ConfluenceUser`; `user.getKey().getStringValue()`, `user.getName()`. Not SAL `UserManager`. |
| 3 | Group membership | **Same** | `UserAccessor.hasMembership(group, username)` — already used by the current plugin. |
| 4 | Base URL | **Same** | SAL `ApplicationProperties.getBaseUrl()` with the `confluenceBaseUrl` override. |
| 5 | Completions target | **Different — deferred** | Completions are out of scope (§3). When added later: spaces, not projects — list via `/rest/api/space`, key field `key`, on a `confluence://space/{spaceKey}` template. |
| 6 | OAuth discovery | **Different (add)** | Current `OAuthServlet` is structurally parallel to Jira's (same `/metadata`, `/protected-resource`, `/register`, `/authorize`, `/token`, `/callback`, same `OAuthStateStore`, PKCE, base-URL logic) but lacks OIDC + CIMD. Port them in. |
| 7 | Jackson / OSGi | **Same** | BOM resolves `jackson-databind` to 2.21.2. Exclude `jackson-dataformat-yaml` from `mcp-json-jackson2` to avoid version skew (the SDK pulls 2.18.3). Note: Confluence has no BannedDependencies enforcer, so this is skew-avoidance, not an enforcer fix. |
| 8 | Overall architecture | **Different (this is the work)** | Confluence does origin/auth/rate-limit/body-size/headers **inline** in `McpResource` and has no `McpBootstrap`. The swap relocates these into `McpBootstrap` + the filter chain. |

### Verification evidence for #1 (the critical de-risk)

```
javap com.atlassian.plugin.servlet.descriptors.ServletFilterModuleDescriptor
  → static final String ASYNC_DEFAULT_SYSPROP;
  → protected boolean getDefaultAsyncSupported();           // reads ASYNC_DEFAULT

ServletFilterModuleDescriptor$1.create():
  ldc           "atlassian.plugins.filter.async.default"
  invokestatic  java/lang/Boolean.getBoolean
```

Jar: `~/.m2/.../atlassian-plugins-servlet/9.0.0-m002` — the framework Confluence 10.2.11 resolves. There is also a second flag, `atlassian.plugins.filter.force.async.dispatcher`, available as a fallback. The mechanism is identical to Jira's.

---

## 6. Architecture

### 6.1 Transport — `McpBootstrap` + `McpTransportFilter`

`McpBootstrap` (a `@Named` component, lazily building the transport on first init) constructs:

```
ObjectMapper          → JacksonMcpJsonMapper, DefaultJsonSchemaValidator
HttpServletStreamableServerTransportProvider.builder()
    .jsonMapper(...)
    .mcpEndpoint("/plugins/servlet/mcp")
    .contextExtractor(confluenceAuthContextExtractor)
    .securityValidator(DefaultServerTransportSecurityValidator … origin allowlist)
    .build()
McpServer.sync(transport)
    .serverInfo(Implementation.builder("confluence-mcp-plugin", VERSION).title…description…websiteUrl…icons…)
    .instructions(SERVER_INSTRUCTIONS)
    .capabilities(tools(false).logging())
    .tools(toolRegistry.toSpecifications())
    .build()
```

`McpTransportFilter` is a `jakarta.servlet.Filter` that **owns** the endpoint: on `init()` it calls `bootstrap.buildTransport()` and `delegate.init(new FilterBackedServletConfig(filterConfig))`; on `doFilter()` it calls `delegate.service(req, resp)` and never calls `chain.doFilter()`. This is the async workaround — `<servlet>` modules hard-code `asyncSupported=false`; `<servlet-filter>` modules honor the JVM flag verified in §5.

**Deployment prerequisite:** `-Datlassian.plugins.filter.async.default=true` on the Confluence JVM. This is documented as a required operational step and confirmed against the live 10.2.11 instance during the e2e gate.

### 6.2 Origin validation — SDK validator (final form, per §2)

Origin checking moves straight to the SDK's `DefaultServerTransportSecurityValidator`, configured in `McpBootstrap`. We skip Jira's throwaway hand-rolled `OriginValidationFilter` entirely. Allowlist: the Confluence base URL (with a `:*` port variant for reverse proxies), `https://claude.ai`, `https://claude.com`, `https://chatgpt.com`, `https://chat.openai.com`, and the loopback set (`localhost`, `127.0.0.1`, `[::1]`, each with a `:*` variant). A missing Origin header (non-browser clients) is allowed; a mismatch returns 403.

### 6.3 Security filter chain

The concerns currently inline in `McpResource` become discrete `<servlet-filter>` modules, ascending by weight, all scoped to `/plugins/servlet/mcp`. Origin is handled by the SDK validator inside the transport, so it is not a filter:

```
BodySizeLimit(200) → RateLimit(300) → AccessControl(400) → SessionBinding(500) → SecurityHeaders(550) → Transport(600)
```

- **BodySizeLimitFilter** — 1 MB cap (current `MAX_BODY_BYTES`), enforced on **actual bytes read**, not on a trusted `Content-Length` header. The filter wraps the request input stream and aborts once the limit is exceeded, so chunked transfers and requests with a missing or lying `Content-Length` are still capped before the SDK transport reads the body. A `Content-Length` over the limit is rejected early as a fast path. The endpoint is reachable unauthenticated (§4.1), so this guard must not be bypassable. Tests cover oversized fixed-length, oversized chunked, and no-`Content-Length` bodies.
- **RateLimitFilter** — 120 calls/min, keyed **per authenticated user, and per client IP for unauthenticated or unresolved-user requests**. This filter runs before `AccessControlFilter`, and §4.1 deliberately lets unauthenticated traffic reach the chain (to receive a JSON `WWW-Authenticate`), so the anonymous case must have a real bucket rather than a shared null key. Over-limit returns `429` with `RateLimit-*` (and `Retry-After`) headers. Tests cover repeated unauthenticated and invalid-token requests hitting the limit.
- **AccessControlFilter** — enforces auth (rejects unauthenticated with a `WWW-Authenticate` challenge) and the allowed-users/allowed-groups policy via `UserAccessor.hasMembership`.
- **SessionBindingFilter** — binds the MCP session to the authenticated user.
- **SecurityHeadersFilter** — `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, frame options.
- **McpTransportFilter** — the endpoint.

Helpers `BufferedRequestWrapper` and `CapturingResponseWrapper` are ported from Jira as needed by the body-size and header filters.

### 6.4 Tool layer — `McpTool`, `McpToolAdapter`, `ToolRegistry`

`McpTool` keeps `name / description / inputSchema / isWriteTool / requiredPluginKey / execute`. It gains SDK-aware default methods, all with sane defaults so existing tools need no edits unless they want to override:

- `title()` → `null`
- `isDestructiveTool()` → `false`, overridden to `true` by every tool that **deletes or overwrites existing content**: `delete_page`, `delete_attachment`, `update_page`, `replace_section`. Additive writes (`create_page`, `add_comment`, `reply_to_comment`, `add_label`, `append_to_page`, `prepend_to_page`) stay non-destructive. `move_page` relocates rather than destroys content, so it stays non-destructive. A false destructive hint would let a client skip confirmation on a content-replacing write, so this is a per-tool decision, not a blanket delete-only rule.
- `idempotentHint()` → `!isWriteTool()`
- `openWorldHint()` → `true` (every tool calls the Confluence REST API)
- `executeWithSdkProgress(args, authHeader, exchange, progressToken)` → defaults to `execute(args, authHeader)`

`McpToolAdapter.adapt(tool)` builds a `SyncToolSpecification`:
- `ToolAnnotations.builder().title().readOnlyHint(!isWriteTool).destructiveHint().idempotentHint().openWorldHint()`
- `Tool.builder().name().title().description().inputSchema(withSchemaDialect(tool.inputSchema())).annotations(...)`
- `withSchemaDialect()` injects `"$schema": "https://json-schema.org/draft/2020-12/schema"` when absent.
- `callHandler` → `dispatch(tool, exchange, request)`: reads auth from `exchange.transportContext()`, runs the tool, wraps the string result as `CallToolResult` (text content; `isError=true` with the message on `McpToolException`).

No `outputSchema` / `structuredContent` — that is the deferred widget data layer.

`ToolRegistry.toSpecifications()` applies the same three filters it uses today — capability gate (`requiredPluginKey` enabled), admin-disabled list, read-only-hides-write — and adapts the survivors.

### 6.5 OAuth discovery additions

The existing `OAuthServlet` and `OAuthAnonymousFilter` keep their structure. We add, mirroring Jira:
- **OpenID Connect Discovery** — the metadata (issuer, authorization/token/registration endpoints, supported response types / PKCE methods / grant types / scopes, `client_id_metadata_document_supported: true`). The plugin does not issue ID tokens; this advertises the same authorization server. It must be reachable at **both** public URLs clients probe:
  - `/.well-known/openid-configuration` — served directly by the `before-login` `OAuthAnonymousFilter` (servlets cannot serve at the context root), exactly as the existing `/.well-known/oauth-*` paths are. The filter's matching must include `openid-configuration`, not just the `oauth-` prefix.
  - `/plugins/servlet/mcp-oauth/openid-configuration` — the servlet-local route.
  - **Acceptance tests** assert both URLs return the OIDC document (not a 404 or login redirect) and that `issuer` / endpoint paths are correct.
- **CIMD** — a `CimdValidator` (under `rest/oauth/`) that recognizes an `https://…/path` client_id, fetches and validates the metadata document, caches it, and feeds the allowed redirect URIs into the authorize flow. Validation controls: 8 KB body cap, 10 s timeout, HTTPS-only, redirect-URI rules, `client_id` self-match.
  - **SSRF defense (mandatory).** The client_id URL is attacker-controlled and the fetch runs from inside the Confluence JVM (reachable by an *unauthenticated* authorize request), so resource-limit controls are not enough. The validator MUST also: follow **no redirects**; resolve the host's IP **before connecting** and **reject** loopback, link-local, private (RFC 1918), unique-local, and cloud-metadata ranges (`169.254.169.254`, `fd00::`, etc.) unless an explicit admin allowlist opts them in; **pin the connection to the resolved address** (or re-resolve and re-check) to defeat DNS-rebinding; and cap total fetch time/size as above. Unit + e2e tests MUST cover blocked `client_id` URLs pointing at `localhost`, a private IP, and the metadata IP.
  - **Bounded cache (mandatory).** The cache is keyed by attacker-supplied client_id URLs, so it MUST be bounded: a hard cap on entries (and/or total bytes), a short positive TTL, and bounded **negative caching** for fetch failures (so a flood of unique URLs neither grows the heap without limit nor re-hammers outbound hosts). A test asserts that many distinct `client_id` URLs do not grow the cache past its cap.
- **Protected-resource URL fix** — the protected-resource metadata (`/plugins/servlet/mcp-oauth/protected-resource` and `/.well-known/oauth-protected-resource`) advertises `<base>/plugins/servlet/mcp` as the resource, matching the new endpoint.

`OAuthAnonymousFilter` url-patterns are updated: drop `/rest/mcp/1.0`, keep `/plugins/servlet/mcp-oauth/*` and `/.well-known/*`, and **add `/plugins/servlet/mcp`** so the transport endpoint stays anonymously reachable past Confluence's login layer (see §4.1).

### 6.6 pom.xml / OSGi

- Add `io.modelcontextprotocol.sdk:mcp-core:2.0.0-M3` and `mcp-json-jackson2:2.0.0-M3`, excluding `jackson-dataformat-yaml`.
- Pin `slf4j-api` as `provided` so the plugin uses Confluence's slf4j (avoids OSGi split-package / log loss).
- OSGi `<Private-Package>` embeds: `io.modelcontextprotocol.*`, `com.networknt.schema.*`, `com.networknt.*`, `reactor.*`, `org.reactivestreams.*`, `com.ethlo.time.*`.
- Widen `<Import-Package>` with `resolution:="optional"` for SDK transitives Confluence does not export (jackson-dataformat-yaml, `io.micrometer.*`, `reactor.blockhound.*`, `org.graalvm.polyglot`, `org.jcodings*`, `org.joni*`, `javax.annotation*`).
- Keep `<DynamicImport-Package>*</DynamicImport-Package>`.
- Bump plugin version (1.1.2 → 1.2.0) for cache-busting and release tracking. AMPS stays at 9.12.5; platform BOM at 8.3.16 (already in place).

---

## 7. What gets deleted

| Deleted | Replaced by |
|---------|-------------|
| `JsonRpcHandler.java` | SDK `McpSyncServer` dispatch |
| `McpResource.java` (POST/GET/DELETE, session map, SSE, inline origin/auth/rate/body/header/protocol logic) | SDK streamable transport + the filter chain + SDK security validator |
| `<rest key="mcp-rest" path="/mcp">` module | `<servlet-filter key="mcp-transport-filter">` at `/plugins/servlet/mcp` |

---

## 8. Testing & acceptance

Rewrite the ~22 e2e tests in `McpEndpointE2ETest.java` against the **SDK sync client** (`McpClient.sync` over streamable HTTP to `/plugins/servlet/mcp`), mirroring Jira's rewrite. Coverage stays equivalent: protocol (initialize/ping), tools/list parity (28 tools), schema validation, read tools (search, search_user, list_spaces), the page CRUD lifecycle, session create/use/delete, and the OAuth refresh paths. New assertions cover the in-scope compliance items: tool annotations present **and correct** across representative tools (e.g. `readOnlyHint` true on `search`; `destructiveHint` true on `update_page`/`replace_section`/`delete_page`, false on `append_to_page`/`create_page`), capabilities correct (no false `listChanged`, no `completions`/`resources` declared), OIDC discovery + protected-resource URL correct, and `RateLimit-*` headers present.

Security acceptance tests (regressions the SDK swap could introduce) are mandatory: (a) **auth routing** — unauthenticated / invalid-PAT / valid-PAT requests to `/plugins/servlet/mcp` return JSON + `WWW-Authenticate`, never a Confluence login redirect (§4.1); (b) **body cap** — oversized fixed-length, oversized chunked, and no-`Content-Length` POSTs are rejected (§6.3); (c) **anonymous rate limit** — repeated unauthenticated / invalid-token requests hit `429` with `RateLimit-*` headers via the per-IP bucket, before `AccessControl` (§6.3); (d) **CIMD SSRF** — `client_id` URLs pointing at `localhost`, a private IP, and the cloud-metadata IP are blocked (§6.5); (e) **CIMD cache bound** — many distinct `client_id` URLs do not grow the cache past its cap (§6.5); (f) **OIDC well-known** — both `/.well-known/openid-configuration` and `/plugins/servlet/mcp-oauth/openid-configuration` return the discovery document, not a 404/redirect (§6.5).

**Acceptance gate:** `just e2e` green against the live Confluence 10.2.11 instance, with `-Datlassian.plugins.filter.async.default=true` set on its JVM.

---

## 9. Implementation order

Each step is a separate commit on `feature/platform-10.2.11-jakarta-sdk`. The build stays green after every step.

1. **SDK deps + OSGi embedding.** Add dependencies, `<Private-Package>`, widened imports, slf4j pin. Nothing wired yet; `atlas-mvn clean package` succeeds.
2. **Tool adapter (before bootstrap, so bootstrap compiles).** `McpToolAdapter` (annotations + 2020-12 dialect), `ToolRegistry.toSpecifications()`, the SDK-aware `McpTool` default methods, `isDestructiveTool()` overrides on delete *and content-overwrite* tools (`delete_page`, `delete_attachment`, `update_page`, `replace_section` — §6.4). Purely additive — the old `McpResource`/`JsonRpcHandler` still serve traffic and the build stays green.
3. **Transport + bootstrap + security chain — one atomic commit.** The endpoint must never be reachable without its replacement controls, so the following land together in a single commit: the full security filter chain (`BodySizeLimitFilter` — cap on actual bytes read, §6.3; `RateLimitFilter` — per-user/per-IP + `RateLimit-*` headers, §6.3; `AccessControlFilter` — auth + `WWW-Authenticate`; `SessionBindingFilter`; `SecurityHeadersFilter`; the request/response wrappers, adapted to Confluence auth/group APIs); `McpBootstrap` (transport, server info, capabilities, SDK origin validator, consuming `toolRegistry.toSpecifications()` from step 2); `McpTransportFilter`; `ConfluenceAuthContextExtractor`; the plugin-descriptor swap (filter at `/plugins/servlet/mcp`); the before-login anon-filter extension for `/plugins/servlet/mcp` (§4.1); and the deletion of `JsonRpcHandler`/`McpResource`. No intermediate commit exposes `/plugins/servlet/mcp` anonymously without body cap, rate limit, access control, and session binding.
4. **OAuth discovery.** OIDC discovery route + explicit well-known serving (§6.5), `CimdValidator` (SSRF defenses + bounded cache, §6.5), protected-resource URL fix.
5. **E2E rewrite.** Port the suite to the SDK sync client; add the security acceptance tests (§8); run `just e2e` against live 10.2.11 — the acceptance gate.
6. **Docs.** Update `CLAUDE.md` (endpoint URL, JVM flag, the new transport architecture), `README`, and mark the HANDOFF's SDK-migration section done.

---

## 10. Risks

- **JVM flag on the live instance.** The async filter needs `-Datlassian.plugins.filter.async.default=true`. The mechanism is verified in the framework jar (§5), but the flag must actually be set on the running 10.2.11 server. Validated at the e2e gate; the fallback flag `atlassian.plugins.filter.force.async.dispatcher` exists if needed.
- **OSGi resolution of SDK transitives.** Mitigated by `<Private-Package>` embedding and optional imports, exactly as Jira proved on the same platform. First real check is step 2's deploy.
- **Jackson skew.** Host 2.21.2 vs the SDK's transitive 2.18.3 for the YAML dataformat. Mitigated by excluding `jackson-dataformat-yaml`; no enforcer to trip.
- **Endpoint URL break.** Any client pinned to `/rest/mcp/1.0/` must update. Accepted (§4); OAuth metadata advertises the new URL.
