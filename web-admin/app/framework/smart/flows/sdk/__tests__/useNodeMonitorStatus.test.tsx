// web-admin/app/flow-designer-sdk/__tests__/useNodeMonitorStatus.test.tsx
import { describe, it, expect, beforeEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useFlowStore } from '../store/useFlowStore';
import { useNodeMonitorStatus } from '../hooks/useNodeMonitorStatus';
import type { FlowMonitorData } from '../store/monitorTypes';

describe('useNodeMonitorStatus (G8)', () => {
  beforeEach(() => {
    useFlowStore.getState().reset();
  });

  it('returns undefined when monitorMode is disabled, even if monitorData exists', () => {
    act(() => {
      useFlowStore
        .getState()
        .setMonitorData({ node_1: { status: 'running' } } satisfies FlowMonitorData);
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current).toBeUndefined();
  });

  it('returns undefined for a node id with no entry in monitorData', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({ other: { status: 'pending' } });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current).toBeUndefined();
  });

  it('returns the pending status for a node with monitorMode enabled', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({
        node_1: { status: 'pending', message: 'queued' },
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current?.status).toBe('pending');
    expect(result.current?.message).toBe('queued');
  });

  it('reacts when status transitions running → completed (reactive subscription)', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({
        node_1: { status: 'running', updatedAt: 100 },
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current?.status).toBe('running');
    act(() => {
      useFlowStore.getState().setMonitorData({
        node_1: { status: 'completed', updatedAt: 200 },
      });
    });
    expect(result.current?.status).toBe('completed');
    expect(result.current?.updatedAt).toBe(200);
  });

  it('surfaces failed status with a message + meta payload', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({
        node_1: {
          status: 'failed',
          message: 'boom',
          meta: { instanceId: 'inst-42', errorCode: 'E_BOOM' },
        },
      });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current?.status).toBe('failed');
    expect(result.current?.message).toBe('boom');
    expect(result.current?.meta?.instanceId).toBe('inst-42');
  });

  it('returns undefined for a null/undefined nodeId regardless of monitorMode', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({ '': { status: 'running' } });
    });
    const { result } = renderHook(() => useNodeMonitorStatus(null));
    expect(result.current).toBeUndefined();
  });

  it('clears monitor state on store reset()', () => {
    act(() => {
      useFlowStore.getState().setMonitorMode(true);
      useFlowStore.getState().setMonitorData({ node_1: { status: 'running' } });
    });
    const { result } = renderHook(() => useNodeMonitorStatus('node_1'));
    expect(result.current?.status).toBe('running');
    act(() => {
      useFlowStore.getState().reset();
    });
    expect(result.current).toBeUndefined();
  });
});
