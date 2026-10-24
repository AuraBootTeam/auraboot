import { act, renderHook } from '@testing-library/react';
import { useState } from 'react';
import { describe, expect, it, vi } from 'vitest';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { useListSelectionBinding } from '../useListSelectionBinding';
import { createSelectionModel, toggleRow, type SelectionState } from '../selectionModel';

function fixture() {
  const state: Record<string, any> = {};
  const listeners = new Set<() => void>();
  const updateState = vi.fn((_scope, key, value) => {
    state[key] = value;
    listeners.forEach((listener) => listener());
  });
  const runtime = {
    getContext: () => ({ state }),
    getScopeId: () => 'page',
    getStateManager: () => ({
      updateState,
      getStore: () => ({
        subscribe: (f: () => void) => {
          listeners.add(f);
          return () => listeners.delete(f);
        },
      }),
    }),
    getDataSourceManager: () => ({ notifyStateChanged: vi.fn() }),
  } as unknown as SchemaRuntime;
  return { runtime, state, updateState, listeners };
}
const row = { pid: 'source1', row_version: 3, status: 'active' };
const tableBlock = { table: { selection: { mode: 'multiple', bind: 'selectedSources' } } };
function mount(f: ReturnType<typeof fixture>, initial = createSelectionModel()) {
  return renderHook(({ data = [row], loading = false, table = tableBlock } = {}) => {
    const [selectionState, setSelectionState] = useState<SelectionState>(initial);
    useListSelectionBinding({
      runtime: f.runtime,
      tableBlock: table,
      data,
      selectionState,
      setSelectionState,
      loading,
    });
    return { selectionState, setSelectionState };
  });
}
describe('list DSL selection binding', () => {
  it('publishes exact selected rows so toolbar target and version are available', () => {
    const f = fixture();
    const { result } = mount(f);
    expect(f.state.selectedSources).toEqual([]);
    act(() => result.current.setSelectionState((s: SelectionState) => toggleRow(s, row.pid)));
    expect(f.state.selectedSources).toEqual([row]);
    expect(f.state.selectedSources[0]).toBe(row);
    act(() => result.current.setSelectionState(createSelectionModel()));
    expect(f.state.selectedSources).toEqual([]);
  });
  it('uses refreshed records and physical versions, never cached selected copies', () => {
    const f = fixture();
    const { rerender } = mount(f, toggleRow(createSelectionModel(), row.pid));
    const latest = { ...row, row_version: 4 };
    rerender({ data: [latest] });
    expect(f.state.selectedSources).toEqual([latest]);
    expect(f.state.selectedSources[0]).toBe(latest);
  });
  it('clears actual list selection when a DSL flow clears runtime state', () => {
    const f = fixture();
    const { result } = mount(f, toggleRow(createSelectionModel(), row.pid));
    act(() => f.updateState('page', 'selectedSources', []));
    expect(result.current.selectionState.ids.size).toBe(0);
    expect(f.state.selectedSources).toEqual([]);
  });
  it('does not clear selections on unrelated runtime writes', () => {
    const f = fixture();
    const { result } = mount(f, toggleRow(createSelectionModel(), row.pid));
    act(() => f.updateState('page', 'unrelated', []));
    expect(result.current.selectionState.ids.has(row.pid)).toBe(true);
  });
  it('does not expose a partial cross-page selection as one selected row', () => {
    const f = fixture();
    const selected = toggleRow(toggleRow(createSelectionModel(), row.pid), 'otherPage');
    const { result } = mount(f, selected);
    expect(f.state.selectedSources).toEqual([]);
    expect(result.current.selectionState.ids.size).toBe(2);
  });
  it('does not expose all-matching selection as the current page', () => {
    const f = fixture();
    const { result } = mount(f, { mode: 'allMatching', ids: new Set() });
    expect(f.state.selectedSources).toEqual([]);
    expect(result.current.selectionState.mode).toBe('allMatching');
  });
  it('does not expose stale rows during loading or after removal', () => {
    const f = fixture();
    const { rerender } = mount(f, toggleRow(createSelectionModel(), row.pid));
    rerender({ data: [row], loading: true });
    expect(f.state.selectedSources).toEqual([]);
    rerender({ data: [], loading: false });
    expect(f.state.selectedSources).toEqual([]);
  });
  it('does not change unbound tables and unsubscribes when unmounted', () => {
    const f = fixture();
    const { rerender, unmount } = mount(f);
    rerender({ table: { table: { selection: { mode: 'multiple', bind: '' } } } });
    expect(f.listeners.size).toBe(0);
    const calls = f.updateState.mock.calls.length;
    rerender({ data: [row], table: { table: { selection: { mode: 'multiple', bind: '' } } } });
    expect(f.updateState).toHaveBeenCalledTimes(calls);
    unmount();
    expect(f.listeners.size).toBe(0);
  });
});
