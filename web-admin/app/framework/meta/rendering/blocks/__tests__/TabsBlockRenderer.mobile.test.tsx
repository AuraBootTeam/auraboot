import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const access = vi.hoisted(() => ({ allowed: true }));
vi.mock('~/contexts/AuthContext', () => ({
  useAuth: () => ({ hasPermission: () => access.allowed }),
}));

vi.mock('@auraboot/runtime-kernel', () => ({
  BlockRenderer: ({ areaId }: { areaId: string }) => <div data-testid={areaId} />,
}));

import { TabsBlockRenderer } from '../TabsBlockRenderer';

describe('TabsBlockRenderer mobile behavior', () => {
  it('removes forbidden content and keeps selection attached to its key when permissions change', () => {
    const runtime = { getContext: () => ({ locale: 'en', t: (key: string) => key }) } as any;
    const block = {
      id: 'guarded-tabs',
      blockType: 'tabs',
      tabs: [
        { key: 'public', label: 'Public', blocks: [{ id: 'public-content' }] },
        {
          key: 'private',
          label: 'Private',
          permissionCode: 'child.manage',
          blocks: [{ id: 'private-content' }],
        },
      ],
    } as any;
    access.allowed = true;
    const view = render(<TabsBlockRenderer runtime={runtime} block={block} />);
    fireEvent.click(screen.getByRole('tab', { name: 'Private' }));
    expect(screen.getByRole('tab', { name: 'Private' })).toHaveAttribute('aria-selected', 'true');
    access.allowed = false;
    view.rerender(<TabsBlockRenderer runtime={runtime} block={block} />);
    expect(screen.queryByRole('tab', { name: 'Private' })).not.toBeInTheDocument();
    expect(screen.getByRole('tab', { name: 'Public' })).toHaveAttribute('aria-selected', 'true');
    access.allowed = true;
  });

  it('keeps three business tabs horizontally scrollable and touch friendly', () => {
    const runtime = {
      getContext: () => ({ locale: 'zh-CN', t: (key: string) => key }),
    } as any;
    render(
      <TabsBlockRenderer
        runtime={runtime}
        block={
          {
            id: 'mobile-tabs',
            blockType: 'tabs',
            tabs: [
              { key: 'info', label: { 'zh-CN': '客户信息' }, blocks: [{ id: 'info-block' }] },
              { key: 'activity', label: { 'zh-CN': '跟进记录' }, blocks: [] },
              { key: 'ownership', label: { 'zh-CN': '归属记录' }, blocks: [] },
            ],
          } as any
        }
      />,
    );

    const tablist = screen.getByRole('tablist');
    expect(tablist.className).toContain('overflow-x-auto');
    expect(screen.getAllByRole('tab')).toHaveLength(3);
    for (const tab of screen.getAllByRole('tab')) {
      expect(tab.className).toContain('min-h-11');
      expect(tab.className).toContain('touch-manipulation');
      expect(tab.className).toContain('whitespace-nowrap');
    }
  });
});
