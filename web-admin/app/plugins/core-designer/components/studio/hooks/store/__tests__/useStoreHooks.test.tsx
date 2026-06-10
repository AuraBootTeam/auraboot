/**
 * Unit tests for store/index.ts hooks:
 *   useStateHistory, useComponentStateBatch, useStatePersistence,
 *   useStateValidation, useStateSnapshot
 *
 * useStateContext is mocked to avoid requiring a real StateProvider.
 */

import React from 'react';
import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { PageStateManager, type PageState } from '../PageStateManager';

// ---------------------------------------------------------------------------
// Mock useStateContext before importing hook consumers
// ---------------------------------------------------------------------------

const mockStateManager = new PageStateManager();

// Track event listeners registered via .on()
const listeners: Record<string, Array<(e: any) => void>> = {};

vi.spyOn(mockStateManager, 'on').mockImplementation((event, handler) => {
  if (!listeners[event]) listeners[event] = [];
  listeners[event].push(handler);
  return () => {};
});

vi.spyOn(mockStateManager, 'off').mockImplementation((event, handler) => {
  if (listeners[event]) {
    listeners[event] = listeners[event].filter((h) => h !== handler);
  }
});

const mockSubscribe = vi.fn((_event: string, _cb: any) => () => {});

const mockContextValue = {
  get state() {
    return mockStateManager.getState();
  },
  stateManager: mockStateManager,
  subscribe: mockSubscribe,
};

vi.mock('../StateProvider', () => ({
  useStateContext: () => mockContextValue,
}));

// ---------------------------------------------------------------------------
// Imports after mocks
// ---------------------------------------------------------------------------

import {
  useStateHistory,
  useComponentStateBatch,
  useStatePersistence,
  useStateValidation,
  useStateSnapshot,
} from '../index';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function fireStateChange(payload?: any) {
  const stateChangeListeners = listeners['stateChange'] || [];
  stateChangeListeners.forEach((fn) => fn({ type: 'stateChange', timestamp: Date.now(), payload }));
}

// ---------------------------------------------------------------------------
// useStateHistory
// ---------------------------------------------------------------------------

describe('useStateHistory', () => {
  beforeEach(() => {
    vi.spyOn(mockStateManager, 'getHistory').mockReturnValue({
      canUndo: false,
      canRedo: false,
      size: 0,
    });
    vi.clearAllMocks();
  });

  it('exposes canUndo, canRedo, historySize', () => {
    const { result } = renderHook(() => useStateHistory());
    expect(result.current.canUndo).toBe(false);
    expect(result.current.canRedo).toBe(false);
    expect(result.current.historySize).toBe(0);
  });

  it('undo calls stateManager.undo', () => {
    const undoSpy = vi.spyOn(mockStateManager, 'undo');
    const { result } = renderHook(() => useStateHistory());
    act(() => result.current.undo());
    expect(undoSpy).toHaveBeenCalledOnce();
  });

  it('redo calls stateManager.redo', () => {
    const redoSpy = vi.spyOn(mockStateManager, 'redo');
    const { result } = renderHook(() => useStateHistory());
    act(() => result.current.redo());
    expect(redoSpy).toHaveBeenCalledOnce();
  });

  it('clearHistory calls stateManager.clearHistory', () => {
    const clearSpy = vi.spyOn(mockStateManager, 'clearHistory');
    const { result } = renderHook(() => useStateHistory());
    act(() => result.current.clearHistory());
    expect(clearSpy).toHaveBeenCalledOnce();
  });
});

// ---------------------------------------------------------------------------
// useComponentStateBatch
// ---------------------------------------------------------------------------

describe('useComponentStateBatch', () => {
  it('batchUpdateComponents delegates to stateManager', () => {
    const spy = vi.spyOn(mockStateManager, 'batchUpdateComponents');
    const { result } = renderHook(() => useComponentStateBatch());
    const updates = [{ componentId: 'c1', state: { type: 'TextInput', props: {} } }];
    act(() => result.current.batchUpdateComponents(updates, 'test'));
    expect(spy).toHaveBeenCalledWith(updates, 'test');
  });

  it('batchRemoveComponents calls removeComponentState for each id', () => {
    const spy = vi.spyOn(mockStateManager, 'removeComponentState');
    const { result } = renderHook(() => useComponentStateBatch());
    act(() => result.current.batchRemoveComponents(['c1', 'c2'], 'test'));
    expect(spy).toHaveBeenCalledTimes(2);
    expect(spy).toHaveBeenCalledWith('c1', 'test');
    expect(spy).toHaveBeenCalledWith('c2', 'test');
  });

  it('getAllComponentStates returns the components map', () => {
    vi.spyOn(mockStateManager, 'getState').mockReturnValue({
      components: { c1: { type: 'Button', props: {} } },
    });
    const { result } = renderHook(() => useComponentStateBatch());
    const all = result.current.getAllComponentStates();
    expect(all).toHaveProperty('c1');
  });

  it('getComponentsByType filters by type', () => {
    vi.spyOn(mockStateManager, 'getState').mockReturnValue({
      components: {
        a: { type: 'TextInput', props: {} },
        b: { type: 'Button', props: {} },
        c: { type: 'TextInput', props: {} },
      },
    });
    const { result } = renderHook(() => useComponentStateBatch());
    const textInputs = result.current.getComponentsByType('TextInput');
    expect(Object.keys(textInputs)).toHaveLength(2);
    expect(Object.keys(textInputs)).toContain('a');
    expect(Object.keys(textInputs)).toContain('c');
  });
});

// ---------------------------------------------------------------------------
// useStatePersistence
// ---------------------------------------------------------------------------

describe('useStatePersistence', () => {
  const KEY = 'designer-state-test';

  beforeEach(() => {
    localStorage.clear();
    vi.spyOn(mockStateManager, 'serialize').mockReturnValue('{"components":{}}');
    vi.spyOn(mockStateManager, 'deserialize').mockImplementation(() => {});
  });

  it('saveState persists serialized state to localStorage', () => {
    const { result } = renderHook(() => useStatePersistence(KEY));
    act(() => result.current.saveState());
    expect(localStorage.getItem(KEY)).toBe('{"components":{}}');
  });

  it('loadState calls stateManager.deserialize with stored data', () => {
    localStorage.setItem(KEY, '{"components":{}}');
    const { result } = renderHook(() => useStatePersistence(KEY));
    act(() => result.current.loadState());
    expect(mockStateManager.deserialize).toHaveBeenCalledWith('{"components":{}}');
  });

  it('hasSavedState returns false for a fresh key', () => {
    const freshKey = `designer-state-fresh-${Date.now()}`;
    const { result } = renderHook(() => useStatePersistence(freshKey));
    expect(result.current.hasSavedState()).toBe(false);
  });

  it('clearSavedState removes the key from localStorage', () => {
    localStorage.setItem(KEY, 'data');
    const { result } = renderHook(() => useStatePersistence(KEY));
    act(() => result.current.clearSavedState());
    expect(localStorage.getItem(KEY)).toBeNull();
  });

  it('hasSavedState returns false when nothing stored', () => {
    const { result } = renderHook(() => useStatePersistence(KEY));
    expect(result.current.hasSavedState()).toBe(false);
  });

  it('hasSavedState returns true when data exists', () => {
    localStorage.setItem(KEY, 'data');
    const { result } = renderHook(() => useStatePersistence(KEY));
    expect(result.current.hasSavedState()).toBe(true);
  });
});

// ---------------------------------------------------------------------------
// useStateValidation
// ---------------------------------------------------------------------------

describe('useStateValidation', () => {
  beforeEach(() => {
    vi.spyOn(mockStateManager, 'getState').mockReturnValue({ components: {} });
  });

  it('starts with empty validationErrors and isValid=true', () => {
    const { result } = renderHook(() => useStateValidation());
    expect(result.current.validationErrors).toEqual({});
    expect(result.current.isValid).toBe(true);
  });

  it('validateComponent returns no errors for a valid component', () => {
    const { result } = renderHook(() => useStateValidation());
    const errors = result.current.validateComponent('c1', { type: 'Button', props: {} });
    expect(errors).toHaveLength(0);
  });

  it('validateComponent returns an error when type is missing', () => {
    const { result } = renderHook(() => useStateValidation());
    const errors = result.current.validateComponent('c1', { type: '', props: {} });
    expect(errors.length).toBeGreaterThan(0);
    expect(errors[0]).toMatch(/类型/);
  });

  it('validateComponent returns errors for missing required props', () => {
    const { result } = renderHook(() => useStateValidation());
    const errors = result.current.validateComponent('c1', {
      type: 'TextInput',
      props: {},
      validation: { required: ['label'] },
    });
    expect(errors.some((e: string) => e.includes('label'))).toBe(true);
  });

  it('validateAllComponents aggregates errors across all components', () => {
    vi.spyOn(mockStateManager, 'getState').mockReturnValue({
      components: {
        bad1: { type: '', props: {} },
        bad2: { type: '', props: {} },
        good: { type: 'Button', props: {} },
      },
    });
    const { result } = renderHook(() => useStateValidation());
    let errors: Record<string, string[]>;
    act(() => {
      errors = result.current.validateAllComponents();
    });
    expect(Object.keys(errors!)).toContain('bad1');
    expect(Object.keys(errors!)).toContain('bad2');
    expect(Object.keys(errors!)).not.toContain('good');
  });

  it('getComponentErrors returns an empty array for unknown componentId', () => {
    const { result } = renderHook(() => useStateValidation());
    expect(result.current.getComponentErrors('unknown')).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// useStateSnapshot
// ---------------------------------------------------------------------------

describe('useStateSnapshot', () => {
  beforeEach(() => {
    vi.spyOn(mockStateManager, 'getState').mockReturnValue({ components: {} });
  });

  it('starts with empty snapshots list', () => {
    const { result } = renderHook(() => useStateSnapshot());
    expect(result.current.snapshots).toEqual([]);
  });

  it('createSnapshot adds a snapshot with the given name', () => {
    const { result } = renderHook(() => useStateSnapshot());
    let id: string;
    act(() => {
      id = result.current.createSnapshot('Before import');
    });
    expect(result.current.snapshots).toHaveLength(1);
    expect(result.current.snapshots[0].name).toBe('Before import');
    expect(result.current.snapshots[0].id).toBe(id!);
  });

  it('createSnapshot returns an id with snapshot- prefix', () => {
    const { result } = renderHook(() => useStateSnapshot());
    let id: string;
    act(() => {
      id = result.current.createSnapshot('snap-1');
    });
    expect(id!).toMatch(/^snapshot-/);
  });

  it('deleteSnapshot removes the matching snapshot', () => {
    const { result } = renderHook(() => useStateSnapshot());
    let id: string;
    act(() => {
      id = result.current.createSnapshot('to-delete');
    });
    act(() => {
      result.current.deleteSnapshot(id!);
    });
    expect(result.current.snapshots).toHaveLength(0);
  });

  it('clearSnapshots removes all snapshots', () => {
    const { result } = renderHook(() => useStateSnapshot());
    act(() => {
      result.current.createSnapshot('s1');
      result.current.createSnapshot('s2');
    });
    expect(result.current.snapshots).toHaveLength(2);
    act(() => {
      result.current.clearSnapshots();
    });
    expect(result.current.snapshots).toHaveLength(0);
  });

  it('restoreSnapshot calls stateManager.setState with snapshot state', () => {
    const setStateSpy = vi.spyOn(mockStateManager, 'setState');
    const { result } = renderHook(() => useStateSnapshot());
    let id: string;
    act(() => {
      id = result.current.createSnapshot('restore-me');
    });
    act(() => {
      result.current.restoreSnapshot(id!);
    });
    expect(setStateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ components: expect.any(Object) }),
      'snapshot-restore',
    );
  });

  it('restoreSnapshot does nothing for unknown snapshotId', () => {
    const setStateSpy = vi.spyOn(mockStateManager, 'setState');
    setStateSpy.mockClear(); // clear calls from prior tests
    const { result } = renderHook(() => useStateSnapshot());
    // hook starts with empty snapshots — restoring nonexistent id is a no-op
    act(() => {
      result.current.restoreSnapshot('nonexistent-id');
    });
    expect(setStateSpy).not.toHaveBeenCalled();
  });
});
