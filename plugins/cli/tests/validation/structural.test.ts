import { mkdtempSync, rmSync, writeFileSync } from 'fs';
import { tmpdir } from 'os';
import { join } from 'path';
import { afterEach, describe, expect, it } from 'vitest';
import { validateStructural } from '../../src/validation/structural.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

const tempDirs: string[] = [];

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
        namespace: 'workflow-demo',
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
});
