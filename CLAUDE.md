# confluence-mcp-plugin

Native Confluence Data Center plugin that embeds an MCP (Model Context Protocol) server. AI agents connect via OAuth 2.0 or PATs. 28 tools — 23 mirrored 1:1 from the upstream [mcp-atlassian](https://github.com/sooperset/mcp-atlassian) Python project + 5 agent ergonomics tools.

## Upstream Parity

The upstream Python project at `.upstream/mcp-atlassian/` is the **source of truth** for all tool definitions. Our plugin is a Java translation — same tool names, same parameters, same descriptions, same behavior. Never invent new tools or modify tool interfaces without checking upstream first.

### Code Generation

Tool classes are generated from the upstream Python definitions:

```bash
just codegen          # runs .codegen/translate.py
```

The translator (`python3 .codegen/translate.py`):
1. Parses all `@confluence_mcp.tool()` definitions from `.upstream/mcp-atlassian/src/mcp_atlassian/servers/confluence.py` via Python AST
2. Extracts names, descriptions, parameter schemas, read/write flags, toolset categories
3. Generates Java `McpTool` classes into `.codegen/generated/tools/`
4. Produces a parity report at `.codegen/generated/report.txt`
5. Produces a `ToolRegistry_fragment.java` with registration code

After generation, copy files to `src/main/java/.../tools/` and update `ToolRegistry.java`. Some tools need hand-tuned `execute()` bodies (especially write tools that must construct Confluence REST API payloads).

### When Upstream Updates

1. Pull latest upstream into `.upstream/mcp-atlassian/`
2. Run `just codegen`
3. Review `.codegen/generated/report.txt` for new/changed tools
4. Copy generated files, fix any `execute()` bodies for new tools
5. Update `ToolRegistry.java` if new tools were added
6. Run `just e2e` to verify

## Architecture

| Layer | What |
|-------|------|
| MCP endpoint | MCP Java SDK streamable transport, mounted as async `<servlet-filter>` modules at `/plugins/servlet/mcp` (transport built by `McpBootstrap`, owned by `McpTransportFilter`) |
| OAuth proxy | Servlet at `/plugins/servlet/mcp-oauth/` — bridges MCP client OAuth with Confluence OAuth 2.0, supports refresh token pass-through |
| Tools | 23 classes in `tools/` — each calls Confluence REST API internally via `ConfluenceRestClient` |
| Response transformer | `ResponseTransformer` — whitelist-based JSON transformation matching upstream's `to_simplified_dict()`. Constructs full URLs, formats timestamps, simplifies pages/comments/labels/users |
| Storage→Markdown | `StorageToMarkdown` — jsoup-based converter for ALL [Confluence storage format](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html) elements (30+ element types) |
| Response trimmer | `ResponseTrimmer` — strips verbose fields from legacy `client.get()` responses (tools now use `client.getRaw()` + `ResponseTransformer` instead) |
| Admin | Servlet at `/plugins/servlet/mcp-admin` + REST at `/rest/mcp-admin/1.0/` |
| Config | `McpPluginConfig` backed by Confluence `PluginSettings` (key-value) |
| Auth | OAuth 2.0 (via Application Link) with refresh token support, or PAT — Confluence validates tokens, plugin checks access control |

## Build & Deploy

All commands via `just`. Env vars auto-loaded by mise from `.credentials/confluence.env`.

```bash
just build            # atlas-package (compile + JAR)
just deploy           # build + upload JAR to Confluence UPM + verify enabled
just test             # unit tests (excludes e2e)
just e2e              # e2e tests against live Confluence instance
just deploy-and-test  # build + deploy + e2e in one shot
just codegen          # regenerate tools from upstream
just clean            # atlas-clean
```

**Local builds must use `atlas-mvn`** (not plain `mvn`). The Atlassian SDK wrapper includes the Atlassian Maven repository.

## Key Identifiers

| What | Value |
|------|-------|
| Plugin key | `com.atlassian.mcp.confluence-mcp-plugin` |
| Maven coordinates | `com.atlassian.mcp:confluence-mcp-plugin` |
| MCP endpoint | `POST /plugins/servlet/mcp` |
| OAuth endpoints | `/plugins/servlet/mcp-oauth/{metadata,register,authorize,callback,token}` |
| Admin REST | `GET/PUT /rest/mcp-admin/1.0/` |
| Admin page | `/plugins/servlet/mcp-admin` |
| Target Confluence | Data Center 10.x |

## MCP Protocol — Streamable HTTP

Single endpoint `/plugins/servlet/mcp` supporting Streamable HTTP transport (MCP spec 2025-06-18).

The official **MCP Java SDK** now owns the protocol entirely — JSON-RPC framing, session ids, SSE streaming, and `Accept` negotiation are all handled by the SDK's `HttpServletStreamableServerTransportProvider` (built in `McpBootstrap`, served by `McpTransportFilter`). The plugin no longer hand-rolls `initialize` / `notifications/initialized` / `tools/list` / `tools/call` / `ping` dispatch; it only supplies the tool specifications and server capabilities. The old `JsonRpcHandler` + `McpResource` are deleted.

| Method | Action (handled by the SDK) |
|--------|--------|
| `initialize` | Return server info + capabilities + `MCP-Session-Id` header |
| `notifications/initialized` | Acknowledge |
| `tools/list` | Return filtered tool list |
| `tools/call` | Dispatch to tool, return result |
| `ping` | Keep-alive |

### Session management (SDK-managed)

- `MCP-Session-Id` returned on `initialize`, required on subsequent requests
- Session lifecycle (creation, lookup, expiry) is owned by the SDK transport
- DELETE closes session, 404 returned for expired/unknown sessions

### Security

- Body-size limit, rate limiting, access control, session binding, and security headers are discrete `<servlet-filter>` modules (see "Security is a filter chain" lesson below)
- **Origin validation** (MUST per spec): handled by the SDK's `DefaultServerTransportSecurityValidator` inside the transport (not a filter). Invalid Origin → 403
- **MCP-Protocol-Version** header validated by the SDK on non-initialize requests

## Tools — 28 Total

| Package | Count | Toolset | Description |
|---------|-------|---------|-------------|
| `pages/` | 13 | `confluence_pages` | Search, get, create, update, delete, move, history, diff, append, prepend, replace section, convert content |
| `comments/` | 3 | `confluence_comments` | Get, add, reply to comments |
| `labels/` | 2 | `confluence_labels` | Get and add labels |
| `attachments/` | 7 | `confluence_attachments` | Upload, download, list, delete attachments and images |
| `spaces/` | 1 | `confluence_spaces` | List available spaces |
| `users/` | 1 | `confluence_users` | Search users |
| `analytics/` | 1 | `confluence_analytics` | Page view statistics (Cloud-only) |

### Tool Interface

Every tool implements `McpTool`:

```java
public interface McpTool {
    String name();                          // snake_case, matches upstream
    String description();                   // from upstream docstring
    Map<String, Object> inputSchema();      // JSON Schema from upstream Field() annotations
    boolean isWriteTool();                  // true = hidden in read-only mode
    default String requiredPluginKey() { return null; }
    String execute(Map<String, Object> args, String authHeader) throws McpToolException;
}
```

Each `McpTool` is adapted to the SDK's `SyncToolSpecification` via `McpToolAdapter`. `ToolRegistry.toSpecifications()` is the registration entry point that hands the filtered specs to the SDK server. Server capabilities are `tools(false).logging()` only — no resources or completions.

### Writing execute() Bodies

Tools call Confluence REST API directly via `ConfluenceRestClient.get/post/put/delete()`. Key patterns:

- **GET tools**: Build query string, return `client.get(path + query, authHeader)`
- **POST/PUT tools**: Build `Map<String, Object>`, serialize with Jackson, send as body
- **Create page**: Must structure as `{"type": "page", "title": "...", "space": {"key": "..."}, "body": {"storage": {"value": "...", "representation": "storage"}}}`
- **Add comment**: Structure as `{"type": "comment", "container": {"id": "...", "type": "page"}, "body": {"storage": {"value": "...", "representation": "storage"}}}`
- **Add label**: Structure as `[{"prefix": "global", "name": "..."}]`

### Content Conversion (bidirectional)

**Markdown → Storage** (write tools: `create_page`, `update_page`, `add_comment`, `reply_to_comment`):

- `MarkdownToStorage` using flexmark-java 0.64.8 with GFM extensions (tables, strikethrough, task lists, autolinks)
- Extended Markdown syntax auto-converted to native Confluence macros:
  - GitHub alerts (`> [!NOTE/TIP/IMPORTANT/WARNING]`) → info/tip/note/warning panels
  - Status labels (`{status:Text|color}`) → status badge macros
  - Table of contents (`{toc}` or `[TOC]`) → TOC macro
  - Task lists (`- [x]` / `- [ ]`) → native `ac:task-list`
  - Expand sections (`<details><summary>`) → expand macro
- Three content formats: `markdown` (default, converted), `wiki` (passed as-is), `storage` (passed as-is)

**Storage → Markdown** (read tools: `get_page`, `get_comments`, `get_page_children`, `get_page_history`):

- `StorageToMarkdown` using jsoup (XML parser) + flexmark-html2md-converter
- Full [Confluence storage format](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html) support:
  - **Macros:** code (with language), noformat, info/note/warning/tip, panel, expand, toc, anchor, jira, status, section/column, excerpt, include, children, attachments, blog-posts, profile, content-by-label
  - **Elements:** ac:image (ri:attachment, ri:url), ac:link (ri:page, ri:attachment, ri:user, ri:space, ri:shortcut, ri:blog-post), ac:emoticon (Unicode mapping), ac:task-list/ac:task, ac:layout/ac:layout-section/ac:layout-cell, ac:placeholder
  - **Unknown macros:** graceful fallback — unwrap body content

### Response Transformation

Each tool transforms raw Confluence API JSON into upstream's `to_simplified_dict()` format via `ResponseTransformer`:

- **Whitelist approach** — only include fields the upstream model defines (not blacklist stripping)
- **URL construction** — `{baseUrl}/pages/viewpage.action?pageId={id}` (Server/DC format)
- **Timestamp formatting** — ISO 8601 → `YYYY-MM-DD HH:MM:SS` (matching upstream's `TimestampMixin`)
- Tools use `client.getRaw()` for untrimmed responses, then `ResponseTransformer` for clean output
- `ResponseTrimmer` remains for backward compatibility with legacy code paths

## Admin Config (PluginSettings keys)

| Key | Default | Purpose |
|-----|---------|---------|
| `com.atlassian.mcp.plugin.enabled` | false | Global MCP on/off |
| `com.atlassian.mcp.plugin.allowedUsers` | "" | Comma-separated usernames |
| `com.atlassian.mcp.plugin.allowedGroups` | "" | Comma-separated group names |
| `com.atlassian.mcp.plugin.disabledTools` | "" | Comma-separated tool names |
| `com.atlassian.mcp.plugin.readOnlyMode` | false | Hide write tools |
| `com.atlassian.mcp.plugin.confluenceBaseUrl` | "" | Override internal base URL |
| `com.atlassian.mcp.plugin.oauthClientId` | "" | OAuth Application Link client ID |
| `com.atlassian.mcp.plugin.oauthClientSecret` | "" | OAuth Application Link client secret |

## E2E Tests

22 tests in `src/test/java/.../e2e/McpEndpointE2ETest.java`. Requires env vars from `.credentials/confluence.env` (auto-loaded by mise).

| Category | What |
|----------|------|
| Protocol | initialize, ping, invalid method |
| Tools list | count, upstream parity, schema validation |
| Read tools | search, search_user |
| Response format | upstream-compatible structure, full URLs, no leaked internal fields |
| Page CRUD | create → get → comment → label → delete lifecycle, response format assertions |
| Error handling | missing param, invalid ID, unknown tool |
| Streamable HTTP | session create, tool call with session, session delete |
| OAuth refresh | metadata advertises refresh_token grant, error paths (missing token, bogus token, unsupported grant) |

Tests skip automatically when `CONFLUENCE_URL`/`CONFLUENCE_PAT_RKADMIN` are not set.

## Project Structure

```
src/main/java/com/atlassian/mcp/plugin/
├── rest/
│   ├── McpResource.java              # JAX-RS MCP endpoint (POST/GET/DELETE)
│   ├── OAuthServlet.java             # OAuth proxy servlet
│   └── OAuthAnonymousFilter.java     # before-login filter for anonymous OAuth access
├── JsonRpcHandler.java                # JSON-RPC dispatch
├── ConfluenceRestClient.java          # HTTP client → Confluence REST API (get/getRaw + trimmed/raw)
├── ResponseTransformer.java           # Whitelist JSON transformation (upstream to_simplified_dict)
├── StorageToMarkdown.java             # Confluence storage format → Markdown (jsoup + flexmark)
├── MarkdownToStorage.java             # Markdown → Confluence storage format (flexmark)
├── ResponseTrimmer.java               # Legacy blacklist field stripping (used by client.get())
├── McpToolException.java              # Checked exception for tool failures
├── config/
│   ├── McpPluginConfig.java           # PluginSettings-backed configuration
│   └── OAuthStateStore.java           # In-memory OAuth state
├── admin/
│   ├── AdminServlet.java              # Admin page (Velocity)
│   └── ConfigResource.java           # Admin REST API
└── tools/
    ├── McpTool.java                   # Tool interface
    ├── ToolRegistry.java              # 28 tools registered, filtered by capability/config
    ├── pages/                         # 13 tools
    ├── comments/                      # 3 tools
    ├── labels/                        # 2 tools
    ├── attachments/                   # 7 tools
    ├── spaces/                        # 1 tool
    ├── users/                         # 1 tool
    └── analytics/                     # 1 tool

.codegen/
├── translate.py                       # Upstream Python → Java translator
└── generated/                         # Output of translate.py (not committed)

.upstream/
├── mcp-atlassian/                     # Upstream Python project
└── java-sdk/                          # Official MCP Java SDK (reference)

.credentials/                          # gitignored — PATs, OAuth config, deploy workflow
```

## Hard-Won Lessons

### jakarta, NOT javax
Confluence 10.x runs on Tomcat 10.1 / Jakarta EE 10 / Spring 6 / Java 21. The API uses `jakarta.servlet`, `jakarta.ws.rs`, `jakarta.inject` — always use `jakarta.*` imports, never `javax.*`. (This reverses the pre-10.x rule; older notes said "javax, NOT jakarta" — that is now wrong.) Spec-jar versions are managed by the `platform-public-api` BOM (see below), not hardcoded.

### Spring Scanner requires scan-indexes XML
`@ComponentImport` requires `src/main/resources/META-INF/spring/plugin-context.xml` with `<atlassian-scanner:scan-indexes/>`.

### Plugin key must match Bundle-SymbolicName
`atlassian-plugin.xml` key must be `${atlassian.plugin.key}` = `com.atlassian.mcp.confluence-mcp-plugin`.

### DynamicImport-Package is required
Without `<DynamicImport-Package>*</DynamicImport-Package>` in pom.xml, runtime class resolution fails.

### Anonymous REST access in Confluence 10
Use `@UnrestrictedAccess` from `com.atlassian.annotations.security`. Combined with a `before-login` servlet filter for full anonymous access.

### Async transport via servlet-filter
The MCP Java SDK streamable transport calls `request.startAsync()`. Atlassian `<servlet>` modules hard-code `asyncSupported=false`, so the transport is mounted as `<servlet-filter>` modules instead (owned by `McpTransportFilter`). The Atlassian plugin framework governs filter async support via the JVM flag `-Datlassian.plugins.filter.async.default=true` (fallback: `-Datlassian.plugins.filter.force.async.dispatcher=true`). **This flag is a documented requirement on the Confluence server** for the MCP endpoint to work. Note: this flag is *only* about async dispatch — it has nothing to do with anonymous reachability (see the next lesson).

### Anonymous endpoint reachability (JSON 401, not Seraph 302)
On a login-required Confluence instance, Seraph 302-redirects an anonymous (or invalid-credential) request to `/login.action` *before* a default-location filter ever runs — so the client gets an HTML login page instead of the spec's JSON 401. To return a proper JSON 401 + `WWW-Authenticate` instead:

1. Every MCP filter class carries `@UnrestrictedAccess` (`com.atlassian.annotations.security.UnrestrictedAccess`), AND
2. all six `<servlet-filter>` modules use `location="before-dispatch"`.

A `before-login` filter that merely passes through does **not** exempt the path from Seraph. (The old JAX-RS `McpResource` got this exemption from `@UnrestrictedAccess` on the resource; the filter chain replicates it on each filter class.) Verified live: unauthenticated / invalid-PAT → 401; authenticated → works.

### Security is a filter chain, not inline
Security is not inline in the transport — it is a chain of discrete `<servlet-filter>` modules at `location="before-dispatch"`, run by ascending weight:

| Weight | Filter | Purpose |
|--------|--------|---------|
| 200 | `BodySizeLimitFilter` | Reject oversized request bodies |
| 300 | `RateLimitFilter` | IP-based rate limiting |
| 400 | `AccessControlFilter` | Auth + user/group allowlist + read-only |
| 500 | `SessionBindingFilter` | Bind MCP session to authenticated principal |
| 550 | `SecurityHeadersFilter` | Response security headers |
| 600 | `McpTransportFilter` | The SDK streamable transport |

Origin validation is **not** a filter — it is performed by the SDK's `DefaultServerTransportSecurityValidator` inside the transport.

### REST package scan must be specific
Use `<package>com.atlassian.mcp.plugin.rest</package>` — never the parent package.

### Version bumps bust JS/CSS cache
Confluence CDN caches web resources by plugin version. Bump version in pom.xml to force browsers to load new JS/CSS.

### Write tools must structure Confluence payloads correctly
The code generator produces flat `requestBody.put("field", value)` for POST/PUT tools. Confluence's REST API expects nested structures like `{"type": "page", "space": {"key": "..."}, "body": {"storage": {"value": "..."}}}`. Always verify write tool payloads against Confluence REST API docs.

### ComponentLocator for filters
Confluence does not have Jira's `ComponentAccessor`. Use `com.atlassian.sal.api.component.ComponentLocator` for getting beans outside DI context (e.g., in servlet filters).

### Confluence 10.x requires Java 21
The `confluence-10.2.11.jar` contains classes compiled for Java 21 (class version 65.0). The plugin must compile with Java 21 (`maven.compiler.source/target=21`, `mise: temurin-21`). Attempting to compile with Java 17 will fail with "class file has wrong version 65.0, should be 61.0" on any Confluence-specific imports like `UserAccessor`.

### Platform versions come from the platform-public-api BOM
There is no public Confluence API BOM (no analog to Jira's `jira-api-bom`). Confluence's internal `confluence-project` parent imports `com.atlassian.platform.dependencies:platform-public-api` — we import that same BOM directly in `<dependencyManagement>` (`platform.dependencies.version`, e.g. `8.3.16` for Confluence 10.2.11). Provided platform deps (`sal-api`, `atlassian-plugins-api`, `atlassian-rest-v2-api`, `atlassian-template-renderer-api`, `jackson`, `jakarta.*`, `atlassian-annotations`) omit `<version>` and inherit from it. To track a new Confluence version, bump `confluence.version` + `platform.dependencies.version` to whatever that release's `confluence-project` POM uses. Use `atlassian-rest-v2-api` (not legacy `atlassian-rest-common`).

### UserAccessor for group membership
Confluence uses `com.atlassian.confluence.user.UserAccessor.hasMembership(groupName, username)` for group checks — not Jira's `GroupManager`.

### Flexmark: two settings for Confluence XML pass-through

Flexmark-java by default HTML-escapes namespaced tags (`<ac:*>`, `<ri:*>`). Two settings fix this:

1. **`Parser.HTML_ALLOW_NAME_SPACE = true`** — enables XML namespace prefix in OPENTAG/CLOSETAG patterns. Without this, inline `<ac:structured-macro>` inside paragraphs is escaped. This is the critical setting. See `Parsing.java:354-399`.

2. **`Parser.HTML_BLOCK_TAGS` += Confluence tags** — registers `ac:structured-macro`, `ac:task-list`, etc. as block-level HTML (Type 6 detection). Without this, block macros fall to Type 7 which is less reliable. See `HtmlBlockParser.java:79`, `Parser.java:118-183`.

With both settings, Confluence XML injected during Markdown pre-processing passes through flexmark completely untouched — no placeholders, no escaping, no post-processing.

Key files in flexmark source (`.upstream/flexmark-java/`):

- `Parser.java:213` — `HTML_ALLOW_NAME_SPACE` DataKey (default: **false**)
- `Parser.java:118-183` — `HTML_BLOCK_TAGS` configurable list
- `Parsing.java:354-399` — OPENTAG/CLOSETAG patterns conditional on `allowNameSpace`
- `HtmlBlockParser.java:79` — block detection pattern with `XML_NAMESPACE`
- `CoreNodeRenderer.java:498-553` — render pass-through logic (`rawPre()` when not escaping)

### Upstream reference libraries

- `.upstream/flexmark-java/` — flexmark-java 0.64.8 source. We use: core parser, html renderer, html2md-converter, GFM extensions. Key: `HTML_ALLOW_NAME_SPACE` and `HTML_BLOCK_TAGS` for Confluence XML
- `.upstream/commonmark-java/` — commonmark-java 0.28.0 source. Reference only — we don't use it. Missing HTML-to-Markdown converter (blocker). Has nice GitHub alerts extension as reference
- `.upstream/mcp-atlassian/` — upstream Python MCP project. Source of truth for tool definitions
- `.upstream/confluence-markdown-exporter/` — reference for comprehensive Confluence storage format element handling

## Future Architecture: Specialized Page Creation Tools

The plugin will have **multiple page creation tools** beyond the generic `create_page`. Each specialized tool targets a specific business scenario with tailored descriptions, default structures, and macro usage guidance. Examples:

- `create_meeting_notes` — pre-structured with attendees, agenda, action items, decisions
- `create_procedure` — numbered steps, warning panels for safety, approval status badges
- `create_status_report` — TOC, status badges, expandable details, risk panels
- `create_policy` — effective date, approval workflow, revision history table

All tools share `MarkdownToStorage` for content conversion but provide richer tool descriptions that guide AI agents to use the right Confluence features (panels, status labels, task lists, expand sections) for each document type. This works for users **without** skills/plugins installed — the intelligence is in the tool description, not in client-side configuration.

When adding new specialized tools, register any new Confluence macro tags they need in `Parser.HTML_BLOCK_TAGS` (see flexmark lesson above).

## Critical Rules

- **Always use `jakarta.*`** imports, never `javax.*` (Confluence 10.x = Jakarta EE 10 / Tomcat 10.1)
- **Plugin key is `com.atlassian.mcp.confluence-mcp-plugin`** everywhere
- **Use `atlas-mvn`** for local builds, never plain `mvn`
- **Use `just`** for all workflows — build, deploy, test, codegen
- **Bump version** in pom.xml when changing JS/CSS (cache busting)
- **Run `just e2e`** after any tool changes to verify against live Confluence
- **Mirror upstream exactly** — same tool names, params, descriptions, behavior
