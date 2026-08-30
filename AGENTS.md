# confluence-mcp-plugin

Native Confluence Data Center plugin that embeds an MCP (Model Context Protocol)
server. AI agents connect via OAuth 2.0 or PATs. 28 tools covering pages,
comments, labels, attachments, spaces, users and analytics.

The tool set was originally translated from the upstream
[mcp-atlassian](https://github.com/sooperset/mcp-atlassian) Python project, which
stays the reference for tool names and what each tool is for. It is not a
contract: where upstream declares a list as a comma-separated string or an object
as a string holding JSON, this plugin declares what the parameter actually is.

## Build & Deploy

All commands are mise tasks, defined as scripts under `.mise/tasks/`. Env vars
auto-loaded by mise from `.credentials/confluence.env` (path set in the
gitignored `mise.local.toml`). Run `mise tasks` to list them.

```bash
mise run build      # atlas-package (compile + JAR)
mise run deploy     # clean + build + upload JAR to Confluence UPM + verify enabled
mise run test       # unit tests (excludes e2e)
mise run test:e2e   # e2e tests against the live Confluence instance
mise run lint       # every linter, via flint
mise run lint:fix   # apply what the linters can fix
mise run dev:run    # atlas-run
mise run dev:debug  # atlas-debug
mise run clean      # atlas-clean
```

**Local builds must use `atlas-mvn`** (not plain `mvn`). The Atlassian SDK
wrapper includes the Atlassian Maven repository. Plain `mvn` only works when
Atlassian repos are configured in `~/.m2/settings.xml` (which CI does via GitHub
Actions).

## Key Identifiers

| What              | Value                                                                     |
| ----------------- | ------------------------------------------------------------------------- |
| Plugin key        | `com.atlassian.mcp.confluence-mcp-plugin`                                 |
| Maven coordinates | `com.atlassian.mcp:confluence-mcp-plugin`                                 |
| MCP endpoint      | `POST /plugins/servlet/mcp`                                               |
| OAuth endpoints   | `/plugins/servlet/mcp-oauth/{metadata,register,authorize,callback,token}` |
| Admin REST        | `GET/PUT /rest/mcp-admin/1.0/`                                            |
| Admin page        | `/plugins/servlet/mcp-admin`                                              |
| Target Confluence | Data Center — exact version in `confluence.version` (pom.xml)             |

## Architecture

| Layer                | What                                                                                                                                                                          |
| -------------------- | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| MCP endpoint         | MCP Java SDK streamable transport, mounted as async `<servlet-filter>` modules at `/plugins/servlet/mcp` (transport built by `McpBootstrap`, owned by `McpTransportFilter`)   |
| OAuth proxy          | Servlet at `/plugins/servlet/mcp-oauth/` — bridges MCP client OAuth with Confluence OAuth 2.0, supports refresh token pass-through                                            |
| Tools                | 28 classes under `tools/` — each calls the Confluence REST API via `ConfluenceRestClient`                                                                                     |
| Response transformer | `ResponseTransformer` — whitelist-based JSON transformation. Constructs full URLs, formats timestamps, simplifies pages/comments/labels/users                                 |
| Storage→Markdown     | `StorageToMarkdown` — jsoup-based converter for the [Confluence storage format](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html) (30+ elements) |
| Response trimmer     | `ResponseTrimmer` — strips verbose fields from legacy `client.get()` responses (tools use `client.getRaw()` + `ResponseTransformer` instead)                                  |
| Admin                | Servlet at `/plugins/servlet/mcp-admin` + REST at `/rest/mcp-admin/1.0/`                                                                                                      |
| Config               | `McpPluginConfig` backed by Confluence `PluginSettings` (key-value)                                                                                                           |
| Auth                 | OAuth 2.0 (via Application Link) with refresh token support, or PAT — Confluence validates tokens, the plugin checks access control                                           |

## MCP Protocol — Streamable HTTP

Single endpoint `/plugins/servlet/mcp` supporting Streamable HTTP transport (MCP
spec 2025-06-18).

The official **MCP Java SDK** owns the protocol entirely — JSON-RPC framing,
session ids, SSE streaming and `Accept` negotiation are all handled by the SDK's
`HttpServletStreamableServerTransportProvider` (built in `McpBootstrap`, served
by `McpTransportFilter`). The plugin only supplies the tool specifications and
server capabilities; it hand-rolls no `initialize` / `tools/list` / `tools/call`
dispatch.

### Session management (SDK-managed)

- `MCP-Session-Id` returned on `initialize`, required on subsequent requests
- Session lifecycle (creation, lookup, expiry) is owned by the SDK transport
- DELETE closes a session; 404 for expired or unknown sessions

### Security

- Body-size limit, rate limiting, access control, session binding and security
  headers are discrete `<servlet-filter>` modules (see the filter-chain lesson below)
- **Origin + Host validation** (MUST per spec): handled by the SDK's
  `DefaultServerTransportSecurityValidator` inside the transport, not by a filter.
  Configured in `McpBootstrap.buildSecurityValidator()` with both an Origin
  allowlist **and** a Host allowlist (deployment FQDN + loopback) — the Host
  allowlist completes the DNS-rebinding defence, which the SDK skips when left
  empty. Invalid Origin → 403; missing or invalid Host → 421
- **MCP-Protocol-Version** is validated by the plugin's `McpProtocolVersionFilter`
  (weight 560, before the transport): a present-but-unsupported value → 400. The
  SDK transport does **not** validate this header. A missing header is tolerated
  (spec back-compat)

## Tools — 28 Total

| Package        | Count | Toolset                  | Description                                                                                         |
| -------------- | ----- | ------------------------ | --------------------------------------------------------------------------------------------------- |
| `pages/`       | 13    | `confluence_pages`       | Search, get, create, update, delete, move, history, diff, append, prepend, replace section, convert |
| `comments/`    | 3     | `confluence_comments`    | Get, add, reply to comments                                                                         |
| `labels/`      | 2     | `confluence_labels`      | Get and add labels                                                                                  |
| `attachments/` | 7     | `confluence_attachments` | Upload, download, list, delete attachments and images                                               |
| `spaces/`      | 1     | `confluence_spaces`      | List available spaces                                                                               |
| `users/`       | 1     | `confluence_users`       | Search users                                                                                        |
| `analytics/`   | 1     | `confluence_analytics`   | Page view statistics (Cloud-only)                                                                   |

### Tool Interface

Every tool implements `McpTool`:

```java
public interface McpTool {
    String name();                          // snake_case
    String description();
    Map<String, Object> inputSchema();      // JSON Schema, written by hand
    boolean isWriteTool();                  // true = hidden in read-only mode
    default String requiredPluginKey() { return null; }
    String execute(Map<String, Object> args, String authHeader) throws McpToolException;
}
```

Each `McpTool` is adapted to the SDK's `SyncToolSpecification` via
`McpToolAdapter`. `ToolRegistry.toSpecifications()` is the registration entry
point that hands the filtered specs to the SDK server. Server capabilities are
`tools(false).logging()` only — no resources or completions.

> **Known gap.** A tool declares its parameters twice: once in `inputSchema()`
> and again in the `execute` body that reads them back out of a
> `Map<String, Object>`, so the two can disagree and nothing catches it. The
> sibling jira-mcp-plugin has retired this shape in favour of `TypedTool<A>` with
> an argument record whose components carry `@ToolArg`, from which the schema is
> derived. Porting that contract here is the next piece of work on this layer.

### Writing execute() Bodies

Tools call the Confluence REST API via `ConfluenceRestClient.get/post/put/delete()`:

- **GET tools**: build the query string, return `client.get(path + query, authHeader)`
- **POST/PUT tools**: build a `Map<String, Object>`, serialize with Jackson, send as body
- **Create page**: `{"type": "page", "title": "...", "space": {"key": "..."}, "body": {"storage": {"value": "...", "representation": "storage"}}}`
- **Add comment**: `{"type": "comment", "container": {"id": "...", "type": "page"}, "body": {"storage": {...}}}`
- **Add label**: `[{"prefix": "global", "name": "..."}]`

Confluence's REST API expects nested structures. Always verify a write tool's
payload against the Confluence REST API docs rather than flattening it.

### Content Conversion (bidirectional)

**Markdown → Storage** (`create_page`, `update_page`, `add_comment`, `reply_to_comment`):

- `MarkdownToStorage` using flexmark-java 0.64.8 with GFM extensions (tables,
  strikethrough, task lists, autolinks)
- Extended Markdown auto-converted to native Confluence macros: GitHub alerts
  (`> [!NOTE/TIP/IMPORTANT/WARNING]`) → info/tip/note/warning panels;
  `{status:Text|color}` → status badge; `{toc}` / `[TOC]` → TOC macro;
  `- [x]` / `- [ ]` → native `ac:task-list`; `<details><summary>` → expand macro
- Three content formats: `markdown` (default, converted), `wiki` and `storage`
  (both passed through as-is)

**Storage → Markdown** (`get_page`, `get_comments`, `get_page_children`, `get_page_history`):

- `StorageToMarkdown` using jsoup (XML parser) + flexmark-html2md-converter
- **Macros:** code (with language), noformat, info/note/warning/tip, panel,
  expand, toc, anchor, jira, status, section/column, excerpt, include, children,
  attachments, blog-posts, profile, content-by-label
- **Elements:** `ac:image` (ri:attachment, ri:url), `ac:link` (ri:page,
  ri:attachment, ri:user, ri:space, ri:shortcut, ri:blog-post), `ac:emoticon`
  (Unicode mapping), `ac:task-list`/`ac:task`, `ac:layout`/`ac:layout-section`/
  `ac:layout-cell`, `ac:placeholder`
- **Unknown macros:** graceful fallback — unwrap the body content

### Response Transformation

Each tool transforms raw Confluence API JSON via `ResponseTransformer`:

- **Whitelist approach** — only fields the response model defines, not blacklist stripping
- **URL construction** — `{baseUrl}/pages/viewpage.action?pageId={id}` (Server/DC format)
- **Timestamp formatting** — ISO 8601 → `YYYY-MM-DD HH:MM:SS`
- Tools use `client.getRaw()` for untrimmed responses, then `ResponseTransformer`

## Admin Config (PluginSettings keys)

| Key                                          | Default | Purpose                              |
| -------------------------------------------- | ------- | ------------------------------------ |
| `com.atlassian.mcp.plugin.enabled`           | false   | Global MCP on/off                    |
| `com.atlassian.mcp.plugin.allowedUsers`      | ""      | Comma-separated usernames            |
| `com.atlassian.mcp.plugin.allowedGroups`     | ""      | Comma-separated group names          |
| `com.atlassian.mcp.plugin.disabledTools`     | ""      | Comma-separated tool names           |
| `com.atlassian.mcp.plugin.readOnlyMode`      | false   | Hide write tools                     |
| `com.atlassian.mcp.plugin.confluenceBaseUrl` | ""      | Override internal base URL           |
| `com.atlassian.mcp.plugin.oauthClientId`     | ""      | OAuth Application Link client ID     |
| `com.atlassian.mcp.plugin.oauthClientSecret` | ""      | OAuth Application Link client secret |

## E2E Tests

`src/test/java/.../e2e/McpEndpointE2ETest.java`, run with `mise run test:e2e`.
Requires env vars from `.credentials/confluence.env`; tests skip automatically
when `CONFLUENCE_URL` / `CONFLUENCE_PAT_RKADMIN` are unset.

| Category         | What                                                                      |
| ---------------- | ------------------------------------------------------------------------- |
| Protocol         | initialize, ping, invalid method                                          |
| Tools list       | count, schema validation                                                  |
| Read tools       | search, search_user                                                       |
| Response format  | structure, full URLs, no leaked internal fields                           |
| Page CRUD        | create → get → comment → label → delete lifecycle                         |
| Error handling   | missing param, invalid ID, unknown tool                                   |
| Streamable HTTP  | session create, tool call with session, session delete                    |
| OAuth refresh    | metadata advertises refresh_token grant, error paths                      |

## Project Structure

```text
src/main/java/com/atlassian/mcp/plugin/
├── ConfluenceRestClient.java          # HTTP client → Confluence REST API (get/getRaw)
├── ResponseTransformer.java           # Whitelist JSON transformation
├── StorageToMarkdown.java             # Confluence storage format → Markdown
├── MarkdownToStorage.java             # Markdown → Confluence storage format
├── ResponseTrimmer.java               # Legacy blacklist field stripping
├── McpToolException.java              # Checked exception for tool failures
├── config/
│   ├── McpPluginConfig.java           # PluginSettings-backed configuration
│   ├── OAuthStateStore.java           # In-memory OAuth state
│   └── UrlSafety.java                 # SSRF guard for the configured base URL
├── admin/
│   ├── AdminServlet.java              # Admin page (Velocity)
│   └── ConfigResource.java            # Admin REST API + user/group pickers
├── rest/                              # Filter chain, transport bootstrap, OAuth proxy
│   └── oauth/CimdValidator.java       # CIMD client_id fetch + SSRF guard
└── tools/
    ├── McpTool.java                   # Tool interface
    ├── ToolRegistry.java              # 28 tools, filtered by capability/config
    ├── CqlSafety.java                 # CQL injection guard
    ├── pages/ comments/ labels/ attachments/ spaces/ users/ analytics/
```

## Hard-Won Lessons

### jakarta, NOT javax

Confluence 10.x runs on Tomcat 10.1 / Jakarta EE 10 / Spring 6 / Java 21. The API
uses `jakarta.servlet`, `jakarta.ws.rs`, `jakarta.inject` — always `jakarta.*`,
never `javax.*`. Checkstyle enforces this via `IllegalImport`. Spec-jar versions
come from the `platform-public-api` BOM, not from hardcoded versions.

### Platform versions come from the platform-public-api BOM

There is no public Confluence API BOM (no analog to Jira's `jira-api-bom`).
Confluence's internal `confluence-project` parent imports
`com.atlassian.platform.dependencies:platform-public-api` — we import that same
BOM directly in `<dependencyManagement>` (`platform.dependencies.version`, e.g.
`8.3.16` for Confluence 10.2.11). Provided platform deps (`sal-api`,
`atlassian-plugins-api`, `atlassian-rest-v2-api`, `atlassian-template-renderer-api`,
`jackson`, `jakarta.*`, `atlassian-annotations`) omit `<version>` and inherit from
it. To track a new Confluence release, bump `confluence.version` +
`platform.dependencies.version` to whatever that release's `confluence-project`
POM uses. Use `atlassian-rest-v2-api`, not legacy `atlassian-rest-common`.

### Confluence 10.x requires Java 21

`confluence-10.2.11.jar` contains classes compiled for Java 21 (class version
65.0). Compiling with Java 17 fails with "class file has wrong version 65.0,
should be 61.0" on any Confluence import such as `UserAccessor`.

### Spring Scanner requires scan-indexes XML

`@ComponentImport` requires `src/main/resources/META-INF/spring/plugin-context.xml`
with `<atlassian-scanner:scan-indexes/>`.

### Plugin key must match Bundle-SymbolicName

`atlassian-plugin.xml` key must be `${atlassian.plugin.key}` =
`com.atlassian.mcp.confluence-mcp-plugin`.

### DynamicImport-Package is required

Without `<DynamicImport-Package>*</DynamicImport-Package>` in pom.xml, runtime
class resolution fails.

### REST package scan must be specific

Use `<package>com.atlassian.mcp.plugin.rest</package>` — never the parent package.

### Async transport via servlet-filter

The MCP Java SDK streamable transport calls `request.startAsync()`. Atlassian
`<servlet>` modules hard-code `asyncSupported=false`, so the transport is mounted
as `<servlet-filter>` modules instead (owned by `McpTransportFilter`). The
Atlassian plugin framework governs filter async support via the JVM flag
`-Datlassian.plugins.filter.async.default=true` (fallback:
`-Datlassian.plugins.filter.force.async.dispatcher=true`). **This flag is a
documented requirement on the Confluence server.** It is only about async
dispatch and has nothing to do with anonymous reachability.

### Anonymous endpoint reachability (JSON 401, not Seraph 302)

On a login-required instance, Seraph 302-redirects an anonymous or
invalid-credential request to `/login.action` *before* a default-location filter
runs, so the client gets an HTML login page instead of the spec's JSON 401. To
return a proper JSON 401 + `WWW-Authenticate`:

1. every MCP filter class carries `@UnrestrictedAccess`
   (`com.atlassian.annotations.security.UnrestrictedAccess`), **and**
2. all six `<servlet-filter>` modules use `location="before-dispatch"`.

A `before-login` filter that merely passes through does **not** exempt the path
from Seraph. Verified live: unauthenticated or invalid PAT → 401; authenticated → works.

### Security is a filter chain, not inline

Discrete `<servlet-filter>` modules at `location="before-dispatch"`, run by
ascending weight:

| Weight | Filter                     | Purpose                                          |
| ------ | -------------------------- | ------------------------------------------------ |
| 200    | `BodySizeLimitFilter`      | Reject oversized request bodies                  |
| 300    | `RateLimitFilter`          | IP-based rate limiting                           |
| 400    | `AccessControlFilter`      | Auth + user/group allowlist + read-only          |
| 500    | `SessionBindingFilter`     | Bind MCP session to authenticated principal      |
| 550    | `SecurityHeadersFilter`    | Response security headers                        |
| 560    | `McpProtocolVersionFilter` | Reject unsupported `MCP-Protocol-Version` (400)  |
| 600    | `McpTransportFilter`       | The SDK streamable transport                     |

Origin and Host validation are **not** filters — the SDK's
`DefaultServerTransportSecurityValidator` does them inside the transport.

### ComponentLocator for filters

Confluence has no analog of Jira's `ComponentAccessor`. Use
`com.atlassian.sal.api.component.ComponentLocator` to get beans outside the DI
context, e.g. in servlet filters.

### UserAccessor for group membership

Confluence uses `com.atlassian.confluence.user.UserAccessor.hasMembership(group,
username)` — not Jira's `GroupManager`. The admin user/group picker uses
`UserAccessor.findUsers` / `findGroups`; `TermQuery.SUBSTRING_CONTAINS` must be
the constant, since atlassian-user validates the matching rule by reference.

### Anonymous REST access in Confluence 10

Use `@UnrestrictedAccess` from `com.atlassian.annotations.security`, combined with
a `before-login` servlet filter for full anonymous access.

### Version bumps bust JS/CSS cache

The Confluence CDN caches web resources by plugin version. Bump the version in
pom.xml to force browsers to load new JS/CSS.

### Flexmark: two settings for Confluence XML pass-through

Flexmark HTML-escapes namespaced tags (`<ac:*>`, `<ri:*>`) by default. Two
settings fix it:

1. **`Parser.HTML_ALLOW_NAME_SPACE = true`** — enables the XML namespace prefix in
   the OPENTAG/CLOSETAG patterns. Without it, inline `<ac:structured-macro>`
   inside a paragraph is escaped. This is the critical setting.
2. **`Parser.HTML_BLOCK_TAGS` += Confluence tags** — registers
   `ac:structured-macro`, `ac:task-list` and friends as block-level HTML (Type 6
   detection). Without it, block macros fall to the less reliable Type 7.

With both, Confluence XML injected during Markdown pre-processing passes through
untouched — no placeholders, no escaping, no post-processing. Key files in
`.upstream/flexmark-java/`: `Parser.java:213` (`HTML_ALLOW_NAME_SPACE`, default
false), `Parser.java:118-183` (`HTML_BLOCK_TAGS`), `Parsing.java:354-399`
(patterns conditional on `allowNameSpace`), `HtmlBlockParser.java:79`,
`CoreNodeRenderer.java:498-553`.

### Upstream reference libraries (`.upstream/`, local only)

- `flexmark-java/` — 0.64.8 source. We use the core parser, HTML renderer,
  html2md-converter and GFM extensions
- `commonmark-java/` — 0.28.0, reference only; no HTML-to-Markdown converter
- `mcp-atlassian/` — the upstream Python MCP project; reference for tool names
  and intent
- `confluence-markdown-exporter/` — reference for storage-format element handling
- `confluence-api/` — 8324 JSON files, one per Confluence Java type, with method
  descriptions. Consult this rather than decompiling `.m2` bytecode
- `confluence-rest-openapi-10.2.11.json` — the REST layer the tools actually call

## Future Architecture: Specialized Page Creation Tools

Beyond the generic `create_page`, the plugin is intended to grow page-creation
tools tailored to a business scenario — `create_meeting_notes`,
`create_procedure`, `create_status_report`, `create_policy` — each with its own
default structure and macro guidance. They share `MarkdownToStorage`; the
intelligence lives in the tool description, so it works for users without any
client-side skill installed. When adding one, register any new Confluence macro
tag it needs in `Parser.HTML_BLOCK_TAGS`.

## Critical Rules

- **Always use `jakarta.*`** imports, never `javax.*`
- **Plugin key is `com.atlassian.mcp.confluence-mcp-plugin`** everywhere
- **Use `atlas-mvn`** for local builds, never plain `mvn`
- **Use `mise run`** for all workflows — build, deploy, test, lint
- **Bump the version** in pom.xml when changing JS/CSS (cache busting)
- **Run `mise run test:e2e`** after any tool change to verify against live Confluence

## Linting

Run `mise run lint:fix` before committing changes.
If the output includes `fixed`, keep those changes.
If it includes `partial` or `review`, address the remaining issues and run
`mise run lint:fix` again.

Example output:
flint: fixed: google-java-format — commit before pushing | partial: rumdl
