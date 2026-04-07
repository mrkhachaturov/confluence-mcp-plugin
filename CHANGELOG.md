# Changelog

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
