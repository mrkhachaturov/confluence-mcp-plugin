# HANDOFF — Jakarta platform + MCP Java SDK migration

**Branch:** `feature/platform-10.2.11-jakarta-sdk` (ALL work for this migration happens
here — do **not** create additional branches. Merge to `main` only when the whole
migration is done and verified.)

**Goal:** Bring `confluence-mcp-plugin` onto the current Confluence 10.2.11 / Jakarta EE 10
platform, then replace the hand-rolled JSON-RPC layer with the official **MCP Java SDK** —
mirroring what was already done in the sibling project `jira-mcp-plugin` (`../jira-mcp-plugin`).

The Jira plugin is the **reference implementation**. Its platform is now identical to ours
(both on `platform-public-api:8.3.16`), so its code and docs port over almost 1:1.

---

## Why (context)

- Confluence Data Center 10.x is **fully Jakarta** — Tomcat 10.1.x (`jakarta.servlet`),
  Spring 6, Jakarta EE 10, Java 21. Verified from the product POMs, not docs.
- The old CLAUDE.md "Hard-Won Lesson: **javax, NOT jakarta**" is **stale/wrong** for 10.2.x.
  Source is already 100% `jakarta.*`. Fix CLAUDE.md as part of this work.
- There is **no public Confluence API BOM** (no analog to `jira-api-bom`). Version
  management uses the internal `confluence-project` parent, which imports the shared
  `com.atlassian.platform.dependencies:platform-public-api`. We import that platform BOM
  directly. Confirmed 3 ways (local `.m2`, mvnrepository "BOMs" tab, the POM chain).

---

## DONE so far (commit `54bbb0c` on this branch)

Platform foundation only — **no tool/runtime code changed yet**. `pom.xml`:

- `confluence.version` `10.2.7` → **`10.2.11`** (the running instance is 10.2.11).
- Added property `platform.dependencies.version = 8.3.16` and a `<dependencyManagement>`
  import of `com.atlassian.platform.dependencies:platform-public-api:8.3.16` (`type=pom`,
  `scope=import`). This is the version `confluence-project:10.2.11` itself uses.
- Dropped hardcoded `<version>` from provided platform deps — now resolved by the BOM to
  exactly what Confluence 10.2.11 ships:
  | dep | resolved |
  |---|---|
  | `atlassian-annotations` | 5.1.5 |
  | `sal-api` | 7.0.4 |
  | `atlassian-template-renderer-api` | 7.0.6 |
  | `atlassian-plugins-api` | 9.1.4 |
  | `jackson-databind` | 2.21.2 |
  | `jakarta.servlet-api / ws.rs-api / inject-api` | 6.0.0 / 3.1.0 / 2.0.1 (unchanged) |
  | `atlassian-spring-scanner-annotation` | 6.0.2 |
- Switched legacy `atlassian-rest-common` → **`atlassian-rest-v2-api`** (BOM-managed 9.1.4,
  matches Jira; our code references no `com.atlassian.plugins.rest` types directly).
- Removed `javax.servlet-api` (source has **0** `javax` imports).
- **AMPS** `confluence-maven-plugin` `9.9.1` → **`9.12.5`** (latest; correct OSGi/jakarta
  manifest generation for Java 21 / Confluence 10.2.x). `atlassian-spring-scanner-maven-plugin`
  is already at latest (6.0.2).

**Verification:** `atlas-mvn clean package` → **BUILD SUCCESS**.

### Build note (important for any session)
`atlas-mvn` needs Java on PATH. In a non-interactive shell the mise shims may not be
active — set:
```bash
export JAVA_HOME="$(mise where java)"   # Temurin 21
```
Then `just build` / `atlas-mvn ...` work. Env vars (`.credentials/confluence.env`) are
auto-loaded by mise from `.mise.toml`.

---

## DONE — SDK migration complete

The MCP Java SDK migration (Steps 0–6 below) is **complete and verified live** (e2e 33/33 green).
The MCP endpoint moved from JAX-RS `POST /rest/mcp/1.0/` to the SDK streamable transport
mounted as async `<servlet-filter>` modules at `POST /plugins/servlet/mcp`; `JsonRpcHandler`
and `McpResource` are deleted; tools are adapted via `McpToolAdapter` /
`ToolRegistry.toSpecifications()`; security is a six-filter chain (`before-dispatch`,
all `@UnrestrictedAccess`); OAuth gained OIDC discovery + CIMD.

The authoritative records are the design spec and implementation plan:

- Plan: `docs/rkstack/plans/2026-05-30-mcp-sdk-migration.md`
- Spec: `docs/rkstack/specs/2026-05-30-mcp-sdk-migration-design.md`

**The spec supersedes the SDK-migration portion of this HANDOFF** (the roadmap below is kept
for historical context only). User-facing docs (`CLAUDE.md`, `README.md`, admin UI) are
updated to match.

## NEXT — the remaining roadmap (historical)

Follow the same disciplined order the Jira plugin used: **docs first (design spec →
dual-review → implementation plan), then incremental code commits.** Do NOT flip everything
at once.

### Step 0 — Design spec + implementation plan (do this next)
Write a design spec and step-by-step plan in `docs/` for the SDK migration, then run it
through Codex dual-review (`/dual-review`), exactly as Jira did. Base it on the Jira docs
(see references) — the platform is identical, so most of it transfers.

### Steps 1–6 — Code (each a separate commit on THIS branch)
Reconstructed from the Jira commit history (branch `feature/jakarta-jira-11`):

1. **Add MCP SDK deps + OSGi embedding.**
   `io.modelcontextprotocol.sdk:mcp-core` + `mcp-json-jackson2` (Jira used `2.0.0-M3` —
   check for a newer release first). Embed via OSGi `<Private-Package>`:
   `io.modelcontextprotocol.*`, `com.networknt.schema.*`, `com.networknt.*`, `reactor.*`,
   `org.reactivestreams.*`, `com.ethlo.time.*`. Widen `<Import-Package>` with
   `resolution:="optional"` entries as Jira did. Pin `slf4j-api` provided.
   (Jira commits: `3501e83`, `c172d5f`.)

2. **Replace hand-rolled JSON-RPC with SDK transport.**
   New `McpBootstrap` builds `HttpServletStreamableServerTransportProvider` +
   `McpSyncServer`. Delete `JsonRpcHandler` and the hand-rolled dispatch in `McpResource`.
   (Jira commit: `9ef51ba`. See `../jira-mcp-plugin/.../rest/McpBootstrap.java`.)

3. **Async transport — the one real platform gotcha.**
   The SDK streamable transport calls `request.startAsync()`. Atlassian `<servlet>` modules
   hard-code `asyncSupported=false`. Jira solved it with a `<servlet-filter>` module
   (`McpTransportFilter`) + the JVM flag `atlassian.plugins.filter.async.default=true` set
   on the server. **Verify this flag works on Confluence 10.2.11** (likely same plugin
   framework). (Jira commits: `45202eb`, `3aac97e`. See `.../rest/McpTransportFilter.java`.)

4. **Adapt all 28 tools to `SyncToolSpecification`.**
   Update the `McpTool` interface + `ToolRegistry.toSpecifications()`. (Jira commit:
   `9a0041f` adapted 49 tools.)

5. **Origin/security via the SDK.**
   Move Origin validation into `DefaultServerTransportSecurityValidator` (allowlist:
   Confluence base URL + claude.ai/claude.com/chatgpt + localhost). (Jira commit: `7c6d5ce`.)

6. **Rewrite e2e against the SDK sync client.** (Jira commit: `bd961b4`.) Then
   `just e2e` against the live 10.2.11 instance is the acceptance gate.

### Optional / later (Jira's v1.4.0 spec-compliance sprint)
Capabilities fix (drop false `listChanged`, declare `logging`/`completions`), tool
annotations, icon + outputSchema, resource templates, OAuth metadata (OIDC discovery,
CIMD), `RateLimit-*` headers, `WWW-Authenticate` scope challenges, MCP Apps widget.
(Jira commits `e70677f`, `2495f1d`, `7ffff9d`, `88476dd`, `f05cb7a`, `8e3ba73`, `e0923a1`,
`3131f3c`, `487669f`, `3584b18`, etc.) Decide scope in the design spec.

---

## Reference material (in `../jira-mcp-plugin`)

- **Commit history** — `git -C ../jira-mcp-plugin log --oneline`. The migration sprint is the
  `feature/jakarta-jira-11` range listed above.
- **Docs** (`../jira-mcp-plugin/docs/`, gitignored there but present locally):
  - `06-mcp-java-sdk.md` — MCP Java SDK overview
  - `09-java-sdk-source-analysis.md` — SDK source analysis
  - `11-upgrade-benefits-java-sdk.md`, `11-upgrade-benefits-platform-bom.md`,
    `11-upgrade-benefits-amps.md`, `11-upgrade-benefits-mcp-spec.md`
  - `10-spec-compliance-audit.md` — the F-01..F-24 audit that drove the v1.4.0 sprint
- **Key source files to port:**
  - `src/main/java/com/atlassian/mcp/plugin/rest/McpBootstrap.java`
  - `src/main/java/com/atlassian/mcp/plugin/rest/McpTransportFilter.java`
  - `src/main/java/com/atlassian/mcp/plugin/tools/ToolRegistry.java` (`toSpecifications`)
  - `pom.xml` (OSGi `<Private-Package>` / `<Import-Package>`, MCP SDK deps)

---

## Invariants / rules for this migration

- **Stay on `feature/platform-10.2.11-jakarta-sdk`.** No new branches. Merge to `main` only
  when complete and `just e2e` is green.
- **Mirror upstream `mcp-atlassian`** for tool names/params/descriptions (unchanged by this
  migration — see CLAUDE.md "Upstream Parity").
- **`jakarta.*` only**, never `javax.*` (10.2.x is Jakarta). Update the stale CLAUDE.md note.
- **Build with `atlas-mvn` / `just`** (set `JAVA_HOME="$(mise where java)"` if needed).
- Confluence 10.2.11 ≡ Jira 11.3.6 platform (`platform-public-api:8.3.16`) — when in doubt,
  copy what the Jira plugin does.
