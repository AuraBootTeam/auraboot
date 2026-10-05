#!/usr/bin/env node
/** batch4 structural gates: large-file ratchet (E7) + i18n CJK ratchet (E8). */
import fs from 'node:fs';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
let failures = 0;

function walk(dir, cb) {
    for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, e.name);
        if (e.isDirectory()) {
            if (e.name === 'node_modules' || e.name.startsWith('.')) continue;
            walk(p, cb);
            continue;
        }
        cb(p);
    }
}

// E7: large-file ratchet
const lf = JSON.parse(fs.readFileSync(path.join(root, 'scripts/baseline-large-files.json'), 'utf8'));
const grandfathered = new Map(lf.grandfathered.map((g) => [g.path, g.lines]));
const seen = new Set();
walk(path.join(root, 'platform/src/main/java'), (p) => {
    if (!p.endsWith('.java')) return;
    const rel = path.relative(root, p);
    const n = fs.readFileSync(p, 'utf8').split('\n').length;
    seen.add(rel);
    if (n > lf.threshold && !grandfathered.has(rel)) {
        console.error(`ERROR: ${rel} has ${n} lines (> ${lf.threshold}). Split it or add to the grandfather list with justification.`);
        failures++;
    } else if (grandfathered.has(rel) && n > grandfathered.get(rel)) {
        console.error(`ERROR: ${rel} grew beyond its grandfathered size (${n} > ${grandfathered.get(rel)}). Split it.`);
        failures++;
    }
});

// E8: CJK ratchet
const i18n = JSON.parse(fs.readFileSync(path.join(root, 'scripts/baseline-i18n-cjk.json'), 'utf8'));
let total = 0;
walk(path.join(root, 'web-admin/app'), (p) => {
    if (!p.endsWith('.tsx') && !p.endsWith('.ts')) return;
    const cjk = fs.readFileSync(p, 'utf8').match(/[\u4e00-\u9fff]/g);
    if (cjk) total += cjk.length;
});
if (total > i18n.cjkCodepoints) {
    console.error(`ERROR: CJK codepoints grew: ${total} > baseline ${i18n.cjkCodepoints}. Use the i18n system instead of hard-coded CJK.`);
    failures++;
} else {
    console.log(`i18n CJK ratchet: ${total} <= ${i18n.cjkCodepoints} (baseline). Reduce toward 0.`);
}

process.exit(failures ? 1 : 0);
