#!/usr/bin/env bash
# Workspace invokes this adapter inside its suspend/resume transaction.
set -euo pipefail
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
case "${1:-}" in
  suspend) exec "$script_dir/oss-golden-stack.sh" down "$2" ;;
  resume) exec "$script_dir/oss-golden-stack.sh" lifecycle-resume "$2" ;;
  *) echo 'Expected a Workspace suspend/resume transaction' >&2; exit 1 ;;
esac
