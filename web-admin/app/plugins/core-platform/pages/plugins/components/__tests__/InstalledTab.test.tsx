import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import InstalledTab from '../InstalledTab';
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (key: string) => key }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: vi.fn() }) }));
afterEach(() => vi.unstubAllGlobals());
describe('InstalledTab public lifecycle contract', () => {
  it('exposes stop for an active plugin and enable after stopping, without offering active uninstall', async () => {
    let status = 'active';
    const fetcher = vi.fn(async (url: string, options?: RequestInit) => {
      if (url.endsWith('/disable')) { expect(options?.method).toBe('post'); status = 'stopped'; }
      if (url.endsWith('/enable')) { expect(options?.method).toBe('post'); status = 'active'; }
      return { ok: true, json: async () => ({ code: '0', data: [{ pid: 'plugin-one', pluginId: 'com.customer.delivery', displayName: 'Customer delivery', namespace: 'cde', version: '1.0.0', status, installedAt: '2026-10-20T00:00:00Z' }] }) };
    });
    vi.stubGlobal('fetch', fetcher);
    render(<InstalledTab />);
    const row = within(await screen.findByRole('row', { name: /Customer delivery/ }));
    expect(screen.getByText('plugin.status.enabled')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'plugin.action.uninstall' })).not.toBeInTheDocument();
    fireEvent.click(row.getByRole('button', { name: 'plugin.action.disable' }));
    await screen.findByText('plugin.status.disabled');
    expect(row.getByRole('button', { name: 'plugin.action.uninstall' })).toBeInTheDocument();
    fireEvent.click(row.getByRole('button', { name: 'plugin.action.enable' }));
    await screen.findByText('plugin.status.enabled');
    expect(row.getByRole('button', { name: 'plugin.action.disable' })).toBeInTheDocument();
    expect(fetcher.mock.calls.filter(([url]) => url.endsWith('/disable') || url.endsWith('/enable')).map(([url]) => url)).toEqual(['/api/plugins/plugin-one/disable', '/api/plugins/plugin-one/enable']);
  });
});
