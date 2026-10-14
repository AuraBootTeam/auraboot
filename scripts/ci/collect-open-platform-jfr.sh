#!/usr/bin/env bash
set -Eeuo pipefail

# The JRE image stays unchanged. Attach using the already admitted JDK image,
# with the application's UID and PID namespace and without network access.
die() { printf 'open-platform-jfr: %s\n' "$*" >&2; exit 2; }
[[ $# == 6 ]] || die 'expected action, app, job, source ref, pinned JDK image, artifacts'
ACTION=$1 APP=$2 JOB=$3 REF=$4 JDK=$5 ARTIFACTS=$6
[[ "$ACTION" == start || "$ACTION" == collect ]] || die 'unknown action'
[[ "$JOB" =~ ^[A-Za-z0-9_-]+$ && "$APP" == "open-platform-$JOB-app" ]] || die 'application identity mismatch'
[[ "$REF" =~ ^[0-9a-f]{40}$ && "$JDK" =~ @sha256:[0-9a-f]{64}$ ]] || die 'immutable source and JDK are required'
[[ -d "$ARTIFACTS" && -d "$ARTIFACTS/logs" ]] || die 'artifact directory missing'
IDENTITY=$(docker inspect --format '{{.State.Running}} {{index .Config.Labels "aura.job-id"}} {{index .Config.Labels "aura.source-ref"}}' "$APP")
[[ "$IDENTITY" == "true $JOB $REF" ]] || die 'application is stopped or belongs to a different job/source'
JVM_PID=$(docker exec "$APP" sh -c 'for p in /proc/[0-9]*/comm; do if [ "$(cat "$p" 2>/dev/null)" = java ]; then p=${p%/comm}; printf "%s\n" "${p##*/}"; fi; done')
[[ "$JVM_PID" =~ ^[0-9]+$ ]] || die 'expected exactly one Java process'
APP_UID=$(docker exec "$APP" id -u)
APP_GID=$(docker exec "$APP" id -g)
[[ "$APP_UID" =~ ^[0-9]+$ && "$APP_GID" =~ ^[0-9]+$ ]] || die 'invalid application UID/GID'
attach() {
  timeout 30 docker run --rm --network none --pid="container:$APP" \
    --user "$APP_UID:$APP_GID" "$JDK" jcmd "$JVM_PID" "$@"
}
if [[ "$ACTION" == start ]]; then
  [[ ! -e "$ARTIFACTS/open-platform-slo.jfr" ]] || die 'recording already exists'
  CONFIG=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)/open-platform-slo.jfc
  docker cp "$CONFIG" "$APP:/tmp/open-platform-slo.jfc"
  attach JFR.start name=open-platform-slo settings=/tmp/open-platform-slo.jfc \
    disk=true maxsize=32m maxage=3m > "$ARTIFACTS/logs/jfr-start.log" 2>&1
else
  [[ ! -e "$ARTIFACTS/open-platform-slo.jfr" ]] || die 'recording already exists'
  attach JFR.stop name=open-platform-slo filename=/app/data/artifacts/open-platform-slo.jfr \
    > "$ARTIFACTS/logs/jfr-stop.log" 2>&1
  docker cp "$APP:/app/data/artifacts/open-platform-slo.jfr" "$ARTIFACTS/open-platform-slo.jfr"
  [[ -s "$ARTIFACTS/open-platform-slo.jfr" ]] || die 'empty recording'
  timeout 30 docker run --rm --network none --user "$(id -u):$(id -g)" \
    -v "$ARTIFACTS:/diagnostics:ro" "$JDK" jfr summary /diagnostics/open-platform-slo.jfr \
    > "$ARTIFACTS/jfr-summary.txt" 2> "$ARTIFACTS/logs/jfr-summary.log"
  python3 - "$ARTIFACTS/jfr-summary.txt" <<'PY'
import re, sys
text = open(sys.argv[1]).read()
for name in ('jdk.InitialEnvironmentVariable', 'jdk.InitialSystemProperty'):
    match = re.search(r'^\s*' + re.escape(name) + r'\s+(\d+)\s+', text, re.M)
    assert match and int(match[1]) == 0, 'unexpected sensitive JFR event: ' + name
PY
fi
