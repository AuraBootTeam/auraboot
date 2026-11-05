import { afterEach, describe, expect, it, vi } from 'vitest';
import { mkdtempSync, mkdirSync, writeFileSync, rmSync, symlinkSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import { buildExtendedManifest } from '../../src/commands/publish.js';
import { pluginImportCommand } from '../../src/commands/plugin-import.js';

const roots: string[] = [];
afterEach(() => { for (const root of roots.splice(0)) rmSync(root, { recursive: true, force: true }); vi.restoreAllMocks(); vi.unstubAllGlobals(); });
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

// Both public CLI entry points must preserve declarations before the platform importer can apply them.
describe('capability declarations in CLI manifests', () => {
  const capability = { code: 'demo.refund_finance', group: 'commerce', 'name:zh-CN': '退款管理', 'name:en': 'Refund finance',
    includes: ['demo_refund.read', 'demo_refund.create'], tier: 'admin', sensitive: true };
  function capabilityFixture() {
    const root = fixture();
    writeFileSync(join(root, 'contributions.json'), '[]');
    writeFileSync(join(root, 'config/capabilities.json'), JSON.stringify([capability]));
    return root;
  }
  it('preserves declared business actions, sensitivity and localization in the publish manifest', () => {
    expect(buildExtendedManifest(loadPlugin(capabilityFixture())).capabilities).toEqual([capability]);
  });
  it('uploads capability declarations through the actual plugin import command', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ code: '0', data: { success: true } }), { status: 200 }));
    vi.stubGlobal('fetch', fetchMock);
    vi.spyOn(console, 'log').mockImplementation(() => {});
    vi.spyOn(process, 'exit').mockImplementation(() => { throw new Error('Unexpected CLI exit'); });
    await pluginImportCommand(capabilityFixture(), { target: 'http://fixture.invalid', token: 'fixture-token', yes: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    const [url, options] = fetchMock.mock.calls[0];
    expect(new URL(url).pathname).toBe('/api/plugins/import/execute-direct');
    expect(JSON.parse(options.body).capabilities).toEqual([capability]);
  });
});
