import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const gatePath = fileURLToPath(new URL('./oss-e2e-gate-run.sh', import.meta.url));
const stackPath = fileURLToPath(new URL('./oss-golden-stack.sh', import.meta.url));

test('new native gate refuses an occupied runtime before stack or database writes', () => {
  const source = readFileSync(gatePath, 'utf8');
  assert.match(source, /registered_slot_for_name\(\)/u);
  assert.match(source, /\[\[ -z "\$registered_slot" \]\] \|\| die/u);
  assert.doesNotMatch(source, /"\$GS" (down|destroy)/u);
  assert.match(source, /--require-new-db/u);
  assert.ok(source.indexOf('registered_slot="$(registered_slot_for_name)"') < source.indexOf('"$GS" up "$NAME"'));
});

test('OSS gate resolves the workspace in local and sibling-repository CI layouts', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /AURA_WORKSPACE_ROOT/u);
  assert.match(source, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(source, /auraboot-workspace\/aura/u);
  assert.match(stack, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(stack, /auraboot-workspace\/dev\.sh/u);
  assert.match(source, /ENVIRONMENT-INVALID:[^]*exit 2/u);
});

test('golden stack uses idempotent runtime identity with source worktree metadata', () => {
  const source = readFileSync(stackPath, 'utf8');
  assert.match(source, /runtime ensure auraboot "\$name"/u);
  assert.match(source, /--source-root "\$REPO_ROOT"/u);
  assert.match(source, /runtime evidence begin/u);
  assert.doesNotMatch(source, /runtime allocate auraboot/u);
  assert.match(source, /--mode "\$runtime_mode"/u);
});

test('fresh gate marks its runtime as verification evidence rather than feature development', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /--runtime-mode verification/u);
  assert.match(source, /LOG="\$AURA_EVIDENCE_ROOT\/logs\/oss-e2e-gate/u);
  assert.match(stack, /export PW_ARTIFACT_DIR=\$evidence_root/u);
});
