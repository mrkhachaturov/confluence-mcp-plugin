# confluence-mcp-plugin

Native Confluence Data Center plugin that embeds an MCP (Model Context Protocol) server. AI agents connect via OAuth 2.0 or PATs. 23 tools mirrored 1:1 from the upstream [mcp-atlassian](https://github.com/sooperset/mcp-atlassian) Python project.

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
| MCP endpoint | JAX-RS at `/rest/mcp/1.0/` — Streamable HTTP (JSON-RPC 2.0 + SSE) |
| OAuth proxy | Servlet at `/plugins/servlet/mcp-oauth/` — bridges MCP client OAuth with Confluence OAuth 2.0, supports refresh token pass-through |
| Tools | 23 classes in `tools/` — each calls Confluence REST API internally via `ConfluenceRestClient` |
| Response trimmer | `ResponseTrimmer` — strips verbose fields (`self`, `_links`, `_expandable`, `profilePicture`) to match upstream's `to_simplified_dict()` |
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
| MCP endpoint | `POST /rest/mcp/1.0/` |
| OAuth endpoints | `/plugins/servlet/mcp-oauth/{metadata,register,authorize,callback,token}` |
| Admin REST | `GET/PUT /rest/mcp-admin/1.0/` |
| Admin page | `/plugins/servlet/mcp-admin` |
| Target Confluence | Data Center 10.x |

## MCP Protocol — Streamable HTTP

Single endpoint `/rest/mcp/1.0/` supporting Streamable HTTP transport (MCP spec 2025-06-18).

| Method | Action |
|--------|--------|
| `initialize` | Return server info + capabilities + `MCP-Session-Id` header |
| `notifications/initialized` | Return 202 |
| `tools/list` | Return filtered tool list |
| `tools/call` | Dispatch to tool, return result |
| `ping` | Keep-alive |

### Session management

- `MCP-Session-Id` returned on `initialize`, required on subsequent requests
- Sessions stored in static `ConcurrentHashMap`
- DELETE closes session, 404 returned for expired/unknown sessions

### Security

- **Origin validation** (MUST per spec): `Origin` header checked against Confluence base URL. Invalid Origin → 403. Localhost always allowed
- **MCP-Protocol-Version** header validated on non-initialize requests

## Tools — 23 Total

| Package | Count | Toolset | Description |
|---------|-------|---------|-------------|
| `pages/` | 9 | `confluence_pages` | Search, get, create, update, delete, move pages, history, diff |
| `comments/` | 3 | `confluence_comments` | Get, add, reply to comments |
| `labels/` | 2 | `confluence_labels` | Get and add labels |
| `attachments/` | 7 | `confluence_attachments` | Upload, download, list, delete attachments and images |
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

### Writing execute() Bodies

Tools call Confluence REST API directly via `ConfluenceRestClient.get/post/put/delete()`. Key patterns:

- **GET tools**: Build query string, return `client.get(path + query, authHeader)`
- **POST/PUT tools**: Build `Map<String, Object>`, serialize with Jackson, send as body
- **Create page**: Must structure as `{"type": "page", "title": "...", "space": {"key": "..."}, "body": {"storage": {"value": "...", "representation": "storage"}}}`
- **Add comment**: Structure as `{"type": "comment", "container": {"id": "...", "type": "page"}, "body": {"storage": {"value": "...", "representation": "storage"}}}`
- **Add label**: Structure as `[{"prefix": "global", "name": "..."}]`

### Markdown-to-Storage Conversion

AI agents send Markdown by default (`content_format=markdown`). The upstream Python project converts markdown to Confluence storage format (XHTML) via `md2conf` before sending to the API. Our plugin mirrors this with `MarkdownToStorage` using [flexmark-java](https://github.com/vsch/flexmark-java) 0.64.8.

**Pipeline:** Markdown → flexmark-java (with GFM extensions) → HTML → Confluence storage format (XHTML)

**Applied to:** `create_page`, `update_page`, `add_comment`, `reply_to_comment`

**Three content formats supported (mirroring upstream):**
- `markdown` (default) — converted to XHTML via flexmark-java
- `wiki` — passed to Confluence as wiki markup
- `storage` — passed as-is (must be valid Confluence XHTML)

**GFM extensions enabled:** tables, strikethrough, task lists, autolinks

## Response Trimming

`ResponseTrimmer` runs on all `ConfluenceRestClient` responses. It strips fields that the upstream's Pydantic models never include:

**Stripped recursively:** `self`, `_links`, `_expandable`, `expand`, `extensions`, `profilePicture`, `userKey`

**Stripped at top level:** `operations`, `restrictions`, `metadata`, `container`, `position`

Search highlight markers (`@@@hl@@@`, `@@@endhl@@@`) are also stripped.

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
| Response trimming | no self links |
| Page CRUD | create → get → comment → label → delete lifecycle |
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
├── ConfluenceRestClient.java          # HTTP client → Confluence REST API (+ ResponseTrimmer)
├── ResponseTrimmer.java               # Strip verbose fields from Confluence JSON responses
├── McpToolException.java              # Checked exception for tool failures
├── config/
│   ├── McpPluginConfig.java           # PluginSettings-backed configuration
│   └── OAuthStateStore.java           # In-memory OAuth state
├── admin/
│   ├── AdminServlet.java              # Admin page (Velocity)
│   └── ConfigResource.java           # Admin REST API
└── tools/
    ├── McpTool.java                   # Tool interface
    ├── ToolRegistry.java              # 23 tools registered, filtered by capability/config
    ├── pages/                         # 9 tools
    ├── comments/                      # 3 tools
    ├── labels/                        # 2 tools
    ├── attachments/                   # 7 tools
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

### javax, NOT jakarta
Confluence 10.x API JARs use `javax.servlet`, `javax.ws.rs`, `javax.inject`. Always use `javax.*` imports.

### Spring Scanner requires scan-indexes XML
`@ComponentImport` requires `src/main/resources/META-INF/spring/plugin-context.xml` with `<atlassian-scanner:scan-indexes/>`.

### Plugin key must match Bundle-SymbolicName
`atlassian-plugin.xml` key must be `${atlassian.plugin.key}` = `com.atlassian.mcp.confluence-mcp-plugin`.

### DynamicImport-Package is required
Without `<DynamicImport-Package>*</DynamicImport-Package>` in pom.xml, runtime class resolution fails.

### Anonymous REST access in Confluence 10
Use `@UnrestrictedAccess` from `com.atlassian.annotations.security`. Combined with a `before-login` servlet filter for full anonymous access.

### REST package scan must be specific
Use `<package>com.atlassian.mcp.plugin.rest</package>` — never the parent package.

### Version bumps bust JS/CSS cache
Confluence CDN caches web resources by plugin version. Bump version in pom.xml to force browsers to load new JS/CSS.

### Write tools must structure Confluence payloads correctly
The code generator produces flat `requestBody.put("field", value)` for POST/PUT tools. Confluence's REST API expects nested structures like `{"type": "page", "space": {"key": "..."}, "body": {"storage": {"value": "..."}}}`. Always verify write tool payloads against Confluence REST API docs.

### ComponentLocator for filters
Confluence does not have Jira's `ComponentAccessor`. Use `com.atlassian.sal.api.component.ComponentLocator` for getting beans outside DI context (e.g., in servlet filters).

### Confluence 10.x requires Java 21
The `confluence-10.2.7.jar` contains classes compiled for Java 21 (class version 65.0). The plugin must compile with Java 21 (`maven.compiler.source/target=21`, `mise: temurin-21`). Attempting to compile with Java 17 will fail with "class file has wrong version 65.0, should be 61.0" on any Confluence-specific imports like `UserAccessor`.

### UserAccessor for group membership
Confluence uses `com.atlassian.confluence.user.UserAccessor.hasMembership(groupName, username)` for group checks — not Jira's `GroupManager`.

## Critical Rules

- **Always use `javax.*`** imports, never `jakarta.*`
- **Plugin key is `com.atlassian.mcp.confluence-mcp-plugin`** everywhere
- **Use `atlas-mvn`** for local builds, never plain `mvn`
- **Use `just`** for all workflows — build, deploy, test, codegen
- **Bump version** in pom.xml when changing JS/CSS (cache busting)
- **Run `just e2e`** after any tool changes to verify against live Confluence
- **Mirror upstream exactly** — same tool names, params, descriptions, behavior
