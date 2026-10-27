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

  it('includes shared presentation types in the 61-definition contract', () => {
    expect(Object.keys(defs).length).toBe(61);
    expect(defs).toHaveProperty('CardImageViewport');
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
    const extension = defs.DslSchema.properties?.extension;
    const ext = extension?.$ref ? defs[extension.$ref.replace('#/definitions/', '')] : extension;
    expect(ext).toBeDefined();
    expect(ext?.type).toBe('object');
    // extension uses open additionalProperties: {} (not false)
    expect(ext?.additionalProperties).not.toBe(false);
  });

  it('publishes typed workspace geometry while preserving product extension properties', () => {
    const validate = new Ajv({ strict: false }).compile({
      ...defs.PageExtensionConfig,
      definitions: defs,
    });
    expect(
      validate({
        workspaceShell: {
          useThemeTokens: true,
          headerHeight: 64,
          mobileHeaderHeight: 55,
          sidebar: { expandedWidth: 218, compactWidth: 190, mobileBreakpoint: 700 },
        },
        detailHeaderLayout: { type: 'grid', cols: 4, compactCols: 2, compactBreakpoint: 700 },
        detailBodyOrder: 'schema',
        productOwnedProperty: 'retained',
      }),
    ).toBe(true);
    expect(validate({ workspaceShell: { headerHeight: '64' } })).toBe(false);
    expect(validate({ detailBodyOrder: 'reorder-by-type' })).toBe(false);
  });

  it('publishes native modal navigation and retained command prompts as separate actions', () => {
    const validate = new Ajv({ strict: false }).compile({ ...defs.ActionDef, definitions: defs });
    expect(
      validate({
        type: 'navigate',
        to: '/p/case/view/PID',
        presentation: { mode: 'modal', width: 900 },
      }),
    ).toBe(true);
    expect(
      validate({ type: 'navigate', to: '/p/case/view/PID', presentation: { mode: 'drawer' } }),
    ).toBe(false);
    expect(
      validate({
        type: 'command',
        command: 'case:close',
        inputFieldsPresentation: {
          width: 600,
          showClose: true,
          closeOnBackdrop: false,
          waitForResult: true,
          summary: {
            columns: 2,
            fields: [
              { field: 'subject', label: 'Event' },
              { field: 'status', dictCode: 'case_status' },
            ],
          },
        },
      }),
    ).toBe(true);
    expect(
      validate({
        type: 'command',
        command: 'case:close',
        inputFieldsPresentation: { summary: { columns: 3, fields: [] } },
      }),
    ).toBe(false);
    expect(
      validate({
        type: 'command',
        command: 'case:close',
        inputFieldsPresentation: { waitForResult: 'true' },
      }),
    ).toBe(false);
  });

  it('publishes the real page key in server pagination without changing the response', () => {
    const validate = new Ajv({ strict: false }).compile({
      ...defs.PaginationConfig,
      definitions: defs,
    });
    expect(validate({ mode: 'server', pageField: 'page', pageSize: 20 })).toBe(true);
    expect(validate({ mode: 'server', pageField: 'inventedPage', pageSize: 20 })).toBe(false);
    expect(validate({ mode: 'all-records' })).toBe(false);
  });

  it('publishes business timeline mappings and distinguishes current values from snapshots', () => {
    const validate = new Ajv({ strict: false }).compile({ ...defs.BlockConfig, definitions: defs });
    const timeline = {
      id: 'history',
      blockType: 'activity-timeline',
      permissionCode: 'model.case_action.read',
      businessOnly: true,
      businessDataSource: 'caseHistory',
      businessRecordMapping: {
        objectRecord: 'case_ref',
        occurredAt: 'created_at',
        subject: 'action_name',
        content: 'note',
        actorName: 'actor_name',
        fromState: 'from_status',
        toState: 'to_status',
      },
      businessStateDictCode: 'case_status',
      businessDetailFields: [{ field: 'outcome', label: 'Current outcome', dictCode: 'outcome' }],
      businessSnapshotFields: [{ field: 'previous_note', label: 'Previous note' }],
      compact: true,
      previewLimit: 100,
    };
    expect(validate(timeline)).toBe(true);
    expect(validate({ ...timeline, permissionCode: ['model.case_action.read'] })).toBe(false);
    expect(validate({ ...timeline, businessDataSource: 12 })).toBe(false);
    expect(validate({ ...timeline, businessSnapshotFields: [{ field: 'previous_note' }] })).toBe(
      false,
    );
  });

  // --- ColumnConfig property types ---

  it('ColumnConfig.editable should be boolean type', () => {
    const editable = defs.ColumnConfig.properties?.editable;
    expect(editable?.type).toBe('boolean');
  });

  it('personalization mandatory flags should be explicit booleans', () => {
    expect(defs.ColumnConfig.properties?.mandatory?.type).toBe('boolean');
    expect(defs.ButtonConfig.properties?.mandatory?.type).toBe('boolean');
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
    expect(defs.CommandInputFieldConfig?.properties?.dictCode).toEqual(
      expect.objectContaining({ type: 'string' }),
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

  it('publishes strict uploaded-files configuration without changing legacy file hints', () => {
    const validate = new Ajv({ strict: false }).compile({
      ...defs.CommandInputFieldConfig,
      definitions: defs,
    });
    const field = {
      field: 'filePids',
      type: 'uploaded-files',
      uploadedFiles: { maxCount: 10, accept: 'image/*,.pdf', maxBytes: 10485760 },
    };
    expect(validate(field)).toBe(true);
    for (const uploadedFiles of [
      { maxCount: 11, accept: '.pdf' },
      { maxCount: 0, accept: '.pdf' },
      { maxCount: 1.5, accept: '.pdf' },
      { maxCount: 1 },
      { maxCount: 1, accept: '.pdf', link: true },
    ])
      expect(validate({ ...field, uploadedFiles })).toBe(false);
    expect(
      validate({ field: 'csvText', type: 'file', fileValueMode: 'text', accept: '.csv' }),
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

  it('validates authoritative source bindings while preserving legacy block props', () => {
    const validate = new Ajv({ strict: false }).compile({ ...defs.BlockConfig, definitions: defs });
    const base = { id: 'reading', blockType: 'stat-card' };
    const binding = {
      mode: 'authoritative',
      identity: [{ responsePath: 'source', contextPath: 'state.source' }],
      valuePath: 'value',
      states: [
        {
          path: 'quality',
          label: 'Quality',
          values: { MISSING: { label: 'Missing', hideValue: true } },
        },
      ],
    };
    expect(validate({ ...base, props: { sourceState: binding } })).toBe(true);
    expect(validate({ ...base, props: { sourceState: { ...binding, mode: 'fallback' } } })).toBe(
      false,
    );
    expect(
      validate({
        ...base,
        props: { sourceState: { ...binding, identity: [{ responsePath: 1 }] } },
      }),
    ).toBe(false);
    expect(validate({ ...base, props: { sourceState: { ...binding, inlineValue: 99 } } })).toBe(
      false,
    );
    expect(validate({ ...base, props: { legacyLayout: 'compact' } })).toBe(true);
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
