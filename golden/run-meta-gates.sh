#!/usr/bin/env bash
# Methodology gates for this repo's golden catalog — four axes present on every
# blocking suite, and the generated matrix still matching the catalog it projects.
#
# Both gates are GENERIC and live in the workspace (tools/). This script only locates
# them and points them at this catalog. There is deliberately no local copy of the
# gate logic: the second copy is how two gates drift apart.
#
#   golden/run-meta-gates.sh
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

find_ws() { local d="$HERE"; while [ "$d" != "/" ]; do [ -f "$d/tools/check-test-catalog-schema.mjs" ] && { echo "$d"; return 0; }; d="$(dirname "$d")"; done; return 1; }
WS="$(find_ws)" || { echo "FAIL: cannot find tools/check-test-catalog-schema.mjs (workspace meta-gates)"; exit 2; }
CAT="$HERE/test-catalog.json"

status=0
node "$WS/tools/check-test-catalog-schema.mjs" --catalog "$CAT" || status=1
node "$WS/tools/check-test-matrix-fresh.mjs"   --catalog "$CAT" || status=1
exit "$status"
