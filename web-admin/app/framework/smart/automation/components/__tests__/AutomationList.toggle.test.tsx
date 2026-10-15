import React from 'react';
import { render, fireEvent, screen, waitFor, cleanup } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { Automation } from '../../services/automationService';

const mocks = vi.hoisted(() => ({ error: vi.fn(), success: vi.fn(), revalidate: vi.fn() }));
vi.mock('react-router', () => ({
  Link: ({ to, children, ...props }: any) => <a href={to} {...props}>{children}</a>,
  useNavigate: () => vi.fn(),
  useRevalidator: () => ({ revalidate: mocks.revalidate }),
}));
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (key: string | {zh: string; en: string}) => typeof key === 'object' ? key.zh : ({
  '$i18n:automation.list.toggleFailed': '切换自动化状态失败，请重试',
  '$i18n:automation.list.disabled': '已禁用',
  '$i18n:automation.list.enabled': '已启用',
}[key] ?? key) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showErrorToast: mocks.error, showSuccessToast: mocks.success }) }));
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


describe('AutomationList import response contract', () => {
  beforeEach(() => vi.clearAllMocks());
  afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

  async function importFile(response: Response) {
    const fetchMock = vi.fn().mockResolvedValue(response);
    vi.stubGlobal('fetch', fetchMock);
    render(<AutomationList initialAutomations={[automation]} token={null} />);
    const file = new File(['{}'], 'design.json', {type: 'application/json'});
    Object.defineProperty(file, 'text', {value: async () => JSON.stringify({name: 'Imported copy', enabled: true, flowConfig: {nodes: [], edges: []}})});
    fireEvent.change(screen.getByTestId('input-import-automation'), {target: {files: [file]}});
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    return fetchMock;
  }

  it.each([
    ['HTTP error', () => new Response('{}', {status: 500})],
    ['HTTP 200 business error', () => Response.json({code: 'denied', data: {...automation, pid: 'copy', name: 'Imported copy'}})],
    ['missing identity', () => Response.json({code: '0', data: {enabled: false}})],
    ['unexpected enabled copy', () => Response.json({code: '0', data: {pid: 'copy', enabled: true}})],
    ['invalid JSON', () => new Response('not-json', {status: 200})],
  ])('preserves the existing list and reports a translated failure for %s', async (_, response) => {
    await importFile(response());
    await waitFor(() => expect(mocks.error).toHaveBeenCalledWith('导入自动化失败，请检查文件及权限后重试。'));
    expect(mocks.success).not.toHaveBeenCalled();
    expect(mocks.revalidate).not.toHaveBeenCalled();
    expect(screen.getByRole('link', {name: automation.name, exact: true})).toBeVisible();
  });

  it('consumes the successful body and revalidates only an identified disabled copy', async () => {
    const response = Response.json({code: '0', data: {...automation, pid: 'copy', name: 'Imported copy'}});
    const readBody = vi.spyOn(response, 'json');
    const fetchMock = await importFile(response);
    await waitFor(() => expect(mocks.success).toHaveBeenCalledWith('已导入“Imported copy”'));
    expect(readBody).toHaveBeenCalledOnce();
    expect(JSON.parse(fetchMock.mock.calls[0][1].body).enabled).toBe(false);
    expect(mocks.revalidate).toHaveBeenCalledOnce();
    expect(mocks.error).not.toHaveBeenCalled();
  });
});
