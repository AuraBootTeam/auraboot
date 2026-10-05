#!/usr/bin/env node
/** Verify the complete T3 source family, including all nested/anonymous classes. */
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const catalog = JSON.parse(fs.readFileSync(path.join(root, 'scripts/oss-remediation-t3-catalog.json'), 'utf8'));
const args = process.argv.slice(2);
function option(name) {
    const index = args.indexOf(name);
    if (index < 0 || !args[index + 1]) throw new Error(`Required: ${name}`);
    return args[index + 1];
}
const report = option('--report');
const output = option('--out');
const xml = fs.readFileSync(report, 'utf8');
if (!xml.startsWith('<?xml') || !xml.includes('<report ') || !xml.includes('</report>')) {
    throw new Error('Missing or invalid complete JaCoCo XML report');
}
const family = new Map(catalog.classes.map(source => [source.replace('platform/src/main/java/', '').replace(/\.java$/, ''), source]));
const seen = new Set();
const classes = [];
const errors = [];
let covered = 0;
let missed = 0;
for (const match of xml.matchAll(/<class\s+name="([^"]+)"[^>]*?(?:\/>|>([\s\S]*?)<\/class>)/g)) {
    const owner = match[1].split('$')[0];
    if (!family.has(owner)) continue;
    seen.add(owner);
    const body = (match[2] ?? '').replace(/<method\b[^>]*?(?:\/>|>[\s\S]*?<\/method>)/g, '');
    const counter = body.match(/<counter type="LINE" missed="(\d+)" covered="(\d+)"\s*\/>/);
    const counters = counter ? { missed: Number(counter[1]), covered: Number(counter[2]) } : { missed: 0, covered: 0 };
    covered += counters.covered;
    missed += counters.missed;
    classes.push({ name: match[1], ...counters });
}
for (const [name] of family) if (!seen.has(name)) errors.push(`Missing production class: ${name}`);
const sources = catalog.classes.map(source => {
    const bytes = fs.readFileSync(path.join(root, source));
    const lines = bytes.toString('utf8').split('\n').length;
    if (catalog.parentLimits[source] !== undefined && lines > catalog.parentLimits[source]) {
        errors.push(`Parent grew: ${source} ${lines} > ${catalog.parentLimits[source]}`);
    }
    return { source, lines, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
});
const total = covered + missed;
if (total === 0) errors.push('Empty coverage denominator');
if (covered < total * catalog.minimumLineCoverage) errors.push(`Line coverage NOT MET: ${covered}/${total} < ${catalog.minimumLineCoverage}`);
const result = {
    requirementId: catalog.requirementId,
    verdict: errors.length ? 'fail' : 'pass',
    report: path.resolve(report),
    reportSha256: crypto.createHash('sha256').update(xml).digest('hex'),
    minimumLineCoverage: catalog.minimumLineCoverage,
    sourceCount: sources.length,
    measuredClassCount: classes.length,
    line: { covered, missed, total, ratio: total ? covered / total : 0 },
    sources, classes, errors,
};
fs.writeFileSync(output, JSON.stringify(result, null, 2) + '\n');
console.log(`${result.verdict}: T3 complete family ${covered}/${total} = ${(result.line.ratio * 100).toFixed(2)}%`);
for (const error of errors) console.error(error);
process.exitCode = errors.length ? 1 : 0;
