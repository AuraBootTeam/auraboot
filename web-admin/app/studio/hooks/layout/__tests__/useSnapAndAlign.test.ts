import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSnapAndAlign } from '~/studio/hooks/layout/useSnapAndAlign';

const createContainer = (): HTMLElement => {
  const div = document.createElement('div');
  div.style.width = '400px';
  div.style.height = '400px';
  document.body.appendChild(div);
  return div;
};

describe('useSnapAndAlign hook', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('initializes state and allows start/end', () => {
    const container = createContainer();
    const ref = { current: container };

    const { result, unmount } = renderHook(() => useSnapAndAlign(ref));

    act(() => {
      result.current[1].start();
      result.current[1].end();
    });

    expect(result.current[0].isSnapping).toBe(false);
    expect(result.current[0].isAligning).toBe(false);

    unmount();
  });

  it('updates configuration via updateConfig', () => {
    const container = createContainer();
    const ref = { current: container };

    const { result, unmount } = renderHook(() => useSnapAndAlign(ref));

    act(() => {
      result.current[1].updateConfig({ priority: 'snap' });
    });

    act(() => {
      result.current[1].start();
      result.current[1].end();
    });

    expect(result.current[0].finalPosition).toBeNull();
    unmount();
  });
});
