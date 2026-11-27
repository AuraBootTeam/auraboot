#!/usr/bin/env bash
set -Eeuo pipefail
# Exit 2 is reserved by Docker HEALTHCHECK; every unready state is exit 1.
if ! pg_isready --host=127.0.0.1 --port=5432 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" >/dev/null; then
  printf '%s\n' 'Database TCP listener is not ready.' >&2
  exit 1
fi
# TCP excludes the entrypoint's temporary server; committed tables exclude a failed bootstrap.
export PGPASSWORD="$POSTGRES_PASSWORD"
if ! result=$(psql -X --host=127.0.0.1 --port=5432 \
  --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --tuples-only --no-align --command="SELECT to_regclass('public.ab_meta_model') IS NOT NULL AND to_regclass('public.ab_command_definition') IS NOT NULL"); then
  printf '%s\n' 'Database readiness query failed.' >&2
  exit 1
fi
if [[ "$result" != t ]]; then
  printf '%s\n' 'Committed application schema is not ready.' >&2
  exit 1
fi
