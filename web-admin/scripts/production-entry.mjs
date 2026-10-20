import http from 'node:http';

// Local ingress only. The existing deployment BFF serves compiled client assets and SSR.
const port = Number(process.env.VITE_PORT);
const upstreamPort = Number(process.env.BFF_PORT);
if (![port, upstreamPort].every(p => Number.isInteger(p) && p > 0 && p <= 65535) || port === upstreamPort) {
  throw new Error('Distinct explicit Web and BFF ports required');
}
const server = http.createServer((request, response) => {
  const upstream = http.request({
    hostname: '127.0.0.1', port: upstreamPort, path: request.url, method: request.method,
    headers: { ...request.headers, 'x-forwarded-host': request.headers.host, 'x-forwarded-proto': 'http' },
  }, result => {
    response.writeHead(result.statusCode, result.headers);
    result.pipe(response);
  });
  upstream.on('error', () => {
    if (!response.headersSent) response.writeHead(502, { 'content-type': 'text/plain' });
    response.end('Production BFF unavailable');
  });
  request.on('aborted', () => upstream.destroy());
  request.pipe(upstream);
});
server.listen(port, '127.0.0.1');
for (const signal of ['SIGTERM', 'SIGINT']) process.once(signal, () => server.close(() => process.exit(0)));
