#!/usr/bin/env bash
# Native library preparation only; no application build or remote publication.
set -Eeuo pipefail
[[ $# == 0 ]] || { echo 'No positional arguments accepted' >&2; exit 2; }
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
: "${AURA_REGRESSION_ARTIFACTS:?managed evidence required}"
: "${AURA_CI_JOB_ID:?managed job required}"
: "${AURA_CI_WORKSPACE_ROOT:?frozen workspace required}"
: "${AURA_CI_FROZEN_CONTEXT:?frozen context required}"
: "${AURA_CI_SOURCE_SET_DIGEST:?frozen source identity required}"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || exit 2
[[ "$(cd "$AURA_CI_WORKSPACE_ROOT/auraboot" && pwd -P)" == "$ROOT" ]] || exit 2
WORK="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd -P)/platform-libraries"
mkdir "$WORK"
freeze() {
  node "$AURA_CI_WORKSPACE_ROOT/auraboot-workspace/tools/local-ci/server.mjs" verify-freeze \
    --context "$AURA_CI_FROZEN_CONTEXT" --expected-source-set "$AURA_CI_SOURCE_SET_DIGEST" \
    --workspace-root "$AURA_CI_WORKSPACE_ROOT" --job "$AURA_CI_JOB_ID" > "$WORK/frozen-$1.json"
}
freeze before
python3 -B - "$WORK" "${SMART_ENGINE_M2_SEED_ROOT:-/opt/aura-ci/cache/maven-seed}" <<'PY'
import hashlib,json,pathlib,shutil,sys
work,seed=map(pathlib.Path,sys.argv[1:])
proof=json.loads((work/'frozen-before.json').read_text())
assert set(proof['repositories'])=={'auraboot'}, 'library source denominator differs'
assert set(proof['builderRepositories'])=={'auraboot-workspace'}, 'library verifier denominator differs'
source=seed/'com/auraboot/smart/framework'
assert source.is_dir() and not any(item.is_symlink() for item in [source,*source.parents,*source.rglob('*')]), 'regular managed dependency seed required'
files=sorted(item for item in source.rglob('*') if item.is_file())
assert files, 'managed SmartEngine dependency seed is empty'
hash_file=lambda item:hashlib.sha256(item.read_bytes()).hexdigest()
before={str(item.relative_to(source)):hash_file(item) for item in files}
target=work/'maven-input/com/auraboot/smart/framework'
shutil.copytree(source,target)
assert all(hash_file(target/name)==value for name,value in before.items()), 'dependency seed copy differs'
(work/'dependency-seed.json').write_text(json.dumps({'files':before},sort_keys=True,indent=2)+'\n')
PY
bash "$AURA_CI_WORKSPACE_ROOT/auraboot-workspace/tools/regression/run-gradle-suite.sh" \
  "$ROOT/platform/gradlew" --project-dir "$ROOT/platform" --no-daemon --console=plain \
  "-Dmaven.repo.local=$WORK/maven-input" -Paura.library.release=true \
  "-Paura.library.output=$WORK/maven" prepareLibraryCandidate
freeze after
cmp "$WORK/frozen-before.json" "$WORK/frozen-after.json"
test -f "$WORK/maven/library-publication-inventory.json"
