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

test('golden stack uses idempotent runtime identity with source worktree metadata', () => {
  const source = readFileSync(stackPath, 'utf8');
  assert.match(source, /runtime ensure auraboot "\$name"/u);
  assert.match(source, /--source-root "\$REPO_ROOT"/u);
  assert.match(source, /runtime allocate auraboot "\$name"/u);
  assert.match(source, /legacy dispatcher/u);
});
