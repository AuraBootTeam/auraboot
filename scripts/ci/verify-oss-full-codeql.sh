#!/usr/bin/env bash
# Nonincremental analysis of the exact OSS candidate, using the existing query policy.
set -Eeuo pipefail
: "${AURA_CI_JOB_ID:?admitted CI job required}"
: "${AURA_REGRESSION_ARTIFACTS:?owned evidence required}"
: "${AURA_CI_WORKSPACE_ROOT:?frozen workspace required}"
: "${AURA_CI_FROZEN_CONTEXT:?frozen source required}"
: "${AURA_CI_SOURCE_SET_DIGEST:?source digest required}"
[[ "$(uname -s)" == Linux && "$(uname -m)" == x86_64 ]] || exit 2
ROOT="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd -P)"
cd "$ROOT"
OUT="$(cd "$AURA_REGRESSION_ARTIFACTS" && pwd -P)"
mkdir -m 700 "$OUT/codeql-private"
PRIVATE="$OUT/codeql-private"
FREEZE="$AURA_CI_WORKSPACE_ROOT/auraboot-workspace/tools/local-ci/server.mjs"
freeze() {
  node "$FREEZE" verify-freeze --context "$AURA_CI_FROZEN_CONTEXT" \
    --expected-source-set "$AURA_CI_SOURCE_SET_DIGEST" \
    --workspace-root "$AURA_CI_WORKSPACE_ROOT" --job "$AURA_CI_JOB_ID" > "$OUT/frozen-$1.json"
}
freeze before
if [[ -n "${AURA_SECURITY_CODEQL_BUNDLE:-}" ]]; then
  python3 -B - "$AURA_SECURITY_CODEQL_BUNDLE" "$AURA_CI_DATASET_INPUTS" "$PRIVATE/bundle.tar.zst" <<'PY'
import hashlib,json,pathlib,shutil,sys
source,receipt,target=map(pathlib.Path,sys.argv[1:])
rows=[row for row in json.loads(receipt.read_text())['files'] if row['file']==str(source)]
if source.is_symlink() or not source.is_file() or len(rows)!=1:
    raise RuntimeError('one admitted regular CodeQL bundle required')
with source.open('rb') as stream:
    actual=hashlib.file_digest(stream,'sha256').hexdigest()
if actual != rows[0]['sha256'] or actual != 'da3e952c7fe978a0b6b93e75d9cbabbdf8535667ca0810bd4b2ae37bc840ffee':
    raise RuntimeError('CodeQL bundle integrity mismatch')
shutil.copyfile(source,target)
PY
else
curl --fail --location --connect-timeout 20 --max-time 1200 \
  'https://github.com/github/codeql-action/releases/download/codeql-bundle-v2.27.4/codeql-bundle-linux64.tar.zst' \
  --output "$PRIVATE/bundle.tar.zst"
fi
printf '%s  %s\n' 'da3e952c7fe978a0b6b93e75d9cbabbdf8535667ca0810bd4b2ae37bc840ffee' "$PRIVATE/bundle.tar.zst" | sha256sum --check
tar --zstd -xf "$PRIVATE/bundle.tar.zst" -C "$PRIVATE"
QL="$PRIVATE/codeql/codeql"
"$QL" version --format=json > "$OUT/codeql-version.json"
sha256sum "$PRIVATE/bundle.tar.zst" .github/codeql/codeql-config.yml \
  .github/codeql/aura-java-models/qlpack.yml .github/codeql/aura-java-models/models/*.yml > "$OUT/tool-and-policy.sha256"
"$QL" resolve qlpacks --format=json > "$OUT/query-packs.json"
"$QL" database create "$PRIVATE/javascript-db" --language=javascript --build-mode=none \
  --source-root="$ROOT" --codescanning-config="$ROOT/.github/codeql/codeql-config.yml" --threads=4 --ram=8192
"$QL" database analyze "$PRIVATE/javascript-db" --format=sarif-latest --output="$OUT/javascript.sarif.json" \
  --threads=4 --ram=8192
"$QL" database create "$PRIVATE/java-db" --language=java --source-root="$ROOT" \
  --codescanning-config="$ROOT/.github/codeql/codeql-config.yml" --threads=4 --ram=8192 \
  --command='bash -c "cd platform && ./gradlew clean classes -x test --no-daemon --no-build-cache"'
"$QL" database analyze "$PRIVATE/java-db" --format=sarif-latest --output="$OUT/java.sarif.json" \
  --additional-packs="$ROOT/.github/codeql/aura-java-models" \
  --model-packs=aurabootteam/aura-java-models@0.1.0 --threads=4 --ram=8192
freeze after
cmp "$OUT/frozen-before.json" "$OUT/frozen-after.json"
git rev-parse HEAD > "$OUT/source-commit.txt"
# Findings are not auto-dismissed here. The existing triage ledger must account for every result.
node --input-type=module - "$OUT" <<'JS'
import {readFileSync,writeFileSync} from 'node:fs';
import {join} from 'node:path';
const root=process.argv[2];
const files=['java.sarif.json','javascript.sarif.json'];
const summary=files.map(file=>{
 const runs=JSON.parse(readFileSync(join(root,file),'utf8')).runs;
 if(!runs?.length || runs.some(run=>run.invocations?.some(item=>item.executionSuccessful===false)))
   throw Error('Missing or failed full CodeQL analysis');
 return {file,findings:runs.reduce((n,run)=>n+(run.results?.length??0),0)};
});
writeFileSync(join(root,'result.json'),JSON.stringify({status:'executed',mode:'full-not-incremental',
  findingsRequireLedgerReview:true,source:readFileSync(join(root,'source-commit.txt'),'utf8').trim(),summary},null,2));
JS
