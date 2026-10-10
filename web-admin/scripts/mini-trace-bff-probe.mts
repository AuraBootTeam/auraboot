// Component integration probe: actual Express/BffProxyService/axios HTTP hops.
// This does not load the product router, authenticate a supplier, or prove device transport.
import express from 'express';
import { spawn } from 'node:child_process';
import { once } from 'node:events';
import path from 'node:path';
import { BffProxyService } from '../app/server/services/BffProxyService';

const backend = process.argv[2];
const port = Number(process.env.BFF_PORT);
if (!/^http:\/\/127\.0\.0\.1:\d+$/.test(backend || '') || !Number.isInteger(port) || port <= 0)
  throw new Error('Owned backend origin and BFF port required');
const service = new BffProxyService({ target: backend });
const app = express();
app.use((req, res) => {
  void service.handleApiRequest(req, res);
});
const server = app.listen(port, '127.0.0.1');
await once(server, 'listening');
try {
  const probe = path.resolve('../packages/wechat-mini-runtime/tests/trace-http-probe.cjs');
  const child = spawn(process.execPath, [probe, `http://127.0.0.1:${port}`], { stdio: 'inherit' });
  const [code] = await once(child, 'exit');
  if (code !== 0) throw new Error(`Shared client through BFF failed: ${code}`);
} finally {
  await new Promise<void>((resolve, reject) =>
    server.close((error) => (error ? reject(error) : resolve())),
  );
}
