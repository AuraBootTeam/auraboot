#!/usr/bin/env bash
set -Eeuo pipefail
pg_isready --host=127.0.0.1 --port=5432 --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" >/dev/null
# TCP excludes the entrypoint's temporary server; committed tables exclude a failed bootstrap.
export PGPASSWORD="$POSTGRES_PASSWORD"
result=$(psql -X --host=127.0.0.1 --port=5432 \
  --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" --set=ON_ERROR_STOP=1 \
  --tuples-only --no-align --command="SELECT to_regclass('public.ab_model_definition') IS NOT NULL AND to_regclass('public.ab_command_definition') IS NOT NULL")
[[ "$result" == t ]]
