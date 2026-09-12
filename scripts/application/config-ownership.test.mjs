import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, readdirSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, resolve } from 'node:path';
import test from 'node:test';

import { writePlatformAdminConfigSplit } from './config-ownership.mjs';

const SCRIPT_ROOT = dirname(new URL(import.meta.url).pathname);
const REPO_ROOT = resolve(SCRIPT_ROOT, '../..');

test('platform-admin release split removes BPM resources from core without dropping them', () => {
  const output = mkdtempSync(resolve(tmpdir(), 'auraboot-config-split-'));
  try {
    const result = writePlatformAdminConfigSplit(resolve(REPO_ROOT, 'plugins/platform-admin'), output);
    const coreJson = readdirSync(resolve(result.coreRoot, 'config'))
      .filter((file) => file.endsWith('.json'))
      .map((file) => readFileSync(resolve(result.coreRoot, 'config', file), 'utf8'))
      .join('\n');
    assert.doesNotMatch(coreJson, /(?:^|[^a-z0-9])(bpm|sla|smartengine|smart_engine)(?:[^a-z0-9]|$)/i);

    const bpmModels = JSON.parse(readFileSync(resolve(result.bpmRoot, 'config/models.json'), 'utf8'));
    assert.deepEqual(
      bpmModels.map((model) => model.code).sort(),
      ['bpm_domain_config', 'bpm_process_management', 'sla_config'],
    );
    for (const row of result.manifest) assert.equal(row.sourceCount, row.coreCount + row.bpmCount);
  } finally {
    rmSync(output, { recursive: true, force: true });
  }
});
