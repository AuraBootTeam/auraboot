import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi, beforeEach } from 'vitest';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { useInlineRowEdit } from '../useInlineRowEdit';
import { fetchResult } from '~/shared/services/http-client';
import type { UseInlineRowEditOptions } from '../useInlineRowEdit';
import type { SubTableConfig } from '~/framework/meta/schemas/types';

const mockFetchResult = vi.mocked(fetchResult);

const baseConfig: SubTableConfig = {
  childModel: 'order_item',
  parentField: 'order_id',
  columns: [
    { field: 'name', required: true, editable: true } as any,
    { field: 'qty', editable: true, min: 1, max: 100 } as any,
  ],
  commands: { update: 'order_item:update' },
};

const rows = [
  { pid: 'row1', name: 'Alpha', qty: 5 },
  { pid: 'row2', name: 'Beta', qty: 10 },
];

function makeHook(opts: Partial<UseInlineRowEditOptions> = {}) {
  return renderHook(() =>
    useInlineRowEdit({
      config: baseConfig,
      rows,
      token: 'tok',
      ...opts,
    }),
  );
}

describe('useInlineRowEdit', () => {
  beforeEach(() => {
    mockFetchResult.mockReset();
  });

  it('initializes with no editing state', () => {
    const { result } = makeHook();
    expect(result.current.editingRowId).toBeNull();
    expect(result.current.editingData).toEqual({});
    expect(result.current.isSaving).toBe(false);
    expect(result.current.fieldErrors).toEqual({});
  });

  it('startEdit sets editingRowId and copies editable fields', () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    expect(result.current.editingRowId).toBe('row1');
    expect(result.current.editingData.name).toBe('Alpha');
    expect(result.current.editingData.qty).toBe(5);
  });

  it('cancelEdit clears all editing state', () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    act(() => result.current.cancelEdit());
    expect(result.current.editingRowId).toBeNull();
    expect(result.current.editingData).toEqual({});
    expect(result.current.fieldErrors).toEqual({});
  });

  it('updateField updates a field value and clears its error', () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    // Manually inject an error
    act(() => {
      // Force validateRow to set error then clear with updateField
      result.current.updateField('name', '');
    });
    act(() => result.current.validateRow()); // sets required error
    act(() => result.current.updateField('name', 'New Name'));
    expect(result.current.editingData.name).toBe('New Name');
    expect(result.current.fieldErrors.name).toBeUndefined();
  });

  it('validateRow returns true when all required fields present', () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    let valid = false;
    act(() => {
      valid = result.current.validateRow();
    });
    expect(valid).toBe(true);
    expect(result.current.fieldErrors).toEqual({});
  });

  it('validateRow returns false and sets error when required field is empty', () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', { ...rows[0], name: '' }));
    let valid = true;
    act(() => {
      valid = result.current.validateRow();
    });
    expect(valid).toBe(false);
    expect(result.current.fieldErrors.name).toBeTruthy();
  });

  it('validateCrossRow returns error when sum exceeds lte threshold', () => {
    const configWithCrossRow: SubTableConfig = {
      ...baseConfig,
      crossRowRules: [
        {
          id: 'cr1',
          field: 'qty',
          aggregation: 'sum',
          operator: 'lte',
          value: 10,
          message: 'Total qty exceeds 10',
        },
      ],
    };
    const { result } = renderHook(() =>
      useInlineRowEdit({ config: configWithCrossRow, rows }),
    );
    let errors: string[] = [];
    act(() => {
      errors = result.current.validateCrossRow([{ qty: 8 }, { qty: 5 }]);
    });
    expect(errors).toHaveLength(1);
    expect(errors[0]).toContain('Total qty');
  });

  it('validateCrossRow passes when sum is within lte threshold', () => {
    const configWithCrossRow: SubTableConfig = {
      ...baseConfig,
      crossRowRules: [
        {
          id: 'cr1',
          field: 'qty',
          aggregation: 'sum',
          operator: 'lte',
          value: 100,
          message: 'Over budget',
        },
      ],
    };
    const { result } = renderHook(() =>
      useInlineRowEdit({ config: configWithCrossRow, rows }),
    );
    let errors: string[] = [];
    act(() => {
      errors = result.current.validateCrossRow([{ qty: 5 }, { qty: 10 }]);
    });
    expect(errors).toHaveLength(0);
  });

  it('saveRow returns false without editingRowId', async () => {
    const { result } = makeHook();
    let res = true;
    await act(async () => {
      res = await result.current.saveRow();
    });
    expect(res).toBe(false);
  });

  it('saveRow calls fetchResult and returns true on success', async () => {
    // ResultHelper.isSuccess checks String(code) === '0'
    mockFetchResult.mockResolvedValue({ code: '0', data: {} } as any);
    const onSaveSuccess = vi.fn();
    const { result } = makeHook({ onSaveSuccess });
    act(() => result.current.startEdit('row1', rows[0]));
    // Change a field so 'changed' set is non-empty
    act(() => result.current.updateField('name', 'Updated'));
    let res = false;
    await act(async () => {
      res = await result.current.saveRow();
    });
    expect(res).toBe(true);
    expect(mockFetchResult).toHaveBeenCalledOnce();
    expect(onSaveSuccess).toHaveBeenCalledOnce();
    expect(result.current.editingRowId).toBeNull();
  });

  it('saveRow returns true without API call when nothing changed', async () => {
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    // No fields changed
    let res = false;
    await act(async () => {
      res = await result.current.saveRow();
    });
    expect(res).toBe(true);
    expect(mockFetchResult).not.toHaveBeenCalled();
  });

  it('saveRow returns false on API error and sets _form error', async () => {
    mockFetchResult.mockRejectedValue(new Error('Network error'));
    const { result } = makeHook();
    act(() => result.current.startEdit('row1', rows[0]));
    act(() => result.current.updateField('name', 'Changed'));
    let res = true;
    await act(async () => {
      res = await result.current.saveRow();
    });
    expect(res).toBe(false);
    expect(result.current.fieldErrors._form).toBe('Network error');
  });

  it('isColumnEditable respects readOnly flag', () => {
    const { result } = makeHook();
    expect(result.current.isColumnEditable({ field: 'x', readOnly: true } as any)).toBe(false);
  });

  it('isColumnEditable respects editable=false', () => {
    const { result } = makeHook();
    expect(result.current.isColumnEditable({ field: 'x', editable: false } as any)).toBe(false);
  });

  it('isColumnEditable respects editableColumns allowlist', () => {
    const config: SubTableConfig = {
      ...baseConfig,
      editableColumns: ['name'],
    };
    const { result } = renderHook(() => useInlineRowEdit({ config, rows }));
    expect(result.current.isColumnEditable({ field: 'name' } as any)).toBe(true);
    expect(result.current.isColumnEditable({ field: 'qty' } as any)).toBe(false);
  });
});
