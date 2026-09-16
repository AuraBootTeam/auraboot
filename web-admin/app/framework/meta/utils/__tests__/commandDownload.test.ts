import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadWithAuth, resolveCommandFileDownload } from '../commandDownload';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  localStorage.clear();
});
describe('explicit command file downloads', () => {
  it('requires an opt-in and never downloads an unrelated record id', () => {
    expect(resolveCommandFileDownload({ fileId: 'file-1' })).toBeUndefined();
    expect(
      resolveCommandFileDownload({ data: { id: 'record-1' } }, { fileIdField: 'fileId' }),
    ).toBeUndefined();
  });
  it('resolves nested command envelopes and encodes the file identifier', () => {
    expect(
      resolveCommandFileDownload(
        { data: { data: { fileId: 'file/a' } } },
        { fileIdField: 'fileId' },
      ),
    ).toBe('/api/file/download/file%2Fa');
    expect(
      resolveCommandFileDownload(
        { data: { artifact: { pid: 'file-b' } } },
        { fileIdField: 'artifact.pid' },
      ),
    ).toBe('/api/file/download/file-b');
  });
  it('sends authentication and downloads the server-named file bytes', async () => {
    localStorage.setItem(JWT_TOKEN_KEY, 'unit-token');
    const blob = new Blob(['xlsx-fixture']);
    const fetchMock = vi
      .fn()
      .mockResolvedValue({
        ok: true,
        blob: async () => blob,
        headers: new Headers({
          'Content-Disposition': "attachment; filename*=UTF-8''%E6%8A%A5%E4%BB%B7.xlsx",
        }),
      });
    vi.stubGlobal('fetch', fetchMock);
    const create = vi.fn().mockReturnValue('blob:fixture');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }));
    let downloaded = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloaded = this.download;
    });
    await downloadWithAuth('/api/file/download/file-1');
    expect(fetchMock).toHaveBeenCalledWith('/api/file/download/file-1', {
      method: 'GET',
      credentials: 'include',
      headers: { Authorization: 'Bearer unit-token' },
    });
    expect(create).toHaveBeenCalledWith(blob);
    expect(downloaded).toBe('报价.xlsx');
  });
  it('does not turn a denied download into a successful artifact', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue({ ok: false, status: 403, statusText: 'Forbidden' }),
    );
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await expect(downloadWithAuth('/api/file/download/denied')).rejects.toThrow('403');
    expect(click).not.toHaveBeenCalled();
  });
});
