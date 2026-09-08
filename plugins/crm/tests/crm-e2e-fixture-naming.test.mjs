import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { globSync } from 'node:fs';
import path from 'node:path';
import test from 'node:test';

// 2026-09-07 lesson (OSS #1858): e2e specs seeded user-visible record titles
// with uniqueId('crm_*') raw tokens. The records surfaced on the sales home
// and the dashboards golden's no-raw-code contract failed on data the tests
// themselves had written. uniqueId prefixes flow into user-visible titles, so
// they must be localized — a pure-ASCII prefix is the raw-code class.
const configRoot = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..');

const specGlobs = [
  'e2e/*.spec.ts',
  '../web-admin/tests/e2e/crm/*.spec.ts',
];

test('crm e2e uniqueId prefixes are localized, not raw-code tokens', async () => {
  const offenders = [];
  for (const pattern of specGlobs) {
    for (const file of globSync(path.join(configRoot, pattern))) {
      const source = await readFile(file, 'utf8');
      const relative = path.relative(configRoot, file);
      for (const match of source.matchAll(/uniqueId\('([^']*)'\)/g)) {
        const prefix = match[1];
        if (!/[^\x00-\x7F]/.test(prefix)) {
          offenders.push(`${relative}: uniqueId('${prefix}')`);
        }
      }
      for (const match of source.matchAll(/uniqueId\("([^"]*)"\)/g)) {
        const prefix = match[1];
        if (!/[^\x00-\x7F]/.test(prefix)) {
          offenders.push(`${relative}: uniqueId("${prefix}")`);
        }
      }
    }
  }
  assert.deepEqual(
    offenders,
    [],
    `raw-code uniqueId prefixes leak into user-visible record titles and trip\n`
    + `the dashboards golden no-raw-code contract. Use a localized prefix\n`
    + `(e.g. uniqueId('转化跟进')) — uniqueness comes from the timestamp+random\n`
    + `tail. Offenders:\n${offenders.join('\n')}`,
  );
});
