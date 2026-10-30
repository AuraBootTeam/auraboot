import { fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, describe, expect, it, vi } from 'vitest';
import InstalledTab from '../InstalledTab';
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (key: string) => key, locale: 'zh-CN' }) }));
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

describe('InstalledTab safe upgrade feedback', () => {
  it.each([true, false])('uses safe import and renders the execution result (success=%s)', async (success) => {
    const preview = { importId: 'upgrade-one', pluginId: 'customer.delivery', namespace: 'delivery', version: '1.1.0', displayName: 'Delivery', valid: true, isUpgrade: true, errors: [], warnings: [], actionCounts: {} };
    const execute = vi.fn();
    vi.stubGlobal('fetch', vi.fn(async (url: string, options?: RequestInit) => {
      if (url.endsWith('/upload')) return { ok: true, json: async () => ({ code: '0', data: preview }) };
      if (url.endsWith('/execute')) {
        execute(JSON.parse(options!.body as string));
        return { ok: true, json: async () => success ? ({ code: '0', data: { success: true, warnings: ['Customer approval rule was preserved'] } }) : ({ code: '400', message: 'Rule validation failed' }) };
      }
      return { ok: true, json: async () => ({ code: '0', data: [] }) };
    }));
    const { container } = render(<InstalledTab />);
    fireEvent.click(await screen.findByRole('button', { name: 'plugin.action.upload' }));
    fireEvent.change(container.querySelector('input[type=file]')!, { target: { files: [new File(['{}'], 'delivery.json', { type: 'application/json' })] } });
    expect(await screen.findByText('安全更新')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'plugin.import.confirmUpgrade' }));
    expect(await screen.findByText(success ? '导入已完成' : '导入未完成')).toBeInTheDocument();
    expect(execute).toHaveBeenCalledWith(expect.objectContaining({ conflictStrategy: 'overwrite_safe' }));
    expect(screen.getByText(success ? 'Customer approval rule was preserved' : 'Rule validation failed')).toBeInTheDocument();
    if (!success) expect(screen.queryByText('导入已完成')).not.toBeInTheDocument();
  });
});

it('does not present count-only preserved pages as an unconditional update', async () => {
  vi.stubGlobal('fetch', vi.fn(async (url: string) => ({ ok: true, json: async () => ({ code: '0', data: url.endsWith('/upload')
    ? { importId: 'page-safe', valid: true, isUpgrade: true, errors: [], warnings: [], actionCounts: {} }
    : url.endsWith('/execute') ? { success: true, warnings: [], resourceCounts: { PAGE: { SKIP: 2 } } } : [] }) })));
  const { container } = render(<InstalledTab />);
  fireEvent.click(await screen.findByRole('button', { name: 'plugin.action.upload' }));
  fireEvent.change(container.querySelector('input[type=file]')!, { target: { files: [new File(['{}'], 'pages.json', { type: 'application/json' })] } });
  await screen.findByText('安全更新');
  fireEvent.click(screen.getByRole('button', { name: 'plugin.import.confirmUpgrade' }));
  expect(await screen.findByText('部分资源已保留，请核对说明')).toBeInTheDocument();
  expect(screen.getByText('已保留配置：2')).toBeInTheDocument();
});
