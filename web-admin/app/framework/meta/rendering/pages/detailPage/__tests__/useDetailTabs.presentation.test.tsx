import { act, renderHook } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { ReactNode } from 'react';
import { useDetailTabs } from '../useDetailTabs';
import { DetailPresentationContext } from '../../detail/DetailPresentationContext';
import type { UnifiedSchema, BlockConfig } from '~/framework/meta/schemas/types';
const blocks = [
  {
    id: 'tabs',
    blockType: 'tabs',
    tabs: [
      { key: 'one', label: 'One', blocks: [] },
      { key: 'two', label: 'Two', blocks: [] },
    ],
  },
] as BlockConfig[];
function options() {
  return {
    allBlocks: blocks,
    evaluateVisibleWhen: () => true,
    hasPermission: () => true,
    recordPid: 'A',
    schema: { id: 'case_detail', kind: 'detail', blocks } as UnifiedSchema,
    location: {
      pathname: '/source',
      search: '?status=new',
      hash: '#two',
      key: 'source',
      state: null,
    },
    routerNavigate: vi.fn(),
    t: (key: string) => key,
  };
}
describe('detail tab presentation', () => {
  it('does not read or write the outer hash in a modal', () => {
    const config = options();
    const wrapper = ({ children }: { children: ReactNode }) => (
      <DetailPresentationContext.Provider
        value={{ mode: 'modal', onClose: vi.fn(), footerTarget: null }}
      >
        {children}
      </DetailPresentationContext.Provider>
    );
    const { result } = renderHook(() => useDetailTabs(config), { wrapper });
    expect(result.current.activeTab).toBe(0);
    act(() => result.current.handleTabChange(1));
    expect(result.current.activeTab).toBe(1);
    expect(config.routerNavigate).not.toHaveBeenCalled();
  });
  it('retains the existing ordinary-route hash behavior without modal context', () => {
    const config = options();
    const { result } = renderHook(() => useDetailTabs(config));
    expect(result.current.activeTab).toBe(1);
    act(() => result.current.handleTabChange(0));
    expect(config.routerNavigate).toHaveBeenCalledWith(
      { pathname: '/source', search: '?status=new', hash: '#one' },
      { preventScrollReset: true },
    );
  });
});
