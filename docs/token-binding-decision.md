# Decision record — token audience binding (token passthrough): deferred

**Status:** Deferred — accepted residual risk, not implemented.
**Date:** 2026-05-31
**Scope:** `confluence-mcp-plugin` OAuth proxy (`OAuthServlet`, `AccessControlFilter`).
**Related:** `docs/SECURITY-AUDIT-2026-05.md` §3 (HIGH — token passthrough); `docs/confused-deputy` decision in the sibling `jira-mcp-plugin`.

---

## TL;DR / Decision

The plugin is an OAuth **proxy**: it hands the MCP client Confluence's own access/refresh token verbatim (token passthrough), and the resource endpoint (`/plugins/servlet/mcp`) only checks that Confluence resolved *a* principal — it does **not** verify the token was minted *for the MCP server* as its audience. This matches the MCP spec's "token passthrough" anti-pattern (`authorization.mdx:263-264, 360, 366`).

**Decision:** do **not** rearchitect to plugin-minted opaque tokens now. Document as accepted residual risk and revisit on the triggers below.

## Why the full fix is large

A proper fix means the plugin issues its **own** opaque `access_token` to the MCP client and keeps a server-side map `opaque → {Confluence access token, refresh token, expiry, client_id}`. On every MCP request the plugin would have to:

1. Intercept the inbound `Authorization: Bearer <opaque>` in (or before) `AccessControlFilter`, look up the mapping, and swap in the real Confluence token so Confluence still populates `AuthenticatedUserThreadLocal`.
2. Own refresh: when the underlying Confluence token expires, refresh it server-side without the client noticing.
3. Own revocation/expiry of the opaque tokens, and persist the map (cluster-aware) so it survives restarts and works across Data Center nodes.

This touches the **working authentication path** for every tool call and adds a stateful token store — material regression risk to a flow that is currently green (36/36 e2e).

## Why the residual risk is bounded

- **Per-user ACL enforcement still holds.** Tools forward the caller's own credential to Confluence, which enforces space/page permissions. A replayed foreign-audience token cannot exceed what that user could already do in Confluence.
- **No privilege escalation across users.** `SessionBindingFilter` binds an MCP session to the authenticating principal; a foreign token cannot ride another user's session.
- **The exploit requires a valid Confluence OAuth token for another client** issued by the *same* Confluence instance — a high bar, and its blast radius is that user's own permissions.

## Residual risk that remains

- The plugin cannot **attribute** calls to a specific MCP client (the token is opaque to it), weakening per-client audit.
- A token minted for any other Confluence OAuth client on this instance is accepted at the MCP endpoint (no audience pinning).

## Revisit triggers — implement plugin-minted tokens if any occur

- Multiple distinct MCP clients need independent audit/revocation.
- A compliance audit requires the spec's audience-binding MUST regardless of mitigating ACLs.
- Confluence DC gains RFC 8707 resource-indicator / audience-restricted token support, making the swap cheap.
- The proxy is exposed to untrusted clients at scale.

## If/when we build it — design notes

- New store (extend `OAuthStateStore` or a sibling): `opaque → ConfluenceTokens`, capped, expiring, cluster-aware.
- `OAuthServlet.handleAuthorizationCodeGrant` / refresh: return the plugin's opaque token, never Confluence's.
- `AccessControlFilter` (or a filter just before it): resolve opaque → real token, re-inject `Authorization` for downstream Confluence calls; 401 on unknown/expired opaque token.
- Add RFC 8707 `resource` to the upstream `/authorize` if/when Confluence honours it.
- Tests: opaque issued not equal to Confluence token; swap works for a tool call; refresh transparent; foreign-audience token rejected; cross-node lookup.

Note: the sibling `jira-mcp-plugin` is in the same position — if implemented here, mirror it there.
