import { describe, expect, it } from 'vitest';
import { buildResourceIndex } from '../../src/utils/resource-index.js';
import type { PluginFiles } from '../../src/utils/plugin-loader.js';

function index(resources: Record<string, unknown[]> = {}) {
  const files: PluginFiles = { dir: '/fixture', configDir: '/fixture/config',
    manifest: { pluginId: 'demo', namespace: 'demo', version: '1.0.0' }, resourceFiles: new Map(Object.entries(resources)) };
  return buildResourceIndex(files);
}

describe('resource index boundary behavior', () => {
  it('indexes an empty plugin without inventing resources or translations', () => {
    const value = index();
    expect(value.pluginId).toBe('demo');
    expect(Object.values(value.raw).every(records => records.length === 0)).toBe(true);
    expect(value.models.size).toBe(0);
    expect(value.expectedI18nKeys).toEqual([]);
    expect(value.missingI18nKeys).toEqual([]);
  });
  it('keeps dangling bindings visible without inventing field definitions', () => {
    const value = index({ bindings: [{ modelCode: 'demo_order', fieldCode: 'unknown' }] });
    expect(value.bindingsByField.get('unknown')).toEqual(['demo_order']);
    expect(value.bindingsByModel.get('demo_order')).toEqual([{ modelCode: 'demo_order', fieldCode: 'unknown' }]);
    expect(value.fieldsByModel.has('demo_order')).toBe(false);
    expect(value.missingI18nKeys).toEqual(['model.demo_order.unknown.label']);
  });
  it('honors explicit empty canonical arrays over legacy resource aliases', () => {
    const value = index({ bindings: [], modelFieldBindings: [{ modelCode: 'demo_order', fieldCode: 'amount' }],
      i18n: [], i18nResources: [{ key: 'model.demo_order._meta.label' }] });
    expect(value.raw.bindings).toEqual([]);
    expect(value.i18nKeys.size).toBe(0);
  });
  it('ignores incomplete reference targets and untranslated entries', () => {
    const value = index({ fields: [{ code: 'reference', dataType: 'reference' },
      { code: 'empty', dataType: 'reference', extension: { referenceModel: '' } }],
      i18n: [{}, { key: 'known', 'en-US': 'Known' }] });
    expect(value.referenceFields.size).toBe(0);
    expect([...value.i18nKeys.keys()]).toEqual(['known']);
  });
  it('indexes multiple field bindings and model relationships without mutating definitions', () => {
    const field = { code: 'amount', dataType: 'decimal' };
    const resources = { models: [{ code: 'demo_order' }, { code: 'demo_line' }], fields: [field],
      bindings: [{ modelCode: 'demo_order', fieldCode: 'amount' }, { modelCode: 'demo_line', fieldCode: 'amount' }],
      commands: [{ code: 'demo:create', modelCode: 'demo_order' }, { code: 'demo:update', modelCode: 'demo_order' }],
      pages: [{ pageKey: 'list', modelCode: 'demo_order' }, { pageKey: 'form', dslSchema: { modelCode: 'demo_order' } }] };
    const value = index(resources);
    expect(value.bindingsByField.get('amount')).toEqual(['demo_order', 'demo_line']);
    expect(value.commandsByModel.get('demo_order')?.map(command => command.code)).toEqual(['demo:create', 'demo:update']);
    expect(value.pagesByModel.get('demo_order')?.map(page => page.pageKey)).toEqual(['list', 'form']);
    expect(value.fieldsByModel.get('demo_line')?.[0].binding.modelCode).toBe('demo_line');
    expect(field).not.toHaveProperty('binding');
  });
});
