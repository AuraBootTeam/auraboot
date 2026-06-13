import { describe, expect, it } from 'vitest';
import {
  BffProxyService,
  isBinaryDownloadPath,
  shouldForwardRequestBody,
} from '../BffProxyService';

describe('isBinaryDownloadPath', () => {
  it('detects /download/{id} as a mid-path segment (the file-download endpoint)', () => {
    // Regression: /api/file/download/{fileId} was missed by the old
    // `/download$|/download?` regex and fell through to the JSON proxy, which
    // re-serialized the xlsx bytes as a JSON string ("PK…").
    expect(isBinaryDownloadPath('/api/file/download/01KSW25R5V19GS99XE77PAG0HW')).toBe(true);
  });

  it('still detects /download at end of path and with a query', () => {
    expect(isBinaryDownloadPath('/api/pages/page_1/download')).toBe(true);
    expect(isBinaryDownloadPath('/api/export-tasks/abc/download?fmt=xlsx')).toBe(true);
    expect(isBinaryDownloadPath('/api/templates/t1/download')).toBe(true);
  });

  it('detects report artifact export endpoints that stream binary bytes', () => {
    expect(isBinaryDownloadPath('/api/reports/export/excel')).toBe(true);
    expect(isBinaryDownloadPath('/api/reports/export/excel?format=xlsx')).toBe(true);
    expect(isBinaryDownloadPath('/api/reports/export/pdf')).toBe(true);
  });

  it('does not over-match paths that merely contain "download"', () => {
    expect(isBinaryDownloadPath('/api/downloads/list')).toBe(false);
    expect(isBinaryDownloadPath('/api/file/downloaded')).toBe(false);
    expect(isBinaryDownloadPath('/api/pages/page_1')).toBe(false);
  });
});

describe('BffProxyService', () => {
  it('does not forward empty JSON bodies on GET or HEAD requests', () => {
    expect(shouldForwardRequestBody('GET')).toBe(false);
    expect(shouldForwardRequestBody('head')).toBe(false);
    expect(shouldForwardRequestBody('POST')).toBe(true);
    expect(shouldForwardRequestBody('PUT')).toBe(true);
    expect(shouldForwardRequestBody('DELETE')).toBe(true);
  });

  it('does not forward browser CORS headers to the Spring backend', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const headers = await (
      service as unknown as {
        sanitizeHeaders(req: {
          headers: Record<string, string>;
          originalUrl: string;
          url: string;
        }): Promise<Record<string, string>>;
      }
    ).sanitizeHeaders({
      originalUrl: '/api/pages/page_1',
      url: '/api/pages/page_1',
      headers: {
        accept: '*/*',
        authorization: 'Bearer test-token',
        'content-type': 'application/json',
        host: 'localhost:5237',
        origin: 'http://localhost:5237',
        referer: 'http://localhost:5237/unified-designer?pageId=page_1',
        'access-control-request-method': 'PUT',
        'access-control-request-headers': 'content-type',
        'sec-fetch-mode': 'cors',
      },
    });

    expect(headers.authorization).toBe('Bearer test-token');
    expect(headers['content-type']).toBe('application/json');
    expect(headers.accept).toBe('application/json');
    expect(headers.origin).toBeUndefined();
    expect(headers.referer).toBeUndefined();
    expect(headers.host).toBeUndefined();
    expect(headers['access-control-request-method']).toBeUndefined();
    expect(headers['access-control-request-headers']).toBeUndefined();
    expect(headers['sec-fetch-mode']).toBeUndefined();
  });
});
