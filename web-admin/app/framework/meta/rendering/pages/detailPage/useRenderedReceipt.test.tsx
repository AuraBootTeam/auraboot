import React, { useRef } from 'react';
import { act, fireEvent, render, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { useRenderedReceipt, type RenderedReceiptConfig } from './useRenderedReceipt';

const mocks = vi.hoisted(() => ({ fetch: vi.fn(), tenant: 't1' }));
vi.mock('~/shared/services/http-client', () => ({ fetchResult: mocks.fetch }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ user: { tenantId: mocks.tenant, pid: 'reader' } }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US' }) }));
const config: RenderedReceiptConfig = { bodyField: 'content', endpoint: '/api/receipt/{recordPid}', payloadFields: { snapshotHash: 'snapshotHash' } };
const record = { pid: 'v1', content: 'Actual body', snapshotHash: 'hash1' };
let visible = true;
function Harness({ value = record, loading = false, failed = false, image = false, onReceipt = vi.fn() }: any) {
  const root = useRef<HTMLDivElement>(null);
  const receipt = useRenderedReceipt({ config, root, record: value, recordPid: value.pid, loading, failed, onReceipt });
  return <div ref={root}>{!loading && !failed && <div data-rendered-field="content">{value.content}{image && <img alt="Body" src="/body.png" />}</div>}
    {receipt.error && <div role="alert">{receipt.error}<button onClick={receipt.retry}>Retry receipt</button></div>}
  </div>;
}
beforeEach(() => {
  vi.clearAllMocks(); mocks.tenant = 't1'; visible = true;
  vi.spyOn(HTMLElement.prototype, 'getClientRects').mockImplementation(() => (visible ? [{}] : []) as any);
  mocks.fetch.mockImplementation(async () => ({ code: '0', data: record }));
});
describe('rendered detail receipt boundary', () => {
  it('explains a failed body image without recording reading and recovers only after the image loads', async () => {
    const received = vi.fn();const ui = render(<Harness image onReceipt={received} />);
    const image = ui.getByRole('img');
    Object.defineProperty(image, 'complete', { configurable: true, value: true });
    Object.defineProperty(image, 'naturalWidth', { configurable: true, value: 0 });
    fireEvent.error(image);
    await waitFor(() => expect(ui.getByRole('alert')).toHaveTextContent('A body image could not load'));
    expect(mocks.fetch).not.toHaveBeenCalled();expect(received).not.toHaveBeenCalled();
    Object.defineProperty(image, 'naturalWidth', { configurable: true, value: 20 });
    fireEvent.click(ui.getByRole('button'));
    fireEvent.load(image);
    await waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    expect(ui.queryByRole('alert')).not.toBeInTheDocument();
  });
  it('submits the exact revision only after actual body DOM is visible and reports the returned fact', async () => {
    const received = vi.fn();
    mocks.fetch.mockImplementation(async () => {
      expect(document.querySelector('[data-rendered-field="content"]')?.textContent).toBe('Actual body');
      return { code: '0', data: { ...record, first_read_at: '2026-10-25T00:00:00Z' } };
    });
    render(<Harness onReceipt={received} />);
    await waitFor(() => expect(received).toHaveBeenCalledWith(expect.objectContaining({ first_read_at: '2026-10-25T00:00:00Z' })));
    expect(mocks.fetch).toHaveBeenCalledTimes(1);
    expect(mocks.fetch).toHaveBeenCalledWith('/api/receipt/v1', { method: 'post', params: { snapshotHash: 'hash1' } });
  });
  it('does not mark a loading, failed or hidden body and can run when it becomes visible', async () => {
    visible = false;
    const ui = render(<Harness loading />);
    ui.rerender(<Harness failed />);
    ui.rerender(<Harness />);
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')); });
    expect(mocks.fetch).not.toHaveBeenCalled();
    visible = true;
    act(() => { document.dispatchEvent(new Event('visibilitychange')); });
    await waitFor(() => expect(mocks.fetch).toHaveBeenCalledTimes(1));
  });
  it('shows receipt failures and requires an explicit retry instead of reporting successful reading', async () => {
    const received = vi.fn();mocks.fetch.mockResolvedValueOnce({ code: '403', message: 'Version unavailable' });
    const ui = render(<Harness onReceipt={received} />);
    await waitFor(() => expect(ui.getByRole('alert')).toHaveTextContent('Version unavailable'));
    expect(received).not.toHaveBeenCalled();
    act(() => ui.getByRole('button').click());
    await waitFor(() => expect(received).toHaveBeenCalledTimes(1));
    expect(mocks.fetch).toHaveBeenCalledTimes(2);
  });
  it('drops an old response after tenant and version context changes', async () => {
    let finish!: (value: unknown) => void;
    mocks.fetch.mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
    const received = vi.fn();const ui = render(<Harness onReceipt={received} />);
    await waitFor(() => expect(mocks.fetch).toHaveBeenCalledTimes(1));
    mocks.tenant = 't2';const newer = { ...record, pid: 'v2', snapshotHash: 'hash2' };
    mocks.fetch.mockResolvedValue({ code: '0', data: newer });ui.rerender(<Harness value={newer} onReceipt={received} />);
    await waitFor(() => expect(received).toHaveBeenCalledWith(newer));
    await act(async () => finish({ code: '0', data: record }));
    expect(received).toHaveBeenCalledTimes(1);
  });
});
