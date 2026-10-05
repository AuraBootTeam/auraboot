#!/usr/bin/env bash
# Run the unchanged application engine with a separately hashed test-only fixture jar.
# The caller owns runtime registration, credentials, process lifetime and port identity.
set -Eeuo pipefail
fail() { echo "e2e fixture backend: $*" >&2; exit 2; }
[[ "${AURA_E2E_FIXTURE_ENABLED:-}" == true ]] || fail 'explicit AURA_E2E_FIXTURE_ENABLED=true is required'
for key in AURA_E2E_BOOT_JAR AURA_E2E_BOOT_SHA256 AURA_E2E_FIXTURE_JAR AURA_E2E_FIXTURE_SHA256 AURA_E2E_PLUGINS_DIR; do
  [[ -n "${!key:-}" ]] || fail "$key is required"
done
[[ "$AURA_E2E_BOOT_JAR" == /* && -f "$AURA_E2E_BOOT_JAR" ]] || fail 'boot jar must be an existing absolute path'
[[ "$AURA_E2E_FIXTURE_JAR" == /* && -f "$AURA_E2E_FIXTURE_JAR" ]] || fail 'fixture jar must be an existing absolute path'
[[ "$AURA_E2E_PLUGINS_DIR" == /* && -d "$AURA_E2E_PLUGINS_DIR" ]] || fail 'plugins directory must be an existing absolute path'
[[ "$AURA_E2E_FIXTURE_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail 'fixture SHA256 is invalid'
actual=$(shasum -a 256 "$AURA_E2E_FIXTURE_JAR")
[[ "${actual%% *}" == "$AURA_E2E_FIXTURE_SHA256" ]] || fail 'fixture SHA256 mismatch'
[[ "$AURA_E2E_BOOT_SHA256" =~ ^[0-9a-f]{64}$ ]] || fail 'boot SHA256 is invalid'
actual=$(shasum -a 256 "$AURA_E2E_BOOT_JAR")
[[ "${actual%% *}" == "$AURA_E2E_BOOT_SHA256" ]] || fail 'boot SHA256 mismatch'
entries=$(unzip -Z1 "$AURA_E2E_FIXTURE_JAR") || fail 'fixture jar is unreadable'
for required in com/auraboot/framework/test/controller/TestFixtureController.class com/auraboot/framework/test/dto/FixtureRequest.class com/auraboot/framework/test/dto/FixtureResult.class; do
  grep -Fxq "$required" <<< "$entries" || fail "fixture jar lacks $required"
done
while IFS= read -r entry; do
  case "$entry" in
    */|META-INF/MANIFEST.MF|com/auraboot/framework/test/controller/*.class|com/auraboot/framework/test/dto/*.class) ;;
    *) fail 'fixture jar contains a non-fixture entry';;
  esac
done <<< "$entries"
for arg in "$@"; do
  case "$arg" in
    --spring.profiles.*|--server.address*) fail 'profiles and loopback binding are owned by this test launcher';;
  esac
done
# PropertiesLauncher adds the isolated support jar without repacking the production jar.
exec java "-Daura.plugins.dir=$AURA_E2E_PLUGINS_DIR" "-Dloader.path=$AURA_E2E_FIXTURE_JAR" \
  -cp "$AURA_E2E_BOOT_JAR" org.springframework.boot.loader.launch.PropertiesLauncher \
  "$@" --spring.profiles.active=community,test --server.address=127.0.0.1
