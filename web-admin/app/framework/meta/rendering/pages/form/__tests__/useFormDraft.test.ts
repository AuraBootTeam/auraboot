import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { draftKey } from '../formDraftStore';
import { useFormDraft } from '../useFormDraft';

function createFakeStorage(seed: Record<string, string> = {}): Storage {
  const map = new Map<string, string>(Object.entries(seed));
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (key: string) => (map.has(key) ? map.get(key)! : null),
    key: (index: number) => Array.from(map.keys())[index] ?? null,
    removeItem: (key: string) => {
      map.delete(key);
    },
    setItem: (key: string, value: string) => {
      map.set(key, value);
    },
  };
}

const KEY = draftKey('crm_account', 'crm_account_form');

describe('useFormDraft', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });
  afterEach(() => {
    vi.runOnlyPendingTimers();
    vi.useRealTimers();
  });

  it('detects an existing, differing draft on mount and exposes it as restorable', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: Date.now(), values: { name: 'Acme' } }),
    });
    const { result } = renderHook(() =>
      useFormDraft({
        enabled: true,
        modelCode: 'crm_account',
        pageKey: 'crm_account_form',
        values: {},
        initialValues: {},
        storage,
      }),
    );
    expect(result.current.restorable).not.toBeNull();
    expect(result.current.restorable!.values).toEqual({ name: 'Acme' });
  });

  it('does NOT offer restore when the stored draft equals the current/initial values', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: Date.now(), values: { name: 'Acme' } }),
    });
    const { result } = renderHook(() =>
      useFormDraft({
        enabled: true,
        modelCode: 'crm_account',
        pageKey: 'crm_account_form',
        values: { name: 'Acme' },
        initialValues: { name: 'Acme' },
        storage,
      }),
    );
    expect(result.current.restorable).toBeNull();
  });

  it('debounce-saves meaningful values after the debounce window', () => {
    const storage = createFakeStorage();
    const props = {
      enabled: true,
      modelCode: 'crm_account',
      pageKey: 'crm_account_form',
      values: {} as Record<string, unknown>,
      initialValues: {},
      debounceMs: 500,
      storage,
    };
    const { rerender } = renderHook((p: typeof props) => useFormDraft(p), { initialProps: props });

    rerender({ ...props, values: { name: 'Acme' } });
    expect(storage.getItem(KEY)).toBeNull(); // not yet (debounced)

    act(() => {
      vi.advanceTimersByTime(500);
    });
    const stored = JSON.parse(storage.getItem(KEY)!);
    expect(stored.values).toEqual({ name: 'Acme' });
  });

  it('does not save when values are all-empty (no useless prompt later)', () => {
    const storage = createFakeStorage();
    const props = {
      enabled: true,
      modelCode: 'crm_account',
      pageKey: 'crm_account_form',
      values: { name: '' } as Record<string, unknown>,
      initialValues: {},
      debounceMs: 500,
      storage,
    };
    const { rerender } = renderHook((p: typeof props) => useFormDraft(p), { initialProps: props });
    rerender({ ...props, values: { name: '' } });
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(storage.getItem(KEY)).toBeNull();
  });

  it('restore() returns the draft values and dismisses the prompt', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: Date.now(), values: { name: 'Acme' } }),
    });
    const { result } = renderHook(() =>
      useFormDraft({
        enabled: true,
        modelCode: 'crm_account',
        pageKey: 'crm_account_form',
        values: {},
        initialValues: {},
        storage,
      }),
    );
    let restored: Record<string, unknown> | null = null;
    act(() => {
      restored = result.current.restore();
    });
    expect(restored).toEqual({ name: 'Acme' });
    expect(result.current.restorable).toBeNull();
  });

  it('discard() clears storage and dismisses the prompt', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: Date.now(), values: { name: 'Acme' } }),
    });
    const { result } = renderHook(() =>
      useFormDraft({
        enabled: true,
        modelCode: 'crm_account',
        pageKey: 'crm_account_form',
        values: {},
        initialValues: {},
        storage,
      }),
    );
    act(() => {
      result.current.discard();
    });
    expect(storage.getItem(KEY)).toBeNull();
    expect(result.current.restorable).toBeNull();
  });

  it('clearDraft() removes the saved draft (used on successful submit)', () => {
    const storage = createFakeStorage();
    const props = {
      enabled: true,
      modelCode: 'crm_account',
      pageKey: 'crm_account_form',
      values: { name: 'Acme' } as Record<string, unknown>,
      initialValues: {},
      debounceMs: 500,
      storage,
    };
    const { result, rerender } = renderHook((p: typeof props) => useFormDraft(p), {
      initialProps: props,
    });
    rerender({ ...props, values: { name: 'Acme Corp' } });
    act(() => {
      vi.advanceTimersByTime(500);
    });
    expect(storage.getItem(KEY)).not.toBeNull();
    act(() => {
      result.current.clearDraft();
    });
    expect(storage.getItem(KEY)).toBeNull();
  });

  it('is inert when disabled (no detection, no save)', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: Date.now(), values: { name: 'Acme' } }),
    });
    const props = {
      enabled: false,
      modelCode: 'crm_account',
      pageKey: 'crm_account_form',
      values: { name: 'changed' } as Record<string, unknown>,
      initialValues: {},
      debounceMs: 500,
      storage,
    };
    const { result, rerender } = renderHook((p: typeof props) => useFormDraft(p), {
      initialProps: props,
    });
    expect(result.current.restorable).toBeNull();
    rerender({ ...props, values: { name: 'changed again' } });
    act(() => {
      vi.advanceTimersByTime(500);
    });
    // disabled never overwrites the pre-existing stored draft
    expect(JSON.parse(storage.getItem(KEY)!).values).toEqual({ name: 'Acme' });
  });

  it('ignores an expired draft on mount', () => {
    const storage = createFakeStorage({
      [KEY]: JSON.stringify({ savedAt: 0, values: { name: 'Acme' } }),
    });
    vi.setSystemTime(25 * 60 * 60 * 1000); // 25h later
    const { result } = renderHook(() =>
      useFormDraft({
        enabled: true,
        modelCode: 'crm_account',
        pageKey: 'crm_account_form',
        values: {},
        initialValues: {},
        storage,
      }),
    );
    expect(result.current.restorable).toBeNull();
  });
});
