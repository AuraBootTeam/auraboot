import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import { fileURLToPath } from 'node:url';

const gatePath = fileURLToPath(new URL('./oss-e2e-gate-run.sh', import.meta.url));
const stackPath = fileURLToPath(new URL('./oss-golden-stack.sh', import.meta.url));

test('fresh OSS gate keeps the registered slot for the same stable runtime name', () => {
  const source = readFileSync(gatePath, 'utf8');
  assert.match(source, /registered_slot_for_name\(\)/u);
  assert.match(source, /SLOT="\$registered_slot"/u);
  assert.match(source, /reusing prior slot .* before the fresh rebuild/u);
  assert.ok(
    source.indexOf('registered_slot="$(registered_slot_for_name)"')
      < source.indexOf('"$GS" destroy "$NAME"'),
    'the old allocation must identify the stable slot before the fresh destroy',
  );
});

test('OSS gate resolves the workspace in local and sibling-repository CI layouts', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /AURA_WORKSPACE_ROOT/u);
  assert.match(source, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(source, /auraboot-workspace\/dev\.sh/u);
  assert.match(stack, /AURA_CI_WORKSPACE_ROOT/u);
  assert.match(stack, /auraboot-workspace\/dev\.sh/u);
  assert.match(source, /ENVIRONMENT-INVALID:[^]*exit 2/u);
});

test('golden stack uses idempotent runtime identity with source worktree metadata', () => {
  const source = readFileSync(stackPath, 'utf8');
  assert.match(source, /runtime ensure auraboot "\$name"/u);
  assert.match(source, /--source-root "\$REPO_ROOT"/u);
  assert.match(source, /runtime allocate auraboot "\$name"/u);
  assert.match(source, /legacy dispatcher/u);
  assert.match(source, /--mode "\$runtime_mode"/u);
});

test('fresh gate marks its runtime as verification evidence rather than feature development', () => {
  const source = readFileSync(gatePath, 'utf8');
  const stack = readFileSync(stackPath, 'utf8');
  assert.match(source, /--runtime-mode verification/u);
  assert.match(source, /SEED_LOG_DIR="\$AURA_EVIDENCE_ROOT/u);
  assert.match(stack, /export PW_ARTIFACT_DIR=\$evidence_root/u);
});
