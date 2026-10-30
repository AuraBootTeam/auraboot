import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import SidebarSubmenu from '../SidebarSubmenu';

// Exercise MemoryRouter's actual location rather than the global '/' test stub.
vi.mock('react-router', async () => vi.importActual<typeof import('react-router')>('react-router'));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

vi.mock('~/utils/icon-resolver', () => ({
  resolveIcon: (_icon: string | null, label: string) => <span>{label.charAt(0)}</span>,
}));

describe('SidebarSubmenu navigation', () => {
  it('exposes the current expansion state without collapsing initially visible leaves', () => {
    render(
      <MemoryRouter>
        <SidebarSubmenu
          name="Metadata"
          submenu={[{ path: '/semantic/models', name: 'Semantic Models' }]}
        />
      </MemoryRouter>,
    );
    const parent = screen.getByRole('button', { name: 'Metadata' });
    expect(parent).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(parent);
    expect(parent).toHaveAttribute('aria-expanded', 'false');
    fireEvent.click(parent);
    expect(parent).toHaveAttribute('aria-expanded', 'true');
  });

  it('keeps decorative fallback letters out of menu accessible names', () => {
    render(
      <MemoryRouter>
        <SidebarSubmenu
          name="Delivery"
          icon="UnknownIcon"
          submenu={[{ path: '/p/plan', name: 'Frozen plans', icon: 'UnknownIcon' }]}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole('button', { name: 'Delivery' })).toBeVisible();
    const link = screen.getByRole('link', { name: 'Frozen plans' });
    expect(link).toHaveAttribute('href', '/p/plan');
    expect(link).toHaveTextContent('FFrozen plans');
    expect(link).toHaveAccessibleName('Frozen plans');
  });

  it('notifies the mobile drawer when a nested leaf navigates', () => {
    const onNavigate = vi.fn();
    render(
      <MemoryRouter>
        <SidebarSubmenu
          name="CRM"
          onNavigate={onNavigate}
          submenu={[
            {
              path: '/p/c',
              name: '业务档案',
              submenu: [
                {
                  path: '/p/c/crm_customer_pool_item_list',
                  name: '客户公海',
                },
              ],
            },
          ]}
        />
      </MemoryRouter>,
    );

    const directory = screen.getByRole('button', { name: 'CRM' });
    expect(directory.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(directory);
    expect(directory.getAttribute('aria-expanded')).toBe('false');
    expect(screen.queryByRole('link', { name: '客户公海' })).toBeNull();
    fireEvent.click(directory);
    expect(directory.getAttribute('aria-expanded')).toBe('true');
    fireEvent.click(screen.getByRole('link', { name: '客户公海' }));

    expect(onNavigate).toHaveBeenCalledOnce();
  });
  it('marks the current leaf for semantic active scrolling independently of navigation colors', () => {
    render(
      <MemoryRouter initialEntries={['/p/c/current']}>
        <SidebarSubmenu
          name="Workspaces"
          submenu={[
            { path: '/p/c/current', name: 'Current workspace' },
            { path: '/p/c/other', name: 'Other workspace' },
          ]}
        />
      </MemoryRouter>,
    );
    expect(screen.getByRole('link', { name: 'Current workspace' })).toHaveAttribute(
      'aria-current',
      'page',
    );
    expect(screen.getByRole('link', { name: 'Other workspace' })).not.toHaveAttribute(
      'aria-current',
    );
  });
});
