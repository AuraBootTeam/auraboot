// @vitest-environment node
//
// MUST run in the node environment: under jsdom axios picks the XHR adapter and never
// touches Node's http stack, so what the BFF really writes on the wire would go
// unmeasured.
import { describe, expect, it, vi } from 'vitest';
import * as http from 'http';
import { BffProxyService } from '../BffProxyService';

/**
 * A backend that captures the exact request body bytes it received.
 *
 * AuraBoot ids are 18-19 digit snowflakes, past the 2^53 that JSON numbers can hold
 * exactly. express.json() parses the client's body with JSON.parse, and the proxy used
 * to re-serialise the result — so `{"id": 339393718375288832}` left the BFF as
 * `...288800`. Nothing errored: the backend simply operated on a different (usually
 * non-existent) record. Measured live before the fix:
 *   number → 339393718375288800   string → 339393718375288832
 */
function startBodyRecorder(): Promise<{
  port: number;
  received: Promise<string>;
  close(): void;
}> {
  return new Promise((resolve) => {
    let settle: (v: string) => void;
    const received = new Promise<string>((r) => (settle = r));

    const server = http.createServer((req, res) => {
      let body = '';
      req.on('data', (c) => (body += c));
      req.on('end', () => {
        settle(body);
        res.writeHead(200, { 'content-type': 'application/json' });
        res.end('{"code":"0"}');
      });
    });

    server.listen(0, '127.0.0.1', () => {
      resolve({
        port: (server.address() as { port: number }).port,
        received,
        close: () => server.close(),
      });
    });
  });
}

function fakeRes() {
  const res = {
    headersSent: false,
    statusCode: 0,
    setHeader: vi.fn(),
    status: vi.fn(() => res),
    send: vi.fn(() => res),
    json: vi.fn(() => res),
  };
  return res;
}

describe('BffProxyService snowflake id precision', () => {
  it('forwards an 18-digit id byte-for-byte instead of re-serialising it', async () => {
    const backend = await startBodyRecorder();
    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });

    // The exact bytes a browser would send. Note the id is a JSON *number*, which is
    // what makes this lossy the moment anything parses and re-stringifies it.
    const rawBody = Buffer.from('{"mentionTargets":[{"type":"human","id":339393718375288832}]}');

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/im/conversations/1/messages',
        url: '/api/im/conversations/1/messages',
        headers: {
          authorization: 'Bearer test-token',
          'content-type': 'application/json',
          'content-length': String(rawBody.length),
        },
        // Both shapes are present, exactly as express.json() leaves them: the parsed
        // object (already rounded) and the untouched bytes captured by its verify hook.
        body: JSON.parse(rawBody.toString()),
        rawBody,
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as never,
      fakeRes() as never,
    );

    const forwarded = await backend.received;
    backend.close();

    expect(forwarded, 'the id must reach the backend with every digit intact').toContain(
      '339393718375288832',
    );
    expect(forwarded, 'the rounded id must not appear').not.toContain('339393718375288800');
  });

  it('still forwards a body when only the parsed form is available', async () => {
    // Routes that consume the stream themselves leave no rawBody; the proxy must keep
    // working for them rather than dropping the body.
    const backend = await startBodyRecorder();
    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/anything',
        url: '/api/anything',
        headers: { authorization: 'Bearer t', 'content-type': 'application/json' },
        body: { hello: 'world' },
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as never,
      fakeRes() as never,
    );

    const forwarded = await backend.received;
    backend.close();
    expect(JSON.parse(forwarded)).toEqual({ hello: 'world' });
  });
});
