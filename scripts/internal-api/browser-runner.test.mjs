import { test } from 'node:test';
import assert from 'node:assert/strict';
import { bindBrowserEnvironment } from './browser.mjs';

test('browser runner binds verified manifest addresses and rejects missing or conflicting runtime namespaces', () => {
  const producer = { webUrl: 'http://127.0.0.1:5381', backendUrl: 'http://127.0.0.1:6681', bffUrl: 'http://127.0.0.1:6381', database: 'auraboot_281' };
  const environment = { PLAYWRIGHT_BASE_URL: producer.webUrl, BACKEND_URL: producer.backendUrl, BE_PORT: '6681', BFF_PORT: '6381', PG_DB: producer.database, PW_SKIP_WEBSERVER: '1' };
  const bound = bindBrowserEnvironment(producer, environment);
  assert.equal(bound.BFF_URL, producer.bffUrl);
  assert.equal(bound.SPRING_BOOT_URL, producer.backendUrl);
  assert.equal(environment.BFF_URL, undefined);
  assert.deepEqual(bindBrowserEnvironment(producer, { ...environment, ...bound, PGDATABASE: producer.database }), bound);
  for (const key of ['PLAYWRIGHT_BASE_URL','BACKEND_URL','BE_PORT','BFF_PORT','PG_DB','PW_SKIP_WEBSERVER']) {
    const missing = { ...environment }; delete missing[key];
    assert.throws(() => bindBrowserEnvironment(producer, missing), `missing ${key}`);
  }
  for (const key of ['PLAYWRIGHT_BASE_URL','BACKEND_URL','BE_PORT','BFF_PORT','BFF_URL','SPRING_BOOT_URL','PG_DB','PGDATABASE','PW_SKIP_WEBSERVER'])
    assert.throws(() => bindBrowserEnvironment(producer, { ...environment, [key]: 'wrong' }), `conflicting ${key}`);
});
