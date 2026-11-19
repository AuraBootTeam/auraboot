import React from 'react';
import { Blob as NodeBlob } from 'node:buffer';
import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const identity = vi.hoisted(() => ({
  route: 'route-a',
  user: { pid: 'buyer-a', tenantId: 'tenant-a' },
}));
const modal = vi.hoisted(() => ({ closeFocus: null as null | ((event: Event) => void) }));
vi.mock('react-router', () => ({ useLocation: () => ({ key: identity.route }) }));
vi.mock('~/contexts/AuthContext', () => ({ useAuth: () => ({ user: identity.user }) }));
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'zh-CN' }) }));
vi.mock('~/framework/smart/components/common/FilePreviewModal', () => ({
  FilePreviewModal: ({ onClose, onCloseAutoFocus, fileUrl, fileName, downloadAction }: any) => {
    modal.closeFocus = onCloseAutoFocus;
    return (
      <div role="dialog">
        <iframe title={fileName} src={fileUrl} />
        <button
          onClick={() => {
            onClose();
            onCloseAutoFocus?.(new Event('close', { cancelable: true }));
          }}
        >
          Close
        </button>
        <button disabled={downloadAction.busy} onClick={downloadAction.onClick}>
          {downloadAction.label}
        </button>
        {downloadAction.error && <p role="alert">{downloadAction.error}</p>}
      </div>
    );
  },
}));
import {
  ProtectedFilePreviewProvider,
  useProtectedFilePreview,
} from '../ProtectedFilePreviewProvider';

const url = '/api/file/download/01ARZ3NDEKTSV4RRFFQ69G5FAW';
let open: NonNullable<ReturnType<typeof useProtectedFilePreview>>;
function Consumer() {
  open = useProtectedFilePreview()!;
  return null;
}
function tree() {
  return (
    <ProtectedFilePreviewProvider>
      <Consumer />
    </ProtectedFilePreviewProvider>
  );
}
function response(
  body = '%PDF-1.7 fixture',
  type = 'application/pdf',
  status = 200,
  attachment = true,
) {
  return {
    ok: status === 200,
    status,
    statusText: status === 200 ? 'OK' : 'Forbidden',
    headers: new Headers({
      'Content-Type': type,
      ...(attachment
        ? {
            'Content-Disposition':
              "attachment; filename*=UTF-8''%E9%87%87%E8%B4%AD%E5%90%88%E5%90%8C.pdf",
          }
        : {}),
    }),
    blob: async () => new NodeBlob([body], { type }),
  };
}
let fetchMock: ReturnType<typeof vi.fn>,
  create: ReturnType<typeof vi.fn>,
  revoke: ReturnType<typeof vi.fn>;
beforeEach(() => {
  modal.closeFocus = null;
  identity.route = 'route-a';
  identity.user = { pid: 'buyer-a', tenantId: 'tenant-a' };
  let count = 0;
  create = vi.fn(() => `blob:preview-${++count}`);
  revoke = vi.fn();
  vi.stubGlobal(
    'URL',
    Object.assign(class extends URL {}, { createObjectURL: create, revokeObjectURL: revoke }),
  );
  fetchMock = vi.fn().mockResolvedValue(response());
  vi.stubGlobal('fetch', fetchMock);
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('protected command PDF presentation', () => {
  it('closing returns focus to the initiating action in the same route and identity', async () => {
    render(
      <>
        <button data-testid="return-trigger">Source action</button>
        {tree()}
      </>,
    );
    const trigger = screen.getByTestId('return-trigger');
    await act(async () => open({ url, contentType: 'application/pdf', returnFocusTo: trigger }));
    screen.getByText('下载 PDF').focus();
    fireEvent.click(screen.getByText('Close'));
    expect(trigger).toHaveFocus();
  });

  it('closing an old identity must not focus its still-connected former trigger', async () => {
    const content = () => (
      <>
        <button data-testid="return-trigger">Source action</button>
        {tree()}
      </>
    );
    const view = render(content()),
      trigger = screen.getByTestId('return-trigger');
    await act(async () => open({ url, contentType: 'application/pdf', returnFocusTo: trigger }));
    const oldCloseFocus = modal.closeFocus!,
      focus = vi.spyOn(trigger, 'focus');
    identity.user = { pid: 'buyer-b', tenantId: 'tenant-b' };
    view.rerender(content());
    oldCloseFocus(new Event('close', { cancelable: true }));
    expect(focus).not.toHaveBeenCalled();
  });
  it('previews only authenticated verified bytes and releases them on close', async () => {
    render(tree());
    await act(async () => open({ url, contentType: 'application/pdf' }));
    expect(fetchMock).toHaveBeenCalledWith(
      url,
      expect.objectContaining({
        method: 'GET',
        credentials: 'same-origin',
        signal: expect.any(AbortSignal),
      }),
    );
    expect(screen.getByTitle('采购合同.pdf').getAttribute('src')).toBe('blob:preview-1');
    fireEvent.click(screen.getByText('Close'));
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(revoke).toHaveBeenCalledWith('blob:preview-1');
    await act(async () => open({ url, contentType: 'application/pdf' }));
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(screen.getByTitle('采购合同.pdf').getAttribute('src')).toBe('blob:preview-2');
  });

  it.each(['mime', 'magic', 'empty', 'attachment', 'denied', 'oversize'])(
    'rejects %s without creating a usable preview',
    async (defect) => {
      let value = response();
      if (defect === 'mime') value = response('%PDF-1.7 fixture', 'text/html');
      if (defect === 'magic') value = response('<html>not PDF</html>');
      if (defect === 'empty') value = response('');
      if (defect === 'attachment')
        value = response('%PDF-1.7 fixture', 'application/pdf', 200, false);
      if (defect === 'denied') value = response('', 'application/json', 403);
      if (defect === 'oversize') {
        const blob = new NodeBlob(['%PDF-1.7 fixture']);
        Object.defineProperty(blob, 'size', { value: 33_554_433 });
        value.blob = async () => blob;
      }
      fetchMock.mockResolvedValue(value);
      render(tree());
      await act(async () => {
        await expect(open({ url, contentType: 'application/pdf' })).rejects.toThrow();
      });
      expect(create).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).toBeNull();
    },
  );

  it.each([
    'https://other.invalid/api/file/download/01ARZ3NDEKTSV4RRFFQ69G5FAW',
    '/api/file/download/../../secret',
  ])('rejects an uncontrolled URL before transport: %s', async (invalid) => {
    render(tree());
    await act(async () => {
      await expect(open({ url: invalid, contentType: 'application/pdf' })).rejects.toThrow();
    });
    expect(fetchMock).not.toHaveBeenCalled();
  });

  it.each(['route', 'identity'])(
    'late bytes cannot reopen a preview after %s changes',
    async (change) => {
      let finish!: (value: ReturnType<typeof response>) => void;
      fetchMock.mockImplementation(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      );
      const view = render(tree());
      let pending!: Promise<void>;
      await act(async () => {
        pending = open({ url, contentType: 'application/pdf' });
      });
      if (change === 'route') identity.route = 'route-b';
      else identity.user = { pid: 'buyer-b', tenantId: 'tenant-b' };
      view.rerender(tree());
      await act(async () => {
        finish(response());
        await pending;
      });
      expect(fetchMock.mock.calls[0][1].signal.aborted).toBe(true);
      expect(create).not.toHaveBeenCalled();
      expect(screen.queryByRole('dialog')).toBeNull();
    },
  );

  it('a retained opener cannot close or replace a newer identity preview', async () => {
    const view = render(tree()),
      oldOpen = open;
    identity.user = { pid: 'buyer-b', tenantId: 'tenant-b' };
    view.rerender(tree());
    await act(async () => open({ url, contentType: 'application/pdf' }));
    await act(async () => oldOpen({ url, contentType: 'application/pdf' }));
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeNull();
  });

  it('explicit download reauthorizes the original file instead of downloading the cached Blob', async () => {
    render(tree());
    await act(async () => open({ url, contentType: 'application/pdf' }));
    fetchMock.mockResolvedValue(response('', 'application/json', 403));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    fireEvent.click(screen.getByText('下载 PDF'));
    expect(await screen.findByRole('alert')).toHaveTextContent('无权');
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([url, url]);
    expect(create).toHaveBeenCalledTimes(1);
    expect(click).not.toHaveBeenCalled();
  });

  it('successful download checks fresh response bytes and preserves the named preview', async () => {
    render(tree());
    await act(async () => open({ url, contentType: 'application/pdf' }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    fireEvent.click(screen.getByText('下载 PDF'));
    await waitFor(() => expect(click).toHaveBeenCalledTimes(1));
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(fetchMock.mock.calls.map((call) => call[0])).toEqual([url, url]);
    expect(screen.getByTitle('采购合同.pdf').getAttribute('src')).toBe('blob:preview-1');
  });

  it('parent cancellation and unmount revoke an already displayed file', async () => {
    const controller = new AbortController(),
      view = render(tree());
    await act(async () => open({ url, contentType: 'application/pdf', signal: controller.signal }));
    act(() => controller.abort());
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(revoke).toHaveBeenCalledWith('blob:preview-1');
    await act(async () => open({ url, contentType: 'application/pdf' }));
    view.unmount();
    expect(revoke).toHaveBeenCalledWith('blob:preview-2');
  });
});
