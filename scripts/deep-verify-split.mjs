#!/usr/bin/env node
import fs from 'fs';
import path from 'path';

const ORIG = 'plugins/pcba-erp';
const SUBS = [
  'plugins/pcba-base', 'plugins/pcba-crm', 'plugins/pcba-srm',
  'plugins/pcba-sales', 'plugins/pcba-procurement', 'plugins/pcba-wms',
  'plugins/pcba-manufacturing', 'plugins/pcba-finance'
];
let errs = 0, warns = 0;

function loadArr(fp, keyFn) {
  if (!fs.existsSync(fp)) return new Map();
  const m = new Map();
  for (const i of JSON.parse(fs.readFileSync(fp, 'utf8'))) m.set(keyFn(i), i);
  return m;
}
function loadDir(dp) {
  if (!fs.existsSync(dp)) return new Map();
  const m = new Map();
  for (const f of fs.readdirSync(dp).filter(x => x.endsWith('.json')))
    m.set(f, JSON.parse(fs.readFileSync(path.join(dp, f), 'utf8')));
  return m;
}
function mergeSArr(rel, keyFn) {
  const m = new Map();
  for (const sp of SUBS) {
    const fp = path.join(sp, rel);
    if (!fs.existsSync(fp)) continue;
    for (const i of JSON.parse(fs.readFileSync(fp, 'utf8'))) {
      const k = keyFn(i);
      if (!m.has(k)) m.set(k, { data: i, from: path.basename(sp) });
    }
  }
  return m;
}
function mergeSDir(rel) {
  const m = new Map();
  for (const sp of SUBS) {
    const d = path.join(sp, rel);
    if (!fs.existsSync(d)) continue;
    for (const f of fs.readdirSync(d).filter(x => x.endsWith('.json')))
      if (!m.has(f)) m.set(f, { data: JSON.parse(fs.readFileSync(path.join(d, f), 'utf8')), from: path.basename(sp) });
  }
  return m;
}

function cmpArr(label, orig, sub) {
  let miss = 0, diff = 0;
  for (const [k, v] of orig) {
    if (!sub.has(k)) { console.log('  \u274C \u7F3A\u5931' + label + ': ' + k); miss++; errs++; }
    else {
      const s = sub.get(k);
      if (JSON.stringify(v) !== JSON.stringify(s.data)) {
        const df = Object.keys(v).filter(x => JSON.stringify(v[x]) !== JSON.stringify(s.data[x]));
        console.log('  \u274C ' + label + '\u4E0D\u4E00\u81F4: ' + k + ' (\u5DEE\u5F02: ' + df.join(', ') + ') [' + s.from + ']');
        diff++; errs++;
      }
    }
  }
  for (const [k] of sub) {
    if (!orig.has(k)) { console.log('  \u26A0\uFE0F  \u591A\u51FA' + label + ': ' + k); warns++; }
  }
  if (miss === 0 && diff === 0) console.log('  \u2705 ' + orig.size + ' \u4E2A' + label + '\u5B8C\u5168\u4E00\u81F4');
}

function cmpDir(label, orig, sub) {
  let miss = 0, diff = 0;
  for (const [f, v] of orig) {
    if (!sub.has(f)) { console.log('  \u274C \u7F3A\u5931' + label + ': ' + f); miss++; errs++; }
    else {
      const s = sub.get(f);
      if (JSON.stringify(v) !== JSON.stringify(s.data)) {
        console.log('  \u274C ' + label + '\u4E0D\u4E00\u81F4: ' + f + ' [' + s.from + ']');
        diff++; errs++;
      }
    }
  }
  for (const [f] of sub) {
    if (!orig.has(f)) { console.log('  \u26A0\uFE0F  \u591A\u51FA' + label + ': ' + f); warns++; }
  }
  if (miss === 0 && diff === 0) console.log('  \u2705 ' + orig.size + ' \u4E2A' + label + '\u5B8C\u5168\u4E00\u81F4');
}

console.log('\n=== 1. \u6A21\u578B ===');
cmpArr('\u6A21\u578B', loadArr(path.join(ORIG, 'config/models.json'), m => m.code), mergeSArr('config/models.json', m => m.code));

console.log('\n=== 2. \u5B57\u6BB5 ===');
cmpDir('\u5B57\u6BB5', loadDir(path.join(ORIG, 'config/fields')), mergeSDir('config/fields'));

console.log('\n=== 3. \u547D\u4EE4 ===');
cmpDir('\u547D\u4EE4', loadDir(path.join(ORIG, 'config/commands')), mergeSDir('config/commands'));

console.log('\n=== 4. \u7ED1\u5B9A ===');
cmpDir('\u7ED1\u5B9A', loadDir(path.join(ORIG, 'config/bindings')), mergeSDir('config/bindings'));

console.log('\n=== 5. \u9875\u9762 ===');
cmpDir('\u9875\u9762', loadDir(path.join(ORIG, 'config/pages')), mergeSDir('config/pages'));

console.log('\n=== 6. \u6743\u9650 ===');
cmpArr('\u6743\u9650', loadArr(path.join(ORIG, 'config/permissions.json'), p => p.code), mergeSArr('config/permissions.json', p => p.code));

console.log('\n=== 7. \u5B57\u5178 ===');
cmpArr('\u5B57\u5178', loadArr(path.join(ORIG, 'config/dicts.json'), d => d.code), mergeSArr('config/dicts.json', d => d.code));

console.log('\n=== 8. \u83DC\u5355 ===');
cmpArr('\u83DC\u5355', loadArr(path.join(ORIG, 'config/menus.json'), m => m.code), mergeSArr('config/menus.json', m => m.code));

console.log('\n=== 9. \u89D2\u8272 ===');
cmpArr('\u89D2\u8272', loadArr(path.join(ORIG, 'config/roles.json'), r => r.code), mergeSArr('config/roles.json', r => r.code));

// i18n
console.log('\n=== 10. i18n ===');
const origI18n = path.join(ORIG, 'config/i18n.json');
if (fs.existsSync(origI18n)) {
  const oi = JSON.parse(fs.readFileSync(origI18n, 'utf8'));
  const seen = new Set();
  for (const sp of SUBS) {
    const fp = path.join(sp, 'config/i18n.json');
    if (!fs.existsSync(fp)) continue;
    for (const e of JSON.parse(fs.readFileSync(fp, 'utf8'))) seen.add(e.key || e.code || JSON.stringify(e));
  }
  let miss = 0;
  const origKeys = oi.map(e => e.key || e.code || JSON.stringify(e));
  for (const k of origKeys) { if (!seen.has(k)) { console.log('  \u274C \u7F3A\u5931 i18n: ' + k); miss++; errs++; } }
  if (miss === 0) console.log('  \u2705 ' + origKeys.length + ' \u4E2A i18n \u6761\u76EE\u5B8C\u6574');
} else { console.log('  \u26A0\uFE0F  \u65E0 i18n.json'); }

// role-permissions
console.log('\n=== 11. \u89D2\u8272\u6743\u9650\u7ED1\u5B9A ===');
const origRP = path.join(ORIG, 'config/role-permissions.json');
if (fs.existsSync(origRP)) {
  const orp = JSON.parse(fs.readFileSync(origRP, 'utf8'));
  const srp = new Map();
  for (const sp of SUBS) {
    const f = path.join(sp, 'config/role-permissions.json');
    if (!fs.existsSync(f)) continue;
    for (const rp of JSON.parse(fs.readFileSync(f, 'utf8'))) {
      if (!srp.has(rp.roleCode)) srp.set(rp.roleCode, new Set());
      for (const pc of (rp.permissionCodes || [])) srp.get(rp.roleCode).add(pc);
    }
  }
  let miss = 0;
  for (const r of orp) {
    const ss = srp.get(r.roleCode) || new Set();
    for (const pc of (r.permissionCodes || [])) { if (!ss.has(pc)) { console.log('  \u274C \u7F3A\u5931: ' + r.roleCode + ' -> ' + pc); miss++; errs++; } }
  }
  const total = orp.reduce((s, r) => s + (r.permissionCodes || []).length, 0);
  if (miss === 0) console.log('  \u2705 \u89D2\u8272\u6743\u9650\u7ED1\u5B9A\u5B8C\u6574 (' + total + ' \u6761)');
} else { console.log('  \u2139\uFE0F  \u65E0 role-permissions.json'); }

console.log('\n========================================');
console.log('\u6DF1\u5EA6\u9A8C\u8BC1: ' + errs + ' \u9519\u8BEF, ' + warns + ' \u8B66\u544A');
if (errs === 0) console.log('\u2705 \u6240\u6709\u5B50\u63D2\u4EF6\u6570\u636E\u4E0E\u539F\u59CB pcba-erp \u5B8C\u5168\u4E00\u81F4');
else console.log('\u274C \u5B58\u5728\u4E0D\u4E00\u81F4\uFF0C\u9700\u8981\u4FEE\u590D');
