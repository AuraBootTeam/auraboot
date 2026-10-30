import { act, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import AdminLayout from '../AdminLayout';
import { useSchemaPresentation } from '~/shared/layout/PagePresentationContext';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';
vi.mock('~/contexts/ApplicationCapabilitiesContext', () => ({
  useApplicationCapabilities: () => ({ agent: 'installed', loading: false }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en', t: (key: string) => key }),
}));
vi.mock('../Header', () => ({ default: () => null }));
vi.mock('../LeftSidebar', () => ({ default: () => null }));
vi.mock('../PageContent', () => ({ default: () => <Owner /> }));
vi.mock('~/plugins/core-aurabot/components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { panelState: 'expanded' } }),
}));
vi.mock('~/plugins/core-aurabot/components-shell/AuraBotPanel', () => ({
  AuraBotPanel: () => null,
}));
vi.mock('~/plugins/core-dashboard/widgets/workbench/useRecentVisits', () => ({
  recordVisit: vi.fn(),
}));
vi.mock('@auraboot/track', () => ({ sanitizeRoute: (path: string) => path }));
vi.mock('~/shared/services/trackerInstance', () => ({
  getTracker: () => ({ init: vi.fn(), pageview: vi.fn() }),
}));
vi.mock('~/components/ImpersonationBanner', () => ({ ImpersonationBanner: () => null }));
const schema = {
  pageKey: 'cases',
  title: 'Cases',
  extension: {
    presentation: 'workspace',
    workspaceShell: {
      headerHeight: 64,
      mobileHeaderHeight: 55,
      sidebar: { mobileBreakpoint: 700 },
    },
  },
  blocks: [],
} as unknown as UnifiedSchema;
function Owner() {
  useSchemaPresentation(schema);
  return <div>Page content</div>;
}
describe('actual shell geometry consumers', () => {
  it('shares the provider-selected height with content padding and the open AI dock across resize', () => {
    const previous = window.innerWidth;
    const resize = (width: number) =>
      act(() => {
        Object.defineProperty(window, 'innerWidth', { configurable: true, value: width });
        window.dispatchEvent(new Event('resize'));
      });
    resize(390);
    const view = render(
      <MemoryRouter>
        <AdminLayout />
      </MemoryRouter>,
    );
    try {
      const shell = view.container.querySelector('[data-workspace-shell="configured"]');
      if (!(shell instanceof HTMLElement))
        throw new Error('Configured workspace shell was not mounted');
      expect(shell.style.getPropertyValue('--workspace-header-height')).toBe('55px');
      expect(view.container.querySelector('[data-print="content"]')).toHaveStyle({
        paddingTop: 'var(--workspace-header-height, 55px)',
      });
      expect(screen.getByTestId('ai-panel-dock')).toHaveStyle({
        paddingTop: 'var(--workspace-header-height, 55px)',
      });
      resize(701);
      expect(shell.style.getPropertyValue('--workspace-header-height')).toBe('64px');
      expect(view.container.querySelector('[data-print="content"]')).toHaveStyle({
        paddingTop: 'var(--workspace-header-height, 64px)',
      });
      expect(screen.getByTestId('ai-panel-dock')).toHaveStyle({
        paddingTop: 'var(--workspace-header-height, 64px)',
      });
    } finally {
      view.unmount();
      resize(previous);
    }
  });
});
