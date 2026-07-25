# AuraBoot PostgreSQL — pgvector + baked schema
#
# Bakes the Flyway-generated canonical DDL snapshot
# (platform/src/main/resources/db/snapshots/schema-current.sql) into
# /docker-entrypoint-initdb.d so a pure `docker pull` needs no repo checkout to
# seed the schema on first boot (see docker-compose.pull.yml). The snapshot
# creates its own extensions (pg_trgm, pgcrypto, vector), so nothing else is
# required.
# The docker path loads this schema directly; Flyway is only used by the reset
# scripts, never at container boot.
#
# Build context: REPO ROOT.
#   docker build -f docker/postgres/Dockerfile -t auraboot-postgres .
FROM pgvector/pgvector:pg16

COPY platform/src/main/resources/db/snapshots/schema-current.sql /docker-entrypoint-initdb.d/01-schema.sql
