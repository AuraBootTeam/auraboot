import { afterEach, describe, expect, it, vi } from 'vitest';
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
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('only forwards non-empty request bodies on body-capable methods', () => {
    expect(shouldForwardRequestBody('GET', { q: 'ignored' })).toBe(false);
    expect(shouldForwardRequestBody('head', { q: 'ignored' })).toBe(false);
    expect(shouldForwardRequestBody('POST')).toBe(false);
    expect(shouldForwardRequestBody('POST', {})).toBe(false);
    expect(shouldForwardRequestBody('DELETE', {})).toBe(false);
    expect(shouldForwardRequestBody('PUT', '')).toBe(false);
    expect(shouldForwardRequestBody('PATCH', Buffer.alloc(0))).toBe(false);
    expect(shouldForwardRequestBody('POST', { value: 1 })).toBe(true);
    expect(shouldForwardRequestBody('PUT', 'raw')).toBe(true);
    expect(shouldForwardRequestBody('PATCH', Buffer.from('raw'))).toBe(true);
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

  it('proxies large binary downloads without JSON reserialization', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const svgBytes = Buffer.concat([
      Buffer.from('<?xml version="1.0"?><svg>'),
      Buffer.alloc(900_000, 'A'),
      Buffer.from('</svg>'),
    ]);
    const fetchMock = vi.fn(async () => {
      return new globalThis.Response(svgBytes, {
        status: 200,
        headers: {
          'content-type': 'image/svg+xml',
          'content-length': String(svgBytes.length),
          'content-disposition': 'inline; filename="board-top.svg"',
        },
      });
    });
    vi.stubGlobal('fetch', fetchMock);

    const responseHeaders = new Map<string, string | number | readonly string[]>();
    const res = {
      headersSent: false,
      statusCode: 0,
      body: undefined as Buffer | undefined,
      setHeader: vi.fn((key: string, value: string | number | readonly string[]) => {
        responseHeaders.set(key, value);
        return res;
      }),
      status: vi.fn((statusCode: number) => {
        res.statusCode = statusCode;
        return res;
      }),
      send: vi.fn((body: Buffer) => {
        res.body = body;
        res.headersSent = true;
        return res;
      }),
      json: vi.fn(),
    };

    await service.handleApiRequest(
      {
        method: 'GET',
        originalUrl: '/api/file/download/01KV6XD0AX2JQ9M3M1VZZFC34J',
        url: '/api/file/download/01KV6XD0AX2JQ9M3M1VZZFC34J',
        headers: {
          authorization: 'Bearer test-token',
          accept: '*/*',
          host: 'numnan.com',
        },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      res as any,
    );

    expect(fetchMock).toHaveBeenCalledWith(
      'http://127.0.0.1:6443/api/file/download/01KV6XD0AX2JQ9M3M1VZZFC34J',
      expect.objectContaining({
        method: 'get',
        headers: expect.objectContaining({
          authorization: 'Bearer test-token',
        }),
      }),
    );
    expect(res.statusCode).toBe(200);
    expect(responseHeaders.get('Content-Type')).toBe('image/svg+xml');
    expect(responseHeaders.get('Content-Length')).toBe(String(svgBytes.length));
    expect(responseHeaders.get('Content-Disposition')).toBe('inline; filename="board-top.svg"');
    expect(res.body).toEqual(svgBytes);
    expect(res.json).not.toHaveBeenCalled();
  });
});
