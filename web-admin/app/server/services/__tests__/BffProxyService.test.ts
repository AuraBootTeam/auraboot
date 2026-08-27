import { afterEach, describe, expect, it, vi } from 'vitest';
import axios from 'axios';
import {
  BffProxyService,
  isLongRunningProxyPath,
  shouldForwardRequestBody,
} from '../BffProxyService';

vi.mock('axios', () => {
  const axiosMock = vi.fn();
  Object.assign(axiosMock, {
    get: vi.fn(),
    isAxiosError: vi.fn(() => false),
  });
  return { default: axiosMock };
});

function createResponseRecorder() {
  const headers = new Map<string, string | number | readonly string[]>();
  const response = {
    headersSent: false,
    statusCode: 0,
    body: undefined as Buffer | undefined,
    setHeader: vi.fn((key: string, value: string | number | readonly string[]) => {
      headers.set(key, value);
      return response;
    }),
    removeHeader: vi.fn((key: string) => {
      headers.delete(key);
      return response;
    }),
    set: vi.fn((key: string, value: string | number | readonly string[]) => {
      headers.set(key, value);
      return response;
    }),
    status: vi.fn((statusCode: number) => {
      response.statusCode = statusCode;
      return response;
    }),
    send: vi.fn((body: Buffer) => {
      response.body = body;
      response.headersSent = true;
      return response;
    }),
    json: vi.fn(),
  };
  return { headers, response };
}

describe('isLongRunningProxyPath', () => {
  it('gives bounded BOM format exploration the long-running proxy budget', () => {
    expect(isLongRunningProxyPath('/api/meta/commands/execute/bom:start_conversion')).toBe(true);
    expect(
      isLongRunningProxyPath(
        '/api/meta/commands/execute/bom:start_conversion?sourceRecordId=01ABC',
      ),
    ).toBe(true);
    expect(isLongRunningProxyPath('/api/meta/commands/execute/bom:explore_format')).toBe(true);
    expect(
      isLongRunningProxyPath('/api/meta/commands/execute/bom:explore_format?taskId=01ABC'),
    ).toBe(true);
  });

  it('preserves existing plugin/deploy classification without widening ordinary commands', () => {
    expect(isLongRunningProxyPath('/api/plugins/import')).toBe(true);
    expect(isLongRunningProxyPath('/api/plugins/packages/quote-bom/deploy')).toBe(true);
    expect(isLongRunningProxyPath('/api/meta/commands/execute/bom:apply_parse_plan')).toBe(false);
    expect(isLongRunningProxyPath('/api/pages/page_1')).toBe(false);
  });
});

describe('BffProxyService', () => {
  afterEach(() => {
    vi.clearAllMocks();
    vi.unstubAllGlobals();
    vi.unstubAllEnvs();
  });

  it('reports an aborted backend request as an uncertain timeout instead of a disconnect', async () => {
    vi.stubEnv('BFF_LONG_RUNNING_TIMEOUT_MS', '123456');
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    vi.mocked(axios).mockRejectedValueOnce({
      message: 'timeout of 30000ms exceeded',
      code: 'ECONNABORTED',
      config: { method: 'post', url: '/api/meta/commands/execute/bom:start_conversion' },
    });
    const { response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/meta/commands/execute/bom:start_conversion',
        url: '/api/meta/commands/execute/bom:start_conversion',
        headers: { 'content-type': 'application/json' },
        body: { rawFileId: 'file-1' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(axios).toHaveBeenCalledWith(expect.objectContaining({ timeout: 123456 }));
    expect(response.statusCode).toBe(504);
    expect(response.json).toHaveBeenCalledWith(
      expect.objectContaining({
        error: 'Gateway Timeout',
        code: 'BACKEND_REQUEST_TIMEOUT',
        message: expect.stringContaining('may still be processing'),
        details: expect.stringContaining('Do not submit the same operation again'),
      }),
    );
    expect(response.json).not.toHaveBeenCalledWith(
      expect.objectContaining({ message: 'Failed to connect to backend service' }),
    );
  });

  it('requests and forwards arbitrary XLSX responses as opaque bytes', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const xlsxBytes = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0xff, 0x00, 0x81]);
    vi.mocked(axios).mockResolvedValueOnce({
      status: 200,
      headers: {
        'content-type': 'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'content-disposition': 'attachment; filename="arbitrary-template.xlsx"',
        'content-length': String(xlsxBytes.length),
      },
      data: xlsxBytes,
    });
    const { headers, response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'GET',
        originalUrl: '/api/arbitrary/templates/current',
        url: '/api/arbitrary/templates/current',
        headers: { accept: '*/*' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(axios).toHaveBeenCalledWith(
      expect.objectContaining({
        url: 'http://127.0.0.1:6443/api/arbitrary/templates/current',
        responseType: 'arraybuffer',
        timeout: 60000,
      }),
    );
    expect(response.statusCode).toBe(200);
    expect(headers.get('Content-Type')).toBe(
      'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
    );
    expect(headers.get('content-disposition')).toBe(
      'attachment; filename="arbitrary-template.xlsx"',
    );
    expect(response.body).toEqual(xlsxBytes);
    expect(response.json).not.toHaveBeenCalled();
  });

  it('forwards JSON success responses as the exact upstream bytes', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const jsonBytes = Buffer.from('{"data":{"name":"王佳霞"}}', 'utf8');
    vi.mocked(axios).mockResolvedValueOnce({
      status: 200,
      headers: {
        'content-type': 'application/json; charset=utf-8',
        'content-length': String(jsonBytes.length),
      },
      data: jsonBytes,
    });
    const { headers, response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'GET',
        originalUrl: '/api/arbitrary/json',
        url: '/api/arbitrary/json',
        headers: { accept: 'application/json' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(axios).toHaveBeenCalledWith(expect.objectContaining({ responseType: 'arraybuffer' }));
    expect(response.statusCode).toBe(200);
    expect(headers.get('Content-Type')).toBe('application/json; charset=utf-8');
    expect(response.body).toEqual(jsonBytes);
    expect(response.json).not.toHaveBeenCalled();
  });

  it('preserves the configured timeout budget for long-running operations', async () => {
    vi.stubEnv('BFF_LONG_RUNNING_TIMEOUT_MS', '123456');
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    vi.mocked(axios).mockResolvedValueOnce({
      status: 200,
      headers: { 'content-type': 'application/json' },
      data: Buffer.from('{"code":"0"}', 'utf8'),
    });
    const { response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/plugins/import',
        url: '/api/plugins/import',
        headers: { accept: 'application/json' },
        body: { plugin: 'example' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(axios).toHaveBeenCalledWith(
      expect.objectContaining({ responseType: 'arraybuffer', timeout: 123456 }),
    );
  });

  it('forwards JavaScript responses without quoting or decoding them', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const scriptBytes = Buffer.from('window.AuraEmbed={ready:true};', 'utf8');
    vi.mocked(axios).mockResolvedValueOnce({
      status: 200,
      headers: {
        'content-type': 'application/javascript; charset=utf-8',
        'x-content-version': 'sdk-7',
      },
      data: scriptBytes,
    });
    const { headers, response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'GET',
        originalUrl: '/api/arbitrary/embed/current',
        url: '/api/arbitrary/embed/current',
        headers: { accept: '*/*' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(headers.get('Content-Type')).toBe('application/javascript; charset=utf-8');
    expect(headers.get('x-content-version')).toBe('sdk-7');
    expect(response.body).toEqual(scriptBytes);
    expect(response.json).not.toHaveBeenCalled();
  });

  it('preserves an empty upstream response without inventing a JSON object', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    vi.mocked(axios).mockResolvedValueOnce({
      status: 204,
      headers: {},
      data: Buffer.alloc(0),
    });
    const { response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'DELETE',
        originalUrl: '/api/arbitrary/resource/42',
        url: '/api/arbitrary/resource/42',
        headers: { accept: 'application/json' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(response.statusCode).toBe(204);
    expect(response.body).toEqual(Buffer.alloc(0));
    expect(response.json).not.toHaveBeenCalled();
  });

  it('keeps JSON error envelopes readable when axios returns arraybuffer data', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const envelope = { code: 'BadParam', message: 'invalid request' };
    vi.mocked(axios.isAxiosError).mockReturnValueOnce(true);
    vi.mocked(axios).mockRejectedValueOnce({
      message: 'Request failed with status code 422',
      code: 'ERR_BAD_REQUEST',
      config: { method: 'post', url: '/api/arbitrary/validate', headers: {} },
      response: {
        status: 422,
        statusText: 'Unprocessable Entity',
        headers: { 'content-type': 'application/json; charset=utf-8' },
        data: Buffer.from(JSON.stringify(envelope), 'utf8'),
      },
    });
    const { response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/arbitrary/validate',
        url: '/api/arbitrary/validate',
        headers: { accept: 'application/json' },
        body: { value: 'invalid' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(response.statusCode).toBe(422);
    expect(response.json).toHaveBeenCalledWith(envelope);
    expect(response.send).not.toHaveBeenCalled();
  });

  it('forwards non-JSON error artifacts without converting bytes to text', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const artifactBytes = Buffer.from([0x25, 0x50, 0x44, 0x46, 0x00, 0xff]);
    vi.mocked(axios.isAxiosError).mockReturnValueOnce(true);
    vi.mocked(axios).mockRejectedValueOnce({
      message: 'Request failed with status code 409',
      code: 'ERR_BAD_REQUEST',
      config: { method: 'get', url: '/api/arbitrary/report', headers: {} },
      response: {
        status: 409,
        statusText: 'Conflict',
        headers: {
          'content-type': 'application/pdf',
          'content-disposition': 'attachment; filename="conflict.pdf"',
        },
        data: artifactBytes,
      },
    });
    const { headers, response } = createResponseRecorder();

    await service.handleApiRequest(
      {
        method: 'GET',
        originalUrl: '/api/arbitrary/report',
        url: '/api/arbitrary/report',
        headers: { accept: '*/*' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      response as any,
    );

    expect(response.statusCode).toBe(409);
    expect(headers.get('Content-Type')).toBe('application/pdf');
    expect(headers.get('content-disposition')).toBe('attachment; filename="conflict.pdf"');
    expect(response.body).toEqual(artifactBytes);
    expect(response.json).not.toHaveBeenCalled();
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
    // `*/*` is passed through, not narrowed. Rewriting it to application/json made every endpoint
    // that produces something else answer 406 — including the scripts customers embed on their own
    // websites, which a browser fetches with exactly this Accept.
    expect(headers.accept).toBe('*/*');
    expect(headers.origin).toBeUndefined();
    expect(headers.referer).toBeUndefined();
    expect(headers.host).toBeUndefined();
    expect(headers['access-control-request-method']).toBeUndefined();
    expect(headers['access-control-request-headers']).toBeUndefined();
    expect(headers['sec-fetch-mode']).toBeUndefined();
  });

  // Every consumer of sanitizeHeaders (the axios proxy and the SSE fetch) rebuilds
  // the request body from the *parsed* req.body, so the byte
  // length it puts on the wire is its own — never the client's. Forwarding the client's
  // framing headers therefore lets `Content-Length` disagree with the bytes actually
  // written, which desyncs the pooled keep-alive socket to Spring: Tomcat reads only the
  // declared number of body bytes and then parses the leftovers as the next request line
  // ("Invalid character found in method name [{}...]"), corrupting an unrelated request.
  //
  // The sharpest case is a body-less POST to a no-@RequestBody endpoint: express.json()
  // turns the empty body into `{}`, axios serializes those 2 bytes, and the forwarded
  // `content-length: 0` leaves `{}` stranded in the socket buffer.
  it('does not forward client framing headers, which would desync the backend socket', async () => {
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
      originalUrl: '/api/decision/versions/01ABC/validate',
      url: '/api/decision/versions/01ABC/validate',
      headers: {
        authorization: 'Bearer test-token',
        'content-type': 'application/json',
        'content-length': '0',
        'transfer-encoding': 'chunked',
      },
    });

    expect(headers['content-length']).toBeUndefined();
    expect(headers['transfer-encoding']).toBeUndefined();
    // …while the headers that describe the payload itself still go through.
    expect(headers['content-type']).toBe('application/json');
    expect(headers.authorization).toBe('Bearer test-token');
  });

  it('proxies large binary downloads without JSON reserialization', async () => {
    const service = new BffProxyService({ target: 'http://127.0.0.1:6443' });
    const svgBytes = Buffer.concat([
      Buffer.from('<?xml version="1.0"?><svg>'),
      Buffer.alloc(900_000, 'A'),
      Buffer.from('</svg>'),
    ]);
    vi.mocked(axios).mockResolvedValueOnce({
      status: 200,
      headers: {
        'content-type': 'image/svg+xml',
        'content-length': String(svgBytes.length),
        'transfer-encoding': 'chunked',
        'content-disposition': 'inline; filename="board-top.svg"',
      },
      data: svgBytes,
    });
    const { headers: responseHeaders, response: res } = createResponseRecorder();

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

    expect(axios).toHaveBeenCalledWith(
      expect.objectContaining({
        method: 'get',
        url: 'http://127.0.0.1:6443/api/file/download/01KV6XD0AX2JQ9M3M1VZZFC34J',
        responseType: 'arraybuffer',
        headers: expect.objectContaining({
          authorization: 'Bearer test-token',
        }),
      }),
    );
    expect(res.statusCode).toBe(200);
    expect(responseHeaders.get('Content-Type')).toBe('image/svg+xml');
    expect(responseHeaders.has('Content-Length')).toBe(false);
    expect(responseHeaders.has('Transfer-Encoding')).toBe(false);
    expect(responseHeaders.get('content-disposition')).toBe('inline; filename="board-top.svg"');
    expect(res.body).toEqual(svgBytes);
    expect(res.json).not.toHaveBeenCalled();
  });

  it('does not narrow Accept: */* — a <script src> must be able to fetch a script', async () => {
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
      originalUrl: '/api/crm/forms/abc/sdk.js',
      url: '/api/crm/forms/abc/sdk.js',
      headers: { accept: '*/*' },
    });

    // A browser fetching <script src="…/sdk.js"> sends exactly this. Rewriting it to
    // application/json narrows what the client said it would take, and the endpoint — which
    // produces application/javascript — answers 406. The customer pastes the snippet and gets
    // nothing, with nothing anywhere saying why.
    expect(headers.accept).toBe('*/*');
  });

  it('supplies */* when the request carries no Accept at all', async () => {
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
      headers: {},
    });

    expect(headers.accept).toBe('*/*');
  });
});
