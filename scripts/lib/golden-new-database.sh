#!/usr/bin/env bash
# Create an absent database before infra ensure can reuse slot state. CREATE is
# exclusive: a concurrent creator causes failure, never replacement or deletion.
golden_create_new_database() {
  local host="$1" port="$2" user="$3" password="$4" database="$5" exists
  [[ "$database" =~ ^[a-zA-Z_][a-zA-Z0-9_]*$ ]] || return 1
  exists="$(PGPASSWORD="$password" psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$user" -d postgres -At -v database_name="$database" <<'SQL'
SELECT count(*) FROM pg_database WHERE datname = :'database_name';
SQL
)" || return 1
  [[ "$exists" == 0 ]] || return 1
  PGPASSWORD="$password" psql -X -v ON_ERROR_STOP=1 -h "$host" -p "$port" -U "$user" -d postgres -v database_name="$database" <<'SQL'
SELECT format('CREATE DATABASE %I', :'database_name') \gexec
SQL
}
