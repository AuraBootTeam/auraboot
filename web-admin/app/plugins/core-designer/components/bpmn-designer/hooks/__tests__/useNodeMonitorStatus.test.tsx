/**
 * B2c Phase 3 batch1 — useNodeMonitorStatus migration test.
 *
 * Verifies that after the import-path migration from `useBPMNStore` →
 * `useBpmFlowStore`, the hook still resolves `null` when not in monitor
 * mode and the three status values when monitor data is set via the
 * adapter's `.setState()` shim (the pattern ProcessStatusViewer uses).
 *
 * This also exercises the adapter's cross-store React subscription path
 * (useSyncExternalStore + subscribeBoth): the hook reads from the
 * BPMN-only sidecar store, and a `.setState({ viewMode, instanceStatus })`
 * call must trigger a re-render in components that selected those keys.
 */

import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useNodeMonitorStatus } from '../useNodeMonitorStatus';
import { useBpmFlowStore } from '~/plugins/core-designer/components/bpm-designer-sdk/store/useBpmFlowStore';

describe('useNodeMonitorStatus (B2c phase3 batch1 — via useBpmFlowStore)', () => {
  beforeEach(() => {
    useBpmFlowStore.setState({
      viewMode: 'design',
      instanceStatus: null,
    });
  });

  it('returns null when not in monitor mode', () => {
    const { result } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBeNull();
  });

  it('returns null when in monitor mode but instanceStatus is null', () => {
    act(() => {
      useBpmFlowStore.setState({ viewMode: 'monitor', instanceStatus: null });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBeNull();
  });

  it('returns "active" when node is in currentNodes', async () => {
    act(() => {
      useBpmFlowStore.setState({
        viewMode: 'monitor',
        instanceStatus: {
          currentNodes: [{ nodeId: 'node-1' }],
          completedNodes: [],
        } as any,
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBe('active');
  });

  it('returns "completed" when node is in completedNodes', () => {
    act(() => {
      useBpmFlowStore.setState({
        viewMode: 'monitor',
        instanceStatus: {
          currentNodes: [],
          completedNodes: [{ nodeId: 'node-1' }],
        } as any,
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBe('completed');
  });

  it('returns "idle" when node is neither current nor completed', () => {
    act(() => {
      useBpmFlowStore.setState({
        viewMode: 'monitor',
        instanceStatus: {
          currentNodes: [{ nodeId: 'other-a' }],
          completedNodes: [{ nodeId: 'other-b' }],
        } as any,
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBe('idle');
  });

  it('re-renders when .setState({ instanceStatus }) flips status (cross-store subscription gate)', async () => {
    const { result, rerender } = renderHook(() => useNodeMonitorStatus('node-1'));
    expect(result.current).toBeNull();

    act(() => {
      useBpmFlowStore.setState({
        viewMode: 'monitor',
        instanceStatus: { currentNodes: [{ nodeId: 'node-1' }], completedNodes: [] } as any,
      });
    });
    // useSyncExternalStore schedules via queueMicrotask in the adapter; wait one tick
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    rerender();
    expect(result.current).toBe('active');

    act(() => {
      useBpmFlowStore.setState({
        instanceStatus: { currentNodes: [], completedNodes: [{ nodeId: 'node-1' }] } as any,
      });
    });
    await act(async () => {
      await Promise.resolve();
      await Promise.resolve();
    });
    rerender();
    expect(result.current).toBe('completed');
  });
});
