import { renderHook, act } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { useMultiSubTable } from '../useMultiSubTable';
import type { SubTableDef, MultiSubTableOptions } from '../useMultiSubTable';

const colText: import('~/framework/meta/components/types').SubTableColumn = {
  field: 'name',
  label: 'Name',
  type: 'text',
  required: true,
};
const colNumber: import('~/framework/meta/components/types').SubTableColumn = {
  field: 'qty',
  label: 'Qty',
  type: 'number',
};

const tableDef: SubTableDef = {
  key: 'items',
  label: 'Items',
  columns: [colText, colNumber],
};

function makeHook(opts: Partial<MultiSubTableOptions> = {}) {
  return renderHook(() =>
    useMultiSubTable({
      tables: [tableDef],
      ...opts,
    }),
  );
}

describe('useMultiSubTable', () => {
  it('initializes with empty tables', () => {
    const { result } = makeHook();
    expect(result.current.tables['items']).toEqual([]);
    expect(result.current.isDirty).toBe(false);
    expect(result.current.submitting).toBe(false);
    expect(result.current.validationErrors).toEqual([]);
  });

  it('initializes with provided initialData', () => {
    const { result } = makeHook({
      initialData: { items: [{ name: 'A', qty: 1 }] },
    });
    expect(result.current.tables['items']).toHaveLength(1);
    expect(result.current.tables['items'][0].name).toBe('A');
  });

  it('addRow appends a new row with defaults and sets isDirty', () => {
    const { result } = makeHook();
    act(() => result.current.addRow('items'));
    expect(result.current.tables['items']).toHaveLength(1);
    expect(result.current.tables['items'][0].name).toBe('');
    expect(result.current.tables['items'][0].qty).toBeNull();
    expect(result.current.isDirty).toBe(true);
  });

  it('addRow respects maxRows limit', () => {
    const limitedTable: SubTableDef = { ...tableDef, maxRows: 1 };
    const { result } = renderHook(() =>
      useMultiSubTable({ tables: [limitedTable] }),
    );
    act(() => result.current.addRow('items'));
    act(() => result.current.addRow('items')); // should be ignored
    expect(result.current.tables['items']).toHaveLength(1);
  });

  it('removeRow removes the correct row', () => {
    const { result } = makeHook({
      initialData: {
        items: [
          { name: 'A', qty: 1 },
          { name: 'B', qty: 2 },
        ],
      },
    });
    act(() => result.current.removeRow('items', 0));
    expect(result.current.tables['items']).toHaveLength(1);
    expect(result.current.tables['items'][0].name).toBe('B');
    expect(result.current.isDirty).toBe(true);
  });

  it('removeRow respects minRows limit', () => {
    const limitedTable: SubTableDef = { ...tableDef, minRows: 1 };
    const { result } = renderHook(() =>
      useMultiSubTable({
        tables: [limitedTable],
        initialData: { items: [{ name: 'X', qty: 1 }] },
      }),
    );
    act(() => result.current.removeRow('items', 0));
    expect(result.current.tables['items']).toHaveLength(1);
  });

  it('updateCell updates the correct cell', () => {
    const { result } = makeHook({
      initialData: { items: [{ name: 'A', qty: 1 }] },
    });
    act(() => result.current.updateCell('items', 0, 'name', 'Z'));
    expect(result.current.tables['items'][0].name).toBe('Z');
    expect(result.current.isDirty).toBe(true);
  });

  it('setTableData replaces all rows', () => {
    const { result } = makeHook();
    act(() => result.current.setTableData('items', [{ name: 'X', qty: 9 }]));
    expect(result.current.tables['items']).toHaveLength(1);
    expect(result.current.tables['items'][0].name).toBe('X');
  });

  it('moveRow reorders rows correctly', () => {
    const { result } = makeHook({
      initialData: {
        items: [
          { name: 'A', qty: 1 },
          { name: 'B', qty: 2 },
          { name: 'C', qty: 3 },
        ],
      },
    });
    act(() => result.current.moveRow('items', 0, 2));
    expect(result.current.tables['items'].map((r: any) => r.name)).toEqual(['B', 'C', 'A']);
  });

  it('getRowCount returns current row count', () => {
    const { result } = makeHook();
    expect(result.current.getRowCount('items')).toBe(0);
    act(() => result.current.addRow('items'));
    expect(result.current.getRowCount('items')).toBe(1);
  });

  it('submit calls onSubmit and clears dirty flag on success', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const { result } = makeHook({ onSubmit });
    act(() => result.current.addRow('items'));
    // Fill required field
    act(() => result.current.updateCell('items', 0, 'name', 'Test'));
    await act(async () => {
      const res = await result.current.submit({});
      expect(res.success).toBe(true);
    });
    expect(onSubmit).toHaveBeenCalledOnce();
    expect(result.current.isDirty).toBe(false);
  });

  it('submit returns error when required field is empty', async () => {
    const onSubmit = vi.fn();
    const { result } = makeHook({ onSubmit });
    act(() => result.current.addRow('items'));
    // name is required but left empty
    await act(async () => {
      const res = await result.current.submit({});
      expect(res.success).toBe(false);
      expect(res.errors.length).toBeGreaterThan(0);
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submit calls onValidate and halts on validation error', async () => {
    const onValidate = vi.fn().mockReturnValue(['Custom error']);
    const onSubmit = vi.fn();
    const { result } = makeHook({ onSubmit, onValidate });
    await act(async () => {
      const res = await result.current.submit({});
      expect(res.success).toBe(false);
      expect(res.errors).toContain('Custom error');
    });
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('submit without onSubmit returns success when validation passes', async () => {
    const { result } = makeHook();
    // No rows means no required-field violations
    await act(async () => {
      const res = await result.current.submit({});
      expect(res.success).toBe(true);
    });
  });

  it('reset restores tables to initial state and clears dirty', () => {
    const { result } = makeHook({
      initialData: { items: [{ name: 'A', qty: 1 }] },
    });
    act(() => result.current.addRow('items'));
    expect(result.current.tables['items']).toHaveLength(2);
    act(() => result.current.reset());
    expect(result.current.tables['items']).toHaveLength(1);
    expect(result.current.isDirty).toBe(false);
  });

  it('submit handles onSubmit rejection and returns error', async () => {
    const onSubmit = vi.fn().mockRejectedValue(new Error('Server error'));
    const { result } = makeHook({ onSubmit });
    act(() => result.current.addRow('items'));
    act(() => result.current.updateCell('items', 0, 'name', 'Valid'));
    await act(async () => {
      const res = await result.current.submit({});
      expect(res.success).toBe(false);
      expect(res.errors[0]).toBe('Server error');
    });
  });
});
