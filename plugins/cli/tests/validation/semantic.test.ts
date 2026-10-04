import { describe, expect, it } from 'vitest';
import { validateSemantic } from '../../src/validation/semantic.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

function plugin(resources: Record<string, unknown[]> = {}, namespace = 'demo'): PluginFiles {
  return { dir: '/fixture', configDir: '/fixture/config',
    manifest: { pluginId: 'demo', namespace, version: '1.0.0' },
    resourceFiles: new Map(Object.entries(resources)),
  };
}
const page = (extra: Record<string, unknown> = {}) => ({
  pageKey: 'demo_list', kind: 'list', layout: { type: 'grid' }, modelCode: 'demo_order',
  blocks: [{ blockType: 'table', columns: [{ field: 'amount', editable: true }] }], ...extra,
});
const messages = (resources: Record<string, unknown[]>) => validateSemantic(plugin(resources)).messages;

describe('semantic validation against shipped DSL metadata', () => {
  it('detects a missing update command on canonical flat page columns', () => {
    expect(messages({ pages: [page()] })).toContainEqual(expect.objectContaining({
      code: 'S-DSL-REQUIRES-CMD', severity: 'warning',
      message: expect.stringContaining("column 'amount' has editable=true"),
    }));
  });
  it.each(['dslSchema', 'dsl_schema'])('also validates wrapped %s pages', key => {
    expect(messages({ pages: [{ pageKey: 'demo_list', [key]: page() }] }))
      .toContainEqual(expect.objectContaining({ code: 'S-DSL-REQUIRES-CMD' }));
  });
  it.each(['type', 'executionConfig'])('accepts a matching update command from %s', property => {
    const command = { code: 'demo:update', modelCode: 'demo_order',
      ...(property === 'type' ? { type: 'update' } : { executionConfig: { type: 'update' } }) };
    expect(messages({ models: [{ code: 'demo_order' }], commands: [command], pages: [page()] }))
      .not.toContainEqual(expect.objectContaining({ code: 'S-DSL-REQUIRES-CMD' }));
  });
  it('checks the child model rather than the parent for sub-table edits', () => {
    const pages = [page({ blocks: [{ blockType: 'tabs', subTable: {
      childModel: 'demo_line', allowInlineEdit: true,
    } }] })];
    const commands = [{ code: 'demo:update', modelCode: 'demo_order', type: 'update' }];
    expect(messages({ commands, pages })).toContainEqual(expect.objectContaining({
      code: 'S-DSL-REQUIRES-CMD', message: expect.stringContaining("sub-table 'demo_line'"),
    }));
    expect(messages({ commands: [...commands, { code: 'demo:update_line', modelCode: 'demo_line', type: 'update' }], pages }))
      .not.toContainEqual(expect.objectContaining({ code: 'S-DSL-REQUIRES-CMD' }));
  });
  it('does not require an update command for disabled or absent inline editing', () => {
    expect(messages({ pages: [page({ blocks: [{ blockType: 'table', columns: [
      { field: 'a', editable: false }, { field: 'b' },
    ] }] })] })).not.toContainEqual(expect.objectContaining({ code: 'S-DSL-REQUIRES-CMD' }));
  });
  it('warns when a list page contains only incompatible block types', () => {
    expect(messages({ pages: [page({ blocks: [{ blockType: 'chart' }] })] }))
      .toContainEqual(expect.objectContaining({ code: 'S-PAGE-BLOCKS', severity: 'warning' }));
  });
  it.each([{ blocks: [] }, { blocks: [{ blockType: 'table' }] }, { blocks: [{}] }])('does not warn for empty or recommended blocks: %j', ({ blocks }) => {
    expect(messages({ pages: [page({ blocks })] }))
      .not.toContainEqual(expect.objectContaining({ code: 'S-PAGE-BLOCKS' }));
  });
  it('reports invalid kinds and missing layout on flat pages', () => {
    const result = validateSemantic(plugin({ pages: [{ pageKey: 'bad', kind: 'typo' }, { pageKey: 'empty' }] }));
    expect(result.valid).toBe(false);
    expect(result.messages.filter(m => m.code === 'S-PAGE-KIND')).toHaveLength(2);
    expect(result.messages.filter(m => m.code === 'S-PAGE-LAYOUT')).toHaveLength(2);
  });
  it('checks model and field binding references independently', () => {
    const result = validateSemantic(plugin({ models: [{ code: 'demo_order' }], fields: [{ code: 'amount' }],
      commands: [{ code: 'demo:bad', modelCode: 'missing' }],
      bindings: [{ modelCode: 'missing', fieldCode: 'missing' }, { modelCode: 'demo_order', fieldCode: 'amount' }],
    }));
    expect(result.errorCount).toBe(3);
    expect(result.messages.map(m => m.code)).toEqual(['S-REF-MODEL', 'S-REF-BINDING-MODEL', 'S-REF-BINDING-FIELD']);
  });
  it('exempts table-bound models and missing codes from namespace checks', () => {
    const resources = { models: [{ code: 'foreign' }, { code: 'bound', extension: { tableName: 'table' } }, {}],
      commands: [{ code: 'foreign' }, {}, { code: 'demo:valid' }] };
    expect(messages(resources).map(m => m.code)).toEqual(['S-NS-MODEL', 'S-NS-COMMAND']);
    expect(validateSemantic(plugin(resources, '')).messages).toEqual([]);
  });
  it('requires a state field and target or transition rules', () => {
    const commands = [
      { code: 'demo:bad', type: 'state_transition' },
      { code: 'demo:empty', type: 'state_transition', stateField: 'status', stateTransitionRules: [] },
      { code: 'demo:target', type: 'state_transition', stateField: 'status', toState: 'done' },
      { code: 'demo:rules', executionConfig: { type: 'state_transition', stateField: 'status', stateTransitionRules: [{}] } },
    ];
    expect(messages({ commands }).filter(m => m.code === 'S-EXEC-ST')).toHaveLength(3);
  });
  it('distinguishes invalid command types from unknown auto-set strategies', () => {
    const result = validateSemantic(plugin({ commands: [{ code: 'demo:bad', type: 'unsupported',
      autoSetFields: { amount: { strategy: 'unsupported' }, empty: null } }] }));
    expect(result.errorCount).toBe(1);
    expect(result.warningCount).toBe(1);
    expect(result.messages.map(m => m.code)).toEqual(['S-EXEC-TYPE', 'S-EXEC-AUTOSET']);
  });
  it('handles non-list pages without model bindings or block recommendations', () => {
    expect(messages({ pages: [{ pageKey: 'generic', kind: 'composite', layout: {} }],
      commands: [{ code: 'demo:unbound', type: 'update', executionConfig: { autoSetFields: { amount: {} } } }],
    })).toEqual([]);
  });
});
