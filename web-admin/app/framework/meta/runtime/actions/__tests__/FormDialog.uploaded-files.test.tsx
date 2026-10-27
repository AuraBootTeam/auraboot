import { act, cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import FormDialog from '../FormDialog';
import { uploadedFilesConfig, validUploadedFilePids } from '../uploadedFiles';
import { actionRegistry } from '../ActionRegistry';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'zh-CN', t: (key: string) => key }),
}));
const A = '01J00000000000000000000001',
  B = '01J00000000000000000000002';
const config = { maxCount: 10, accept: 'image/*,.pdf', maxBytes: 100 };
const image = (name = '现场甲.jpg') => new File(['real test bytes'], name, { type: 'image/jpeg' });
const uploaded = (pid = A) =>
  new Response(JSON.stringify({ code: '0', data: { fileId: pid } }), { status: 200 });
function deferred() {
  let resolve!: (value: Response) => void;
  const promise = new Promise<Response>((r) => {
    resolve = r;
  });
  return { promise, resolve };
}
function open(overrides: Record<string, unknown> = {}) {
  const submit = vi.fn();
  act(() =>
    window.dispatchEvent(
      new CustomEvent('dialog:form', {
        detail: {
          title: '提交记录',
          fields: [
            { field: 'note', type: 'textarea', label: '说明', required: true },
            { field: 'filePids', type: 'uploaded-files', label: '附件', uploadedFiles: config },
          ],
          defaults: { note: '实际处理说明' },
          onSubmit: submit,
          ...overrides,
        },
      }),
    ),
  );
  return submit;
}
const choose = (files: File[]) =>
  fireEvent.change(screen.getByTestId('attachment-file-input'), { target: { files } });
const submitButton = () => screen.getByTestId('form-dialog-submit');
let transport: ReturnType<typeof vi.fn>;
beforeEach(() => {
  transport = vi.fn();
  vi.stubGlobal('fetch', transport);
  render(<FormDialog />);
});
afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
});

describe('Official staged uploaded-files command input', () => {
  it('focuses the visible chooser rather than its screen-only file input', async () => {
    open({
      fields: [{ field: 'filePids', label: '附件', type: 'uploaded-files', uploadedFiles: config }],
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: '选择附件', exact: true })).toHaveFocus(),
    );
  });
  it('passes PID[] through the real native command collector in one versioned request', async () => {
    const upload = deferred();
    transport.mockReturnValueOnce(upload.promise);
    const rpc = vi.fn().mockResolvedValue({ code: '0', data: { recordPid: B } });
    let command!: Promise<unknown>;
    act(() => {
      command = actionRegistry.execute('command.execute', {
        args: {
          command: 'anc_maintenance_order:record_repair',
          targetRecordPid: B,
          expectedVersion: 4,
          operationType: 'UPDATE',
          idempotent: true,
          inputFieldsPresentation: { waitForResult: true },
          inputFields: [
            {
              field: 'repairDescription',
              type: 'textarea',
              defaultValue: '实际维修说明',
              required: true,
            },
            { field: 'filePids', type: 'uploaded-files', uploadedFiles: config },
          ],
        },
        fetchResult: rpc,
        locale: 'zh-CN',
        t: (key: string) => key,
      });
    });
    choose([image()]);
    expect(submitButton()).toBeDisabled();
    expect(rpc).not.toHaveBeenCalled();
    await act(async () => upload.resolve(uploaded()));
    await waitFor(() => expect(submitButton()).toBeEnabled());
    fireEvent.click(submitButton());
    await act(async () => {
      await command;
    });
    expect(rpc).toHaveBeenCalledTimes(1);
    expect(rpc.mock.calls[0][0]).toBe(
      '/api/meta/commands/execute/anc_maintenance_order:record_repair',
    );
    expect(rpc.mock.calls[0][1].params).toEqual({
      targetRecordPid: B,
      expectedVersion: 4,
      operationType: 'UPDATE',
      clientRequestId: expect.any(String),
      payload: { repairDescription: '实际维修说明', filePids: [A] },
    });
    expect(transport).toHaveBeenCalledTimes(1);
  });
  it('blocks pending and partial failure, retries only the failed file, then emits only PID[] once', async () => {
    const first = deferred();
    transport
      .mockReturnValueOnce(first.promise)
      .mockResolvedValueOnce(new Response('{}', { status: 503 }));
    const submit = open();
    choose([image(), new File(['pdf bytes'], '说明.pdf', { type: 'application/pdf' })]);
    expect(submitButton()).toBeDisabled();
    fireEvent.click(submitButton());
    expect(submit).not.toHaveBeenCalled();
    await act(async () => first.resolve(uploaded()));
    await screen.findByText('上传失败，文件仍保留');
    expect(screen.getByText('现场甲.jpg')).toBeVisible();
    expect(screen.getByText('说明.pdf')).toBeVisible();
    expect(submitButton()).toBeDisabled();
    transport.mockResolvedValueOnce(uploaded(B));
    fireEvent.click(screen.getByRole('button', { name: '重试上传' }));
    await waitFor(() => expect(submitButton()).toBeEnabled());
    expect(transport).toHaveBeenCalledTimes(3);
    for (const [url, request] of transport.mock.calls) {
      expect(url).toBe('/api/file/upload');
      expect(request.method).toBe('post');
      expect(request.body).toBeInstanceOf(FormData);
      expect(request.body.get('file')).toBeInstanceOf(File);
      expect(request.headers).toBeUndefined();
    }
    expect(
      transport.mock.calls
        .map((call) => (call[1].body as FormData).get('file') as File)
        .map((file) => file.name),
    ).toEqual(['现场甲.jpg', '说明.pdf', '说明.pdf']);
    fireEvent.click(submitButton());
    expect(submit).toHaveBeenCalledExactlyOnceWith({ note: '实际处理说明', filePids: [A, B] });
  });
  it('retains successful staging until the user explicitly removes the failed selection', async () => {
    transport.mockResolvedValueOnce(uploaded()).mockRejectedValueOnce(new Error('offline'));
    const submit = open();
    choose([image(), image('失败.jpg')]);
    await screen.findByText('上传失败，文件仍保留');
    expect(submitButton()).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: '移除 失败.jpg' }));
    expect(submitButton()).toBeEnabled();
    fireEvent.click(submitButton());
    expect(submit).toHaveBeenCalledExactlyOnceWith({ note: '实际处理说明', filePids: [A] });
    expect(transport.mock.calls.every((call) => call[0] === '/api/file/upload')).toBe(true);
  });
  it('never substitutes a numeric file id or URL for a canonical public PID', async () => {
    transport.mockResolvedValueOnce(uploaded('123'));
    const submit = open();
    choose([image()]);
    await screen.findByText('上传失败，文件仍保留');
    expect(submitButton()).toBeDisabled();
    expect(submit).not.toHaveBeenCalled();
  });
  it('rejects duplicate receipts without silently merging two selected files', async () => {
    transport.mockResolvedValue(uploaded());
    open();
    choose([image(), image('另一个.jpg')]);
    await screen.findByText('上传失败，文件仍保留');
    expect(submitButton()).toBeDisabled();
  });
  it('keeps wrong type and oversize selections visible without uploading them', async () => {
    open();
    choose([
      new File(['x'], '脚本.txt', { type: 'text/plain' }),
      new File(['x'.repeat(101)], '过大.pdf', { type: 'application/pdf' }),
    ]);
    await screen.findByText('不支持此文件类型，请移除或另选文件');
    expect(screen.getByText('超出单文件大小限制，请移除或另选文件')).toBeVisible();
    expect(transport).not.toHaveBeenCalled();
    expect(submitButton()).toBeDisabled();
  });
  it('rejects an entire over-capacity selection and retains all selected names', async () => {
    open();
    choose(Array.from({ length: 11 }, (_, i) => image('照片' + i + '.jpg')));
    expect(screen.getAllByText('附件数量超限，本次选择未上传，请移除多余文件后重试')).toHaveLength(
      11,
    );
    expect(transport).not.toHaveBeenCalled();
    expect(submitButton()).toBeDisabled();
  });
  it('ignores a late upload after cancel and never adds it to the next prompt', async () => {
    const late = deferred();
    transport.mockReturnValueOnce(late.promise);
    open();
    choose([image()]);
    fireEvent.click(screen.getByTestId('form-dialog-cancel'));
    const next = open();
    await act(async () => late.resolve(uploaded()));
    expect(screen.queryByText('现场甲.jpg')).toBeNull();
    expect(submitButton()).toBeEnabled();
    fireEvent.click(submitButton());
    expect(next).toHaveBeenCalledExactlyOnceWith({ note: '实际处理说明', filePids: [] });
  });
  it.each([
    undefined,
    { maxCount: 11, accept: '.pdf' },
    { maxCount: 2, accept: 'arbitrary' },
    { maxCount: 2, accept: '.pdf', relation: 'parent' },
  ])('blocks invalid configuration %j', async (uploadedFiles) => {
    open({ fields: [{ field: 'filePids', type: 'uploaded-files', uploadedFiles }] });
    expect(await screen.findByRole('alert')).toHaveTextContent('附件配置或初始值无效');
    expect(submitButton()).toBeDisabled();
    expect(transport).not.toHaveBeenCalled();
  });
  it('refuses unverified seeded PID values instead of displaying raw IDs or granting file reads', async () => {
    open({ defaults: { filePids: [A] } });
    expect(await screen.findByRole('alert')).toHaveTextContent('附件配置或初始值无效');
    expect(screen.queryByText(A)).toBeNull();
    expect(submitButton()).toBeDisabled();
    expect(transport).not.toHaveBeenCalled();
  });
  it('validates payload shape independently of uploader state', () => {
    expect(uploadedFilesConfig(config).maxCount).toBe(10);
    expect(validUploadedFilePids([], 10)).toBe(true);
    expect(validUploadedFilePids([A, B], 10)).toBe(true);
    for (const invalid of [
      [A, A],
      ['123'],
      [{ fileId: A }],
      '/api/file/download/' + A,
      Array(11).fill(A),
    ])
      expect(validUploadedFilePids(invalid, 10)).toBe(false);
  });
});
