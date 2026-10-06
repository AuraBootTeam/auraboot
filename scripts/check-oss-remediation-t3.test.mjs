import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

const root = path.resolve(import.meta.dirname, '..');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'scripts/oss-remediation-t3-catalog.json'), 'utf8'));
const owners = catalog.classes.map(source => source.replace('platform/src/main/java/', '').replace(/\.java$/, ''));
const evidence = fs.mkdtempSync(path.join(os.tmpdir(), 'oss-t3-check-'));
function verify(name, classes) {
    const report = path.join(evidence, `${name}.xml`);
    const output = path.join(evidence, `${name}.json`);
    fs.writeFileSync(report, `<?xml version="1.0"?><report name="fixture">${classes}</report>`);
    const run = spawnSync(process.execPath, [path.join(root, 'scripts/check-oss-remediation-t3.mjs'), '--report', report, '--out', output], { encoding: 'utf8' });
    return { status: run.status, result: JSON.parse(fs.readFileSync(output, 'utf8')) };
}
function fixture(covered, missed, includeNested = true, missing = false) {
    const items = owners.filter((_, index) => !(missing && index === owners.length - 1))
        .map((name, index) => index === 0
            ? `<class name="${name}"><method name="run"><counter type="LINE" missed="900" covered="900"/></method><counter type="LINE" missed="${missed}" covered="${covered}"/></class>`
            : `<class name="${name}"/>`);
    if (includeNested) items.push(`<class name="${owners[0]}$Inner"><counter type="LINE" missed="2" covered="8"/></class>`);
    return items.join('');
}
test('includes nested classes, accepts self-closing interfaces and ignores method counters', () => {
    const { status, result } = verify('valid', fixture(8, 2));
    assert.equal(status, 0);
    assert.deepEqual(result.line, { covered: 16, missed: 4, total: 20, ratio: 0.8 });
    assert.equal(result.sourceCount, 34);
    assert.equal(result.measuredClassCount, 35);
});
test('actual undercoverage fails without relaxing the threshold', () => {
    const { status, result } = verify('undercoverage', fixture(7, 3));
    assert.equal(status, 1);
    assert.equal(result.line.ratio, 0.75);
    assert.equal(result.minimumLineCoverage, 0.8);
});
test('a missing family owner fails even with covered remaining classes', () => {
    const { status, result } = verify('missing-owner', fixture(10, 0, false, true));
    assert.equal(status, 1);
    assert.ok(result.errors.some(error => error.startsWith('Missing production class:')));
});
test('zero executable lines cannot become an empty green', () => {
    const { status, result } = verify('empty', owners.map(name => `<class name="${name}"/>`).join(''));
    assert.equal(status, 1);
    assert.ok(result.errors.includes('Empty coverage denominator'));
});
test('a low-coverage nested class remains in the denominator', () => {
    const { status, result } = verify('nested-undercoverage', fixture(10, 0, false)
            + `<class name="${owners[0]}$Hidden"><counter type="LINE" missed="10" covered="0"/></class>`);
    assert.equal(status, 1);
    assert.equal(result.line.ratio, 0.5);
});
