import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import test from 'node:test';

import {
  assertManifestMatches,
  buildReleaseManifest,
  runMutationProof,
} from '../scripts/verify_release_coverage.mjs';

test('CRM release manifest derives the complete RG-1 through RG-4 denominator', async () => {
  const generated = buildReleaseManifest();
  const committed = JSON.parse(await readFile(new URL('../coverage-manifest.json', import.meta.url), 'utf8'));
  assertManifestMatches(committed, generated);

  assert.equal(committed.axes.semanticActions.length, 26);
  assert.equal(committed.axes.commands.length, 22);
  assert.equal(committed.axes.pages.length, 8);
  assert.equal(committed.axes.permissions.length, 17);
  assert.equal(committed.axes.queries.length, 11);
  assert.deepEqual(committed.untested.map((row) => row.id), ['RG3-INDEPENDENT-HUMAN-ADOPTER']);
});

test('CRM release coverage gate rejects a controlled missing-action mutation', async () => {
  const committed = JSON.parse(await readFile(new URL('../coverage-manifest.json', import.meta.url), 'utf8'));
  const proof = runMutationProof(committed);
  assert.equal(proof.verdict, 'pass');
  assert.deepEqual(proof.phases.map((phase) => phase.phase), [
    'green-before',
    'red-controlled-mutation',
    'green-restored',
  ]);
  assert.ok(proof.phases.every((phase) => phase.result === 'pass'));
});
