import { existsSync, mkdtempSync, readdirSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join, resolve } from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { validateStructural } from '../../src/validation/structural.js';
import { loadPlugin } from '../../src/utils/plugin-loader.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

const tempDirs: string[] = [];
const shippedPluginsDir = resolve(import.meta.dirname, '../../../../plugins');
const shippedPluginNames = readdirSync(shippedPluginsDir)
  .filter((name) => existsSync(join(shippedPluginsDir, name, 'plugin.json')))
  .sort();

function pluginWithManifest(manifest: Record<string, unknown>): PluginFiles {
  const dir = mkdtempSync(join(tmpdir(), 'aura-plugin-structural-'));
  tempDirs.push(dir);
  writeFileSync(join(dir, 'plugin.json'), `${JSON.stringify(manifest, null, 2)}\n`, 'utf8');
  return {
    dir,
    configDir: join(dir, 'config'),
    manifest: manifest as PluginFiles['manifest'],
    resourceFiles: new Map(),
  };
}

describe('validateStructural plugin manifest schema', () => {
  afterEach(() => {
    for (const dir of tempDirs.splice(0)) {
      rmSync(dir, { recursive: true, force: true });
    }
  });

  it('accepts the canonical backend jarPath and entryClass fields', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'workflow-demo',
        namespace: 'workflow_demo',
        version: '1.0.0',
        backend: {
          jarPath: 'backend/build/libs/workflow-demo-plugin-1.0.0.jar',
          entryClass: 'com.auraboot.plugins.workflowdemo.WorkflowDemoPlugin',
        },
      }),
    );

    expect(result.messages.filter((message) => message.code === 'L1-MANIFEST')).toEqual([]);
    expect(result.errorCount).toBe(0);
  });

  it('rejects the removed top-level entryPoint field', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-plugin',
        namespace: 'legacy',
        version: '1.0.0',
        entryPoint: 'com.acme.LegacyPlugin',
      }),
    );

    expect(result.messages).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'L1-MANIFEST',
          path: 'plugin.json',
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('rejects the removed top-level clientConfig field', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-frontend-plugin',
        namespace: 'legacy_frontend',
        version: '1.0.0',
        clientConfig: {
          remoteEntry: 'frontend/remoteEntry.js',
        },
      }),
    );

    expect(result.messages).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          code: 'L1-MANIFEST',
          path: 'plugin.json',
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('rejects removed backend jarFile and entryPoint aliases', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'legacy-plugin',
        namespace: 'legacy',
        version: '1.0.0',
        backend: {
          jarFile: 'backend/legacy.jar',
          entryPoint: 'com.acme.LegacyPlugin',
        },
      }),
    );

    const manifestErrors = result.messages.filter((message) => message.code === 'L1-MANIFEST');
    expect(manifestErrors.every((message) => message.path === 'plugin.json/backend')).toBe(true);
    expect(manifestErrors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          message: expect.stringContaining("must have required property 'jarPath'"),
        }),
        expect.objectContaining({
          message: expect.stringContaining("must have required property 'entryClass'"),
        }),
        expect.objectContaining({
          message: expect.stringContaining('must NOT have additional properties'),
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it('requires the canonical backend jarPath and entryClass fields together', () => {
    const result = validateStructural(
      pluginWithManifest({
        pluginId: 'partial-backend-plugin',
        namespace: 'partial_backend',
        version: '1.0.0',
        backend: {
          jarPath: 'backend/partial.jar',
        },
      }),
    );

    const manifestErrors = result.messages.filter((message) => message.code === 'L1-MANIFEST');
    expect(manifestErrors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          path: 'plugin.json/backend',
          message: expect.stringContaining("must have required property 'entryClass'"),
        }),
      ]),
    );
    expect(result.errorCount).toBeGreaterThan(0);
  });

  it.each(shippedPluginNames)(
    'validates shipped %s resource schemas without schema load warnings',
    (pluginName) => {
      const plugin = loadPlugin(join(shippedPluginsDir, pluginName));

      const result = validateStructural(plugin);

      expect(result.messages.filter((message) => message.code === 'L1-RESOURCE-SCHEMA')).toEqual([]);
      expect(result.messages.filter((message) => message.code === 'L1-RESOURCE')).toEqual([]);
      expect(result.errorCount).toBe(0);
    },
  );
});
