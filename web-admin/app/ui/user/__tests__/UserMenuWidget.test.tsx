import { describe, it, expect, vi, beforeEach } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { UserMenuWidget } from '../UserMenuWidget';

const rootLoaderData = vi.hoisted(() => ({ value: {} as Record<string, any> }));

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => rootLoaderData.value,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (k: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? k,
    locale: 'en-US',
    setLocale: vi.fn(),
  }),
}));

const authedRootData = {
  user: { name: 'Admin User', email: 'admin@auraboot.com', tenantId: 't-1' },
  accessPolicy: { deploymentMode: 'multi', actorSwitchEnabled: false },
  branding: { productName: 'AuraBoot', logoUrl: '/logo.png' },
};

describe('UserMenuWidget', () => {
  beforeEach(() => {
    rootLoaderData.value = authedRootData;
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, json: () => Promise.resolve({}) }),
    );
  });

  it('pins the avatar to the bottom-start corner outside the header', () => {
    const { container } = render(
      <MemoryRouter>
        <UserMenuWidget />
      </MemoryRouter>,
    );

    const menu = screen.getByTestId('user-menu');
    expect(menu.className).toMatch(/\bfixed\b/);
    expect(menu.className).toMatch(/\bbottom-4\b/);
    expect(menu.className).toMatch(/ltr:left-4/);
    expect(menu.className).toMatch(/rtl:right-4/);
    // Rendered as a viewport-level widget, not inside the header toolbar
    expect(container.querySelector('header')).toBeNull();
    expect(menu.className).toMatch(/print-hide/);
  });

  it('opens the account menu upward with the standard actions', () => {
    render(
      <MemoryRouter>
        <UserMenuWidget />
      </MemoryRouter>,
    );

    expect(screen.queryByTestId('user-dropdown')).toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'User avatar' }));

    const dropdown = screen.getByTestId('user-dropdown');
    expect(dropdown.className).toMatch(/\bbottom-full\b/);
    expect(screen.getByTestId('about-link')).toHaveAttribute('href', '/about');
    expect(dropdown.querySelector('a[href="/logout"]')).not.toBeNull();
    expect(screen.getByText('Admin User')).toBeInTheDocument();
    expect(screen.getByText('admin@auraboot.com')).toBeInTheDocument();
  });

  it('renders nothing for anonymous visitors', () => {
    rootLoaderData.value = { accessPolicy: {}, branding: authedRootData.branding };

    const { container } = render(
      <MemoryRouter>
        <UserMenuWidget />
      </MemoryRouter>,
    );

    expect(container).toBeEmptyDOMElement();
  });

  it('skips identity lookups in simplified mode', async () => {
    render(
      <MemoryRouter>
        <UserMenuWidget simplified />
      </MemoryRouter>,
    );

    await Promise.resolve();
    expect(fetch).not.toHaveBeenCalledWith('/api/tenant-selection/my-spaces');
    expect(fetch).not.toHaveBeenCalledWith('/api/actors');
  });
});
