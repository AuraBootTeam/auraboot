import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useCrossColumnDrag } from '~/studio/hooks/drag/useCrossColumnDrag';
import type { ResizeTarget } from '~/studio/services/layout/resize/CrossColumnDragEngine';

const createContainer = (): HTMLElement => {
  const container = document.createElement('div');
  container.style.width = '400px';
  container.style.height = '400px';
  document.body.appendChild(container);
  return container;
};

const createTarget = (id: string, container: HTMLElement): ResizeTarget => {
  const element = document.createElement('div');
  element.style.width = '100px';
  element.style.height = '80px';
  container.appendChild(element);

  return {
    id,
    element,
    gridArea: { columnStart: 1, columnEnd: 2, rowStart: 1, rowEnd: 2 },
    minWidth: 50,
    minHeight: 40,
    resizable: { column: true, row: true },
  };
};

describe('useCrossColumnDrag hook', () => {
  beforeEach(() => {
    document.body.innerHTML = '';
  });

  it('manages targets through add/remove actions', () => {
    const container = createContainer();
    const target = createTarget('target-1', container);

    const { result, unmount } = renderHook(() =>
      useCrossColumnDrag({ container, autoUpdateTargets: true }),
    );

    act(() => {
      result.current.actions.addTarget(target);
    });
    expect(result.current.state.targets).toHaveLength(1);

    act(() => {
      result.current.actions.removeTarget(target.id);
    });
    expect(result.current.state.targets).toHaveLength(0);

    unmount();
  });

  it('exposes accessors for engine state', () => {
    const container = createContainer();
    const target = createTarget('target-2', container);

    const { result, unmount } = renderHook(() =>
      useCrossColumnDrag({ container, autoUpdateTargets: true }),
    );

    act(() => {
      result.current.actions.addTarget(target);
    });

    expect(result.current.actions.getTargets()).toHaveLength(1);
    expect(result.current.actions.getCurrentOperation()).toBeNull();

    unmount();
  });
});
