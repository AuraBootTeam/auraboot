import { afterEach, describe, expect, it, vi } from 'vitest';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

const plugin: PluginFiles = {
  dir: '/fixture', configDir: '/fixture/config',
  manifest: { pluginId: 'demo', namespace: 'demo', version: '1.0.0' },
  resourceFiles: new Map([['commands', [{ code: 'demo:bad', modelCode: 'unknown' }]]]),
};

afterEach(() => {
  vi.doUnmock('fs');
  vi.doUnmock('../../src/utils/dsl-registry-loader.js');
  vi.resetModules();
});

async function loadWithSchema(schema: unknown, available = true) {
  vi.resetModules();
  vi.doMock('fs', async () => {
    const actual = await vi.importActual<typeof import('fs')>('fs');
    return { ...actual,
      existsSync: (path: unknown) => String(path).endsWith('dsl-schema.generated.json')
        ? available : actual.existsSync(path as string),
      readFileSync: (path: unknown, ...args: unknown[]) => String(path).endsWith('dsl-schema.generated.json')
        ? (typeof schema === 'string' ? schema : JSON.stringify(schema))
        : (actual.readFileSync as Function)(path, ...args),
    };
  });
  return (await import('../../src/validation/semantic.js')).validateSemantic;
}

describe('semantic validator dependency boundaries', () => {
  it.each([{ schema: {} }, { schema: { definitions: { DslSchema: {} } } },
    { schema: { definitions: { DslSchema: { allOf: [{}] } } } },
    { schema: '{ malformed' }, { schema: {}, available: false }])(
    'preserves reference validation when optional schema metadata is unavailable: %j', async ({ schema, available }) => {
      const validate = await loadWithSchema(schema, available);
      expect(validate(plugin)).toMatchObject({ valid: false, errorCount: 1,
        messages: [expect.objectContaining({ code: 'S-REF-MODEL' })] });
    },
  );
  it.each([new Error('registry unavailable'), 'non-Error failure'])('reports an enum registry failure: %s', async failure => {
    vi.resetModules();
    vi.doMock('../../src/utils/dsl-registry-loader.js', () => ({ getEnumCodes: () => { throw failure; } }));
    const { validateSemantic } = await import('../../src/validation/semantic.js');
    const result = validateSemantic(plugin);
    expect(result.warningCount).toBe(1);
    expect(result.messages[0]).toMatchObject({ code: 'S-REGISTRY', severity: 'warning' });
    expect(result.messages[0].message).toContain(String(failure instanceof Error ? failure.message : failure));
  });
  it('keeps legacy area recommendations and editable columns working', async () => {
    const validate = await loadWithSchema({ definitions: {
      ColumnConfig: { properties: { editable: { 'x-requiresCommand': 'update' }, field: { type: 'string' } } },
      DslSchema: { allOf: [{ if: { properties: { kind: { const: 'list' } } },
        then: { properties: { areas: { 'x-recommended-block-types': ['table'] } } } }] },
    } });
    const resources = new Map([['pages', [{ page_key: 'legacy', dsl_schema: {
      kind: 'list', modelCode: 'demo_order', layout: {}, areas: { empty: null,
        main: { blocks: [{ blockType: 'chart', columns: [{ field: 'amount', editable: true }] }] } },
    } }]]]);
    const result = validate({ ...plugin, resourceFiles: resources });
    expect(result.messages.map(m => m.code)).toEqual(['S-DSL-REQUIRES-CMD', 'S-PAGE-BLOCKS']);
  });
});
