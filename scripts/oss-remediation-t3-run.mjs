#!/usr/bin/env node
/** Run the fixed complete-family regression through an owned managed executor. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const args = process.argv.slice(2);
function option(name) {
    const index = args.indexOf(name);
    if (index < 0 || !args[index + 1]) throw new Error(`Required: ${name}`);
    return args[index + 1];
}
const executor = path.resolve(option('--executor'));
const runtime = option('--runtime');
const database = option('--database-url');
const user = option('--database-user');
const output = path.resolve(option('--out'));
if (!database.startsWith('jdbc:postgresql://') || /[?&](password|user)=/i.test(database)) {
    throw new Error('Require explicit PostgreSQL JDBC URL without embedded credentials');
}
if (fs.existsSync(output)) throw new Error('Evidence output must be a fresh directory');
// DB-identity assertions (e.g. SELECT current_database() = POSTGRES_DB) must see the
// pinned IT database, not the runtime slot allocation injected by the managed executor.
const databaseName = database.replace(/^jdbc:postgresql:\/\/[^/]+\/([^/?]+).*$/, '$1');
if (!databaseName || databaseName === database) throw new Error(`Cannot derive database name from ${database}`);
const hostPort = url => url.replace(/^jdbc:postgresql:\/\/([^/?]+).*$/, '$1');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'scripts/oss-remediation-t3-catalog.json'), 'utf8'));
const git = (...command) => {
    const result = spawnSync('git', command, { cwd: root, encoding: 'utf8' });
    if (result.status !== 0) throw new Error(result.stderr);
    return result.stdout.trim();
};
if (git('status', '--porcelain')) throw new Error('Commit changes before acceptance: clean source required');
const source = git('rev-parse', 'HEAD');
const hash = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const productionBefore = Object.fromEntries(catalog.classes.map(file => [file, hash(fs.readFileSync(path.join(root, file)))]));
fs.mkdirSync(output, { recursive: true });
const command = ['run', runtime, '--workdir', path.join(root, 'platform'), '--', 'env',
    `TEST_DATABASE_URL=${database}`, `SPRING_DATASOURCE_URL=${database}`, `DATABASE_URL=${database}`,
    `TEST_DATABASE_USERNAME=${user}`, `SPRING_DATASOURCE_USERNAME=${user}`, `POSTGRES_DB=${databaseName}`,
    `TEST_EXPECTED_DATABASE=${databaseName}`,
    `AURA_TEST_POSTGRES_JDBC_URL=jdbc:postgresql://${hostPort(database)}/${databaseName}`,
    './gradlew', ':test', '--rerun', ...catalog.testSelectors.flatMap(selector => ['--tests', selector]),
    'jacocoUnitFullReport', '--no-daemon'];
fs.writeFileSync(path.join(output, 'command.json'), JSON.stringify({ source, executor, command, productionBefore }, null, 2) + '\n');
const log = fs.openSync(path.join(output, 'regression.log'), 'wx');
const start = new Date();
const run = spawnSync(executor, command, { cwd: path.dirname(executor), stdio: ['ignore', log, log],
    env: { ...process.env, TEST_DATABASE_PASSWORD: process.env.TEST_DATABASE_PASSWORD ?? '', SPRING_DATASOURCE_PASSWORD: process.env.TEST_DATABASE_PASSWORD ?? '' } });
fs.closeSync(log);
const errors = [];
if (run.status !== 0) errors.push(`Regression exit: ${run.status}; ${run.error?.message ?? ''}`);
if (git('rev-parse', 'HEAD') !== source || git('status', '--porcelain')) errors.push('Source changed during execution');
for (const file of catalog.classes) if (hash(fs.readFileSync(path.join(root, file))) !== productionBefore[file]) errors.push(`Production source changed: ${file}`);
const report = path.join(root, 'platform/build/reports/jacoco/jacocoUnitFullReport/jacocoUnitFullReport.xml');
const exec = path.join(root, 'platform/build/jacoco/test.exec');
const results = path.join(root, 'platform/build/test-results/test');
const totals = { declared: 0, passed: 0, failures: 0, errors: 0, skipped: 0 };
const skipped = [];
if (!fs.existsSync(results)) errors.push('Missing test XML directory');
else {
    fs.mkdirSync(path.join(output, 'xml'));
    for (const name of fs.readdirSync(results).filter(name => /^TEST-.*\.xml$/.test(name))) {
        const file = path.join(results, name);
        const xml = fs.readFileSync(file, 'utf8');
        fs.copyFileSync(file, path.join(output, 'xml', name));
        const suite = xml.match(/<testsuite\b[^>]*>/)?.[0];
        if (!suite || fs.statSync(file).mtimeMs < start.getTime()) { errors.push(`Stale/invalid test XML: ${name}`); continue; }
        for (const [metric, attr] of Object.entries({ declared: 'tests', failures: 'failures', errors: 'errors', skipped: 'skipped' })) {
            const value = suite.match(new RegExp(`\\b${attr}="(\\d+)"`));
            if (!value) errors.push(`Missing ${attr}: ${name}`);
            else totals[metric] += Number(value[1]);
        }
        for (const match of xml.matchAll(/<testcase\b([^>]*)>([\s\S]*?)<\/testcase>/g)) {
            if (match[2].includes('<skipped')) skipped.push({ suite: name, testcase: match[1].trim(), reason: match[2].match(/<skipped[^>]*(?:\/>|>[\s\S]*?<\/skipped>)/)?.[0] });
        }
    }
}
totals.passed = totals.declared - totals.failures - totals.errors - totals.skipped;
if (!totals.declared || !totals.passed || totals.failures || totals.errors) errors.push('Regression has empty or failing execution evidence');
for (const [file, target] of [[report, 'full.xml'], [exec, 'test.exec']]) {
    if (!fs.existsSync(file) || fs.statSync(file).mtimeMs < start.getTime()) errors.push(`Missing/stale artifact: ${target}`);
    else fs.copyFileSync(file, path.join(output, target));
}
if (!errors.length) {
    const gate = spawnSync(process.execPath, [path.join(root, 'scripts/check-oss-remediation-t3.mjs'), '--report', path.join(output, 'full.xml'), '--out', path.join(output, 'coverage.json')], { cwd: root, encoding: 'utf8' });
    fs.writeFileSync(path.join(output, 'coverage.log'), gate.stdout + gate.stderr);
    if (gate.status !== 0) errors.push('Complete-family coverage/size gate failed');
}
const receipt = { requirementId: 'OSS-T3', source, start: start.toISOString(), end: new Date().toISOString(), exit: run.status,
    verdict: errors.length ? 'fail' : 'pass', totals, skipped, errors,
    artifacts: Object.fromEntries(fs.readdirSync(output).filter(name => fs.statSync(path.join(output, name)).isFile()).map(name => [name, hash(fs.readFileSync(path.join(output, name)))])) };
fs.writeFileSync(path.join(output, 'receipt.json'), JSON.stringify(receipt, null, 2) + '\n');
console.log(JSON.stringify({ verdict: receipt.verdict, source, totals, output, errors }));
process.exitCode = errors.length ? 1 : 0;
