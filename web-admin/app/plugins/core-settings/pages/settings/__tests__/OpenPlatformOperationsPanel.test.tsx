import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import OpenPlatformOperationsPanel from '../OpenPlatformOperationsPanel';

const state = vi.hoisted(() => ({ locale: 'en', rows: [] as Record<string, unknown>[] }));
const translate = vi.hoisted(() => (_key: string, _params?: unknown, fallback?: string) => fallback ?? _key);
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ t: translate, locale: state.locale }) }));
vi.mock('~/contexts/ToastContext', () => ({ useToastContext: () => ({ showToast: vi.fn() }) }));
vi.mock('../open-platform-api', () => ({ openPlatformApiFetch: vi.fn(async (path: string) => {
  if (path.includes('/audits')) return state.rows;
  if (path.includes('/overview')) return { totalCalls: 1, errorCalls: 0, errorRate: 0,
    p95DurationMs: 5, throttledCalls: 0, deadLetterCount: 0 };
  return [];
}) }));

const accepted = { requestId: 'owned-request', method: 'POST',
  path: '/api/open/v1/event-sources/kingdee-procurement/events', status: 202,
  durationMs: 5, occurredAt: '2026-11-12T12:00:00Z', resource: 'kingdee-procurement',
  operation: 'accept', recordPid: '01MOWNEDRECEIPTIDENTITY00001', idempotentReplay: false };

beforeEach(() => { state.locale = 'en'; state.rows = [{ ...accepted }]; });

describe('installation call audit receipts', () => {
  it('renders the existing resource, operation and exact complete receipt without dumping other response fields', async () => {
    state.rows = [{ ...accepted, accessToken: 'never-render-this-token' }];
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    const entry = await screen.findByTestId('open-platform-call-audit-entry');
    expect(screen.getByTestId('call-audit-resource')).toHaveTextContent('kingdee-procurement');
    expect(screen.getByTestId('call-audit-operation')).toHaveTextContent('accept');
    expect(screen.getByTestId('call-audit-record')).toHaveTextContent(accepted.recordPid);
    expect(screen.getByTestId('call-audit-record')).toHaveClass('break-all');
    expect(entry).toContainElement(screen.getByTestId('call-audit-record'));
    expect(screen.getByText('POST ' + accepted.path)).not.toHaveClass('truncate');
    expect(screen.queryByText('never-render-this-token')).not.toBeInTheDocument();
  });

  it.each([[true, 'Duplicate request'], [false, 'First request'], [null, 'Not recorded'],
    [undefined, 'Not recorded']] as const)('keeps the replay marker distinct for %s', async (value, label) => {
    state.rows = [{ ...accepted, idempotentReplay: value }];
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    expect(await screen.findByTestId('call-audit-replay')).toHaveTextContent(label);
  });

  it('explains accepted202 without fabricating a final processing result', async () => {
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    expect(await screen.findByTestId('call-audit-accepted-help')).toHaveTextContent(
      'HTTP 202 confirms acceptance only. Query the event result using its receipt for the final processing outcome.');
    const entry = screen.getByTestId('open-platform-call-audit-entry');
    expect(entry).not.toHaveTextContent('Completed');
    expect(entry).not.toHaveTextContent('Delivered');
  });

  it('does not invent a receipt or replay result for older audit records without those fields', async () => {
    const { resource, operation, recordPid, idempotentReplay, ...older } = accepted;
    state.rows = [{ ...older, status: 200 }];
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    await screen.findByText('owned-request');
    expect(screen.queryByTestId('call-audit-record')).not.toBeInTheDocument();
    expect(screen.queryByTestId('call-audit-replay')).not.toBeInTheDocument();
    expect(screen.queryByTestId('call-audit-accepted-help')).not.toBeInTheDocument();
  });

  it('keeps rejected requests distinct from accepted events', async () => {
    state.rows = [{ ...accepted, status: 403, recordPid: null, idempotentReplay: null }];
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    expect(await screen.findByTestId('call-audit-record')).toHaveTextContent('Not recorded');
    expect(screen.getByText('403')).toHaveClass('text-red-600');
    expect(screen.queryByTestId('call-audit-accepted-help')).not.toBeInTheDocument();
  });

  it('localizes receipt and replay information in Chinese', async () => {
    state.locale = 'zh-CN'; state.rows = [{ ...accepted, idempotentReplay: true }];
    render(<OpenPlatformOperationsPanel installationPid="owned-installation" />);
    expect(await screen.findByTestId('call-audit-replay')).toHaveTextContent('重复请求');
    expect(screen.getByText('记录／回执标识')).toBeVisible();
    expect(screen.getByTestId('call-audit-accepted-help')).toHaveTextContent('HTTP 202 仅表示已接收');
  });
});
