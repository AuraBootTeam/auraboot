#!/bin/sh
# Exact, isolated verification dependencies; never install into the host Python.
set -eu
[ "$#" = 2 ] || { echo "usage: prepare_openapi_verifier.sh SOURCE OUTPUT" >&2; exit 2; }
[ ! -e "$2" ] || { echo "verifier output already exists" >&2; exit 2; }
PYTHON="${AURA_OPENAPI_PYTHON:-python3}"
WHEELHOUSE="${AURA_OPENAPI_WHEELHOUSE:-}"
[ -z "$WHEELHOUSE" ] || [ -d "$WHEELHOUSE" ] || { echo "locked OpenAPI wheelhouse missing" >&2; exit 2; }
"$PYTHON" -c 'import sys; sys.exit(0 if sys.version_info >= (3, 10) else "OpenAPI verifier requires Python >=3.10")'
"$PYTHON" -m venv "$2"
if [ -n "$WHEELHOUSE" ]; then
  "$2/bin/python" -m pip install --disable-pip-version-check --no-index --find-links "$WHEELHOUSE" \
    --only-binary=:all: --require-hashes -r "$1/scripts/fixtures/open-platform-openapi/requirements.txt"
else
  "$2/bin/python" -m pip install --disable-pip-version-check --index-url https://pypi.org/simple --only-binary=:all: --require-hashes \
    --cache-dir "$2/pip-cache" -r "$1/scripts/fixtures/open-platform-openapi/requirements.txt"
fi
