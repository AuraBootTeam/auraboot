import { mkdtempSync, readFileSync, rmSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { initCommand } from '../../src/commands/init.js';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import { validateGovernance } from '../../src/validation/governance.js';
import { validateSemantic } from '../../src/validation/semantic.js';
import { validateStructural } from '../../src/validation/structural.js';

/**
 * Regression guard for the `aura plugin init` scaffold: a freshly scaffolded
 * sample plugin MUST be schema-valid (it used to emit `fieldType`, old dict/page
 * shapes, etc. that failed both the CLI validator and platform import).
 * Verified live against a real backend on 2026-07-23 (publishes clean).
 */
describe('scaffolded sample plugin is schema-valid', () => {
  let workDir: string;
  let target: string;

  beforeEach(async () => {
    workDir = mkdtempSync(join(tmpdir(), 'aura-scaffold-valid-'));
    target = join(workDir, 'sample');
    await initCommand('sample', {
      dir: target,
      nonInteractive: true,
      pluginId: 'com.acme.sample',
      namespace: 'smpl',
      displayName: 'Sample',
      pluginType: 'config',
    });
  });

  afterEach(() => {
    rmSync(workDir, { recursive: true, force: true });
  });

  const read = (name: string) => JSON.parse(readFileSync(join(target, 'config', `${name}.json`), 'utf8'));

  it('passes all three validation layers with zero errors', () => {
    const plugin = loadPlugin(target);
    for (const validate of [validateStructural, validateSemantic, validateGovernance]) {
      const r = validate(plugin);
      const errs = r.messages.filter((m) => m.severity === 'error');
      expect(errs, `${validate.name}: ${JSON.stringify(errs)}`).toEqual([]);
    }
  });

  it('fields use dataType, never the legacy fieldType', () => {
    const fields = read('fields');
    expect(fields.length).toBeGreaterThan(0);
    for (const f of fields) {
      expect(f).toHaveProperty('dataType');
      expect(f).not.toHaveProperty('fieldType');
    }
  });

  it('model declares modelType/modelCategory and a table name', () => {
    const [model] = read('models');
    expect(model.modelType).toBe('entity');
    expect(model.modelCategory).toBe('master');
    expect(model.extension?.tableName).toMatch(/^mt_/);
  });

  it('bindings use sequence (not the legacy orderNo)', () => {
    const bindings = read('bindings');
    for (const b of bindings) {
      expect(b).toHaveProperty('sequence');
      expect(b).not.toHaveProperty('orderNo');
    }
  });

  it('dict is static with value-keyed items', () => {
    const [dict] = read('dicts');
    expect(dict.dictType).toBe('static');
    expect(dict.items[0]).toHaveProperty('value');
    expect(dict.items[0]).not.toHaveProperty('code');
  });

  it('menu carries a pageKey pointing at the auto-generated list page', () => {
    const [menu] = read('menus');
    expect(menu.pageKey).toBe('smpl_sample_list');
  });

  it('ships empty pages/commands/i18n (platform auto-generates default pages)', () => {
    expect(read('pages')).toEqual([]);
    expect(read('commands')).toEqual([]);
  });
});
