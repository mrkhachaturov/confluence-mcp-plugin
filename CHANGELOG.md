# Changelog

## [1.2.3] - 2026-05-31

### Security

Hardening from the multi-agent deep security audit (`docs/SECURITY-AUDIT-2026-05.md`), audited against the MCP 2025-06-18 spec and MCP Java SDK. No CRITICAL findings; these close the HIGH/MEDIUM items. Verified by 26 new unit tests + 2 new e2e tests (36 e2e total, green) and live checks on the running instance.

- **Admin config PUT is now CSRF-protected** — `ConfigResource.putConfig` rejects any request whose `Origin`/`Referer` host does not match this Confluence instance (403). Closes the cross-site control-plane takeover (the mutating REST endpoint previously had no XSRF protection). Non-browser admin automation with neither header is still allowed.
- **`confluenceBaseUrl` override is SSRF-validated** — `UrlSafety` rejects non-http(s) schemes, embedded credentials, and loopback/link-local/cloud-metadata (`169.254.169.254`) targets at admin set-time (400), with a read-time fail-safe in `ConfluenceRestClient.getBaseUrl()`. Private (RFC1918) hosts remain allowed for legitimate internal Confluence. Closes the "repoint the plugin → exfiltrate every caller's bearer token" SSRF chain.
- **Default-DENY access control** — an empty allowlist no longer silently grants every authenticated user. Access requires an explicit `allowedUsers`/`allowedGroups` entry, or the new opt-in **Allow all authenticated users** toggle (`allowAllAuthenticatedUsers`, default off). Admin UI shows the effective scope. **Migration:** existing installs relying on empty-list = allow-all must now add their users/groups or enable the toggle.
- **Host-header validation completes the DNS-rebinding defense** — `McpBootstrap` now configures the SDK validator with a Host allowlist (deployment FQDN + loopback) in addition to Origin; a missing/forged Host fails closed (421). The SDK skips Host validation entirely when the list is empty.
- **`MCP-Protocol-Version` is now enforced** — new `McpProtocolVersionFilter` (weight 560) returns 400 for a present-but-unsupported version, per spec. The MCP Java SDK does not validate this header. (The stale `CLAUDE.md` claim that the SDK validated it has been corrected.)
- **Rate limiting hardened** — `X-Forwarded-For` is honoured only from a loopback peer (`ClientIp`), removing the trivial anonymous-bucket-spoofing bypass and audit-log poisoning. `RateLimiter` is now a per-key sliding window: no global counter wipe on the minute boundary, and lazy stale-eviction instead of rejecting all new keys when the map is full.
- **Dynamic Client Registration validates `redirect_uris`** — `OAuthServlet.handleRegister` applies the same policy as CIMD (https for any host, or http only for exact loopback; no embedded credentials) → 400 `invalid_redirect_uri`. Closes the one-click phishing leg of the confused-deputy class (the silent zero-click variant is non-exploitable — Atlassian's OAuth provider re-prompts consent on every authorize).
- **CQL injection fixed** — `CqlSafety` properly escapes CQL string literals (backslash then quote) in `search`/`search_user`, and `spaces_filter` tokens are validated (`[A-Za-z0-9_~.-]+`) so the space filter can no longer be escaped to inject CQL clauses.
- **Download/image tool output built with Jackson** — `download_attachment`, `download_content_attachments`, `get_page_images` no longer hand-concatenate JSON, so attachment filenames/error messages can't corrupt or inject into the structured result.

### Deferred (accepted residual risk — documented)

- **Token audience binding** (`docs/token-binding-decision.md`) — the proxy still passes Confluence's token through. Risk is bounded by per-user ACL enforcement; a plugin-minted opaque token is a larger redesign tracked separately.
- **Per-client OAuth consent page** — the silent confused-deputy bypass is empirically non-exploitable (see audit §3).

## [1.2.2] - 2026-05-31

### Security

- **OAuth endpoint hardening — parity with jira-mcp-plugin's OAuthServlet** (the sibling plugin was ahead on three items):
  - **`RateLimit-*` headers on every OAuth endpoint** — `/metadata`, `/protected-resource`, `/openid-configuration`, `/authorize`, `/register`, `/token` now emit `RateLimit-Limit/Remaining/Reset` (and `Retry-After` derived from the bucket reset on 429) per draft-ietf-httpapi-ratelimit-headers-09, via a new `enforceRate` gate replacing the header-less `sendRateLimited`
  - **CIMD re-validation on `/token`** — a CIMD-style (`https://`) `client_id` is re-resolved at token time, catching revoked/mutated Client ID Metadata Documents between `/authorize` and `/token`; failure returns `invalid_client`. No-op for DCR clients
  - **Log-injection sanitization** — `client_id` is now passed through `sanitizeLog()` in the authorize CIMD-failure and redirect_uri-mismatch warnings (CR/LF/tab stripped)

## [1.2.1] - 2026-05-31

### Fixed

- **OAuth `invalid_scope` on connect** — the spec-compliant 401 work (1.2.0) made `AccessControlFilter`'s `WWW-Authenticate` challenge reachable by anonymous clients, where it advertised `scope="read write"`. MCP clients then requested `read write` at `/authorize`, which Confluence's OAuth provider rejected because the Application Link registers a single `WRITE` scope (which already grants read). Confluence strictly validates each requested scope *token* against the client's registered set, so the unregistered `read` token failed with `invalid_scope`. Now every discovery path advertises only `WRITE`: the `WWW-Authenticate` challenge (`AccessControlFilter`), and `scopes_supported` in `OAuthServlet` (both metadata documents) and `OAuthAnonymousFilter` (both well-known branches). The `handleAuthorize` forward is unchanged — the root cause was the advertised scope, not the forward
- **OAuth scope test coverage** — the consent leg had none (functional tests use a PAT; `t70`/`t71` only checked a `WWW-Authenticate` header was present, not its value). `t70`/`t71` now assert the challenge advertises `scope="WRITE"` and not `read`; new `t78` asserts every OAuth/OIDC discovery document advertises exactly `["WRITE"]` (fails against pre-1.2.1 code)

### Changed

- Version bump to 1.2.1 (also busts the CDN cache for web resources)

## [1.2.0] - 2026-05-31

### Added

- **Official MCP Java SDK transport** (`io.modelcontextprotocol.sdk:2.0.0-M3`) — replaces the hand-rolled JSON-RPC layer. The SDK now owns protocol framing, session IDs, SSE wrapping, `Accept` negotiation, and `MCP-Protocol-Version`. Mounted as an async `<servlet-filter>` at `/plugins/servlet/mcp` (built by `McpBootstrap`, owned by `McpTransportFilter`)
- **Security filter chain** — six `<servlet-filter>` modules in ascending weight: body-size cap (1 MiB, enforced on actual bytes read), per-user/per-IP rate limiting (120/min with `RateLimit-*` headers), access control, session-user binding, and security headers. Request `Origin` is validated by the SDK's `DefaultServerTransportSecurityValidator`
- **Spec-compliant OAuth challenge** — unauthenticated or invalid-token requests now return `401` with `WWW-Authenticate: Bearer ... resource_metadata=...` (RFC 9728) instead of an HTML login redirect, so MCP clients can discover and start the OAuth flow. Achieved with `@UnrestrictedAccess` + `before-dispatch` on the filter chain (a Seraph login-exemption only — tokens are still validated by Confluence)
- **Session-user binding** — an `MCP-Session-Id` issued to one Confluence user cannot be replayed by another (403); unknown/expired sessions return 401
- **OAuth OIDC discovery** — `/.well-known/openid-configuration` and `/plugins/servlet/mcp-oauth/openid-configuration`
- **CIMD (OAuth Client ID Metadata Documents)** — `client_id` as an HTTPS URL, with SSRF defenses (HTTPS-only, no redirects, private/loopback/link-local/CGNAT/ULA/cloud-metadata IPs blocked, 8 KB body cap) and a bounded positive/negative cache
- **Tool annotations** — read-only / destructive / idempotent / open-world hints on every tool (`update_page`, `replace_section`, `delete_page`, `delete_attachment` marked destructive)

### Changed

- **MCP endpoint moved** from `POST /rest/mcp/1.0/` to `POST /plugins/servlet/mcp` — update MCP client configuration accordingly (the admin page now shows the new URL)
- Tools are adapted to the SDK's `SyncToolSpecification` via `McpToolAdapter`; `ToolRegistry.toSpecifications()` is the registration entry point. Read-only mode and disabled-tool toggles are enforced at tool-call time
- Server capabilities advertise `tools` (listChanged=false) and `logging` only (no resources/completions)
- Targets Confluence 10.2.11 / Jakarta EE 10 / Tomcat 10.1 / Java 21, via the `platform-public-api` BOM 8.3.16
- Version bump to 1.2.0 (also busts the CDN cache for web resources)

### Removed

- Hand-rolled `JsonRpcHandler` and the JAX-RS `McpResource` endpoint — replaced by the official SDK transport

## [1.1.2] - 2026-04-09

### Added

- **5 new tools** (23 → 28 total):
  - `list_spaces` — discover available Confluence spaces (key, name, type, URL)
  - `append_to_page` — add content to the end of a page without full replacement
  - `prepend_to_page` — add content to the beginning of a page without full replacement
  - `convert_content` — preview markdown-to-storage conversion without creating a page
  - `replace_section` — edit a single section by heading without touching the rest of the page
- **Optimistic locking** — `update_page`, `append_to_page`, `prepend_to_page`, `replace_section` accept optional `expected_version` parameter. Rejects update if page was modified since last read
- **URL resolution in `page_id`** — all tools that accept page IDs now accept Confluence URLs (`viewpage.action?pageId=N`, `/pages/N/Title`). Extracts the ID automatically
- **`return_markdown`** — `create_page` and `update_page` accept optional `return_markdown` parameter to get content as Markdown in the response instead of storage format
- **Batch page creation** — `create_page` accepts optional `labels` (array) and `initial_comment` (markdown string), applied best-effort after page creation
- **`ResponseTransformer.simplifySpaceNode()`** — whitelist transformation for space objects, consistent with page/comment/label/user patterns
- E2E tests for all new features: list_spaces, convert_content, markdown diff, optimistic locking, append/prepend, batch create with labels (28 tests total)

### Changed

- **Markdown-based diff** — `get_page_diff` converts both versions to Markdown before computing the diff. No more noisy `ac:macro-id`, `ac:task-id`, and internal XML identifiers
- **Unified response keys** — all page tools now use `"page"` as the wrapper key. `get_page` and `get_page_history` aligned with `create_page`/`update_page` (was inconsistent `"metadata"`)
- `convert_content` response uses `{"content": {"value": "...", "format": "storage"}}` — same shape as the `content` field inside page responses
- Version bump to 1.1.2 (cache bust for CDN-cached web resources)

## [1.1.1] - 2026-04-09

### Added

- **Extended Markdown syntax** auto-converted to native Confluence macros:
  - GitHub alerts (`> [!NOTE]`, `> [!TIP]`, `> [!IMPORTANT]`, `> [!WARNING]`, `> [!CAUTION]`) with optional custom title (`> [!NOTE|title:Custom]`) → native info/tip/note/warning panels
  - Status labels (`{status:Text|color}`) → native status badge macros. Colors: green, red, yellow, blue, grey
  - Table of contents (`{toc}` or `[TOC]`) → native TOC macro
  - Expandable sections (`<details><summary>Title</summary>content</details>`) → native expand macro
- **Native Confluence task lists** — `- [x]` / `- [ ]` now convert to `ac:task-list` with proper complete/incomplete status (was: broken `<li class="task-list-item">` HTML)
- **Rich tool descriptions** — `create_page` and `update_page` descriptions document all supported Markdown features with examples, valid status colors, and guidance for business content (procedures, policies, meeting notes, reports)

### Changed

- **MarkdownToStorage rewritten** using proper flexmark-java configuration:
  - `Parser.HTML_ALLOW_NAME_SPACE = true` — enables XML namespace recognition for inline `<ac:*>` tags
  - `Parser.HTML_BLOCK_TAGS` extended with Confluence tags — enables Type 6 block detection
  - No placeholder hacks needed — Confluence XML passes through flexmark untouched
- Tool descriptions explicitly list unsupported features (mentions, dates, Jira links) to prevent AI hallucination
- Tool descriptions advise against starting content with `# Title` (Confluence shows title separately)

### Fixed

- Diamond symbols (◆) no longer appear in rendered pages — caused by null-byte placeholder mechanism (now removed)
- Status badges now render correctly inline within paragraphs

## [1.1.0] - 2026-04-09

### Added

- **Storage-to-Markdown conversion** -- all read tools now convert Confluence storage format (XHTML) to clean Markdown for AI consumption. Full [Confluence storage format](https://confluence.atlassian.com/doc/confluence-storage-format-790796544.html) support: 20+ macro types (code, info, note, warning, tip, panel, expand, noformat, jira, status, anchor, toc, include, children, attachments, blog-posts, profile), images, links, emoticons, task lists, layouts, placeholders. Uses jsoup XML parser + flexmark-html2md-converter
- **Full page URLs in all responses** -- every tool response includes `url` field with full Server/DC URL (`{baseUrl}/pages/viewpage.action?pageId={id}`), matching upstream's behavior. AI clients now get clickable links
- **Response transformation** -- `ResponseTransformer` implements upstream's `to_simplified_dict()` whitelist approach. Each tool returns exactly the fields upstream returns: `{id, title, type, url, space, author, version, content, ancestors}`
- **Upstream-compatible response formats** for all 16 non-attachment tools:
  - `search` → flat list of simplified page dicts (was: raw nested Confluence API response)
  - `get_page` → `{"metadata": {...}}` wrapper with markdown content
  - `get_page_children` → `{parent_id, count, results: [...]}`
  - `get_comments` → flat list of simplified comment dicts with markdown bodies
  - `create_page/update_page` → `{"message": "...", "page": {...}}`
  - `delete_page` → `{"success": true, "message": "..."}`
  - `add_comment/reply_to_comment` → `{"success": true, "comment": {...}}`
  - `get_labels/add_label` → flat list of `{id, name, prefix}`
  - `search_user` → flat list of `{display_name, email}`
  - `get_page_diff` → JSON `{page_id, from_version, to_version, diff}`
- **Timestamp formatting** -- ISO 8601 timestamps converted to `YYYY-MM-DD HH:MM:SS` matching upstream's `TimestampMixin`
- **siteSearch fallback** -- search tool tries `siteSearch ~ "query"` first, falls back to `text ~ "query"` on error (mirrors upstream)

### Changed

- Version bump to 1.1.0
- Tools now use `client.getRaw()` + `ResponseTransformer` (whitelist) instead of `client.get()` + `ResponseTrimmer` (blacklist)
- `ConfluenceRestClient.getBaseUrl()` is now public, with `getRaw()`/`postRaw()`/`putRaw()`/`deleteRaw()` methods for untrimmed responses
- Comments fetched with `body.view` (rendered HTML) instead of `body.storage` (raw XHTML), matching upstream
- E2E tests updated to assert upstream-compatible response formats (URL presence, metadata wrapper, search result structure)

### Dependencies

- Added `flexmark-html2md-converter` 0.64.8 (HTML → Markdown conversion)
- Added `jsoup` 1.22.1 as provided scope (Confluence bundles it; used for storage format XML parsing)

## [1.0.1] - 2026-04-07

### Added

- **OAuth refresh token support** -- token endpoint accepts `grant_type=refresh_token`, enabling silent token renewal. User authenticates once, session stays alive indefinitely via automatic refresh
- Real `expires_in` from Confluence passed through to clients (was hardcoded 3600)
- E2E test for refresh token grant type: metadata validation + error paths (22 tests total)

### Changed

- OAuth metadata advertises `grant_types_supported: ["authorization_code", "refresh_token"]`
- Token exchange captures both `access_token` and `refresh_token` from Confluence's response
- `handleToken()` split into `handleAuthorizationCodeGrant()` + `handleRefreshTokenGrant()`
- Refresh token lifecycle managed by Confluence's database -- stateless on plugin side, survives restarts
- Deploy recipe: `clean` before `build`, resolve JAR glob via variable

### Security

- PKCE (S256) enforced on all authorization code grants
- Refresh token errors return `invalid_grant` with descriptive messages
- Rate limiting applies to refresh token requests (20/min per IP, same as token endpoint)

## [1.0.0] - 2026-04-07

### Added

- **23 MCP tools** mirrored 1:1 from upstream mcp-atlassian -- pages (search, get, create, update, delete, move, history, diff), comments (get, add, reply), labels (get, add), attachments (upload, download, list, delete, images), user search, page views
- **Streamable HTTP transport** -- MCP spec 2025-06-18 compliant. Session management via `MCP-Session-Id`, Origin validation, SSE streaming
- **OAuth 2.0 proxy** -- users authenticate via browser consent. RFC 9728 protected resource metadata, RFC 8414 authorization server metadata, PKCE (S256) enforced
- **PAT authentication** -- Personal Access Tokens as alternative to OAuth
- **Security hardening** -- rate limiting (IP-based), request body size limits, session TTL (4h), session-user binding, open redirect prevention, XSS fix, security headers, `[MCP-SEC]` event logging
- **Group and user access control** -- allowlists via Confluence groups or individual users
- **Per-tool management** -- enable/disable individual tools, read-only mode
- **Response trimming** -- strips verbose fields (`self`, `_links`, `_expandable`, `profilePicture`, `userKey`, `container`, `position`) and search highlight markers. 83% size reduction
- **Admin UI** -- tabbed interface (General, Access Control, Tools, OAuth) at `/plugins/servlet/mcp-admin`
- **Code generator** -- `python3 .codegen/translate.py` parses upstream Python tool definitions and generates Java tool classes
- **E2E test suite** -- protocol, tools, streaming, sessions, error handling tests
- **CI/CD** -- GitHub Actions for build (on push/PR) and release (on tag)

### Confluence 10.x adaptations

- `jakarta.*` namespace throughout (not `javax.*`)
- Spring Scanner 6.0.2 for `jakarta.inject` support
- `AuthenticatedUserThreadLocal` for current user (not SAL `UserManager`)
- `PermissionManager.isConfluenceAdministrator()` for admin checks
- Java 21 required (Confluence 10.2.7 class files)
- `ConfluenceAdministratorCondition` for admin web-item (not `UserLoggedInCondition`)
- User search via CQL `/rest/api/search` (group member API blocked for OAuth/3LO tokens)
