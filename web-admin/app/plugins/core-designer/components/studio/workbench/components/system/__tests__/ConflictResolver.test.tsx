import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ConflictResolver } from '~/plugins/core-designer/components/studio/workbench/components/system/ConflictResolver';

const conflicts = [
  {
    id: 'c1',
    type: 'component_update' as const,
    componentId: 'cmp-1',
    localChange: { props: { text: 'local' } },
    remoteChange: { props: { text: 'remote' } },
    timestamp: Date.now(),
    user: { id: 'u2', name: 'Remote User' },
  },
];

describe('ConflictResolver (studio)', () => {
  it('renders conflicts and resolves them', () => {
    const onResolve = vi.fn();
    const onResolveAll = vi.fn();

    render(
      <ConflictResolver conflicts={conflicts} onResolve={onResolve} onResolveAll={onResolveAll} />,
    );

    expect(screen.getByText('解决协作冲突 (1 个冲突)')).toBeInTheDocument();
    fireEvent.click(screen.getByText('保留我的更改'));

    expect(onResolve).toHaveBeenCalledWith({
      conflictId: 'c1',
      resolution: 'accept_local',
      customData: undefined,
    });

    fireEvent.click(screen.getByText('应用所有解决方案'));
    expect(onResolveAll).toHaveBeenCalledWith([
      { conflictId: 'c1', resolution: 'accept_local', customData: undefined },
    ]);
  });
});
