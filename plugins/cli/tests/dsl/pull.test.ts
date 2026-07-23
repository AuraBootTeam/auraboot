import { describe, expect, it } from 'vitest';
import { PLATFORM_TYPE_TO_FILE, exportToFiles } from '../../src/dsl/pull.js';

describe('exportToFiles', () => {
  it('maps platform singular resource types to CLI plural config file names', () => {
    const files = exportToFiles({
      model: [{ code: 'order' }],
      field: [{ code: 'name' }, { code: 'status' }],
      model_field_binding: [{ modelCode: 'order', fieldCode: 'name' }],
      page: [],
    });
    expect(Object.keys(files).sort()).toEqual(['bindings', 'fields', 'models', 'pages']);
    expect(files.models).toEqual([{ code: 'order' }]);
    expect(files.fields).toHaveLength(2);
    expect(files.bindings).toEqual([{ modelCode: 'order', fieldCode: 'name' }]);
  });

  it('has a known mapping for the core resource types', () => {
    expect(PLATFORM_TYPE_TO_FILE.model).toBe('models');
    expect(PLATFORM_TYPE_TO_FILE.field).toBe('fields');
    expect(PLATFORM_TYPE_TO_FILE.model_field_binding).toBe('bindings');
    expect(PLATFORM_TYPE_TO_FILE.command).toBe('commands');
    expect(PLATFORM_TYPE_TO_FILE.page).toBe('pages');
    expect(PLATFORM_TYPE_TO_FILE.dict).toBe('dicts');
    expect(PLATFORM_TYPE_TO_FILE.menu).toBe('menus');
    expect(PLATFORM_TYPE_TO_FILE.permission).toBe('permissions');
  });

  it('falls back to <type>s for an unknown resource type (never drops data)', () => {
    const files = exportToFiles({ widget: [{ code: 'w' }] });
    expect(files.widgets).toEqual([{ code: 'w' }]);
  });

  it('coerces a non-array payload to an empty array', () => {
    expect(exportToFiles({ model: null as unknown as unknown[] })).toEqual({ models: [] });
  });
});
