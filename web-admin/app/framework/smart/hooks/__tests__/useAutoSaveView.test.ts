import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAutoSaveView } from '../useAutoSaveView';

describe('useAutoSaveView', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('cancels pending auto-save work when the owner unmounts', () => {
    const updateViewConfig = vi.fn();
    const hook = renderHook(() =>
      useAutoSaveView({
        currentView: null,
        updateViewConfig,
      }),
    );

    act(() => {
      hook.result.current.autoSave({ rowHeight: 'comfortable' as any });
    });
    hook.unmount();
    act(() => {
      vi.advanceTimersByTime(2000);
    });

    expect(updateViewConfig).not.toHaveBeenCalled();
  });
});
