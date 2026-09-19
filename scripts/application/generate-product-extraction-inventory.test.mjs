import assert from 'node:assert/strict';
import { mkdirSync, mkdtempSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { describe, it } from 'node:test';

import { buildInventory } from './generate-product-extraction-inventory.mjs';

function fixture(files) {
  const root = mkdtempSync(join(tmpdir(), 'aura-product-inventory-'));
  for (const [path, content] of Object.entries(files)) {
    const target = join(root, path);
    mkdirSync(dirname(target), { recursive: true });
    writeFileSync(target, content);
  }
  return { root, files: Object.keys(files) };
}

describe('product extraction inventory', () => {
  it('assigns dedicated product roots to exactly one target repository', () => {
    const input = fixture({
      'platform/src/main/java/com/auraboot/framework/bpm/service/TaskService.java': 'package bpm;',
      'plugins/crm/config/models.json': '[]',
    });

    const inventory = buildInventory(input);

    assert.deepEqual(
      inventory.rows.map(({ product, path, targetOwner, disposition }) => ({
        product,
        path,
        targetOwner,
        disposition,
      })),
      [
        {
          product: 'bpm',
          path: 'platform/src/main/java/com/auraboot/framework/bpm/service/TaskService.java',
          targetOwner: 'aura-bpm',
          disposition: 'move',
        },
        {
          product: 'crm',
          path: 'plugins/crm/config/models.json',
          targetOwner: 'aura-crm',
          disposition: 'move',
        },
      ],
    );
  });

  it('keeps a platform shell file in auraboot while requiring its BPM import to be removed', () => {
    const input = fixture({
      'web-admin/app/framework/boot-plugins.ts': "import coreBpm from '~/plugins/core-bpm';\n",
    });

    const [row] = buildInventory(input).rows;

    assert.equal(row.product, 'bpm');
    assert.equal(row.targetOwner, 'auraboot');
    assert.equal(row.disposition, 'remove-direct-reference-or-generalize');
    assert.deepEqual(row.dependencyRefs, [
      { line: 1, text: "import coreBpm from '~/plugins/core-bpm';" },
    ]);
  });

  it('assigns product migrations to the product even when they currently live in core', () => {
    const input = fixture({
      'platform/src/main/resources/db/migration/core/V1__baseline.sql':
        'CREATE TABLE ab_bpm_task (id bigint);\nCREATE TABLE ab_crm_lead (id bigint);\n',
    });

    const inventory = buildInventory(input);

    assert.deepEqual(
      inventory.rows.map(({ product, targetOwner, disposition }) => ({ product, targetOwner, disposition })),
      [
        { product: 'bpm', targetOwner: 'aura-bpm', disposition: 'split-migration-owner' },
        { product: 'crm', targetOwner: 'aura-crm', disposition: 'split-migration-owner' },
      ],
    );
  });

  it('does not include unrelated source files in the denominator', () => {
    const input = fixture({
      'platform/src/main/java/com/auraboot/framework/auth/AuthService.java': 'package auth;',
      'web-admin/app/routes/login.tsx': 'export default function Login() {}',
    });

    const inventory = buildInventory(input);

    assert.deepEqual(inventory.rows, []);
    assert.equal(inventory.counts.bpm.total, 0);
    assert.equal(inventory.counts.crm.total, 0);
  });

  it('links implementation rows to candidate tests without claiming execution', () => {
    const input = fixture({
      'web-admin/app/plugins/core-bpm/services/bpmWorkbenchService.ts': 'export const start = () => {};',
      'web-admin/app/plugins/core-bpm/services/__tests__/bpmWorkbenchService.test.ts':
        "import { start } from '../bpmWorkbenchService';\n",
    });

    const inventory = buildInventory(input);
    const implementation = inventory.rows.find((row) => !row.isTest);

    assert.deepEqual(implementation.testEvidence, {
      status: 'candidate-mapped',
      candidates: ['web-admin/app/plugins/core-bpm/services/__tests__/bpmWorkbenchService.test.ts'],
    });
  });
});
