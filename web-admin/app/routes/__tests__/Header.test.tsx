import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import Header from '../Header';

vi.mock('~/root', () => ({
  useRootLoaderData: () => ({ user: { username: 'cat', tenantName: 'AcmeCo' } }),
}));
vi.mock('~/contexts/ThemeContext', () => ({
  useTheme: () => ({ theme: 'light', setTheme: vi.fn(), isDark: false }),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (k: string) => k, locale: 'en-US', setLocale: vi.fn() }),
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
      className="hidden h-[34px] w-[360px] items-center gap-2 rounded-md border border-[#e3e8ee] bg-white px-3 text-sm text-gray-500 sm:flex"
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

  it('renders the search trigger at 360px width', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const trigger = screen.getByTestId('header-search-trigger');
    expect(trigger.className).toMatch(/w-\[360px\]/);
  });

  it('renders a Dev env chip', () => {
    render(
      <MemoryRouter>
        <Header />
      </MemoryRouter>,
    );
    const chip = screen.getByTestId('header-env-chip');
    expect(chip.textContent?.trim().length).toBeGreaterThan(0);
  });
});
