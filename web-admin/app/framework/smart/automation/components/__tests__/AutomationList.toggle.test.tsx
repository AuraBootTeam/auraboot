import React from 'react';
import { render, fireEvent, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Automation } from '../../services/automationService';

const mocks = vi.hoisted(() => ({ error: vi.fn(), revalidate: vi.fn() }));
vi.mock('react-router', () => ({
  Link: ({ to, children, ...props }: any) => <a href={to} {...props}>{children}</a>,
  useNavigate: () => vi.fn(),
  useRevalidator: () => ({ revalidate: mocks.revalidate }),
}));
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (key: string) => ({
  '$i18n:automation.list.toggleFailed': '切换自动化状态失败，请重试',
  '$i18n:automation.list.disabled': '已禁用',
  '$i18n:automation.list.enabled': '已启用',
}[key] ?? key) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: mocks.error, showSuccessToast: vi.fn() }) }));
vi.mock('../ExecutionLogDialog', () => ({ ExecutionLogDialog: () => null }));
vi.mock('../TemplateGallery', () => ({ TemplateGallery: () => null }));
import { AutomationList } from '../AutomationList';

const automation: Automation = {
  pid: 'own-automation', name: 'Owned automation', enabled: false,
  triggerType: 'on_record_create', triggerConfig: {}, createdAt: '', updatedAt: '',
};

async function toggle(response: Response) {
  const fetchMock = vi.fn().mockResolvedValue(response);
  vi.stubGlobal('fetch', fetchMock);
  render(<AutomationList initialAutomations={[automation]} token={null} />);
  fireEvent.click(screen.getByTestId('btn-toggle-own-automation'));
  await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
}

describe('AutomationList toggle response contract', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
  it.each([
    ['HTTP failure', () => new Response('{}', { status: 500 })],
    ['business failure', () => Response.json({ code: 'denied', data: null })],
    ['wrong record', () => Response.json({ code: '0', data: { ...automation, pid: 'other', enabled: true } })],
    ['malformed JSON', () => new Response('invalid-json', { status: 200 })],
  ])('preserves disabled status and shows a translated error on %s', async (_, response) => {
    await toggle(response());
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('切换自动化状态失败，请重试'));
    expect(screen.getByTestId('status-own-automation')).toHaveTextContent('已禁用');
    expect(mocks.revalidate).not.toHaveBeenCalled();
  });
  it('reflects the exact successful server state and revalidates', async () => {
    await toggle(Response.json({ code: '0', data: { ...automation, enabled: true } }));
    await waitFor(() => expect(screen.getByTestId('status-own-automation')).toHaveTextContent('已启用'));
    expect(mocks.revalidate).toHaveBeenCalledOnce();
    expect(mocks.error).not.toHaveBeenCalled();
  });
});
