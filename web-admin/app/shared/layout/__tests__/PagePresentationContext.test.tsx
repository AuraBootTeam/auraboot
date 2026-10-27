import React from 'react';
import { act, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  PagePresentationProvider,
  useSchemaPresentation,
  usePagePresentation,
  usePresentationNavigation,
} from '../PagePresentationContext';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
const t = (key: string) => key;
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en', t }) }));
function Page({
  schema,
  title,
  enabled = true,
}: {
  schema: UnifiedSchema | null;
  title?: string;
  enabled?: boolean;
}) {
  useSchemaPresentation(schema, title, enabled);
  return null;
}
function Observer() {
  const presentation = usePagePresentation();
  return <output data-testid="mode">{presentation?.title || 'standard'}</output>;
}
const schema = {
  version: '1',
  id: 'orders',
  layout: { type: 'stack' },
  pageKey: 'orders_list',
  kind: 'list',
  title: { en: 'Orders' },
  extension: { presentation: 'workspace', workspaceLabel: { en: 'Sales' } },
  blocks: [],
} as UnifiedSchema;
describe('Schema-owned page presentation', () => {
  it('keeps a modal detail from replacing or clearing the surrounding workspace presentation', () => {
    const detail = { ...schema, pageKey: 'case_detail', title: 'Case A' };
    const view = render(
      <PagePresentationProvider>
        <Observer />
        <Page schema={schema} />
        <Page schema={detail} enabled={false} />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('Orders');
    view.rerender(
      <PagePresentationProvider>
        <Observer />
        <Page schema={schema} />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('Orders');
  });
  it('uses the runtime mode title instead of the static schema title', () => {
    render(
      <PagePresentationProvider>
        <Observer />
        <Page schema={schema} title="Edit order" />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('Edit order');
  });
  it('uses the declared page configuration and clears it when that page unmounts', () => {
    const { rerender } = render(
      <PagePresentationProvider>
        <Observer />
        <Page schema={schema} />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('Orders');
    rerender(
      <PagePresentationProvider>
        <Observer />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('standard');
  });
  it('does not change an undeclared page into a workspace', () => {
    render(
      <PagePresentationProvider>
        <Observer />
        <Page schema={{ ...schema, extension: {} }} />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('mode')).toHaveTextContent('standard');
  });
  it('carries declared shell geometry and restores the legacy height on owner cleanup', () => {
    function Geometry() {
      const value = usePresentationNavigation();
      return (
        <output data-testid="geometry">
          {value.headerHeight}:{value.shell?.sidebar.brandHeight ?? 'legacy'}
        </output>
      );
    }
    const configured = {
      ...schema,
      extension: {
        ...schema.extension,
        workspaceShell: { headerHeight: 64, sidebar: { brandHeight: 80 } },
      },
    } as UnifiedSchema;
    const { rerender } = render(
      <PagePresentationProvider>
        <Geometry />
        <Page schema={configured} />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('geometry')).toHaveTextContent('64:80');
    rerender(
      <PagePresentationProvider>
        <Geometry />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('geometry')).toHaveTextContent('56:legacy');
  });
});

it('selects the mobile header only at the declared sidebar breakpoint and preserves omission', () => {
  const previous = window.innerWidth;
  const resize = (width: number) =>
    act(() => {
      Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
      window.dispatchEvent(new Event('resize'));
    });
  function Geometry() {
    const { headerHeight } = usePresentationNavigation();
    return <output data-testid="height">{headerHeight}</output>;
  }
  const configured = {
    ...schema,
    extension: {
      ...schema.extension,
      workspaceShell: {
        headerHeight: 64,
        mobileHeaderHeight: 55,
        sidebar: { mobileBreakpoint: 700 },
      },
    },
  } as UnifiedSchema;
  resize(701);
  const view = render(
    <PagePresentationProvider>
      <Geometry />
      <Page schema={configured} />
    </PagePresentationProvider>,
  );
  try {
    expect(screen.getByTestId('height')).toHaveTextContent('64');
    resize(700);
    expect(screen.getByTestId('height')).toHaveTextContent('55');
    resize(390);
    expect(screen.getByTestId('height')).toHaveTextContent('55');
    view.rerender(
      <PagePresentationProvider>
        <Geometry />
        <Page
          schema={{
            ...configured,
            extension: {
              ...configured.extension,
              workspaceShell: { headerHeight: 64, sidebar: { mobileBreakpoint: 700 } },
            },
          }}
        />
      </PagePresentationProvider>,
    );
    expect(screen.getByTestId('height')).toHaveTextContent('64');
  } finally {
    view.unmount();
    resize(previous);
  }
});
