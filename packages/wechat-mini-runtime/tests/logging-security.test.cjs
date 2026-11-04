const test = require('node:test');
const assert = require('node:assert/strict');
const { Worker } = require('node:worker_threads');

test('hostile stack text cannot cause quadratic source-location extraction', async () => {
  const location = await new Promise((resolve, reject) => {
    const worker = new Worker(`
      const { parentPort, workerData } = require('node:worker_threads');
      const { createLogger } = require(workerData);
      const logger = createLogger({ consoleApi: {} });
      const error = new Error('private');
      error.stack = 'at '.repeat(100000) + 'unavailable';
      parentPort.postMessage(logger.capture('uncaught', error).location);
    `, { eval: true, workerData: require.resolve('../runtime/logger') });
    const timer = setTimeout(() => {
      void worker.terminate();
      reject(new Error('Stack parsing exceeded the isolated three-second CPU budget'));
    }, 3000);
    worker.once('message', (result) => {
      clearTimeout(timer);
      void worker.terminate();
      resolve(result);
    });
    worker.once('error', (error) => {
      clearTimeout(timer);
      void worker.terminate();
      reject(error);
    });
  });
  assert.equal(location, 'unavailable');
});
