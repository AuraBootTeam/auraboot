import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { describe, expect, it, vi } from 'vitest';
import SidebarSubmenu from '../SidebarSubmenu';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (key: string, _params?: Record<string, unknown>, fallback?: string) => fallback ?? key,
  }),
}));

vi.mock('~/utils/icon-resolver', () => ({
  resolveIcon: () => null,
}));

describe('SidebarSubmenu navigation', () => {
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

    fireEvent.click(screen.getByRole('link', { name: '客户公海' }));

    expect(onNavigate).toHaveBeenCalledOnce();
  });
});
