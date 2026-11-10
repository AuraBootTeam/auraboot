import { fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import HistoryTab from '../HistoryTab';
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: (key: string) => key, locale: 'zh-CN' }) }));
afterEach(() => vi.unstubAllGlobals());
it('renders persisted preservation and failure feedback after loading history', async () => {
  vi.stubGlobal('fetch', vi.fn(async () => ({ ok: true, json: async () => ({ code: '0', data: [
    { importId: 'safe', pluginId: 'delivery', version: '1.1.0', status: 'success', startedAt: '2026-10-30T00:00:00Z', preservedResourceCount: 2, warnings: ['Preserved DECISION:approval: customer changes or an unknown import baseline'] },
    { importId: 'failed', pluginId: 'delivery', version: '1.2.0', status: 'failed', startedAt: '2026-10-30T00:01:00Z', errorMessage: 'Invalid rule adapter' },
  ] }) })));
  render(<HistoryTab />);
  expect(await screen.findByText('已保留配置：2')).toBeInTheDocument();
  expect(screen.getByText(/业务决策：已有客户修改/)).toBeVisible();
  expect(screen.getByText('Invalid rule adapter')).toBeVisible();
  fireEvent.click(screen.getByText('查看保留原因与技术诊断'));
  expect(screen.getByText(/Preserved DECISION:approval/)).toBeVisible();
});
