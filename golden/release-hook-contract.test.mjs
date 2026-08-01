import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const RELEASE = fs.readFileSync(path.join(ROOT, 'golden', 'release.sh'), 'utf8');
const DIGITAL_EMPLOYEE = fs.readFileSync(
  path.join(ROOT, 'scripts', 'digital-employee-golden-run.sh'),
  'utf8',
);
const SUSPENDED_TENANT = fs.readFileSync(
  path.join(ROOT, 'scripts', 'suspended-tenant-login-golden.sh'),
  'utf8',
);
const KB_INGESTION = fs.readFileSync(
  path.join(ROOT, 'scripts', 'kb-ingestion-golden-run.sh'),
  'utf8',
);

test('FAQ release dispatch supplies an isolated slot', () => {
  assert.match(
    RELEASE,
    /faq-loop-golden-run\.sh --slot \$\{OSS_FAQ_GOLDEN_SLOT:-73\}/,
  );
});

test('digital employee release suite runs stateful ACP journeys sequentially', () => {
  assert.match(DIGITAL_EMPLOYEE, /--workers=1/);
});

test('suspended tenant API golden owns bootstrap credentials', () => {
  assert.match(SUSPENDED_TENANT, /-Dauraboot\.bootstrap\.enabled=false/);
});

test('knowledge ingestion aligns live vision selection with backend mode', () => {
  assert.match(KB_INGESTION, /export AGENT_LLM_STUB_MODE=false/);
  assert.match(KB_INGESTION, /--workers=1/);
});
