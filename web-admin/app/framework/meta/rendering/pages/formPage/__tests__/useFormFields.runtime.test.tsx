import React, { useState } from 'react';
import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
vi.mock('~/framework/meta/utils/createFieldRenderer', () => ({
  createFieldRenderer: () => vi.fn(),
}));
vi.mock('~/framework/meta/rendering/pages/form/useFormDraft', () => ({ useFormDraft: () => ({}) }));
import { useFormFields } from '../useFormFields';
describe('Nested form scoped state persistence', () => {
  it('bridges nested field changes and explicit clears into the submitted form without copying route metadata', () => {
    let listener: ((next: Record<string, unknown>) => void) | undefined;
    let current: Record<string, unknown> = { owner: 'Lin' };
    const dirtyFieldsRef = { current: new Set<string>() },
      clearFieldError = vi.fn();
    const unsubscribe = vi.fn();
    const runtime = {
      getScopeId: () => 'form',
      getStateManager: () => ({
        subscribe: (_scope: string, _selector: unknown, callback: typeof listener) => {
          listener = callback;
          return unsubscribe;
        },
      }),
    };
    const { unmount } = renderHook(() => {
      const [formData, setFormData] = useState(current);
      current = formData;
      return useFormFields({
        formData,
        setFormData,
        runtime,
        dirtyFieldsRef,
        clearFieldError,
        pageContext: {},
        fieldErrors: {},
        schema: { blocks: [] },
        initialFormData: {},
        clearFormDraftRef: { current: null },
        syncRuntimeFieldValue: vi.fn(),
        t: (s: string) => s,
      } as any);
    });
    act(() =>
      listener?.({ owner: 'Lin', services: ['models', 'rules'], mode: 'edit', pid: 'record-pid' }),
    );
    expect(current).toEqual({ owner: 'Lin', services: ['models', 'rules'] });
    expect(dirtyFieldsRef.current.has('services')).toBe(true);
    expect(clearFieldError).toHaveBeenCalledWith('services');
    const previous = current;
    act(() => listener?.({ ...current, mode: 'edit' }));
    expect(current).toBe(previous);
    act(() => listener?.({ owner: 'Lin', services: null }));
    expect(current.services).toBeNull();
    unmount();
    expect(unsubscribe).toHaveBeenCalled();
  });
});
