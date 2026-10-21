import { renderHook } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import type { BlockConfig, ColumnConfig } from '~/framework/meta/schemas/types';
import { useListColumnLabel, useListFieldOptions, useListTableColumns } from '../listColumnModel';
import type { ColumnSettingsDefinition } from '~/framework/smart/components/view/ColumnSettingsPanel';
import type { ListFilterFieldMetadata, ViewManageFieldOption } from '../listFieldMetadata';

type ColumnOptions = Parameters<typeof useListTableColumns>[0];
type LabelOptions = Parameters<typeof useListColumnLabel>[0];
const labels: Record<string, string> = {
  'common.created_at': 'Created at',
  'common.updated_at': 'Updated at',
  'common.creator': 'Creator',
  'common.modifier': 'Modifier',
  'table.actions': 'Actions',
};
const t = (key: string) => labels[key] ?? key;

function columnsOptions(overrides: Partial<ColumnOptions> = {}): ColumnOptions {
  return {
    tableBlock: {
      id: 'list',
      blockType: 'table',
      table: {
        columns: [
          { field: 'name', label: 'Name' },
          { field: 'amount', label: 'Amount', valueType: 'currency' },
        ],
      },
    } as BlockConfig,
    effectiveViewConfig: {},
    modelFieldMap: new Map(),
    t,
    ...overrides,
  };
}

describe('list column model', () => {
  it.each([
    null,
    { id: 'missing', blockType: 'table' },
    {
      id: 'malformed',
      blockType: 'table',
      columns: 'invalid',
    },
  ])('keeps an absent or malformed table empty: %j', (tableBlock) => {
    const { result } = renderHook(() =>
      useListTableColumns(columnsOptions({ tableBlock: tableBlock as BlockConfig })),
    );
    expect(result.current.tableColumns).toEqual([]);
  });

  it('keeps DSL defaults and the same memoized arrays across unrelated renders', () => {
    const options = columnsOptions();
    const { result, rerender } = renderHook(() => useListTableColumns(options));
    const columns = result.current.tableColumns;
    const system = result.current.SYSTEM_FIELD_DEFS;
    expect(columns.map((c: ColumnConfig) => c.field)).toEqual(['name', 'amount']);
    rerender();
    expect(result.current.tableColumns).toBe(columns);
    expect(result.current.SYSTEM_FIELD_DEFS).toBe(system);
  });

  it('synthesizes nested row actions without losing the DSL columns', () => {
    const options = columnsOptions();
    options.tableBlock!.table!.rowActions = [{ code: 'inspect', label: 'Inspect' }];
    const { result } = renderHook(() => useListTableColumns(options));
    expect(result.current.tableColumns.at(-1)).toMatchObject({
      field: '_actions',
      isActionColumn: true,
      buttons: [{ code: 'inspect', label: 'Inspect' }],
    });
  });

  it('preserves an existing action and appends only distinct nested action codes', () => {
    const options = columnsOptions({
      tableBlock: {
        id: 'list',
        blockType: 'table',
        table: {
          columns: [
            {
              field: '_actions',
              isActionColumn: true,
              buttons: [{ code: 'inspect', label: 'Original' }],
            },
          ],
          rowActions: [
            { code: 'inspect', label: 'Duplicate' },
            { code: 'approve', label: 'Approve' },
          ],
        },
      } as BlockConfig,
    });
    const { result } = renderHook(() => useListTableColumns(options));
    expect(result.current.tableColumns[0].buttons).toEqual([
      { code: 'inspect', label: 'Original' },
      { code: 'approve', label: 'Approve' },
    ]);
  });

  it('applies owned visibility, order, width and explicit unfreezing without mutating DSL', () => {
    const options = columnsOptions({
      effectiveViewConfig: {
        columns: [
          { fieldCode: 'name', visible: false },
          { fieldCode: 'amount', visible: true, order: 2, width: 184, frozen: false },
          {
            fieldCode: 'created_at',
            visible: true,
            order: 1,
            frozen: true,
            frozenPosition: 'left',
          },
        ],
      },
    });
    const original = JSON.stringify(options.tableBlock);
    const { result } = renderHook(() => useListTableColumns(options));
    expect(result.current.tableColumns).toEqual([
      expect.objectContaining({ field: 'created_at', label: 'Created at', fixed: 'left' }),
      expect.objectContaining({ field: 'amount', width: 184, fixed: undefined }),
    ]);
    expect(JSON.stringify(options.tableBlock)).toBe(original);
  });

  it('promotes readable model fields with their actual renderer, dictionary and reference metadata', () => {
    const options = columnsOptions({
      modelFieldMap: new Map([
        [
          'extra',
          {
            displayName: 'Extra amount',
            dataType: 'decimal',
            extension: {
              renderComponent: 'money',
              dictCode: 'amounts',
              refTarget: { modelCode: 'account', displayField: 'name' },
            },
          },
        ],
      ]),
      effectiveViewConfig: {
        columns: [
          { fieldCode: 'extra', visible: true, order: 0 },
          { fieldCode: 'unknown', visible: true },
          { fieldCode: 'created_at', visible: true },
        ],
      },
    });
    const { result } = renderHook(() => useListTableColumns(options));
    expect(result.current.tableColumns[0]).toMatchObject({
      field: 'extra',
      label: 'Extra amount',
      valueType: 'currency',
      sorter: 'decimal',
      dictCode: 'amounts',
      refTarget: { modelCode: 'account', displayField: 'name' },
    });
    expect(
      result.current.tableColumns.filter((c: ColumnConfig) => c.field === 'created_at'),
    ).toHaveLength(1);
    expect(result.current.tableColumns.some((c: ColumnConfig) => c.field === 'unknown')).toBe(
      false,
    );
  });

  it('refreshes promoted field metadata and translated system labels on rerender', () => {
    const options = columnsOptions({
      effectiveViewConfig: { columns: [{ fieldCode: 'extra', visible: true }] },
      modelFieldMap: new Map([['extra', { displayName: 'Before', dataType: 'string' }]]),
    });
    const { result, rerender } = renderHook((props: ColumnOptions) => useListTableColumns(props), {
      initialProps: options,
    });
    const previous = result.current.tableColumns;
    rerender({
      ...options,
      modelFieldMap: new Map([['extra', { displayName: 'After', dataType: 'date' }]]),
      t: (key: string) => (key === 'common.created_at' ? 'Created localized' : t(key)),
    });
    expect(result.current.tableColumns).not.toBe(previous);
    expect(
      result.current.tableColumns.find((c: ColumnConfig) => c.field === 'extra'),
    ).toMatchObject({
      label: 'After',
      sorter: 'date',
    });
    expect(result.current.SYSTEM_FIELD_DEFS[0].label).toBe('Created localized');
  });
});

describe('list column labels and field options', () => {
  function labelOptions() {
    return {
      locale: 'en-US',
      t,
      schema: { modelCode: 'invoice' },
      tableName: 'fallback',
      modelFieldMap: new Map([['untranslated', { displayName: 'Imported name' }]]),
    } as Parameters<typeof useListColumnLabel>[0];
  }

  it('retains explicit localized labels before action, system, model and metadata labels', () => {
    const options = labelOptions();
    const translated = {
      'model.invoice.code.label': 'Invoice code',
      'field.invoice.number.label': 'Invoice number',
      'field.generic.label': 'Generic',
      'common.field.legacy': 'Legacy',
      'common.fallback': 'Fallback',
    };
    options.t = (key) => translated[key as keyof typeof translated] ?? t(key);
    const { result } = renderHook(() => useListColumnLabel(options));
    expect(
      result.current({ field: 'created_at', label: { en: 'Explicit', 'zh-CN': 'Explicit zh' } }),
    ).toBe('Explicit');
    expect(result.current({ field: '_actions', isActionColumn: true })).toBe('Actions');
    expect(result.current({ field: 'created_at' })).toBe('Created at');
    for (const [field, value] of [
      ['code', 'Invoice code'],
      ['number', 'Invoice number'],
      ['generic', 'Generic'],
      ['legacy', 'Legacy'],
      ['fallback', 'Fallback'],
      ['untranslated', 'Imported name'],
      ['absent', 'absent'],
    ]) {
      expect(result.current({ field })).toBe(value);
    }
  });

  it('keeps the label callback stable until its captured locale or metadata changes', () => {
    const options = labelOptions();
    const { result, rerender } = renderHook((props: LabelOptions) => useListColumnLabel(props), {
      initialProps: options,
    });
    const callback = result.current;
    rerender(options);
    expect(result.current).toBe(callback);
    rerender({
      ...options,
      modelFieldMap: new Map([['untranslated', { displayName: 'Changed' }]]),
    });
    expect(result.current).not.toBe(callback);
    expect(result.current({ field: 'untranslated' })).toBe('Changed');
  });

  it('keeps hidden fields in settings and views, adding filter metadata only when displayed', () => {
    const options = columnsOptions({
      modelFieldMap: new Map([
        ['name', { code: 'name', displayName: 'Name', dataType: 'string' }],
        [
          'hidden_amount',
          { code: 'hidden_amount', displayName: 'Hidden amount', dataType: 'decimal' },
        ],
      ]),
    });
    const resolveColumnLabel = (c: ColumnConfig) => String(c.label ?? c.field);
    const { result, rerender } = renderHook(
      (props: ColumnOptions) => {
        const { tableColumns, SYSTEM_FIELD_DEFS } = useListTableColumns(props);
        return useListFieldOptions({
          tableBlock: props.tableBlock,
          tableColumns,
          SYSTEM_FIELD_DEFS,
          modelFieldMap: props.modelFieldMap,
          resolveColumnLabel,
        });
      },
      { initialProps: options },
    );
    expect(
      result.current.allColumnDefs.find(
        (c: ColumnSettingsDefinition) => c.field === 'hidden_amount',
      ),
    ).toMatchObject({
      defaultVisible: false,
    });
    expect(
      result.current.viewManageFields.find(
        (f: ViewManageFieldOption) => f.code === 'hidden_amount',
      ),
    ).toMatchObject({
      dataType: 'decimal',
    });
    expect(
      result.current.filterFieldMetadata.some(
        (f: ListFilterFieldMetadata) => f.fieldCode === 'hidden_amount',
      ),
    ).toBe(false);
    expect(
      result.current.filterFieldMetadata.filter(
        (f: ListFilterFieldMetadata) => f.fieldCode === 'created_at',
      ),
    ).toHaveLength(1);
    rerender({
      ...options,
      effectiveViewConfig: { columns: [{ fieldCode: 'hidden_amount', visible: true }] },
    });
    expect(
      result.current.filterFieldMetadata.find(
        (f: ListFilterFieldMetadata) => f.fieldCode === 'hidden_amount',
      ),
    ).toMatchObject({ fieldType: 'decimal' });
  });
});
