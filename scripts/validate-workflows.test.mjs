import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { readFileSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const repoRoot = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const validatorPath = path.join(repoRoot, 'scripts', 'validate-workflows.sh');
const workflowPath = path.join(repoRoot, '.github', 'workflows', 'build-image.yml');

test('workflow validator accepts the canonical build-image workflow', () => {
  const result = spawnSync('bash', [validatorPath], {
    cwd: repoRoot,
    encoding: 'utf8',
  });

  assert.equal(result.status, 0, `${result.stdout}\n${result.stderr}`);
  assert.match(result.stdout, /build-image\.yml uses build-push-action/);
  assert.match(result.stdout, /build-image\.yml uses the repository-root build context/);
});

test('canonical image workflow builds backend and frontend from repository root', () => {
  const workflow = readFileSync(workflowPath, 'utf8');

  assert.match(workflow, /image: \[backend, frontend, postgres\]/);
  assert.match(workflow, /backend\)\s+name=auraboot;\s+file=platform\/Dockerfile/);
  assert.match(workflow, /frontend\)\s+name=auraboot-frontend;\s+file=web-admin\/Dockerfile/);
  assert.match(workflow, /context: \./);
  assert.doesNotMatch(workflow, /context: (platform|web-admin)/);
});
