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

  it('renders a full-width identity row in the sidebar variant', () => {
    render(
      <MemoryRouter>
        <UserMenuWidget variant="sidebar" />
      </MemoryRouter>,
    );

    const menu = screen.getByTestId('user-menu');
    expect(menu.className).toMatch(/w-full/);
    expect(menu.className).not.toMatch(/\bfixed\b/);

    const trigger = screen.getByRole('button', { name: /User avatar/ });
    expect(trigger.className).toMatch(/w-full/);
    // Identity text is part of the row itself, not hidden behind the popover
    expect(trigger).toHaveTextContent('Admin User');
    expect(trigger).toHaveTextContent('admin@auraboot.com');
  });

  it('collapses the sidebar row to the avatar only and widens the popover', () => {
    render(
      <MemoryRouter>
        <UserMenuWidget variant="sidebar" collapsed />
      </MemoryRouter>,
    );

    const trigger = screen.getByRole('button', { name: /User avatar/ });
    expect(trigger.className).toMatch(/justify-center/);
    expect(trigger).not.toHaveTextContent('admin@auraboot.com');

    fireEvent.click(trigger);
    expect(screen.getByTestId('user-dropdown').className).toMatch(/\bw-64\b/);
  });

  it('opens the sidebar-variant menu upward across the full row width', () => {
    render(
      <MemoryRouter>
        <UserMenuWidget variant="sidebar" />
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole('button', { name: /User avatar/ }));

    const dropdown = screen.getByTestId('user-dropdown');
    expect(dropdown.className).toMatch(/\bbottom-full\b/);
    expect(dropdown.className).toMatch(/w-full/);
    expect(screen.getByTestId('about-link')).toHaveAttribute('href', '/about');
  });
});
