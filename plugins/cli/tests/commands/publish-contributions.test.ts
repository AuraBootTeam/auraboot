import { afterEach, describe, expect, it } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import { buildExtendedManifest } from '../../src/commands/publish.js';

const roots: string[] = [];
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }); });
function fixture() {
  const root = mkdtempSync(join(tmpdir(), 'aura-contribution-')); roots.push(root);
  mkdirSync(join(root, 'config'));
  writeFileSync(join(root, 'plugin.json'), JSON.stringify({ pluginId: 'com.test.customer', namespace: 'cde', version: '1.0.0',
    resourceDirs: { pageContributions: 'contributions.json' } }));
  return root;
}
describe('published page contributions', () => {
  it('keeps declared contributions in the exact extended import manifest', () => {
    const root = fixture();
    const row = { id: 'follow-up', targetPageKey: 'project-detail', slotId: 'delivery', kind: 'block', priority: 10,
      payload: { id: 'follow-up-actions', blockType: 'workbench-action-bar', actions: [] } };
    writeFileSync(join(root, 'contributions.json'), JSON.stringify([row]));
    expect(buildExtendedManifest(loadPlugin(root)).pageContributions).toEqual([row]);
  });
  it('fails on a missing declaration instead of publishing an empty extension', () => {
    expect(() => loadPlugin(fixture())).toThrow(/Required public publication path missing/);
  });
  it('rejects a declared contribution symlink escaping the plugin root', () => {
    const root = fixture(); const outside = mkdtempSync(join(tmpdir(), 'aura-outside-')); roots.push(outside);
    writeFileSync(join(outside, 'secret.json'), '[]');
    symlinkSync(join(outside, 'secret.json'), join(root, 'contributions.json'));
    expect(() => loadPlugin(root)).toThrow(/escapes plugin/);
  });
});
