import { Worker } from 'node:worker_threads';
import { expect, it } from 'vitest';
import { normalizeRuntimePathname } from '../runtimeProfile';

it('normalizes long slash runs without quadratic trailing-slash search', async () => {
  const result = await new Promise<boolean>((resolve, reject) => {
    const worker = new Worker(
      `
      const { parentPort } = require('node:worker_threads');
      const normalize = ${normalizeRuntimePathname.toString()};
      const prefix = '/'.repeat(100000) + 'x';
      const suffix = '/merchant' + '/'.repeat(100000);
      parentPort.postMessage(normalize(prefix) === prefix && normalize(suffix) === '/merchant');
    `,
      { eval: true },
    );
    const timer = setTimeout(() => {
      void worker.terminate();
      reject(new Error('Path normalization exceeded the isolated three-second CPU budget'));
    }, 3000);
    worker.once('message', (value: boolean) => {
      clearTimeout(timer);
      void worker.terminate();
      resolve(value);
    });
    worker.once('error', (error) => {
      clearTimeout(timer);
      void worker.terminate();
      reject(error);
    });
  });
  expect(result).toBe(true);
});
