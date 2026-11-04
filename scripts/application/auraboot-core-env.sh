#!/usr/bin/env bash
set -Eeuo pipefail

DRIVER_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
# The public bin entry and nested source driver share one module directory.
if [[ -f "$DRIVER_DIR/application/auraboot-core-env.sh" ]]; then DRIVER_DIR="$DRIVER_DIR/application"; fi
ACTION="${1:-}"
shift || true
ARTIFACT_ROOT="${AURA_APP_ARTIFACT_ROOT:-}"
SLOT="${AURA_SLOT:-1}"
for arg in "$@"; do
  case "$arg" in
    --artifact-root=*) ARTIFACT_ROOT="${arg#*=}" ;;
    --slot=*) SLOT="${arg#*=}" ;;
    *) printf 'unknown argument: %s\n' "$arg" >&2; exit 2 ;;
  esac
done
[[ -n "$ARTIFACT_ROOT" ]] || { echo 'AURA_APP_ARTIFACT_ROOT or --artifact-root is required' >&2; exit 2; }
ARTIFACT_ROOT="$(cd "$ARTIFACT_ROOT" && pwd)"
STATE_ROOT="${AURA_STATE_ROOT:-$PWD/.aura-runtime/core-$SLOT}"
DB_NAME="${PGDATABASE:-aura_core_$SLOT}"
DB_USER="${PGUSER:-auraboot}"
DB_PASSWORD="${PGPASSWORD:-auraboot}"
DB_HOST="${PGHOST:-127.0.0.1}"
DB_PORT="${PGPORT:-5432}"
APP_PORT="${AURA_BACKEND_PORT:-${AURA_PORT:-$((6600 + SLOT))}}"
WEB_PORT="${AURA_WEB_PORT:-$((5600 + SLOT))}"
BASE_URL="http://127.0.0.1:$APP_PORT"
WEB_URL="http://127.0.0.1:$WEB_PORT"
ADMIN_EMAIL="${ADMIN_EMAIL:-admin@auraboot.local}"
ADMIN_PASSWORD="${ADMIN_PASSWORD:-}"
SESSION_SECRET="${SESSION_SECRET:-}"
JWT_SECRET="${JWT_SECRET:-}"
SPRING_PROFILES_ACTIVE="${SPRING_PROFILES_ACTIVE:-community}"
PUBLIC_URL="${PUBLIC_URL:-$WEB_URL}"
if [[ -z "${SESSION_COOKIE_SECURE:-}" ]]; then
  [[ "$PUBLIC_URL" == https://* ]] && SESSION_COOKIE_SECURE=true || SESSION_COOKIE_SECURE=false
fi
JDBC_URL="jdbc:postgresql://$DB_HOST:$DB_PORT/$DB_NAME"

need() { command -v "$1" >/dev/null 2>&1 || { echo "missing dependency: $1" >&2; exit 2; }; }
preflight() {
  need lsof; need node; need pnpm; need tar; need java; need curl; need python3; need flyway; need psql
  test -f "$ARTIFACT_ROOT/application.lock"
  test -f "$ARTIFACT_ROOT/release-receipt.json"
  test -f "$ARTIFACT_ROOT/runtime/AuraBoot-1.0.0-boot.jar"
  test -d "$ARTIFACT_ROOT/web/client"
  test -f "$ARTIFACT_ROOT/web/server/index.js"
  node "$DRIVER_DIR/application-artifact-verifier.mjs" \
    --lock "$ARTIFACT_ROOT/application.lock" --artifact-root "$ARTIFACT_ROOT"
}
require_web_settings() {
  [[ ${#SESSION_SECRET} -ge 32 ]] || { echo 'SESSION_SECRET must contain at least 32 characters' >&2; exit 2; }
}
require_backend_settings() {
  [[ ${#JWT_SECRET} -ge 32 ]] || { echo 'JWT_SECRET must contain at least 32 characters' >&2; exit 2; }
}
materialize_locked_npm_packages() {
  local web_root="$1" package_name package_path package_target
  while IFS=$'\t' read -r package_name package_path; do
    [[ "$package_name" == @auraboot/* ]] || { echo "refusing unexpected npm package: $package_name" >&2; exit 2; }
    package_target="$web_root/web-admin/node_modules/$package_name"
    rm -rf -- "$package_target"
    mkdir -p "$package_target"
    tar -xzf "$ARTIFACT_ROOT/$package_path" -C "$package_target" --strip-components=1
  done < <(node -e "const c=require(process.argv[1]); for(const a of c.artifacts.filter(x=>x.type==='npm')) console.log(a.id+'\\t'+a.localPath)" "$ARTIFACT_ROOT/artifact-catalog.json")
}
create_db() {
  need createdb
  if ! psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d postgres -Atqc "select 1 from pg_database where datname='$DB_NAME'" | grep -q 1; then
    createdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" "$DB_NAME"
  fi
}
migrate() {
  preflight
  flyway -url="$JDBC_URL" -user="$DB_USER" -password="$DB_PASSWORD" \
    -locations="filesystem:$ARTIFACT_ROOT/migrations/core" \
    -table=ab_flyway_schema_history -baselineOnMigrate=false -validateMigrationNaming=true -cleanDisabled=true migrate
}
start_backend() {
  preflight
  require_backend_settings
  local application_id application_version lock_identity source_commit image_digest
  application_id="$(node -e "const fs=require('node:fs'); const l=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); process.stdout.write(l.application.id)" "$ARTIFACT_ROOT/application.lock")"
  application_version="$(node -e "const fs=require('node:fs'); const l=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); process.stdout.write(l.application.version)" "$ARTIFACT_ROOT/application.lock")"
  lock_identity="$(node -e "const fs=require('node:fs'); const l=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); process.stdout.write(l.identity)" "$ARTIFACT_ROOT/application.lock")"
  source_commit="$(node -e "const r=require(process.argv[1]); process.stdout.write(r.source.commit)" "$ARTIFACT_ROOT/release-receipt.json")"
  image_digest="$(node -e "const r=require(process.argv[1]); process.stdout.write((r.image&&r.image.digest)||'')" "$ARTIFACT_ROOT/release-receipt.json")"
  mkdir -p "$STATE_ROOT/empty-plugins"
  native_owner_init
  if [[ -f "$(aura_reset_service_record_file backend)" ]]; then
    aura_reset_assert_service_owned backend "$APP_PORT" "$ARTIFACT_ROOT" AuraBoot-1.0.0-boot.jar
    echo 'backend runtime already active'; return
  fi
  reject_unowned_pid_file runtime
  aura_reset_assert_service_absent backend "$APP_PORT" "$ARTIFACT_ROOT" AuraBoot-1.0.0-boot.jar
  local pid
  pid="$(aura_reset_spawn_detached "$ARTIFACT_ROOT" "$STATE_ROOT/runtime.log" env \
  AURA_APPLICATION_MODE=core-only AURABOOT_BOOTSTRAP_ENABLED=false AURABOOT_DEMO_SEED=false JWT_SECRET="$JWT_SECRET" AURA_FILE_STORAGE_PATH="${AURA_FILE_STORAGE_PATH:-$STATE_ROOT/files}" \
  java -Daura.plugins.dir="$STATE_ROOT/empty-plugins" -jar "$ARTIFACT_ROOT/runtime/AuraBoot-1.0.0-boot.jar" \
    --server.port="$APP_PORT" --spring.profiles.active="$SPRING_PROFILES_ACTIVE" --spring.datasource.url="$JDBC_URL" \
    --spring.datasource.username="$DB_USER" --spring.datasource.password="$DB_PASSWORD" \
    --aura.application.id="$application_id" --aura.application.version="$application_version" \
    --aura.application.lock-identity="$lock_identity" --aura.application.source-commit="$source_commit" \
    --aura.application.image-digest="$image_digest" \
  )"
  aura_reset_register_process backend "$pid" "$APP_PORT" "$ARTIFACT_ROOT" AuraBoot-1.0.0-boot.jar
  printf '%s\n' "$pid" > "$STATE_ROOT/runtime.pid"
}
wait_ready() {
  for _ in $(seq 1 90); do
    curl -fsS "$BASE_URL/actuator/health" | grep -q '"status":"UP"' && return
    sleep 2
  done
  echo "runtime did not become ready; see $STATE_ROOT/runtime.log" >&2; exit 1
}
start_web() {
  preflight
  require_web_settings
  wait_ready
  local identity web_root web_shell
  identity="$(node -e "const fs=require('node:fs'); const l=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); process.stdout.write(l.identity.replace(/^sha256:/,''))" "$ARTIFACT_ROOT/application.lock")"
  web_root="$STATE_ROOT/web-$identity"
  if [[ ! -f "$web_root/.ready" ]]; then
    mkdir -p "$web_root"
    web_shell="$(node -e "const c=require(process.argv[1]); const a=c.artifacts.find(x=>x.type==='npm'&&x.id==='@auraboot/web-shell'); if(!a)process.exit(2); process.stdout.write(a.localPath)" "$ARTIFACT_ROOT/artifact-catalog.json")"
    tar -xzf "$ARTIFACT_ROOT/$web_shell" -C "$web_root" --strip-components=1
    mkdir -p "$web_root/web-admin"
    cp -R "$ARTIFACT_ROOT/web" "$web_root/web-admin/build"
    node "$DRIVER_DIR/web-shell-capsule.mjs" "$web_root"
    HUSKY=0 pnpm --dir "$web_root" install --frozen-lockfile --ignore-scripts
    materialize_locked_npm_packages "$web_root"
    touch "$web_root/.ready"
  fi
  native_owner_init
  if [[ -f "$(aura_reset_service_record_file web)" ]]; then
    aura_reset_assert_service_owned web "$WEB_PORT" "$web_root/web-admin" app/server/bff.server.ts
    echo 'Web BFF already active'; return
  fi
  reject_unowned_pid_file web
  aura_reset_assert_service_absent web "$WEB_PORT" "$web_root/web-admin" app/server/bff.server.ts
  local pid
  pid="$(aura_reset_spawn_detached "$web_root/web-admin" "$STATE_ROOT/web.log" \
    env NODE_ENV=production BFF_PORT="$WEB_PORT" SPRING_BOOT_URL="$BASE_URL" \
      SESSION_SECRET="$SESSION_SECRET" PUBLIC_URL="$PUBLIC_URL" SESSION_COOKIE_SECURE="$SESSION_COOKIE_SECURE" \
      ./node_modules/.bin/tsx app/server/bff.server.ts)"
  aura_reset_register_process web "$pid" "$WEB_PORT" "$web_root/web-admin" app/server/bff.server.ts
  printf '%s\n' "$pid" > "$STATE_ROOT/web.pid"
}
wait_web() {
  for _ in $(seq 1 90); do
    curl -fsS "$WEB_URL/health" >/dev/null && return
    sleep 2
  done
  echo "Web BFF did not become ready; see $STATE_ROOT/web.log" >&2; exit 1
}
start_app() { start_backend; start_web; wait_web; }
init_core() {
  [[ -n "$ADMIN_PASSWORD" ]] || { echo 'ADMIN_PASSWORD is required for init-core' >&2; exit 2; }
  wait_ready
  curl -fsS -X POST "$BASE_URL/api/bootstrap/setup" -H 'Content-Type: application/json' \
    -d "{\"companyName\":\"AuraBoot Core\",\"adminEmail\":\"$ADMIN_EMAIL\",\"adminPassword\":\"$ADMIN_PASSWORD\",\"adminDisplayName\":\"AuraBoot Admin\",\"systemMode\":\"single\"}"
}
login_jwt() {
  [[ -n "$ADMIN_PASSWORD" ]] || { echo 'ADMIN_PASSWORD is required for publish' >&2; exit 2; }
  AURA_CORE_BASE_URL="$BASE_URL" ADMIN_EMAIL="$ADMIN_EMAIL" ADMIN_PASSWORD="$ADMIN_PASSWORD" \
    python3 "$DRIVER_DIR/core-admin-session.py"
}
publish() {
  preflight; wait_ready
  local jwt response owners
  jwt="$(login_jwt)"
  [[ -n "$jwt" ]] || { echo 'login failed' >&2; exit 1; }
  owners="$(node "$DRIVER_DIR/native-config-imports.mjs" "$ARTIFACT_ROOT/application.lock")"
  for owner in $owners; do
    response="$(curl -fsS -X POST "$BASE_URL/api/plugins/import/import-directory-sync" \
      -H "Authorization: Bearer $jwt" -H 'Content-Type: application/json' \
      -d "{\"path\":\"$ARTIFACT_ROOT/config/$owner\",\"conflictStrategy\":\"OVERWRITE\",\"autoPublishModels\":true,\"autoPublishFields\":true,\"autoPublishCommands\":true,\"autoPublishPages\":true,\"deferReferenceValidation\":true}")"
    python3 -c "import json,sys; d=json.load(sys.stdin); assert d.get('code') == '0' or d.get('success') is True, d" <<<"$response"
  done
}
verify() {
  preflight; wait_ready; wait_web
  local jwt expected_identity actual_identity product_rows
  POSTGRES_HOST="$DB_HOST" POSTGRES_PORT="$DB_PORT" POSTGRES_DB="$DB_NAME" \
    POSTGRES_USER="$DB_USER" POSTGRES_PASSWORD="$DB_PASSWORD" \
    "$ARTIFACT_ROOT/bin/application/audit-core-only-schema.sh"
  product_rows="$(psql -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" -d "$DB_NAME" -Atqc \
    "select count(*) from ab_menu where (code ~* '(^|_)(bpm|crm)(_|$)' or path ~* '/(bpm|crm)(/|$)') and deleted_flag=false")"
  [[ "$product_rows" == 0 ]]
  jwt="$(login_jwt)"
  expected_identity="$(node -e "const fs=require('node:fs'); const l=JSON.parse(fs.readFileSync(process.argv[1],'utf8')); process.stdout.write(l.identity)" "$ARTIFACT_ROOT/application.lock")"
  actual_identity="$(curl -fsS "$BASE_URL/api/application/identity" -H "Authorization: Bearer $jwt" | python3 -c "import json,sys; print(json.load(sys.stdin)['lockIdentity'])")"
  [[ "$actual_identity" == "$expected_identity" ]]
  curl -fsS "$WEB_URL/api/bootstrap/status" | grep -q '"initialized":true'
  curl -fsS "$WEB_URL/" >/dev/null
  echo "PASS auraboot-core artifact=$ARTIFACT_ROOT backend=$APP_PORT web=$WEB_PORT database=$DB_NAME"
}
# Reuse the same ownership contract as managed development runtimes. Native
# packages have two listeners (the production BFF also serves the client).
source "$DRIVER_DIR/../lib/runtime-process-owner.sh"
native_owner_init() {
  [[ "${NATIVE_OWNER_READY:-0}" == 1 ]] && return
  mkdir -p "$STATE_ROOT"
  STATE_ROOT="$(cd "$STATE_ROOT" && pwd -P)"
  AURA_RESET_OWNER_STATE_DIR="$STATE_ROOT/owners"
  aura_reset_owner_init auraboot-core "$ARTIFACT_ROOT" "core-$SLOT" \
    "$DB_HOST" "$DB_PORT" "$DB_NAME" "$APP_PORT" "$WEB_PORT"
  aura_reset_acquire_locks
  NATIVE_OWNER_READY=1
}
reject_unowned_pid_file() {
  [[ ! -f "$STATE_ROOT/$1.pid" ]] || {
    echo 'Unregistered legacy PID file: refusing to attach or stop an unverified process. Retain the state and inspect ownership.' >&2
    return 2
  }
}
stop_app() {
  native_owner_init
  local identity web_root
  identity="$(node -e "const l=JSON.parse(require('node:fs').readFileSync(process.argv[1],'utf8'));process.stdout.write(l.identity.replace(/^sha256:/,''))" "$ARTIFACT_ROOT/application.lock")"
  web_root="$STATE_ROOT/web-$identity/web-admin"
  if [[ -f "$(aura_reset_service_record_file web)" ]]; then
    aura_reset_stop_service web "$WEB_PORT" "$web_root" app/server/bff.server.ts
  else
    reject_unowned_pid_file web
    aura_reset_assert_port_available web "$WEB_PORT"
  fi
  if [[ -f "$(aura_reset_service_record_file backend)" ]]; then
    aura_reset_stop_service backend "$APP_PORT" "$ARTIFACT_ROOT" AuraBoot-1.0.0-boot.jar
  else
    reject_unowned_pid_file runtime
    aura_reset_assert_port_available backend "$APP_PORT"
  fi
  rm -f "$STATE_ROOT/web.pid" "$STATE_ROOT/runtime.pid"
}
destroy() { stop_app; dropdb -h "$DB_HOST" -p "$DB_PORT" -U "$DB_USER" --if-exists "$DB_NAME"; }

case "$ACTION" in
  preflight) preflight ;; create-db) create_db ;; migrate) migrate ;; start) start_app ;;
  init-core) init_core ;; publish) publish ;; verify) verify ;; stop) stop_app ;; destroy) destroy ;;
  *) echo "usage: $0 preflight|create-db|migrate|start|init-core|publish|verify|stop|destroy --artifact-root=<path> [--slot=N]" >&2; exit 2 ;;
esac
