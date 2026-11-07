import { after, test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');
const web = path.join(root, 'web-admin');
const parent = process.env.AURA_NPM_COMPAT_ARTIFACTS || os.tmpdir();
fs.mkdirSync(parent, { recursive: true });
const output = fs.mkdtempSync(path.join(parent, 'npm-toolchain-'));
const commands = [];
function write(file, value) {
  fs.mkdirSync(path.dirname(file), { recursive: true });
  fs.writeFileSync(file, typeof value === 'string' ? value : JSON.stringify(value));
}
function run(tool, args, cwd = web) {
  const result = spawnSync(path.join(web, 'node_modules/.bin', tool), args, {
    cwd, encoding: 'utf8', timeout: 30000, maxBuffer: 4 * 1024 * 1024,
    env: { ...process.env, CI: '1' },
  });
  commands.push({ tool, args, cwd, status: result.status, signal: result.signal,
    error: result.error?.message, stdout: result.stdout, stderr: result.stderr });
  return result;
}
function files(dir) {
  return fs.readdirSync(dir, { withFileTypes: true }).flatMap(entry =>
    entry.isDirectory() ? files(path.join(dir, entry.name)) : [path.join(dir, entry.name)]);
}
after(() => write(path.join(output, 'commands.json'), { node: process.version, commands }));

test('Allure generates a real report containing the fixture result', { timeout: 40000 }, () => {
  const input = path.join(output, 'allure-results');
  write(path.join(input, 'fixture-result.json'), { uuid: 'npm-compat-result',
    name: 'Customer delivery toolchain fixture', fullName: 'npm.compat.customer-delivery',
    status: 'passed', stage: 'finished', start: 1000, stop: 1003,
    labels: [{ name: 'suite', value: 'dependency-compatibility' }] });
  const report = path.join(output, 'allure-report');
  const result = run('allure', ['generate', input, '--output', report, '--report-name', 'Dependency compatibility'], output);
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  assert.ok(fs.existsSync(path.join(report, 'index.html')));
  const data = files(report).filter(file => /\.(json|jsonl)$/.test(file));
  assert.ok(data.some(file => fs.readFileSync(file, 'utf8').includes('Customer delivery toolchain fixture')),
    'Generated report must contain the actual supplied business result, not just an HTML shell');
});

test('nyc executes and measures the actual fixture statements', { timeout: 40000 }, () => {
  // NYC intentionally ignores hidden source trees, including evidence under .workspace.
  const cwd = fs.realpathSync(fs.mkdtempSync(path.join(os.tmpdir(), 'aura-npm-coverage-')));
  write(path.join(cwd, 'program.js'), 'const amount = 68000;\nif (amount > 0) console.log(amount);\n');
  const report = path.join(output, 'coverage-report');
  const result = run('nyc', ['--cwd', cwd, '--include', 'program.js', '--temp-dir', path.join(output, 'coverage-temp'),
    '--report-dir', report, '--reporter=json-summary', process.execPath, path.join(cwd, 'program.js')], cwd);
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  assert.match(result.stdout, /68000/);
  const summary = JSON.parse(fs.readFileSync(path.join(report, 'coverage-summary.json'), 'utf8'));
  assert.ok(summary.total.statements.total >= 3);
  assert.equal(summary.total.statements.covered, summary.total.statements.total);
});

test('TypeScript ESLint accepts strict equality and rejects loose equality', { timeout: 40000 }, () => {
  const source = path.join(output, 'lint-fixture.ts');
  const args = ['--no-eslintrc', '--no-ignore', '--parser', '@typescript-eslint/parser',
    '--parser-options', '{"ecmaVersion":2022,"sourceType":"module"}', '--rule', 'eqeqeq:error', '--format', 'json', source];
  write(source, 'const amount: number = 68000; if (amount === 68000) {}\n');
  const good = run('eslint', args);
  assert.equal(good.status, 0, good.stderr || good.error?.message);
  assert.equal(JSON.parse(good.stdout)[0].errorCount, 0);
  write(source, 'const amount: number = 68000; if (amount == 68000) {}\n');
  const bad = run('eslint', args);
  assert.equal(bad.status, 1, 'The actual lint rule must still reject the bad fixture');
  assert.ok(JSON.parse(bad.stdout)[0].messages.some(message => message.ruleId === 'eqeqeq'));
});

test('schema generator preserves property types and required fields', { timeout: 40000 }, () => {
  const input = path.join(output, 'schema/entity.ts');
  write(input, 'export interface Customer { customerName: string; amount: number; note?: string; }\n');
  const config = path.join(output, 'schema/tsconfig.json');
  write(config, { compilerOptions: { strict: true, target: 'ES2020', types: [], skipLibCheck: true } });
  const destination = path.join(output, 'schema/customer.schema.json');
  const result = run('ts-json-schema-generator', ['--path', input, '--type', 'Customer', '--tsconfig', config, '--out', destination]);
  assert.equal(result.status, 0, result.stderr || result.error?.message);
  const schema = JSON.parse(fs.readFileSync(destination, 'utf8')).definitions.Customer;
  assert.equal(schema.properties.amount.type, 'number');
  assert.equal(schema.properties.customerName.type, 'string');
  assert.deepEqual(schema.required.toSorted(), ['amount', 'customerName']);
});

test('glob abort rejects without starting further directory traversal', { timeout: 5000 }, async () => {
  const require = createRequire(fs.realpathSync(path.join(web, 'node_modules/nyc/package.json')));
  const { glob } = require('glob');
  const cwd = path.join(output, 'abort-tree');
  for (let i = 0; i < 8; i++) write(path.join(cwd, `child-${i}`, 'nested', 'record.txt'), 'fixture');
  const controller = new AbortController();
  let started = 0, afterAbort = 0;
  const instrumentedFs = { ...fs, readdir(...args) {
    if (controller.signal.aborted) afterAbort++;
    const value = fs.readdir(...args);
    if (++started === 1) controller.abort(new Error('fixture cancellation'));
    return value;
  } };
  await assert.rejects(glob('**/*', { cwd, signal: controller.signal, fs: instrumentedFs }), /fixture cancellation|abort/i);
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.ok(started > 0, 'The test must reach actual filesystem traversal');
  write(path.join(output, 'abort-result.json'), { sourceRoot: root, resolved: require.resolve('glob'), version: require('glob/package.json').version, started, afterAbort });
  assert.equal(afterAbort, 0, 'Cancellation must bound new filesystem work after rejection');
});
