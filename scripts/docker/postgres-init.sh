#!/usr/bin/env bash
# The official entrypoint may source this script; keep shell options local.
(
  set -Eeuo pipefail
  exec psql --host=/var/run/postgresql --port=5432 \
    --username="$POSTGRES_USER" --dbname="$POSTGRES_DB" \
    --set=ON_ERROR_STOP=1 --single-transaction \
    --file=/auraboot-bootstrap/schema-current.sql
)
