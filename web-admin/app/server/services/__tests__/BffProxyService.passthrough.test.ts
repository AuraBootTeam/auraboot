// @vitest-environment node
//
// Node, not jsdom, for the same reason as the framing test: under jsdom axios uses the XHR adapter
// and never touches Node's http stack, so what the BFF actually writes on the wire goes unmeasured.
import { describe, expect, it, vi } from 'vitest';
import * as http from 'http';
import { BffProxyService } from '../BffProxyService';

/**
 * The BFF sits between a customer's browser and the backend on the one path we ask customers to put
 * on their own website: `<script src=".../widget.js">`. It has now broken that path twice, in two
 * different ways, and both times only through the BFF — a curl straight at the backend was fine.
 *
 *   1. A wildcard Accept header was narrowed to application/json, so the script tag got a 406.
 *   2. Accept was fixed; then the response body was re-serialised as JSON, so the script tag
 *      loaded `"var AuraCS=…"` — a quoted string. No error, no log, no widget.
 *
 * These tests pin both, against a real HTTP server rather than a mocked axios, because a mock would
 * have happily "passed" while the real socket carried a JSON-quoted program.
 */

/** A backend that answers with whatever content-type and body the test asks for. */
function startBackend(contentType: string, body: string) {
  return new Promise<{ port: number; lastHeaders: () => http.IncomingHttpHeaders; close(): void }>(
    (resolve) => {
      let headers: http.IncomingHttpHeaders = {};
      const server = http.createServer((req, res) => {
        headers = req.headers;
        res.writeHead(200, { 'Content-Type': contentType });
        res.end(body);
      });
      server.listen(0, '127.0.0.1', () => {
        const port = (server.address() as any).port;
        resolve({
          port,
          lastHeaders: () => headers,
          close: () => server.close(),
        });
      });
    },
  );
}

/** An express-ish response that records what was actually sent. */
function recordingResponse() {
  const state = {
    status: 0,
    headers: {} as Record<string, string>,
    body: undefined as unknown,
    sentVia: '' as 'json' | 'send' | '',
  };
  const res: any = {
    status(code: number) {
      state.status = code;
      return res;
    },
    set(key: string, value: string) {
      state.headers[String(key).toLowerCase()] = value;
      return res;
    },
    setHeader(key: string, value: string) {
      state.headers[String(key).toLowerCase()] = value;
      return res;
    },
    removeHeader(key: string) {
      delete state.headers[String(key).toLowerCase()];
    },
    json(payload: unknown) {
      state.body = payload;
      state.sentVia = 'json';
    },
    send(payload: unknown) {
      state.body = payload;
      state.sentVia = 'send';
    },
    write() {},
    end() {},
    headersSent: false,
  };
  return { res, state };
}

function browserRequest(path: string, origin?: string) {
  return {
    method: 'GET',
    url: path,
    originalUrl: path,
    headers: {
      // Exactly what a browser sends for <script src>. Not application/json, and not absent.
      accept: '*/*',
      ...(origin ? { origin } : {}),
    },
    body: undefined,
    ip: '127.0.0.1',
    connection: { remoteAddress: '127.0.0.1' },
  } as any;
}

describe('BffProxyService — non-JSON pass-through', () => {
  it('🚨 forwards an embeddable script as JavaScript, not as a JSON string', async () => {
    const program = 'var AuraCS=(function(){return{init:function(){}}})();';
    const backend = await startBackend('application/javascript; charset=utf-8', program);

    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });
    const { res, state } = recordingResponse();

    await service.handleApiRequest(browserRequest('/api/public/cs/widget.js'), res);
    backend.close();

    // The body is the program itself. If this were res.json(), the customer's <script> tag would
    // receive `"var AuraCS=…"` — a string literal that parses, runs, and does nothing at all.
    expect(state.sentVia).toBe('send');
    expect(state.body).toBe(program);
    expect(state.headers['content-type']).toContain('javascript');
    expect(state.headers['content-type']).not.toContain('json');
  });

  it('still sends JSON as JSON', async () => {
    const backend = await startBackend('application/json', JSON.stringify({ code: '0', data: 42 }));

    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });
    const { res, state } = recordingResponse();

    await service.handleApiRequest(browserRequest('/api/meta/models'), res);
    backend.close();

    expect(state.sentVia).toBe('json');
    expect(state.body).toEqual({ code: '0', data: 42 });
    expect(state.headers['content-type']).toContain('json');
  });
});

describe('BffProxyService — Origin as a security input', () => {
  it('🚨 forwards Origin on the keyed public paths, where the backend decides who may speak', async () => {
    const backend = await startBackend('application/json', '{"ok":true}');

    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });
    const { res } = recordingResponse();

    await service.handleApiRequest(
      browserRequest('/api/public/cs/session', 'https://shop.example.com'),
      res,
    );
    const seen = backend.lastHeaders();
    backend.close();

    // Without this the backend sees no Origin at all and refuses every widget on every site — the
    // allowlist is the whole trust boundary, and it cannot check a header that never arrives.
    expect(seen.origin).toBe('https://shop.example.com');
  });

  it('does NOT forward Origin on ordinary admin paths', async () => {
    const backend = await startBackend('application/json', '{"ok":true}');

    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });
    const { res } = recordingResponse();

    await service.handleApiRequest(
      browserRequest('/api/meta/models', 'http://localhost:5101'),
      res,
    );
    const seen = backend.lastHeaders();
    backend.close();

    // The backend never reads Origin here, and handing it one would invite its CORS filter to start
    // judging requests it currently waves through — every admin call, in every deployment whose web
    // origin is not in the backend's list.
    expect(seen.origin).toBeUndefined();
  });
});

describe('BffProxyService — recognising a stream', () => {
  it('🚨 treats any request that asks for an event stream as one, not just the five listed paths', async () => {
    // A never-ending body. If the BFF proxies this as an ordinary request, axios waits for an end
    // that by construction never comes — which is exactly what happened to the visitor's live
    // stream: ERR_BAD_RESPONSE in the browser, and the seat's reply stranded on the other side.
    const server = http.createServer((_req, res) => {
      res.writeHead(200, { 'Content-Type': 'text/event-stream' });
      res.write('event: message\ndata: {"content":"hi"}\n\n');
      // deliberately never res.end()
    });
    await new Promise<void>((r) => server.listen(0, '127.0.0.1', () => r()));
    const port = (server.address() as any).port;

    const service = new BffProxyService({ target: `http://127.0.0.1:${port}` });
    const chunks: string[] = [];
    const res: any = {
      status: () => res,
      set: () => res,
      setHeader: () => res,
      removeHeader: () => {},
      flushHeaders: () => {},
      write: (c: any) => chunks.push(String(c)),
      end: () => {},
      json: () => {
        throw new Error('a stream must not be forwarded as JSON');
      },
      send: () => {
        throw new Error('a stream must not be buffered');
      },
      on: () => res,
      headersSent: true,
    };

    const req = {
      method: 'GET',
      url: '/api/public/cs/stream?conversationPid=x',
      originalUrl: '/api/public/cs/stream?conversationPid=x',
      // The path is on no allowlist. The Accept header is the whole signal.
      headers: { accept: 'text/event-stream' },
      body: undefined,
      ip: '127.0.0.1',
      connection: { remoteAddress: '127.0.0.1' },
      on: () => {},
    } as any;

    void service.handleApiRequest(req, res);
    await vi.waitFor(() => expect(chunks.join('')).toContain('data:'), { timeout: 5000 });

    server.close();
    expect(chunks.join('')).toContain('"content":"hi"');
  });
});
