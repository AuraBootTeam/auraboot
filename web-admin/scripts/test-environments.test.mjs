import { test } from 'node:test';
import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const cwd = fileURLToPath(new URL('..', import.meta.url));
function evaluate(expression, selected = {}) {
  const env = { ...process.env };
  for (const key of ['PLAYWRIGHT_BASE_URL','BASE_URL','BACKEND_URL','BFF_URL','BE_PORT','VITE_PORT','BFF_PORT','PG_HOST','PGHOST','PG_PORT','PGPORT','PG_USER','PGUSER','PG_DB','PGDATABASE','PW_SKIP_WEBSERVER']) delete env[key];
  Object.assign(env, selected);
  return JSON.parse(execFileSync(process.execPath, ['--import','tsx','--input-type=module','-e',
    `import * as e from './tests/helpers/environments.ts'; console.log(JSON.stringify(${expression}));`], { cwd, env, encoding: 'utf8' }));
}
test('explicit-runtime journeys reject a missing target instead of using the shared host', () => {
  assert.equal(evaluate(`(() => { try { e.requireRuntimeUrl('base'); return false; } catch { return true; } })()`), true);
});
test('one owned target supplies all HTTP and database coordinates', () => {
  const selected = { PLAYWRIGHT_BASE_URL:'http://localhost:5181', BACKEND_URL:'http://localhost:6481', BFF_URL:'http://localhost:6181', BE_PORT:'6481', BFF_PORT:'6181', PG_HOST:'owned-pg', PG_PORT:'5439', PG_USER:'owned-user', PG_DB:'enterprise_31', PW_SKIP_WEBSERVER:'1' };
  assert.deepEqual(evaluate("[e.requireRuntimeUrl('base'),e.BACKEND_URL,e.BFF_URL,e.PG_CONN.host,e.PG_CONN.port,e.PG_CONN.user,e.PG_CONN.database,e.PG_ENV.PGDATABASE]", selected), ['http://localhost:5181','http://localhost:6481','http://localhost:6181','owned-pg',5439,'owned-user','enterprise_31','enterprise_31']);
});
test('incoherent explicit ports remain rejected', () => {
  assert.throws(() => evaluate('e.BASE_URL', {PLAYWRIGHT_BASE_URL:'http://localhost:5181', BACKEND_URL:'http://localhost:6481', BE_PORT:'6482',BFF_PORT:'6181',PG_DB:'enterprise_31',PW_SKIP_WEBSERVER:'1'}), /does not match/);
});
