#!/usr/bin/env bash
#MISE description="End-to-end tests against the live Confluence instance"
#MISE dir="{{config_root}}"
set -euo pipefail

: "${CONFLUENCE_URL:?CONFLUENCE_URL is not set — see .credentials/confluence.env}"
exec atlas-mvn test -Dtest=McpEndpointE2ETest -DfailIfNoTests=false
