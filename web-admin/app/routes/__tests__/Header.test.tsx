import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import Header from '../Header';

const rootLoaderData = vi.hoisted(() => ({
  value: {
    user: { username: 'cat', tenantName: 'AcmeCo' },
    menus: [{ id: 'home', path: '/home' }],
    branding: {
      productName: 'AuraBoot',
      logoUrl: '/android-chrome-192x192.png',
    },
  },
}));

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));
vi.mock('~/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', setTheme: vi.fn(), isDark: false }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (k: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? k,
    locale: 'en-US',
    setLocale: vi.fn(),
  }),
}));
vi.mock('~/hooks/useHydrated', () => ({ useHydrated: () => true }));
vi.mock('~/hooks/useSSE', () => ({ useSSE: () => null }));
vi.mock('~/ui/inbox/InboxDropdown', () => ({
  InboxHeaderWidget: () => <button aria-label="notifications">99</button>,
}));
vi.mock('~/ui/CommandPalette', () => ({
  CommandPalette: () => (
    <button
      data-testid="header-search-trigger"
      className="hidden h-[34px] w-9 items-center justify-center gap-2 rounded-md border border-[#e3e8ee] bg-white px-0 text-sm text-gray-500 sm:flex md:w-48 md:justify-start md:px-3 lg:w-64 xl:w-[360px]"
    >
      Search...
    </button>
  ),
}));
vi.mock('~/plugins/core-aurabot/components-shell/AuraBotProvider', () => ({
  useAuraBot: () => ({ state: { panelState: 'closed' }, togglePanel: vi.fn() }),
}));

// Stub fetch to keep useEffect for spaces quiet
beforeEach(() => {
  rootLoaderData.value = {
    user: { username: 'cat', tenantName: 'AcmeCo' },
    menus: [{ id: 'home', path: '/home' }],
    branding: {
      productName: 'AuraBoot',
      logoUrl: '/android-chrome-192x192.png',
    },
  };
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve({}) }));
});

describe('Header — polish', () => {
  it('renders with h-14 height', () => {
    const { container } = render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const header = container.querySelector('header');
    expect(header).not.toBeNull();
    // h-14 lives on the inner flex row container
    const inner = header!.querySelector('.h-14');
    expect(inner).not.toBeNull();
    expect(header!.innerHTML).not.toMatch(/\bh-16\b/);
  });

  it('expands the search trigger progressively across responsive breakpoints', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const trigger = screen.getByTestId('header-search-trigger');
    expect(trigger.className).toMatch(/w-9/);
    expect(trigger.className).toMatch(/md:w-48/);
    expect(trigger.className).toMatch(/lg:w-64/);
    expect(trigger.className).toMatch(/xl:w-\[360px\]/);
  });

  it('renders a Dev env chip', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const chip = screen.getByTestId('header-env-chip');
    expect(chip.textContent?.trim().length).toBeGreaterThan(0);
    expect(chip.className).toMatch(/hidden/);
    expect(chip.className).toMatch(/xl:inline-flex/);
  });

  it('keeps the compact header controls inside narrow viewports by collapsing brand text', () => {
    const { container } = render(
      <MemoryRouter>
        <Header sidebarOpen={false} setSidebarOpen={vi.fn()} />
      </MemoryRouter>,
    );

    expect(screen.getByTestId('header-brand-name').className).toMatch(/hidden/);
    expect(screen.getByTestId('header-brand-name').className).toMatch(/xl:inline/);
    expect(container.querySelector('header .h-14')?.className).toMatch(/px-2/);
  });

  it('localizes the mobile sidebar toggle aria contract', () => {
    const { rerender } = render(
      <MemoryRouter>
        <Header sidebarOpen={false} setSidebarOpen={vi.fn()} />
      </MemoryRouter>,
    );

    const toggle = screen.getByTestId('header-sidebar-toggle');
    expect(toggle).toHaveAttribute('aria-controls', 'app-sidebar');
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    expect(toggle).toHaveAttribute('aria-label', 'Open navigation menu');

    rerender(
      <MemoryRouter>
        <Header sidebarOpen={true} setSidebarOpen={vi.fn()} />
      </MemoryRouter>,
    );

    expect(screen.getByTestId('header-sidebar-toggle')).toHaveAttribute('aria-expanded', 'true');
    expect(screen.getByTestId('header-sidebar-toggle')).toHaveAttribute(
      'aria-label',
      'Close navigation menu',
    );
  });

  it('exposes the About page from the account menu', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: 'User avatar' }));

    const aboutLink = screen.getByTestId('about-link');
    expect(aboutLink).toHaveAttribute('href', '/about');
    expect(aboutLink).toHaveTextContent('About AuraBoot');
  });

  it('hides the sidebar toggle when the user has no available menus', () => {
    rootLoaderData.value = {
      user: { username: 'cat', tenantName: 'AcmeCo' },
      menus: [],
      branding: {
        productName: 'AuraBoot',
        logoUrl: '/android-chrome-192x192.png',
      },
    };

    render(
      <MemoryRouter>
        <Header sidebarOpen={false} setSidebarOpen={vi.fn()} />
      </MemoryRouter>,
    );

    expect(screen.queryByTestId('header-sidebar-toggle')).not.toBeInTheDocument();
  });
});
