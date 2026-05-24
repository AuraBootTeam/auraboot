import { describe, expect, it, beforeEach } from 'vitest';
import { getEnumCodes, resetRegistryCache } from '../../src/utils/dsl-registry-loader.js';
import { validateSemantic } from '../../src/validation/semantic.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

describe('dsl registry loader', () => {
  beforeEach(() => {
    resetRegistryCache();
  });

  it('loads the repo DSL registry when running in ESM', () => {
    expect([...getEnumCodes('CommandType')]).toContain('create');
  });

  it('does not skip semantic enum validation because of ESM path resolution', () => {
    const plugin: PluginFiles = {
      dir: '',
      configDir: '',
      manifest: {
        pluginId: 'test-plugin',
        namespace: 'pe',
        version: '0.0.0',
      },
      resourceFiles: new Map([
        ['models', [{ code: 'pe_order' }]],
        ['fields', []],
        ['commands', [{ code: 'pe:create_order', modelCode: 'pe_order', type: 'create' }]],
        ['bindings', []],
        ['pages', []],
      ]),
    };

    const result = validateSemantic(plugin);

    expect(result.messages.some((message) => message.code === 'S-REGISTRY')).toBe(false);
  });
});
