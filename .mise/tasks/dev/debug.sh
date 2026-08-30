#!/usr/bin/env bash
#MISE description="Run Confluence locally with remote debugging enabled"
#MISE dir="{{config_root}}"
set -euo pipefail

exec atlas-debug
