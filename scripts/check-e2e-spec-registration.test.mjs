import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { auditRegistrations, readNameArray, stripLineComments } from './check-e2e-spec-registration.mjs';

// A gate nobody has watched fail is not a gate. Every case below builds a repo
// that SHOULD be red and asserts it is, then the clean case asserts silence —
// otherwise "PASS" only means the audit never looked.

const SPEC_DIR = 'web-admin/tests/e2e/demo';
const CONFIG_FILE = 'web-admin/playwright.config.ts';

function makeRepo({ specs = [], registered = [], allow = {}, configExtra = '' } = {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'specreg-'));
  fs.mkdirSync(path.join(root, SPEC_DIR), { recursive: true });
  for (const s of specs) fs.writeFileSync(path.join(root, SPEC_DIR, `${s}.spec.ts`), '// spec\n');
  fs.mkdirSync(path.join(root, path.dirname(CONFIG_FILE)), { recursive: true });
  fs.writeFileSync(path.join(root, CONFIG_FILE),
    `${configExtra}\nconst demoSpecNames = [\n${registered.map((n) => `  '${n}',`).join('\n')}\n];\n`);
  const config = {
    registries: [{ dir: SPEC_DIR, configFile: CONFIG_FILE, arrayName: 'demoSpecNames', project: 'demo' }],
    allow: { [SPEC_DIR]: allow },
  };
  return { root, config };
}

const kinds = (r) => r.findings.filter((f) => f.level === 'error').map((f) => f.kind);

test('a fully registered directory is silent', () => {
  const { root, config } = makeRepo({ specs: ['a', 'b'], registered: ['a', 'b'] });
  assert.deepEqual(auditRegistrations({ root, config }).findings, []);
});

test('a spec nobody registered is an error, not a warning', () => {
  const { root, config } = makeRepo({ specs: ['a', 'b'], registered: ['a'] });
  const r = auditRegistrations({ root, config });
  assert.deepEqual(kinds(r), ['orphan']);
  assert.match(r.findings[0].message, /never select it/);
});

test('registry rot — a name with no file behind it — is caught too', () => {
  const { root, config } = makeRepo({ specs: ['a'], registered: ['a', 'ghost'] });
  const r = auditRegistrations({ root, config });
  assert.deepEqual(kinds(r), ['rot']);
  assert.match(r.findings[0].message, /ghost/);
});

test('an orphan can be silenced only with a reason', () => {
  const withReason = makeRepo({ specs: ['a', 'b'], registered: ['a'], allow: { b: 'superseded by X; kept for history' } });
  assert.deepEqual(auditRegistrations(withReason).findings, []);

  const withoutReason = makeRepo({ specs: ['a', 'b'], registered: ['a'], allow: { b: '' } });
  assert.deepEqual(kinds(auditRegistrations(withoutReason)), ['allow-without-reason']);
});

test('an allowlist entry for a file that no longer exists is reported', () => {
  const { root, config } = makeRepo({ specs: ['a'], registered: ['a'], allow: { gone: 'was flaky' } });
  const r = auditRegistrations({ root, config });
  assert.deepEqual(kinds(r), []);
  assert.deepEqual(r.findings.map((f) => f.kind), ['stale-allow']);
});

test('comments cannot register a spec, and cannot hide one either', () => {
  // Both directions are real hazards: a name mentioned in a comment must not
  // count as registered, and a real entry must not be lost because a comment
  // sits next to it. Getting this wrong yields a confident wrong answer.
  const commented = makeRepo({
    specs: ['a', 'b'],
    registered: ['a'],
    configExtra: "// 'b' was dropped from the gate on purpose, see DDR\n",
  });
  assert.deepEqual(kinds(auditRegistrations(commented)), ['orphan']);

  const src = "const xs = [\n  'keep', // 'decoy'\n  'also',\n];\n";
  assert.deepEqual(readNameArray(src, 'xs'), ['keep', 'also']);
});

test('stripLineComments leaves code and removes only the comment tail', () => {
  assert.equal(stripLineComments("const a = 1; // 'x'\nconst b = 2;"), 'const a = 1; \nconst b = 2;');
});

test('a missing directory or array fails loudly instead of reporting zero orphans', () => {
  // The dangerous failure is silence: if the audit cannot find what it is meant
  // to inspect it must say so, not conclude "nothing wrong here".
  const { root, config } = makeRepo({ specs: ['a'], registered: ['a'] });
  const wrongDir = { ...config, registries: [{ ...config.registries[0], dir: 'no/such/dir' }] };
  assert.deepEqual(kinds(auditRegistrations({ root, config: wrongDir })), ['missing-dir']);

  const wrongArray = { ...config, registries: [{ ...config.registries[0], arrayName: 'nope' }] };
  assert.deepEqual(kinds(auditRegistrations({ root, config: wrongArray })), ['missing-array']);
});
