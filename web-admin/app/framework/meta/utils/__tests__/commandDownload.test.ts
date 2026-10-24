import { afterEach, describe, expect, it, vi } from 'vitest';
import { downloadWithAuth, FileDownloadValidationError, resolveCommandFileDownload } from '../commandDownload';

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
  it('uses the httpOnly session cookie and downloads the server-named file bytes', async () => {
    localStorage.setItem('jwtToken', 'legacy-token-that-must-not-be-read');
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
      credentials: 'same-origin',
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
  it.each(['text/html', 'application/json'])('rejects a successful %s response before reading artifact bytes', async contentType => {
    const read = vi.fn().mockResolvedValue(new Blob(['not a CSV']));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, headers: new Headers({
      'Content-Type': contentType, 'Content-Disposition': 'attachment; filename="statistics.csv"',
    }), blob: read }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await expect(downloadWithAuth('/api/exports/domain/task/download', {
      expectedContentTypes: ['text/csv'], requireAttachment: true, requireNonEmpty: true,
    })).rejects.toBeInstanceOf(FileDownloadValidationError);
    expect(read).not.toHaveBeenCalled();expect(click).not.toHaveBeenCalled();
  });
  it('rejects missing attachment names and empty files without triggering a browser download', async () => {
    const read = vi.fn().mockResolvedValue(new Blob([]));
    const headers = new Headers({ 'Content-Type': 'text/csv' });
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, headers, blob: read }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await expect(downloadWithAuth('/api/exports/domain/task/download', {
      expectedContentTypes: ['text/csv'], requireAttachment: true,
    })).rejects.toMatchObject({ code: 'ATTACHMENT' });
    expect(read).not.toHaveBeenCalled();
    headers.set('Content-Disposition', 'attachment; filename="statistics.csv"');
    await expect(downloadWithAuth('/api/exports/domain/task/download', {
      expectedContentTypes: ['text/csv'], requireAttachment: true, requireNonEmpty: true,
    })).rejects.toMatchObject({ code: 'EMPTY' });
    expect(click).not.toHaveBeenCalled();
  });
  it('validates the named CSV response and retains the exact BOM-containing blob', async () => {
    const blob = new Blob(['\uFEFFname,state\r\n"Reader, A",Unread\r\n']);
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, headers: new Headers({
      'Content-Type': 'text/csv; charset=UTF-8', 'Content-Disposition': 'attachment; filename="statistics.csv"',
    }), blob: async () => blob }));
    const create = vi.fn().mockReturnValue('blob:validated-csv');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }));
    let name = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { name = this.download; });
    await downloadWithAuth('/api/exports/domain/task/download', {
      expectedContentTypes: ['text/csv'], requireAttachment: true, requireNonEmpty: true,
    });
    expect(create).toHaveBeenCalledWith(blob);expect(name).toBe('statistics.csv');
  });
});
