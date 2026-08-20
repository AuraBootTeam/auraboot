import { describe, it, expect } from 'vitest';
import Ajv from 'ajv';
import { readFileSync } from 'fs';
import { resolve } from 'path';

const schemaPath = resolve(__dirname, '../../../../../plugins/schemas/dsl-schema.generated.json');

describe('DSL Generated Schema', () => {
  const schema = JSON.parse(readFileSync(schemaPath, 'utf-8'));
  const defs = schema.definitions || {};

  // --- Definition existence ---

  it('should be a valid JSON Schema draft-07', () => {
    expect(schema.$schema).toBe('http://json-schema.org/draft-07/schema#');
    expect(schema.$ref).toBe('#/definitions/DslSchema');
  });

  it('should have 42 definitions', () => {
    expect(Object.keys(defs).length).toBe(42);
  });

  it('should have DslSchema definition', () => {
    expect(defs).toHaveProperty('DslSchema');
  });

  it('should have BlockConfig definition', () => {
    expect(defs).toHaveProperty('BlockConfig');
  });

  it('should have ColumnConfig definition', () => {
    expect(defs).toHaveProperty('ColumnConfig');
  });

  it('should have SubTableConfig definition', () => {
    expect(defs).toHaveProperty('SubTableConfig');
  });

  it('should have all core layout definitions', () => {
    expect(defs).toHaveProperty('LayoutConfig');
    expect(defs).toHaveProperty('BlockLayoutConfig');
  });

  it('should have all linkage definitions', () => {
    expect(defs).toHaveProperty('LinkageRule');
    expect(defs).toHaveProperty('LinkageTrigger');
    expect(defs).toHaveProperty('LinkageAction');
    expect(defs).toHaveProperty('CrossFieldRule');
  });

  // --- additionalProperties enforcement ---

  it('DslSchema should have additionalProperties: false', () => {
    expect(defs.DslSchema.additionalProperties).toBe(false);
  });

  it('BlockConfig should have additionalProperties: false', () => {
    expect(defs.BlockConfig.additionalProperties).toBe(false);
  });

  it('ColumnConfig should have additionalProperties: false', () => {
    expect(defs.ColumnConfig.additionalProperties).toBe(false);
  });

  it('SubTableConfig should have additionalProperties: false', () => {
    expect(defs.SubTableConfig.additionalProperties).toBe(false);
  });

  it('PageDataSourceConfig should have additionalProperties: false', () => {
    expect(defs.PageDataSourceConfig.additionalProperties).toBe(false);
  });

  // --- DslSchema required fields ---

  it('DslSchema should have kind as required', () => {
    expect(defs.DslSchema.required).toContain('kind');
  });

  it('DslSchema should have version, id, title, layout, blocks as required', () => {
    const required = defs.DslSchema.required;
    expect(required).toContain('version');
    expect(required).toContain('id');
    expect(required).toContain('title');
    expect(required).toContain('layout');
    expect(required).toContain('blocks');
  });

  // --- DslSchema.kind enum ---

  it('DslSchema.kind should be string enum with known values', () => {
    const kindProp = defs.DslSchema.properties?.kind;
    expect(kindProp?.type).toBe('string');
    expect(kindProp?.enum).toEqual(
      expect.arrayContaining(['page', 'list', 'form', 'detail', 'page_layout']),
    );
  });

  // --- DslSchema.extension open for extensibility ---

  it('DslSchema.extension should allow additional properties', () => {
    const ext = defs.DslSchema.properties?.extension;
    expect(ext).toBeDefined();
    expect(ext?.type).toBe('object');
    // extension uses open additionalProperties: {} (not false)
    expect(ext?.additionalProperties).not.toBe(false);
  });

  // --- ColumnConfig property types ---

  it('ColumnConfig.editable should be boolean type', () => {
    const editable = defs.ColumnConfig.properties?.editable;
    expect(editable?.type).toBe('boolean');
  });

  it('ColumnConfig.valueType should be string enum with known values', () => {
    const vt = defs.ColumnConfig.properties?.valueType;
    expect(vt?.type).toBe('string');
    expect(vt?.enum).toEqual(
      expect.arrayContaining(['text', 'boolean', 'date', 'currency', 'tag']),
    );
  });

  it('ColumnConfig.cellRenderer should be a string extension hook', () => {
    const renderer = defs.ColumnConfig.properties?.cellRenderer;
    expect(renderer?.type).toBe('string');
  });

  it('ColumnConfig.tagMap labels should allow localized text', () => {
    const tagMapEntry = defs.ColumnConfig.properties?.tagMap?.additionalProperties;
    const label = tagMapEntry?.properties?.label;
    expect(label?.anyOf).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'string' }),
        expect.objectContaining({ $ref: '#/definitions/LocalizedText' }),
      ]),
    );
  });

  it('BlockConfig.dataSource should allow string ids or inline configs', () => {
    const dataSource = defs.BlockConfig.properties?.dataSource;
    expect(dataSource?.anyOf).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'string' }),
        expect.objectContaining({ $ref: '#/definitions/DataSourceConfig' }),
      ]),
    );
  });

  it('ActionDef should accept localized business groups on command input fields', () => {
    const group = defs.CommandInputFieldConfig?.properties?.group;
    expect(group?.anyOf).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ type: 'string' }),
        expect.objectContaining({ $ref: '#/definitions/LocalizedText' }),
      ]),
    );

    const validateAction = new Ajv({ strict: false }).compile({
      ...defs.ActionDef,
      definitions: defs,
    });

    expect(
      validateAction({
        type: 'command',
        command: 'inventory:record_package',
        inputFieldsTitle: { 'zh-CN': '记录包装', en: 'Record package' },
        inputFields: [
          {
            field: 'boxNo',
            label: { 'zh-CN': '箱号', en: 'Box number' },
            group: { 'zh-CN': '箱号与数量', en: 'Box and quantity' },
            placeholder: { 'zh-CN': '请扫描或输入箱号', en: 'Scan or enter a box number' },
            helpText: {
              'zh-CN': '每个包装箱的唯一业务编号',
              en: 'Unique business number for the package',
            },
            required: true,
          },
        ],
      }),
    ).toBe(true);
  });

  it('SelectionConfig should publish the grouped-radio contract', () => {
    const selectionDefinition = defs.SelectionConfig;
    const selection = selectionDefinition.properties;
    expect(selection?.presentation?.enum).toEqual(['table', 'grouped-radio']);
    expect(selection?.exclusiveBy?.type).toBe('string');
    expect(selection?.optionLabelField?.type).toBe('string');
    expect(selection?.recommendedField?.type).toBe('string');
    expect(selection?.safeField?.type).toBe('string');
    expect(selectionDefinition?.['x-distinct-properties']).toContainEqual([
      'recommendedField',
      'safeField',
    ]);

    const validateTable = new Ajv({ strict: false }).compile({
      ...defs.TableConfig,
      definitions: defs,
    });
    const selectionBase = {
      mode: 'multiple',
      bind: 'selected',
      presentation: 'grouped-radio',
      exclusiveBy: 'groupId',
      optionLabelField: 'label',
    };
    const tableBase = {
      rowKey: 'pid',
      columns: [{ field: 'label', label: 'Option' }],
    };

    expect(
      validateTable({
        ...tableBase,
        selection: {
          ...selectionBase,
          recommendedField: 'recommended',
          safeField: 'safe',
        },
      }),
    ).toBe(true);
    expect(
      validateTable({
        ...tableBase,
        selection: { ...selectionBase, recommendedField: 'recommended' },
      }),
    ).toBe(false);
    expect(
      validateTable({
        ...tableBase,
        selection: { ...selectionBase, defaultFirst: true },
      }),
    ).toBe(false);
    expect(validateTable({ columns: tableBase.columns, selection: selectionBase })).toBe(false);
    expect(
      validateTable({
        columns: tableBase.columns,
        selection: { ...selectionBase, keyField: 'pid' },
      }),
    ).toBe(true);
    expect(
      validateTable({
        columns: tableBase.columns,
        selection: { ...selectionBase, keyField: '' },
      }),
    ).toBe(false);
  });

  it('ColumnConfig.field should be required', () => {
    expect(defs.ColumnConfig.required).toContain('field');
  });

  it('ColumnConfig.align should be enum with left/center/right', () => {
    const align = defs.ColumnConfig.properties?.align;
    expect(align?.enum).toEqual(['left', 'center', 'right']);
  });

  // --- SubTableConfig properties ---

  it('SubTableConfig.allowInlineEdit should be boolean', () => {
    const prop = defs.SubTableConfig.properties?.allowInlineEdit;
    expect(prop?.type).toBe('boolean');
  });

  it('SubTableConfig should require childModel, parentField, columns', () => {
    const required = defs.SubTableConfig.required;
    expect(required).toContain('childModel');
    expect(required).toContain('parentField');
    expect(required).toContain('columns');
  });

  it('SubTableConfig.columns should be array of ColumnConfig', () => {
    const cols = defs.SubTableConfig.properties?.columns;
    expect(cols?.type).toBe('array');
    expect(cols?.items?.$ref).toBe('#/definitions/ColumnConfig');
  });

  // --- BlockConfig properties ---

  it('BlockConfig should require id and blockType', () => {
    expect(defs.BlockConfig.required).toContain('id');
    expect(defs.BlockConfig.required).toContain('blockType');
  });

  it('BlockConfig.subTable should reference SubTableConfig', () => {
    const st = defs.BlockConfig.properties?.subTable;
    expect(st?.$ref).toBe('#/definitions/SubTableConfig');
  });

  it('BlockConfig.monthlyGrid should reference MonthlyGridConfig', () => {
    const mg = defs.BlockConfig.properties?.monthlyGrid;
    expect(mg?.$ref).toBe('#/definitions/MonthlyGridConfig');
  });

  // --- PageDataSourceConfig ---

  it('PageDataSourceConfig.type should be enum with table/namedQuery/api', () => {
    const typeProp = defs.PageDataSourceConfig.properties?.type;
    expect(typeProp?.enum).toEqual(['table', 'namedQuery', 'api']);
  });

  // --- Cross-references integrity ---

  it('all $ref targets should exist in definitions', () => {
    const allRefs: string[] = [];
    const collectRefs = (obj: unknown) => {
      if (obj && typeof obj === 'object') {
        for (const [key, val] of Object.entries(obj as Record<string, unknown>)) {
          if (key === '$ref' && typeof val === 'string' && val.startsWith('#/definitions/')) {
            allRefs.push(val.replace('#/definitions/', ''));
          }
          collectRefs(val);
        }
      }
    };
    collectRefs(schema);

    const missingRefs = allRefs.filter((r) => !defs[r]);
    expect(missingRefs).toEqual([]);
  });
});
