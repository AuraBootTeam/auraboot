import { afterEach, describe, expect, it, vi } from 'vitest';
import { actionRegistry } from '../ActionRegistry';

afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); });
const messages: Record<string, string> = {
  'file.download.invalid_url': 'Protected file link is unavailable',
  'file.download.invalid_response': 'A complete file was not returned',
  'file.download.failed': 'Download failed; check current access and expiry',
};
const t = (key: string) => messages[key] || key;

describe('native protected file download action', () => {
  it.each(['https://foreign.invalid/api/file.csv', '/api/../private-file', '/p/home', '/api\\file'])('rejects an uncontrolled URL: %s', async url => {
    const fetch = vi.fn();vi.stubGlobal('fetch', fetch);
    await expect(actionRegistry.execute('file.download', { args: { url }, t })).rejects.toThrow('Protected file link is unavailable');
    expect(fetch).not.toHaveBeenCalled();
  });
  it('resolves the current record URL and downloads the server-named CSV with the session cookie', async () => {
    const blob = new Blob(['\uFEFFname\r\nReader\r\n']);
    const fetch = vi.fn().mockResolvedValue({ ok: true, headers: new Headers({
      'Content-Type': 'text/csv', 'Content-Disposition': 'attachment; filename="recipient-statistics.csv"',
    }), blob: async () => blob });vi.stubGlobal('fetch', fetch);
    const create = vi.fn().mockReturnValue('blob:registry-csv');
    vi.stubGlobal('URL', Object.assign(URL, { createObjectURL: create, revokeObjectURL: vi.fn() }));
    let name = '';
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) { name = this.download; });
    const evaluateTemplate = vi.fn().mockReturnValue('/api/exports/domain/current-task/download');
    await actionRegistry.execute('file.download', {
      args: { url: '${record.downloadUrl}', expectedContentType: 'text/csv' }, t,
      expressionEvaluator: { evaluateTemplate }, expressionContext: { record: { downloadUrl: '/api/exports/domain/current-task/download' } },
    });
    expect(evaluateTemplate).toHaveBeenCalledWith('${record.downloadUrl}', { record: { downloadUrl: '/api/exports/domain/current-task/download' } });
    expect(fetch).toHaveBeenCalledWith('/api/exports/domain/current-task/download', { method: 'GET', credentials: 'same-origin' });
    expect(create).toHaveBeenCalledWith(blob);expect(name).toBe('recipient-statistics.csv');
  });
  it('keeps a denied response as a failed action with its public reason', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, headers: new Headers({ 'Content-Type': 'application/json' }),
      json: async () => ({ code: '403', message: 'Forbidden', context: { detail: 'Current access was revoked' } }),
    }));
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    await expect(actionRegistry.execute('file.download', { args: { url: '/api/exports/domain/revoked/download' }, t, locale: 'en-US' }))
      .rejects.toThrow('Current access was revoked');
    expect(click).not.toHaveBeenCalled();
  });
  it('explains an HTML success response instead of downloading a login page as a CSV', async () => {
    const read = vi.fn();vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true,
      headers: new Headers({ 'Content-Type': 'text/html' }), blob: read }));
    await expect(actionRegistry.execute('file.download', { args: { url: '/api/exports/domain/task/download', expectedContentType: 'text/csv' }, t }))
      .rejects.toThrow('A complete file was not returned');
    expect(read).not.toHaveBeenCalled();
  });
});
