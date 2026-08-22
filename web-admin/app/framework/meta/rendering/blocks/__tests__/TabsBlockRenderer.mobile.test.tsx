import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

vi.mock('@auraboot/runtime-kernel', () => ({
  BlockRenderer: ({ areaId }: { areaId: string }) => <div data-testid={areaId} />,
}));

import { TabsBlockRenderer } from '../TabsBlockRenderer';

describe('TabsBlockRenderer mobile behavior', () => {
  it('keeps three business tabs horizontally scrollable and touch friendly', () => {
    const runtime = {
      getContext: () => ({ locale: 'zh-CN', t: (key: string) => key }),
    } as any;
    render(
      <TabsBlockRenderer
        runtime={runtime}
        block={{
          id: 'mobile-tabs',
          blockType: 'tabs',
          tabs: [
            { key: 'info', label: { 'zh-CN': '客户信息' }, blocks: [{ id: 'info-block' }] },
            { key: 'activity', label: { 'zh-CN': '跟进记录' }, blocks: [] },
            { key: 'ownership', label: { 'zh-CN': '归属记录' }, blocks: [] },
          ],
        } as any}
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
