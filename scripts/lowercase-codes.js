const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

function lc(str) {
  return typeof str === 'string' ? str.toLowerCase() : str;
}

// Find all config JSON files
const files = execSync('find plugins -path "*/config/*.json" -type f')
  .toString().trim().split('\n').filter(Boolean);

let updatedCount = 0;

for (const file of files) {
  const basename = path.basename(file);
  let raw = fs.readFileSync(file, 'utf8');
  let data;
  try { data = JSON.parse(raw); } catch(e) { continue; }

  let changed = false;

  if (basename === 'permissions.json' && Array.isArray(data)) {
    data.forEach(p => {
      ['code', 'module', 'resourceType'].forEach(k => {
        if (p[k] && p[k] !== lc(p[k])) { p[k] = lc(p[k]); changed = true; }
      });
    });
  }

  if (basename === 'menus.json' && Array.isArray(data)) {
    data.forEach(m => {
      ['code', 'parentCode', 'permissionCode'].forEach(k => {
        if (m[k] && m[k] !== lc(m[k])) { m[k] = lc(m[k]); changed = true; }
      });
    });
  }

  if (basename === 'roles.json' && Array.isArray(data)) {
    data.forEach(r => {
      if (r.code && r.code !== lc(r.code)) { r.code = lc(r.code); changed = true; }
      if (Array.isArray(r.permissions)) {
        const newPerms = r.permissions.map(p => lc(p));
        if (JSON.stringify(newPerms) !== JSON.stringify(r.permissions)) {
          r.permissions = newPerms;
          changed = true;
        }
      }
    });
  }

  if (basename === 'default-bootstrap.json') {
    const bindings = data.rolePermissionBindings || data;
    if (Array.isArray(bindings)) {
      bindings.forEach(b => {
        if (b.roleCode && b.roleCode !== lc(b.roleCode)) {
          b.roleCode = lc(b.roleCode);
          changed = true;
        }
      });
    }
  }

  if (changed) {
    fs.writeFileSync(file, JSON.stringify(data, null, 2) + '\n');
    updatedCount++;
    console.log('UPDATED:', file);
  }
}

console.log(`\nDone. Updated ${updatedCount} files.`);
