# Changelog

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
