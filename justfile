# Atlassian MCP Plugin (Confluence) — Task Runner
# Env vars loaded by mise from .credentials/confluence.env (see .mise.toml)

set dotenv-load := false

# List all available recipes
default:
    @just --list

# Build the plugin JAR/OBR
[group('build')]
build:
    atlas-package -DskipTests

# Clean build artifacts
[group('build')]
clean:
    atlas-clean

# Run unit tests (excludes e2e)
[group('test')]
test:
    atlas-mvn test -Dtest="!*E2E*"

# Run e2e tests against live Confluence
[group('test')]
e2e:
    atlas-mvn test -Dtest="McpEndpointE2ETest" -DfailIfNoTests=false

# Deploy plugin to Confluence instance
[group('deploy')]
deploy: build
    #!/usr/bin/env bash
    set -euo pipefail
    UPM_TOKEN=$(curl -sI \
      -H "Authorization: Bearer $CONFLUENCE_PAT_RKADMIN" \
      -H "X-Atlassian-Token: no-check" \
      "$CONFLUENCE_URL/rest/plugins/1.0/" | grep -i upm-token | awk '{print $2}' | tr -d '\r')
    curl -s -w '\n%{http_code}' \
      -H "Authorization: Bearer $CONFLUENCE_PAT_RKADMIN" \
      -H "X-Atlassian-Token: no-check" \
      -F "plugin=@target/confluence-mcp-plugin-*.jar" \
      "$CONFLUENCE_URL/rest/plugins/1.0/?token=$UPM_TOKEN"
    echo "Waiting for plugin to enable..."
    sleep 20
    curl -s "$CONFLUENCE_URL/rest/plugins/1.0/com.atlassian.mcp.confluence-mcp-plugin-key" \
      -H "Authorization: Bearer $CONFLUENCE_PAT_RKADMIN" \
      -H "X-Atlassian-Token: no-check" \
      | python3 -c "import sys,json; d=json.load(sys.stdin); print('enabled:', d.get('enabled'), 'version:', d.get('version'))"

# Build + deploy + e2e in one shot
[group('deploy')]
deploy-and-test: deploy e2e

# Run Confluence locally with the plugin installed
[group('dev')]
run:
    atlas-run

# Run Confluence with remote debugging enabled
[group('dev')]
debug:
    atlas-debug

# Regenerate Java tool classes from upstream Python definitions
[group('dev')]
codegen:
    python3 .codegen/translate.py
