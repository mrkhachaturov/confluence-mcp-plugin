# Security Audit — confluence-mcp-plugin

|                     |                                                                                                                                                                                                    |
| ------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Artifact**        | `com.atlassian.mcp:confluence-mcp-plugin` v1.2.2                                                                                                                                                   |
| **Audit date**      | 2026-05-31                                                                                                                                                                                         |
| **Target platform** | Confluence Data Center 10.2.11 (Jakarta EE 10 / Tomcat 10.1 / Java 21)                                                                                                                             |
| **MCP transport**   | Streamable HTTP, MCP Java SDK `2.0.0-M3`                                                                                                                                                           |
| **Method**          | 5-agent parallel deep audit, each dimension cross-checked against the vendored MCP specification (`.upstream/modelcontextprotocol`, 2025-06-18) and the MCP Java SDK source (`.upstream/java-sdk`) |
| **Scope**           | 56 Java sources, `atlassian-plugin.xml`, `pom.xml`, admin JS/Velocity                                                                                                                              |

> **Reference standards used as the source of truth**
>
> - MCP spec — `docs/specification/2025-06-18/basic/{authorization,transports}.mdx`, `docs/docs/tutorials/security/{security_best_practices,authorization}.mdx`, `SECURITY.md` (trust model), SEP-985 / SEP-991.
> - MCP Java SDK — `mcp-core/.../server/transport/{DefaultServerTransportSecurityValidator,HttpServletStreamableServerTransportProvider}.java`, `SECURITY.md`, `DEPENDENCY_POLICY.md`.

---

## 1. Executive Summary

The plugin is **well-engineered defensively**. Authentication is delegated to Confluence (no blind trust of request-supplied identity), tools forward the *caller's own* credential so Confluence enforces per-user ACLs (no service-account IDOR), Origin validation is genuinely enabled, PKCE is mandatory and S256-only, the SSRF defense on the CIMD client-metadata fetch is strong, and **no credentials are written to logs**. There are **no insecure XML parsers (no XXE), no polymorphic Jackson deserialization, and no shell execution** anywhere in the codebase.

The findings concentrate in two structural areas: (1) the **OAuth proxy** is a *transparent* pass-through of Confluence's tokens and consent, which opens confused-deputy and token-passthrough gaps the MCP spec explicitly calls out; and (2) the **default access posture and admin control plane** are weaker than the rest of the design — an empty allowlist silently grants all authenticated users, and the admin config mutation endpoint has no CSRF protection.

### Severity tally

| Severity      | Count | Findings                                                                                                                                                                                                                                             |
| ------------- | ----- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| 🔴 CRITICAL   | 0     | —                                                                                                                                                                                                                                                    |
| 🟠 HIGH       | 4     | Host-header/DNS-rebinding half-wired; MCP-Protocol-Version not validated; no token audience binding (token passthrough); empty allowlist = allow-all; no CSRF on admin PUT                                                                           |
| 🟡 MEDIUM     | 6     | Session-binding drift; session bindings JVM-local; open DCR without redirect validation; client secret plaintext at rest; PKCE/scope conduit; XFF-spoofable rate limit; global rate-limit reset; broken/injectable JSON output; CQL clause injection |
| 🔵 LOW / INFO | many  | confused-deputy / per-client consent (silent bypass empirically non-exploitable — see §3); headers, code TTLs, ReDoS surface, dependency tracking, stored-content trust boundary                                                                     |

> Note: the HIGH row lists 5 labels for a count of 4 because two HIGH items share the OAuth/access-control dimension — see the per-section detail. The **confused-deputy / per-client-consent** finding was downgraded from HIGH to LOW after an empirical test (2026-05-31) showed the Atlassian OAuth provider re-prompts for consent on every authorize even with a warm cookie, defeating the silent zero-click bypass (§3). Prioritise the remediation list in §8.

---

## 1a. Remediation status — shipped in v1.2.3 (2026-05-31)

All findings below were remediated or formally deferred in **v1.2.3**, verified by 26 new unit tests + 2 new e2e tests (36 e2e total, green) and live checks on the running instance (CSRF forged-Origin → 403, SSRF metadata URL → 400, default-deny denied then allowlist restored). See `CHANGELOG.md` [1.2.3].

| Finding                                                    | Severity | Status in 1.2.3                                                                                        |
| ---------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------------------------ |
| Admin PUT CSRF (+ `confluenceBaseUrl` SSRF chain)          | HIGH     | **Fixed** — Origin/Referer CSRF check in `ConfigResource`; `UrlSafety` set-time + read-time validation |
| Empty allowlist = allow-all                                | HIGH     | **Fixed** — default-deny + opt-in `allowAllAuthenticatedUsers` toggle                                  |
| Token audience binding (passthrough)                       | HIGH     | **Deferred** — `docs/token-binding-decision.md` (bounded by per-user ACL)                              |
| Host header not validated (DNS-rebinding)                  | HIGH     | **Fixed** — `McpBootstrap` Host allowlist (421 on mismatch)                                            |
| `MCP-Protocol-Version` not validated                       | HIGH     | **Fixed** — `McpProtocolVersionFilter` (400); stale `CLAUDE.md` claim corrected                        |
| Per-client OAuth consent (confused deputy)                 | LOW      | **Deferred** — silent bypass non-exploitable (§3)                                                      |
| Open DCR without redirect validation                       | MEDIUM   | **Fixed** — `isAllowedRedirectUri` applied to `/register`                                              |
| `X-Forwarded-For` spoofable                                | MEDIUM   | **Fixed** — `ClientIp` honours XFF only from loopback peer                                             |
| Rate-limiter global reset / new-key reject                 | MEDIUM   | **Fixed** — per-key sliding window + lazy eviction                                                     |
| Broken/injectable download JSON                            | MEDIUM   | **Fixed** — Jackson-built output                                                                       |
| CQL clause injection                                       | MEDIUM   | **Fixed** — `CqlSafety` escaping + `spaces_filter` validation                                          |
| Session-binding drift / JVM-local                          | MEDIUM   | Open — tracked (single-node assumption documented)                                                     |
| OAuth client secret plaintext at rest                      | LOW      | Accepted — platform-standard (PluginSettings)                                                          |
| `scope` conduit, code TTLs, headers, flexmark/SDK tracking | LOW/INFO | Open — non-urgent backlog                                                                              |

---

## 2. Transport & MCP Protocol Security

Division of responsibility: the SDK transport owns JSON-RPC framing, session creation/lookup/expiry, `Accept` negotiation, DELETE handling, and Origin/Host validation (via the injected `securityValidator`). The plugin owns the filter chain and the validator configuration.

### 🟠 HIGH — Host header is never validated; DNS-rebinding defense is half-wired

- **Location** `McpBootstrap.java:117-145` (`buildSecurityValidator`); enforced in SDK `DefaultServerTransportSecurityValidator.java:108-131`.
- **Evidence** The builder only ever calls `allowedOrigin(...)`, never `allowedHost(...)`. The SDK's `validateHost` short-circuits when `allowedHosts.isEmpty()` → every Host value passes, and a missing Host is tolerated.
- **Risk** Origin validation *is* wired (the primary control), but the SDK treats an **absent** `Origin` as same-origin → allowed. A non-browser client or one that omits/forges Origin is then not stopped by Host checking either. Pinning Host to the deployment FQDN is the belt-and-suspenders control and it is disabled.
- **Reference** `transports.mdx:76` ("Servers MUST validate the `Origin` header … to prevent DNS rebinding"); SDK `Builder.allowedHost(...)`.
- **Fix** Populate `allowedHosts` from the resolved Confluence base-URL host (+ `host:*`) alongside the origins, so a missing/forged Host fails closed (421).

### 🟠 HIGH — `MCP-Protocol-Version` header is NOT validated (docs claim it is)

- **Location** SDK `HttpServletStreamableServerTransportProvider.java` `doPost`/`doGet`/`doDelete` — no read of `MCP-Protocol-Version`. Contradicts `CLAUDE.md` ("validated by the SDK on non-initialize requests").
- **Risk** Spec violation, not a direct exploit: an unsupported/garbage protocol version is silently accepted instead of `400 Bad Request`. The project docs assert this check exists, so reviewers wrongly assume coverage. No filter compensates.
- **Reference** `transports.mdx:242-257` — invalid/unsupported `MCP-Protocol-Version` **MUST** → `400`.
- **Fix** Add a small filter (or extend `SessionBindingFilter`) to reject non-initialize requests whose present `MCP-Protocol-Version` is not in the SDK's `protocolVersions()` set; **and** correct the stale claim in `CLAUDE.md`/`atlassian-plugin.xml`.

### 🟡 MEDIUM — Session-user binding drift vs the SDK session table

- **Location** `SessionBindingFilter.java:52-95`.
- **Risk** The plugin's binding map is populated only from the `initialize` POST (detected by body-sniffing `looksLikeInitialize`). An SDK-known session can therefore be plugin-unbound; cross-user replay still fails closed (401/403), but the two tables can disagree, and a batched/wrapped initialize would evade detection.
- **Fix** Share one `MCP-Session-Id` constant between filter and SDK; bind on the captured response `MCP-Session-Id` for *any* successful POST that issues one; add a drift metric.

### 🟡 MEDIUM — Session bindings are JVM-local (no clustering, capped at 200)

- **Location** `SessionBindingFilter.java:33-36,111-119` (`static` map, `MAX_BINDINGS=200`, FIFO eviction, 4h TTL).
- **Risk** Breaks multi-node Confluence DC (binding on node 1 unknown on node 2 → 401); under load a still-valid binding can be evicted while its SDK session persists → spurious 401 (availability). Fails closed, so no confidentiality breach.
- **Fix** Document the single-node assumption or back the map with a cluster-aware store; align binding TTL with SDK session lifetime; parameterise the cap.

### 🔵 LOW

- **Security headers** (`SecurityHeadersFilter.java:21-25`) set `nosniff`, `no-store`, `X-Frame-Options: DENY` — good baseline. Missing `Referrer-Policy` and `CSP` (cheap to add for a JSON/SSE API). SSE responses get the SDK's `no-cache` (last-writer-wins) — acceptable.
- **Request body buffered into memory twice** (`BufferedRequestWrapper.java:22-25`); the unbounded `readAllBytes()` constructor is safe *only* because `BodySizeLimitFilter` (weight 200) runs before `SessionBindingFilter` (weight 500). Latent — make the cap intrinsic to the wrapper rather than ordering-dependent.
- **Internal exception messages leaked to clients** (`McpToolAdapter.java:104-110`) — the `RuntimeException` branch returns `"Internal error: " + e.getMessage()` verbatim, which could carry an internal URL/CQL fragment. Return a generic message; keep detail server-side.

### ✅ Done correctly

Origin validation genuinely enabled & configured (`McpBootstrap.buildSecurityValidator` → `.securityValidator(...)`); session id is `UUID.randomUUID()` (adequate entropy); session lifecycle SDK-owned (404 on unknown); cross-user replay blocked (403/401); JSON 401 + `WWW-Authenticate` instead of Seraph 302; body size enforced on **actual bytes read**, not `Content-Length`; runtime admin toggles re-checked at call time; async dispatch wired safely via `<servlet-filter>` + `<dispatcher>ASYNC</dispatcher>`.

---

## 3. OAuth & Authentication

The plugin is an OAuth 2.0 **proxy** ("MCP Proxy Server"): it fronts Confluence's own OAuth provider, mints its own proxy codes, and passes Confluence's access/refresh tokens back to the MCP client. Resource-endpoint token validation is delegated to Confluence.

### 🔵 LOW (downgraded from HIGH) — No per-client consent screen; confused-deputy *silent bypass* empirically non-exploitable

- **Location** `OAuthServlet.java:222-311` (`handleAuthorize`), `:181-220` (`handleRegister`).
- **Evidence** `handleAuthorize` validates `redirect_uri`, then immediately redirects to Confluence using the plugin's **static** upstream `client_id`, with no MCP-server-owned consent step. `/register` (DCR) is open and unauthenticated. The plugin structurally matches the confused-deputy proxy pattern (static client_id + DCR + redirect straight to the upstream `/authorize` with no interstitial).
- **Why it is *not* HIGH — empirical test (2026-05-31).** The zero-click confused-deputy attack requires condition (3): the upstream authorization server **skips its consent screen when a consent cookie is present**. This was tested live on the sibling Atlassian DC OAuth provider (Jira, `bpm.astrateam.net`, same `/rest/oauth2/latest/authorize` + `/plugins/servlet/oauth2/consent` provider family as Confluence): the provider **re-prompted for consent on every authorize, even with the cookie present** (two consecutive authorizes in the same window both showed the consent screen). Condition (3) is therefore **false**, so the silent/zero-click bypass is **not exploitable** — the AS itself handles the thing the spec is most worried about. See `jira-mcp-plugin/docs/confused-deputy-consent-decision.md` for the full method/result.
- **Residual risk (why LOW, not INFO).** A **one-click phishing** risk remains: the upstream consent screen shows the plugin's own `…/mcp-oauth/callback` redirect_uri (which looks legitimate), not the attacker's *final* registered `redirect_uri`. A victim who habitually clicks "Allow" can still be tricked. This is the generic OAuth-consent phishing class, not the proxy-specific silent bypass. An MCP-owned consent page would close it by displaying the *true* destination (`attacker.com`).
- **Caveat — confirm on Confluence.** The live test was run on Jira. Confluence uses the same Atlassian OAuth2 provider, so behavior is expected to be identical, but **re-run the test against this Confluence instance** (and after any major Confluence upgrade) to confirm it also re-prompts. Reproduction steps are in the Jira decision record.
- **Reference** `security_best_practices.mdx:155-160` (per-client consent MUST), consent-flow diagram `:175-205`, SEP-991 `:89`. (The MUST is a defense-in-depth control here, not an active-exploit fix, given the AS re-prompts.)
- **Decision** **Deferred — accepted residual risk**, mirroring the Jira plugin's decision record. Build the MCP-owned consent page only if a revisit trigger fires: Confluence starts skipping consent on a warm cookie; the proxy targets a different/external AS with unknown behavior; DCR is opened to untrusted clients at scale; or strict compliance demands the spec MUST regardless. Design notes for that page (per-client storage, consent UI showing the true `redirect_uri`, CSRF, anti-clickjacking) are in the Jira decision record.

### 🟠 HIGH — No token audience binding / resource indicator → token passthrough

- **Location** `OAuthServlet.java:300-308` (authorize, no `resource` param), `:440-452` (token grant + refresh pass-through), `AccessControlFilter.java:61-90` (resource side checks only `principal != null`).
- **Risk** The MCP client receives Confluence's raw token, unmodified; the resource side performs no audience check. A token minted for **any** Confluence OAuth client can be replayed against the MCP endpoint, and the plugin cannot attribute calls per client (opaque token).
- **Reference** `authorization.mdx:263-264, 360, 366` (MUST validate audience; reject tokens not in audience; token passthrough forbidden); `security_best_practices.mdx:278-308`.
- **Fix** Issue a **plugin-minted opaque access token** mapped server-side to the Confluence token (plugin owns audience + revocation + per-client audit). If pass-through must remain, document the residual risk and tie the session to the specific client.

### 🟡 MEDIUM — Open, unauthenticated DCR with no redirect-URI validation

- **Location** `OAuthServlet.java:181-220`; `OAuthStateStore.java:135-145`.
- **Risk** DCR stores arbitrary `redirect_uris` with no scheme/host check — unlike the CIMD path, which correctly rejects non-HTTPS/non-loopback and embedded credentials. This is the mechanism behind the residual **one-click phishing** variant of confused deputy (the silent variant is non-exploitable — see §3) and a general hygiene gap.
- **Fix** Apply `CimdValidator.isAllowedRedirectUri` to DCR `redirect_uris`; consider admin-gating or disabling DCR in favour of CIMD-only.

### 🟡 MEDIUM — PKCE is enforced client↔plugin, but `scope` is a client-controlled, unvalidated conduit

- **Location** `OAuthServlet.java:303-308` (scope forwarded as-is), `:501-511`.
- **Risk** The client's `scope` is forwarded to Confluence verbatim (default `WRITE`), never validated against the advertised `["WRITE"]`. Impact is bounded — Confluence rejects unregistered scopes (`invalid_scope`), so it's a UX/availability issue, not escalation. (Background: the WRITE-only requirement is recorded in project memory.)
- **Fix** Validate/normalise incoming `scope` against the advertised allowlist before forwarding.

### 🔵 LOW

- Client `state` is optional and reflected unconditionally — the plugin's own internal-state CSRF protection is sound (single-use UUID, 10-min expiry); the gap is only for non-compliant downstream clients. Consider requiring a non-empty `state`.
- Proxy authorization codes share the 10-min pending-auth TTL — OAuth 2.1 prefers ≤60s. Give codes a separate short TTL.
- Two metadata documents (servlet `LinkedHashMap` builder vs `OAuthAnonymousFilter` hand-built `.well-known` JSON) can drift — generate both from one builder.

### ✅ Done correctly

PKCE mandatory & **S256-only** (no `plain` downgrade), verified server-side; exact-match `redirect_uri` at authorize **and** re-checked at token exchange; `client_id` binding on exchange; single-use/expiring/capacity-bounded state & codes; **strong CIMD SSRF defense** (HTTPS-only, `Redirect.NEVER`, IP allowlist incl. `169.254.169.254`, 8 KB body cap, bounded cache, re-validated at `/token`); client secret never disclosed via API/logs; anonymous filter narrowly scoped (only `.well-known/*`, never bypasses auth on protected paths); spec-compliant 401 + `WWW-Authenticate` w/ `resource_metadata`; rate-limit + body caps on every OAuth endpoint; no upstream redirect following.

---

## 4. Access Control & Authorization

### 🟠 HIGH — Empty allowlist silently grants all-authenticated access

- **Location** `McpPluginConfig.java:51-57` → `AccessControlFilter.java:93-103`.
- **Evidence** `isUserAllowed` returns `true` when both `allowedUsers` and `allowedGroups` are blank — the **shipped default** (both default to `""`).
- **Risk** Default posture is "any logged-in Confluence user (incl. service/bot accounts) is an MCP operator," with full write-tool access. Clearing the field to remove one user silently re-opens to everyone. Violates least-privilege.
- **Reference** `security_best_practices.mdx` (least privilege / explicit tool exposure); SDK `SECURITY.md`.
- **Fix** Default deny when no allowlist is configured (or require an explicit opt-in "allow all authenticated" boolean). Surface the effective scope in the admin UI.

### 🟠 HIGH — No CSRF protection on admin config PUT; XSRF check actively disabled

- **Location** `ConfigResource.java:64-101`; `js/admin.js:3` sends `X-Atlassian-Token: no-check`.
- **Risk** The mutating REST endpoint authorizes only on the admin's ambient session cookie, and the client opts **out** of Atlassian XSRF enforcement. A logged-in admin visiting an attacker page can be CSRF'd into `enabled=true`, `readOnlyMode=false`, adding the attacker to `allowedUsers`, or overwriting `oauthClientId`/`oauthClientSecret` — full privilege escalation into the MCP control plane.
- **Reference** Atlassian DC plugin guidance (mutating REST must enforce XSRF); `security_best_practices.mdx`.
- **Fix** Remove `X-Atlassian-Token: no-check`; require a valid XSRF token on all mutating methods.

### 🟡 MEDIUM — Rate-limit bucket trusts client-supplied `X-Forwarded-For`

- **Location** `RateLimitFilter.java:78-84` (dup in `AccessControlFilter.java:115-121`).
- **Risk** Anonymous attacker rotates `X-Forwarded-For` per request to mint unlimited buckets, defeating the 120/min anonymous limit (DoS / brute-force amplification on the auth & OAuth layers) and poisoning the `[MCP-SEC]` audit log.
- **Fix** Honour `X-Forwarded-For` only when `getRemoteAddr()` is a trusted proxy (configurable allowlist); else use `getRemoteAddr()`.

### 🟡 MEDIUM — Rate limiter does a global counter reset each minute

- **Location** `RateLimiter.java:45-67`.
- **Risk** Fixed wall-clock window → ~2× burst across the boundary; `counters.clear()` + `MAX_TRACKED_IPS=10_000` cap means an attacker who fills the map causes the limiter to **reject all new IPs** until reset (DoS of legitimate clients). Check-then-act over the cap is also racy.
- **Fix** Per-key sliding window / token bucket with per-key TTL eviction; when over the cap, evict stale keys rather than blanket-reject.

### 🔵 LOW

- Capability (`requiredPluginKey`) and the per-user allowlist are not re-checked on the dispatch path (`McpToolAdapter.dispatch` re-checks only `isToolEnabled` + read-only). Allowlist is still enforced per HTTP request (every `tools/call` re-enters the filter), so the residual gap is only stale capability after a companion-plugin disable. Have `dispatch` call `ToolRegistry.checkToolAccess(...)` for a single source of truth.
- `allowedUsers` stored raw; matched against **both** `user.getKey()` and `user.getName()`, case-sensitively — key-vs-name ambiguity and case mismatches cause confusing 403s. Normalise case; validate users exist at save time.

### ✅ Done correctly

Real authentication via Confluence `AuthenticatedUserThreadLocal` (never request-parsed); tools forward the caller's own `Authorization` header → Confluence enforces per-user ACLs (no service-account IDOR); auth runs before tool dispatch with spec-correct anonymous JSON 401; read-only & disabled-tool enforced **server-side at execution**, not just list hiding; admin page **and** both REST methods gated by `isConfluenceAdministrator`; client secret not exposed via admin GET; session binding blocks cross-user replay; body-size limit on actual bytes.

---

## 5. Injection, SSRF & Unsafe Parsing

> Grep for `DocumentBuilderFactory|SAXParser|XMLInputFactory|Unmarshaller|enableDefaultTyping|TrustManager|HostnameVerifier|setFollowRedirects|Runtime.exec|ProcessBuilder` across `src/main/java` returned **zero matches**.

### 🟠 HIGH — SSRF via admin-configurable `confluenceBaseUrl` override

- **Location** `McpPluginConfig.java:101-108` → `ConfluenceRestClient.java:143-149` (`getBaseUrl`), used by every tool's `buildRequest`/`postMultipart`/`getBytes`.
- **Evidence** `getBaseUrl()` returns the override (only trailing-slash-trimmed — no scheme/host validation, no allowlist). Set to `http://169.254.169.254` or any internal host, **all 28 tools** become a request proxy carrying the caller's `Authorization` header to the attacker host (credential exfiltration + internal SSRF).
- **Risk** A compromised admin (or anyone reaching the CSRF-able admin PUT, §4) repoints the whole plugin and harvests every caller's bearer token. *(This finding compounds the §4 CSRF finding — together they are the highest-impact chain.)*
- **Fix** Validate the override at set & read time: require `https?://`, reject loopback/link-local/private/metadata ranges (or enforce a host allowlist); never forward `Authorization` to a host differing from `applicationProperties.getBaseUrl()`. Consider removing the override entirely.

### 🟡 MEDIUM — Broken/injectable JSON from hand-built response strings

- **Location** `DownloadAttachmentTool.java:65-66`; `DownloadContentAttachmentsTool.java:67,71-74`; `GetPageImagesTool.java:88,92-95`.
- **Evidence** Attachment `title` is concatenated into a JSON literal **without escaping**; error branches only strip `"` (leaving `\`/newline), so a title/message can break out of the field.
- **Risk** Output-integrity / injection into the structured result the consuming agent trusts.
- **Fix** Build these with Jackson (`ObjectNode` + `writeValueAsString`) like the other tools; never embed `e.getMessage()`/`title` into hand-assembled JSON.

### 🟡 MEDIUM — CQL clause injection in `search` / `search_user`

- **Location** `SearchTool.java:62-78,89-99`; `SearchUserTool.java:59`.
- **Evidence** URL-encoding is correct (no HTTP-level injection), but values are interpolated into the **CQL grammar** with only naive `"`→`\"`; `spaces_filter` tokens get **no escaping**, so `A") OR (space=B` injects clauses and escapes the intended space restriction.
- **Risk** The agent can broaden/alter its own search; bounded by Confluence per-result ACLs (info-shaping, not escalation), but `spaces_filter` is not a real boundary as built.
- **Fix** Escape CQL literals (`\` then `"`); validate `spaces_filter` tokens against `[A-Za-z0-9_~-]+`.

### 🔵 LOW

- `MarkdownToStorage` deliberately passes raw `<ac:*>`/`<ri:*>` through (HTML_ALLOW_NAME_SPACE) — intended for native macros, sanitised by Confluence on render, so **not** stored XSS. The real note is a **prompt-injection trust boundary**: content returned by read tools is untrusted *data*, not instructions. Document for consumers.
- `escapeXml` omits `'`; the `<details>`→expand path does no escaping (relies on flexmark) — route expand titles through `escapeXml`, add `&#39;`.
- Regex passes (`DETAILS_BLOCK`, task lists) are lazy/linear — no catastrophic ReDoS; input already bounded by the body-size filter.

### ✅ Done correctly

**No XXE / billion-laughs** — `Jsoup.parse(..., Parser.xmlParser())` does not process DTDs/entities and does no network I/O; redirects disabled (`Redirect.NEVER`); no TLS trust bypass; no polymorphic Jackson; no shell exec; URL-query encoding correct; outbound write bodies built with Jackson; downloads returned base64 inline (no disk write, no path traversal); 50 MB download caps; `ResponseTrimmer`/`ResponseTransformer` whitelist output (no tokens/links leaked).

---

## 6. Dependencies, Secrets & Configuration

### Dependency inventory (key items)

| Dependency                                                   | Version                  | Scope                 | Concern                                          |
| ------------------------------------------------------------ | ------------------------ | --------------------- | ------------------------------------------------ |
| `confluence`                                                 | 10.2.11                  | provided              | Host platform                                    |
| `platform-public-api` (BOM)                                  | 8.3.16                   | import                | Manages jakarta/spring/jackson/sal               |
| `jackson-*`                                                  | BOM-managed (~2.21.2)    | provided              | Host-provided — **VERIFY** host ships patched    |
| `io.modelcontextprotocol.sdk:mcp-core` / `mcp-json-jackson2` | **2.0.0-M3**             | compile (**bundled**) | Milestone pre-release shipped in JAR             |
| reactor / networknt json-schema-validator / ethlo time       | transitive (**bundled**) | compile               | Unpinned SDK transitives — **VERIFY** advisories |
| `org.jsoup:jsoup`                                            | 1.22.1                   | provided              | Recent; no known open CVE                        |
| `com.vladsch.flexmark:*`                                     | 0.64.8                   | compile (**bundled**) | Final release; upstream archived/unmaintained    |

### 🟡/🔵 Findings

- **LOW — OAuth client secret plaintext in `PluginSettings`** (`McpPluginConfig.java:120-127`). No host secret vault exists for DC plugins, so this is platform-standard and acceptable; document that DB/backups are secret-bearing. OAuth tokens are **not** persisted here (in-memory `OAuthStateStore` + Confluence's own token DB — correct).
- **INFO — MCP SDK pinned to milestone `2.0.0-M3`** (`pom.xml:20`), bundled with its transitives. SDK `DEPENDENCY_POLICY.md` runs no scheduled bumps → downstream owns transitive CVE risk. **VERIFY** newer milestone/GA; run a one-time OSS scan of the bundled transitives.
- **INFO — flexmark 0.64.8 bundled, upstream unmaintained.** Parses attacker-influenced Markdown & storage HTML; no future patches. No known CVE (**VERIFY**). Input bounded by `BodySizeLimitFilter`. Accept with monitoring.
- **INFO — `DynamicImport-Package:*`** (`pom.xml:247`) widens OSGi resolution; documented as required for reflective SDK/flexmark wiring. Residual risk LOW (host controls installed bundles). Narrow to an explicit list later if feasible.

### ✅ Done correctly

Admin GET masks the secret (`oauthClientSecretSet` boolean only); password field write-only; empty PUT preserves existing secret; **no credentials in logs** across 33 log sites (only IPs/usernames/tool names/truncated ids/messages); `sanitizeLog()` strips CR/LF/TAB (log-injection hardening); host-provided deps kept out of the bundle (jackson/jsoup/slf4j `provided`); `.credentials/` and friends gitignored, no committed secrets; bounded/expiring in-memory state stores.

---

## 7. Trust Model Notes (not vulnerabilities)

Per MCP `SECURITY.md`, the following are intentional and in-scope by design:

- **Caller-credential forwarding.** Tools act *as the authenticated user*; the plugin cannot exceed that user's Confluence permissions. This is the right model and neutralises most "injection" to information-shaping within the user's own ACL scope.
- **Agent-authored content.** Write tools intentionally let agents emit native Confluence macros (raw storage XML). The mitigation for round-tripped untrusted content is consumer-side (treat read-tool output as data, not instructions), not server-side sanitisation.

---

## 8. Prioritised Remediation

**Fix first (HIGH, exploitable or escalating):**

1. **Admin PUT CSRF + `confluenceBaseUrl` SSRF chain** (§4 + §5) — remove `X-Atlassian-Token: no-check` and enforce XSRF; validate/allowlist the base-URL override and never forward `Authorization` cross-host. *This chain = remote credential exfiltration of every caller's token.*
2. **Default empty-allowlist = allow-all** (§4) — flip to default-deny; surface effective scope in admin UI.
3. **Token audience binding** (§3) — issue plugin-minted opaque tokens instead of passing Confluence's token through.
4. **Host-header validation + `MCP-Protocol-Version` enforcement** (§2) — complete the DNS-rebinding defense and the spec-required 400 path; fix the stale doc claims.

**Then (MEDIUM):** DCR redirect-URI validation (also closes the one-click phishing leg of confused deputy); `X-Forwarded-For` trust; per-key sliding-window rate limiter; Jackson-build the download/image JSON; CQL literal/`spaces_filter` escaping; session-binding drift + clustering.

**Deferred — accepted residual risk (§3):** per-client OAuth consent page. The silent confused-deputy bypass is empirically non-exploitable (Atlassian AS re-prompts on every authorize); only one-click phishing remains. Re-run the consent test on Confluence to confirm, then build the page only if a revisit trigger fires.

**Track (INFO):** verify & bump MCP SDK off `2.0.0-M3`, OSS-scan bundled transitives; monitor unmaintained flexmark; narrow `DynamicImport-Package`.

---

*Generated by a 5-agent parallel security audit (transport, OAuth, access control, injection/SSRF, dependencies/secrets), each cross-referenced against the MCP 2025-06-18 specification and MCP Java SDK source vendored under `.upstream/`. Line numbers reflect the working tree at audit time (v1.2.2).*
