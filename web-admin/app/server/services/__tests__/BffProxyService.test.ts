import { describe, expect, it } from 'vitest';
import { BffProxyService } from '../BffProxyService';

describe('BffProxyService', () => {
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
