// @vitest-environment node
//
// MUST run in the node environment: under jsdom axios picks the XHR adapter and never
// touches Node's http stack, so the Content-Length the BFF really puts on the wire in
// production would go unmeasured (jsdom even turns this into a CORS preflight).
import { describe, expect, it, vi } from 'vitest';
import * as http from 'http';
import { BffProxyService } from '../BffProxyService';

/**
 * A backend that answers 200 but reports, for the request it received, the Content-Length
 * that was declared versus the number of body bytes that actually arrived. Anything other
 * than declared === actual leaves stray bytes in the keep-alive socket, which Tomcat then
 * parses as the *next* request's request line.
 */
function startFramingRecorder(): Promise<{
  port: number;
  received: Promise<{ declared: number; actual: number }>;
  close(): void;
}> {
  return new Promise((resolve) => {
    let settle: (v: { declared: number; actual: number }) => void;
    const received = new Promise<{ declared: number; actual: number }>((r) => (settle = r));

    const server = http.createServer();
    // Read the raw socket rather than using the parsed request: Node's own parser would
    // stop at Content-Length bytes and hide exactly the overrun we are looking for.
    // Settle on a quiet period rather than on `actual >= declared` — with a declared
    // length of 0 the latter would settle before the stray bytes even arrive.
    server.on('connection', (socket) => {
      let buf = Buffer.alloc(0);
      let quiet: NodeJS.Timeout | undefined;
      socket.on('data', (chunk) => {
        buf = Buffer.concat([buf, chunk]);
        if (buf.indexOf('\r\n\r\n') < 0) return;
        clearTimeout(quiet);
        quiet = setTimeout(() => {
          const sep = buf.indexOf('\r\n\r\n');
          const head = buf.subarray(0, sep).toString('latin1');
          settle({
            declared: Number(/content-length:\s*(\d+)/i.exec(head)?.[1] ?? 0),
            actual: buf.length - sep - 4,
          });
          socket.end(
            'HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: 2\r\n\r\n{}',
          );
        }, 60);
      });
    });

    server.listen(0, '127.0.0.1', () => {
      resolve({
        port: (server.address() as import('net').AddressInfo).port,
        received,
        close: () => server.close(),
      });
    });
  });
}


describe('BffProxyService request framing', () => {
  // End-to-end over a real socket: drive the real proxy and measure what it actually puts
  // on the wire. A body-less POST is the sharp case — express.json() hands the proxy `{}`,
  // so it writes 2 bytes; if the client's `content-length: 0` were forwarded, those 2 bytes
  // would be stranded in the socket and corrupt whatever request reuses it next.
  it('declares a Content-Length matching the bytes it actually writes (body-less POST)', async () => {
    const backend = await startFramingRecorder();
    const service = new BffProxyService({ target: `http://127.0.0.1:${backend.port}` });

    const res = {
      headersSent: false,
      statusCode: 0,
      setHeader: vi.fn(),
      status: vi.fn(() => res),
      send: vi.fn(() => res),
      json: vi.fn(() => res),
    };

    await service.handleApiRequest(
      {
        method: 'POST',
        originalUrl: '/api/decision/versions/01ABC/validate',
        url: '/api/decision/versions/01ABC/validate',
        // What Chromium sends for fetch(url, { method: 'POST' }) against a JSON endpoint,
        // and what express.json() then turns into req.body === {}.
        headers: {
          authorization: 'Bearer test-token',
          'content-type': 'application/json',
          'content-length': '0',
        },
        body: {},
        ip: '127.0.0.1',
        connection: { remoteAddress: '127.0.0.1' },
      } as any,
      res as any,
    );

    const { declared, actual } = await backend.received;
    backend.close();

    expect(actual).toBe(2); // the `{}` axios serializes from req.body
    expect(declared).toBe(actual); // …and it must say so, or the socket is left dirty
  });

});
