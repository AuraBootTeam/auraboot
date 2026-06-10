/**
 * Unit tests for useDesignerShortcuts hook and SHORTCUTS constant / getShortcutDisplay util.
 *
 * The hook wires keyboard events on `document`. We mock:
 *   - useCanvasEditorState (Zustand store)
 *   - useClipboard (clipboard service hook)
 */

import { renderHook, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach } from 'vitest';

// ---- Mock dependencies before importing the hook ----
const mockSelectComponent = vi.fn();
const mockSelectedComponentId = { current: null as string | null };

vi.mock(
  '~/plugins/core-designer/components/studio/hooks/store/useCanvasEditorState',
  () => ({
    useCanvasEditorState: () => ({
      get selectedComponentId() {
        return mockSelectedComponentId.current;
      },
      selectComponent: mockSelectComponent,
    }),
  }),
);

const mockCopy = vi.fn();
const mockCut = vi.fn();
const mockPaste = vi.fn();
const mockDuplicate = vi.fn();

vi.mock('~/plugins/core-designer/components/studio/services/clipboard', () => ({
  useClipboard: () => ({
    copy: mockCopy,
    cut: mockCut,
    paste: mockPaste,
    duplicate: mockDuplicate,
    hasContent: false,
    contentCount: 0,
    isCut: vi.fn(() => false),
  }),
}));

import {
  useDesignerShortcuts,
  SHORTCUTS,
  getShortcutDisplay,
  type ShortcutDefinition,
} from '../useDesignerShortcuts';

// ---------------------------------------------------------------------------
// Helper: fire a keyboard event.
// We dispatch from document.body so e.target is an HTMLElement with tagName,
// and it bubbles up to document where the listener is registered.
// ---------------------------------------------------------------------------
function fireKey(
  key: string,
  opts: { ctrlKey?: boolean; metaKey?: boolean; shiftKey?: boolean } = {},
) {
  const event = new KeyboardEvent('keydown', {
    key,
    bubbles: true,
    cancelable: true,
    ctrlKey: opts.ctrlKey ?? false,
    metaKey: opts.metaKey ?? false,
    shiftKey: opts.shiftKey ?? false,
  });
  document.body.dispatchEvent(event);
}

beforeEach(() => {
  vi.clearAllMocks();
  mockSelectedComponentId.current = null;
});

// ---------------------------------------------------------------------------
// SHORTCUTS constant
// ---------------------------------------------------------------------------
describe('SHORTCUTS', () => {
  it('contains expected number of shortcuts', () => {
    expect(SHORTCUTS.length).toBeGreaterThan(0);
  });

  it('includes Save (Ctrl+S)', () => {
    const save = SHORTCUTS.find((s) => s.key === 's' && s.ctrl);
    expect(save).toBeDefined();
    expect(save?.category).toBe('edit');
  });

  it('includes Delete shortcut', () => {
    const del = SHORTCUTS.find((s) => s.key === 'Delete');
    expect(del).toBeDefined();
    expect(del?.category).toBe('edit');
  });

  it('includes Escape shortcut (Deselect)', () => {
    const esc = SHORTCUTS.find((s) => s.key === 'Escape');
    expect(esc).toBeDefined();
    expect(esc?.category).toBe('selection');
  });
});

// ---------------------------------------------------------------------------
// getShortcutDisplay
// ---------------------------------------------------------------------------
describe('getShortcutDisplay', () => {
  it('formats ctrl+s correctly', () => {
    const shortcut: ShortcutDefinition = {
      key: 's',
      ctrl: true,
      description: 'Save',
      category: 'edit',
    };
    expect(getShortcutDisplay(shortcut)).toBe('⌘S');
  });

  it('formats ctrl+shift+z correctly', () => {
    const shortcut: ShortcutDefinition = {
      key: 'z',
      ctrl: true,
      shift: true,
      description: 'Redo',
      category: 'edit',
    };
    expect(getShortcutDisplay(shortcut)).toBe('⌘⇧Z');
  });

  it('formats plain key (no modifiers)', () => {
    const shortcut: ShortcutDefinition = {
      key: 'Escape',
      description: 'Deselect',
      category: 'selection',
    };
    expect(getShortcutDisplay(shortcut)).toBe('ESCAPE');
  });

  it('includes alt modifier', () => {
    const shortcut: ShortcutDefinition = {
      key: 'x',
      alt: true,
      description: 'Alt-X',
      category: 'edit',
    };
    expect(getShortcutDisplay(shortcut)).toBe('⌥X');
  });
});

// ---------------------------------------------------------------------------
// useDesignerShortcuts — event registration and dispatch
// ---------------------------------------------------------------------------
describe('useDesignerShortcuts', () => {
  it('registers document keydown listener on mount', () => {
    const spy = vi.spyOn(document, 'addEventListener');
    renderHook(() => useDesignerShortcuts({ enabled: true }));
    expect(spy).toHaveBeenCalledWith('keydown', expect.any(Function));
    spy.mockRestore();
  });

  it('removes document keydown listener on unmount', () => {
    const spy = vi.spyOn(document, 'removeEventListener');
    const { unmount } = renderHook(() => useDesignerShortcuts({ enabled: true }));
    unmount();
    expect(spy).toHaveBeenCalledWith('keydown', expect.any(Function));
    spy.mockRestore();
  });

  it('does not register listener when enabled=false', () => {
    const spy = vi.spyOn(document, 'addEventListener');
    renderHook(() => useDesignerShortcuts({ enabled: false }));
    const keydownCalls = spy.mock.calls.filter(([event]) => event === 'keydown');
    expect(keydownCalls.length).toBe(0);
    spy.mockRestore();
  });

  // ---- Ctrl shortcuts ----

  it('calls onSave on Ctrl+S', () => {
    const onSave = vi.fn();
    renderHook(() => useDesignerShortcuts({ onSave }));
    act(() => {
      fireKey('s', { ctrlKey: true });
    });
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('calls onUndo on Ctrl+Z', () => {
    const onUndo = vi.fn();
    renderHook(() => useDesignerShortcuts({ onUndo }));
    act(() => {
      fireKey('z', { ctrlKey: true });
    });
    expect(onUndo).toHaveBeenCalledTimes(1);
  });

  it('calls onRedo on Ctrl+Shift+Z', () => {
    const onRedo = vi.fn();
    renderHook(() => useDesignerShortcuts({ onRedo }));
    act(() => {
      fireKey('z', { ctrlKey: true, shiftKey: true });
    });
    expect(onRedo).toHaveBeenCalledTimes(1);
  });

  it('calls onRedo on Ctrl+Y', () => {
    const onRedo = vi.fn();
    renderHook(() => useDesignerShortcuts({ onRedo }));
    act(() => {
      fireKey('y', { ctrlKey: true });
    });
    expect(onRedo).toHaveBeenCalledTimes(1);
  });

  it('calls copy() on Ctrl+C', () => {
    renderHook(() => useDesignerShortcuts({}));
    act(() => {
      fireKey('c', { ctrlKey: true });
    });
    expect(mockCopy).toHaveBeenCalledTimes(1);
  });

  it('calls cut() on Ctrl+X', () => {
    renderHook(() => useDesignerShortcuts({}));
    act(() => {
      fireKey('x', { ctrlKey: true });
    });
    expect(mockCut).toHaveBeenCalledTimes(1);
  });

  it('calls paste() on Ctrl+V', () => {
    renderHook(() => useDesignerShortcuts({}));
    act(() => {
      fireKey('v', { ctrlKey: true });
    });
    expect(mockPaste).toHaveBeenCalledTimes(1);
  });

  it('calls duplicate() on Ctrl+D', () => {
    renderHook(() => useDesignerShortcuts({}));
    act(() => {
      fireKey('d', { ctrlKey: true });
    });
    expect(mockDuplicate).toHaveBeenCalledTimes(1);
  });

  it('calls onZoomIn on Ctrl+=', () => {
    const onZoomIn = vi.fn();
    renderHook(() => useDesignerShortcuts({ onZoomIn }));
    act(() => {
      fireKey('=', { ctrlKey: true });
    });
    expect(onZoomIn).toHaveBeenCalledTimes(1);
  });

  it('calls onZoomOut on Ctrl+-', () => {
    const onZoomOut = vi.fn();
    renderHook(() => useDesignerShortcuts({ onZoomOut }));
    act(() => {
      fireKey('-', { ctrlKey: true });
    });
    expect(onZoomOut).toHaveBeenCalledTimes(1);
  });

  it('calls onZoomReset on Ctrl+0', () => {
    const onZoomReset = vi.fn();
    renderHook(() => useDesignerShortcuts({ onZoomReset }));
    act(() => {
      fireKey('0', { ctrlKey: true });
    });
    expect(onZoomReset).toHaveBeenCalledTimes(1);
  });

  // ---- Select-all ----

  it('selects first component on Ctrl+A when allComponentIds is provided', () => {
    renderHook(() =>
      useDesignerShortcuts({ allComponentIds: ['comp-1', 'comp-2'] }),
    );
    act(() => {
      fireKey('a', { ctrlKey: true });
    });
    expect(mockSelectComponent).toHaveBeenCalledWith('comp-1');
  });

  it('does not call selectComponent on Ctrl+A when allComponentIds is empty', () => {
    renderHook(() => useDesignerShortcuts({ allComponentIds: [] }));
    act(() => {
      fireKey('a', { ctrlKey: true });
    });
    expect(mockSelectComponent).not.toHaveBeenCalled();
  });

  // ---- Delete ----

  it('calls onRemoveComponent and clears selection on Delete key', () => {
    const onRemoveComponent = vi.fn();
    mockSelectedComponentId.current = 'comp-selected';
    renderHook(() => useDesignerShortcuts({ onRemoveComponent }));
    act(() => {
      fireKey('Delete');
    });
    expect(onRemoveComponent).toHaveBeenCalledWith('comp-selected');
    expect(mockSelectComponent).toHaveBeenCalledWith(null);
  });

  it('does not call onRemoveComponent on Delete when nothing selected', () => {
    const onRemoveComponent = vi.fn();
    mockSelectedComponentId.current = null;
    renderHook(() => useDesignerShortcuts({ onRemoveComponent }));
    act(() => {
      fireKey('Delete');
    });
    expect(onRemoveComponent).not.toHaveBeenCalled();
  });

  it('calls onRemoveComponent on Backspace key', () => {
    const onRemoveComponent = vi.fn();
    mockSelectedComponentId.current = 'comp-abc';
    renderHook(() => useDesignerShortcuts({ onRemoveComponent }));
    act(() => {
      fireKey('Backspace');
    });
    expect(onRemoveComponent).toHaveBeenCalledWith('comp-abc');
  });

  // ---- Escape ----

  it('clears selection on Escape', () => {
    renderHook(() => useDesignerShortcuts({}));
    act(() => {
      fireKey('Escape');
    });
    expect(mockSelectComponent).toHaveBeenCalledWith(null);
  });

  // ---- Input element guard ----

  it('ignores shortcuts when event target is an input element', () => {
    const onSave = vi.fn();
    renderHook(() => useDesignerShortcuts({ onSave }));

    const input = document.createElement('input');
    document.body.appendChild(input);
    const event = new KeyboardEvent('keydown', {
      key: 's',
      ctrlKey: true,
      bubbles: true,
      cancelable: true,
    });
    Object.defineProperty(event, 'target', { value: input });
    act(() => {
      document.dispatchEvent(event);
    });
    document.body.removeChild(input);

    // onSave should NOT be called because target is an input
    expect(onSave).not.toHaveBeenCalled();
  });

  // ---- metaKey (Mac ⌘) works the same as ctrlKey ----

  it('calls onSave on Meta+S', () => {
    const onSave = vi.fn();
    renderHook(() => useDesignerShortcuts({ onSave }));
    act(() => {
      fireKey('s', { metaKey: true });
    });
    expect(onSave).toHaveBeenCalledTimes(1);
  });
});
