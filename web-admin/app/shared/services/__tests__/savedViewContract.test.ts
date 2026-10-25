import { describe, expect, it } from 'vitest';
import {
  savedViewForView,
  savedViewConfigForView,
  savedViewConfigToWire,
  savedViewJson,
} from '../savedViewContract';
import type { SavedViewWire } from '~/framework/smart/types/savedViewWire';

const view: SavedViewWire = {
  pid: 'owned-view',
  name: 'Owned view',
  modelCode: 'ab_team',
  scope: 'personal',
  viewType: 'table',
  createdAt: '2026-10-01T00:00:00Z',
  updatedAt: '2026-10-01T00:00:00Z',
  dirty: false,
  actions: ['view'],
  effectivePermission: 'view',
};
describe('SavedView wire/editor projection', () => {
  it('accepts count kanban aggregations without a field and still rejects field-less value aggregations', () => {
    const withCountNoField = savedViewForView({
      ...view,
      scope: 'global',
      viewType: 'kanban',
      viewConfig: {
        groupByField: 'inv_in_status',
        titleField: 'inv_in_code',
        idField: 'pid',
        kanbanAggregations: [
          { field: null, function: 'count', label: { en: 'Count', 'zh-CN': '计数' } },
          { field: '', function: 'count' },
          { field: 'inv_in_total_amount', function: 'sum', label: { en: 'Total', 'zh-CN': '总金额' } },
        ],
      },
    } as SavedViewWire);
    expect(withCountNoField.viewConfig?.kanbanAggregations).toEqual([
      { field: '', function: 'count', label: { en: 'Count', 'zh-CN': '计数' } },
      { field: '', function: 'count' },
      { field: 'inv_in_total_amount', function: 'sum', label: { en: 'Total', 'zh-CN': '总金额' } },
    ]);
    expect(() =>
      savedViewForView({
        ...view,
        scope: 'global',
        viewType: 'kanban',
        viewConfig: {
          groupByField: 'inv_in_status',
          kanbanAggregations: [{ field: null, function: 'sum' }],
        },
      } as SavedViewWire),
    ).toThrow('card aggregation field');
  });

  it('keeps nullable settings absent while preserving false, zero, JSON null and metadata', () => {
    const result = savedViewForView({
      ...view,
      description: null,
      ownerName: null,
      viewConfig: {
        showRowNumbers: false,
        columns: null,
        sorts: [{ fieldCode: 'name', direction: 'ASC', priority: 0 }],
        filters: [{ fieldCode: 'status', operator: 'isNull', logic: 'AND', value: null }],
        conditionalFormats: [
          { fieldCode: 'cost', operator: 'eq', value: 0, style: { bold: false } },
        ],
        meta: {
          capabilityReasonCodes: ['UNKNOWN_FIELD'],
          basePageKey: 'ab_team_list',
          overlayStatus: 'REBASED',
        },
      },
    });
    expect(result.description).toBeUndefined();
    expect(result.viewConfig?.columns).toBeUndefined();
    expect(result.viewConfig?.showRowNumbers).toBe(false);
    expect(result.viewConfig?.sorts?.[0]).toEqual({
      fieldCode: 'name',
      direction: 'asc',
      priority: 0,
    });
    expect(result.viewConfig?.filters?.[0]).toMatchObject({ logic: 'and', value: null });
    expect(result.viewConfig?.conditionalFormats?.[0]).toMatchObject({
      value: 0,
      style: { bold: false },
    });
    const wire = savedViewConfigToWire(result.viewConfig);
    expect(wire?.filters?.[0].value).toBeNull();
    expect(wire?.conditionalFormats?.[0].value).toBe(0);
    expect(wire?.meta?.capabilityReasonCodes).toEqual(['UNKNOWN_FIELD']);
    expect(wire?.meta?.basePageKey).toBe('ab_team_list');
  });
  it('supports persisted uppercase view/sort/logic tokens without inventing a different view kind', () => {
    const result = savedViewForView({
      ...view,
      viewType: 'KANBAN',
      viewConfig: {
        sorts: [{ fieldCode: 'name', direction: 'DESC' }],
        filters: [{ fieldCode: 'status', operator: 'eq', value: 'open', logic: 'or' }],
      },
    });
    expect(result.viewType).toBe('kanban');
    expect(result.viewConfig?.sorts?.[0].direction).toBe('desc');
    expect(result.viewConfig?.filters?.[0].logic).toBe('OR');
  });
  it('preserves localized labels, grouping and collaborator identities', () => {
    const result = savedViewConfigForView({
      cardFields: [{ field: 'name', label: { 'zh-CN': '名称', 'en-US': 'Name' } }],
      groupBy: [
        {
          fieldCode: 'status',
          collapsed: false,
          aggregations: [{ fieldCode: 'cost', function: 'sum' }],
        },
      ],
      meta: {
        collaborators: [{ principalPid: 'user-1', permission: 'save', principalType: 'user' }],
      },
    });
    expect(result?.cardFields?.[0].label).toEqual({ 'zh-CN': '名称', 'en-US': 'Name' });
    expect(result?.groupBy?.[0].collapsed).toBe(false);
    expect(result?.meta?.collaborators?.[0]).toMatchObject({
      principalPid: 'user-1',
      permission: 'save',
    });
  });
  it('encodes dynamic filter operands explicitly while preserving JSON containers and Date compatibility', () => {
    const operand = {
      from: new Date('2026-10-01T00:00:00Z'),
      to: undefined,
      values: [null, false, 0, ''],
    };
    const result = savedViewConfigToWire({
      filters: [{ fieldCode: 'created_at', operator: 'between', value: operand }],
    });
    expect(result?.filters?.[0].value).toEqual({
      from: '2026-10-01T00:00:00.000Z',
      values: [null, false, 0, ''],
    });
    expect(operand.from).toBeInstanceOf(Date);
  });
  it.each([NaN, Infinity, undefined, () => 1, new Map(), new Date('invalid')])(
    'rejects a non-JSON operand instead of silently replacing it: %s',
    (value) => {
      expect(() => savedViewJson(value)).toThrow();
    },
  );
  it('rejects cyclic operands and does not reject a repeated non-cyclic JSON object', () => {
    const repeated = { value: null };
    expect(savedViewJson([repeated, repeated])).toEqual([{ value: null }, { value: null }]);
    const cyclic: Record<string, unknown> = {};
    cyclic.self = cyclic;
    expect(() => savedViewJson(cyclic)).toThrow('circular');
  });
  it('rejects unsupported identities and GUI settings instead of presenting empty or fabricated success', () => {
    expect(() => savedViewForView({ ...view, pid: '' })).toThrow('identity');
    expect(() => savedViewForView({ ...view, scope: 'unrecognized' })).toThrow('scope');
    expect(() =>
      savedViewConfigForView({ sorts: [{ fieldCode: 'name', direction: 'sideways' }] }),
    ).toThrow('sort direction');
    expect(() =>
      savedViewConfigForView({ meta: { collaborators: [{ permission: 'save' }] } }),
    ).toThrow('identity');
    expect(() =>
      savedViewConfigForView({ cardFields: [{ field: 'name', label: { en: 3 } }] }),
    ).toThrow('localized label');
  });
});
