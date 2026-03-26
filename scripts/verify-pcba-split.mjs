#!/usr/bin/env node
/**
 * Verify PCBA ERP plugin split completeness.
 * Compares source plugin resources against the 8 sub-plugins.
 */
import fs from 'fs';
import path from 'path';

const SRC = 'plugins/pcba-erp';
const SUBS = [
  'pcba-base','pcba-crm','pcba-srm','pcba-sales',
  'pcba-procurement','pcba-wms','pcba-manufacturing','pcba-finance',
];

let errors = 0;
let warnings = 0;

function err(msg) { console.error(`  ❌ ${msg}`); errors++; }
function warn(msg) { console.warn(`  ⚠️  ${msg}`); warnings++; }
function ok(msg) { console.log(`  ✅ ${msg}`); }

function readJSON(p) { return JSON.parse(fs.readFileSync(p, 'utf8')); }
function listFiles(dir) {
  if (!fs.existsSync(dir)) return [];
  return fs.readdirSync(dir).filter(f => f.endsWith('.json')).sort();
}

// ============================================================
console.log('=== 1. 模型完整性 ===');
const srcModels = readJSON(path.join(SRC, 'config/models.json'));
const srcModelCodes = new Set(srcModels.map(m => m.code));
const splitModelCodes = new Set();
const modelDuplicates = [];
for (const sub of SUBS) {
  const models = readJSON(path.join('plugins', sub, 'config/models.json'));
  for (const m of models) {
    if (splitModelCodes.has(m.code)) modelDuplicates.push(m.code);
    splitModelCodes.add(m.code);
  }
}
const missingModels = [...srcModelCodes].filter(c => !splitModelCodes.has(c));
const extraModels = [...splitModelCodes].filter(c => !srcModelCodes.has(c));
if (missingModels.length) err(`缺失模型: ${missingModels.join(', ')}`);
if (extraModels.length) err(`多余模型: ${extraModels.join(', ')}`);
if (modelDuplicates.length) err(`重复模型: ${modelDuplicates.join(', ')}`);
if (!missingModels.length && !extraModels.length && !modelDuplicates.length) {
  ok(`模型完整: ${srcModelCodes.size} = ${splitModelCodes.size}`);
}

// ============================================================
console.log('\n=== 2. 命令文件完整性 ===');
const srcCmds = new Set(listFiles(path.join(SRC, 'config/commands')));
const splitCmds = new Set();
const cmdDuplicates = [];
for (const sub of SUBS) {
  for (const f of listFiles(path.join('plugins', sub, 'config/commands'))) {
    if (splitCmds.has(f)) cmdDuplicates.push(f);
    splitCmds.add(f);
  }
}
const missingCmds = [...srcCmds].filter(c => !splitCmds.has(c));
const extraCmds = [...splitCmds].filter(c => !srcCmds.has(c));
if (missingCmds.length) err(`缺失命令: ${missingCmds.join(', ')}`);
if (extraCmds.length) err(`多余命令: ${extraCmds.join(', ')}`);
if (cmdDuplicates.length) err(`重复命令: ${cmdDuplicates.join(', ')}`);
if (!missingCmds.length && !extraCmds.length && !cmdDuplicates.length) {
  ok(`命令完整: ${srcCmds.size} = ${splitCmds.size}`);
}

// ============================================================
console.log('\n=== 3. 字段文件完整性 ===');
const srcFields = new Set(listFiles(path.join(SRC, 'config/fields')));
const splitFields = new Set();
const fieldDuplicates = [];
for (const sub of SUBS) {
  for (const f of listFiles(path.join('plugins', sub, 'config/fields'))) {
    if (splitFields.has(f)) fieldDuplicates.push(f);
    splitFields.add(f);
  }
}
const missingFields = [...srcFields].filter(c => !splitFields.has(c));
const extraFields = [...splitFields].filter(c => !srcFields.has(c));
if (missingFields.length) err(`缺失字段: ${missingFields.length} 个 (前5: ${missingFields.slice(0,5).join(', ')})`);
if (extraFields.length) err(`多余字段: ${extraFields.join(', ')}`);
if (fieldDuplicates.length) warn(`重复字段: ${fieldDuplicates.length} 个 (跨插件共享字段: ${fieldDuplicates.slice(0,5).join(', ')})`);
if (!missingFields.length && !extraFields.length) {
  ok(`字段完整: ${srcFields.size} 个源字段均已分配 (含 ${fieldDuplicates.length} 个跨插件共享)`);
}

// ============================================================
console.log('\n=== 4. 绑定文件完整性 ===');
const srcBindings = new Set(listFiles(path.join(SRC, 'config/bindings')));
const splitBindings = new Set();
const bindDuplicates = [];
for (const sub of SUBS) {
  for (const f of listFiles(path.join('plugins', sub, 'config/bindings'))) {
    if (splitBindings.has(f)) bindDuplicates.push(f);
    splitBindings.add(f);
  }
}
const missingBindings = [...srcBindings].filter(c => !splitBindings.has(c));
const extraBindings = [...splitBindings].filter(c => !srcBindings.has(c));
if (missingBindings.length) err(`缺失绑定: ${missingBindings.join(', ')}`);
if (extraBindings.length) err(`多余绑定: ${extraBindings.join(', ')}`);
if (bindDuplicates.length) err(`重复绑定: ${bindDuplicates.join(', ')}`);
if (!missingBindings.length && !extraBindings.length && !bindDuplicates.length) {
  ok(`绑定完整: ${srcBindings.size} = ${splitBindings.size}`);
}

// ============================================================
console.log('\n=== 5. 页面文件完整性 ===');
const srcPages = new Set(listFiles(path.join(SRC, 'config/pages')));
const splitPages = new Set();
const pageDuplicates = [];
for (const sub of SUBS) {
  for (const f of listFiles(path.join('plugins', sub, 'config/pages'))) {
    if (splitPages.has(f)) pageDuplicates.push(f);
    splitPages.add(f);
  }
}
const missingPages = [...srcPages].filter(c => !splitPages.has(c));
const extraPages = [...splitPages].filter(c => !srcPages.has(c));
if (missingPages.length) err(`缺失页面: ${missingPages.join(', ')}`);
if (extraPages.length) err(`多余页面: ${extraPages.join(', ')}`);
if (pageDuplicates.length) err(`重复页面: ${pageDuplicates.join(', ')}`);
if (!missingPages.length && !extraPages.length && !pageDuplicates.length) {
  ok(`页面完整: ${srcPages.size} = ${splitPages.size}`);
}

// ============================================================
console.log('\n=== 6. 权限完整性 ===');
const srcPerms = readJSON(path.join(SRC, 'config/permissions.json'));
const srcPermCodes = new Set(srcPerms.map(p => p.code));
const splitPermCodes = new Set();
const permDuplicates = [];
for (const sub of SUBS) {
  const perms = readJSON(path.join('plugins', sub, 'config/permissions.json'));
  for (const p of perms) {
    if (splitPermCodes.has(p.code)) permDuplicates.push(p.code);
    splitPermCodes.add(p.code);
  }
}
const missingPerms = [...srcPermCodes].filter(c => !splitPermCodes.has(c));
const extraPerms = [...splitPermCodes].filter(c => !srcPermCodes.has(c));
if (missingPerms.length) err(`缺失权限: ${missingPerms.join(', ')}`);
if (extraPerms.length) err(`多余权限: ${extraPerms.join(', ')}`);
if (permDuplicates.length) err(`重复权限: ${permDuplicates.join(', ')}`);
if (!missingPerms.length && !extraPerms.length && !permDuplicates.length) {
  ok(`权限完整: ${srcPermCodes.size} = ${splitPermCodes.size}`);
}

// ============================================================
console.log('\n=== 7. 字典完整性 ===');
const srcDicts = readJSON(path.join(SRC, 'config/dicts.json'));
const srcDictCodes = new Set(srcDicts.map(d => d.code));
const splitDictCodes = new Set();
const dictDuplicates = [];
for (const sub of SUBS) {
  const dicts = readJSON(path.join('plugins', sub, 'config/dicts.json'));
  for (const d of dicts) {
    if (splitDictCodes.has(d.code)) dictDuplicates.push(d.code);
    splitDictCodes.add(d.code);
  }
}
const missingDicts = [...srcDictCodes].filter(c => !splitDictCodes.has(c));
const extraDicts = [...splitDictCodes].filter(c => !srcDictCodes.has(c));
if (missingDicts.length) err(`缺失字典: ${missingDicts.join(', ')}`);
if (extraDicts.length) err(`多余字典: ${extraDicts.join(', ')}`);
if (dictDuplicates.length) warn(`重复字典: ${dictDuplicates.length} 个 (跨插件共享是允许的)`);
if (!missingDicts.length && !extraDicts.length) {
  ok(`字典完整: ${srcDictCodes.size} = ${splitDictCodes.size}`);
}

// ============================================================
console.log('\n=== 8. 菜单完整性 ===');
const srcMenus = readJSON(path.join(SRC, 'config/menus.json'));
const srcMenuCodes = new Set(srcMenus.map(m => m.code));
const splitMenuCodes = new Set();
// Root menu (pe_root) is expected to be duplicated across plugins
const splitMenuCodesAll = [];
for (const sub of SUBS) {
  const menus = readJSON(path.join('plugins', sub, 'config/menus.json'));
  for (const m of menus) {
    splitMenuCodesAll.push({ code: m.code, plugin: sub });
    splitMenuCodes.add(m.code);
  }
}
const missingMenus = [...srcMenuCodes].filter(c => !splitMenuCodes.has(c));
const extraMenus = [...splitMenuCodes].filter(c => !srcMenuCodes.has(c));
if (missingMenus.length) err(`缺失菜单: ${missingMenus.join(', ')}`);
if (extraMenus.length) err(`多余菜单: ${extraMenus.join(', ')}`);
// Check non-root duplicates
const nonRootDups = [];
const seen = new Set();
for (const { code, plugin } of splitMenuCodesAll) {
  if (code === 'pe_root') continue;
  if (seen.has(code)) nonRootDups.push(code);
  seen.add(code);
}
if (nonRootDups.length) err(`非根菜单重复: ${nonRootDups.join(', ')}`);
if (!missingMenus.length && !extraMenus.length && !nonRootDups.length) {
  ok(`菜单完整: ${srcMenuCodes.size} 个唯一菜单 (pe_root 在各子插件中重复是正常的)`);
}

// ============================================================
console.log('\n=== 9. i18n 完整性 ===');
const srcI18n = readJSON(path.join(SRC, 'config/i18n.json'));
const srcI18nKeys = new Set(srcI18n.map(e => e.key));
const splitI18nKeys = new Set();
const i18nDuplicates = [];
for (const sub of SUBS) {
  const entries = readJSON(path.join('plugins', sub, 'config/i18n.json'));
  for (const e of entries) {
    if (splitI18nKeys.has(e.key)) i18nDuplicates.push(e.key);
    splitI18nKeys.add(e.key);
  }
}
const missingI18n = [...srcI18nKeys].filter(k => !splitI18nKeys.has(k));
const extraI18n = [...splitI18nKeys].filter(k => !srcI18nKeys.has(k));
if (missingI18n.length) err(`缺失 i18n: ${missingI18n.length} 个 (前5: ${missingI18n.slice(0,5).join(', ')})`);
if (extraI18n.length) err(`多余 i18n: ${extraI18n.join(', ')}`);
if (i18nDuplicates.length) warn(`重复 i18n: ${i18nDuplicates.length} 个 (前5: ${i18nDuplicates.slice(0,5).join(', ')})`);
if (!missingI18n.length && !extraI18n.length && !i18nDuplicates.length) {
  ok(`i18n 完整: ${srcI18nKeys.size} = ${splitI18nKeys.size}`);
}

// ============================================================
console.log('\n=== 10. 角色权限引用完整性 ===');
// Check that every permission referenced in roles.json actually exists in permissions.json of the same or dependency plugin
const pluginPerms = {};
for (const sub of SUBS) {
  pluginPerms[sub] = new Set(
    readJSON(path.join('plugins', sub, 'config/permissions.json')).map(p => p.code)
  );
}
const pluginDeps = {};
for (const sub of SUBS) {
  const pj = readJSON(path.join('plugins', sub, 'plugin.json'));
  pluginDeps[sub] = (pj.dependencies || []).map(d => {
    // com.auraboot.pcba-base -> pcba-base
    return d.replace('com.auraboot.', '');
  });
}
// All permissions available to a plugin = own + all transitive deps
function getAvailablePerms(sub, visited = new Set()) {
  if (visited.has(sub)) return new Set();
  visited.add(sub);
  const perms = new Set(pluginPerms[sub] || []);
  for (const dep of (pluginDeps[sub] || [])) {
    for (const p of getAvailablePerms(dep, visited)) perms.add(p);
  }
  return perms;
}

let rolePermErrors = 0;
for (const sub of SUBS) {
  const rolesFile = path.join('plugins', sub, 'config/roles.json');
  const roles = readJSON(rolesFile);
  const available = getAvailablePerms(sub);
  for (const role of roles) {
    for (const permCode of (role.permissions || [])) {
      if (!available.has(permCode)) {
        // Check if it exists in ANY plugin (cross-plugin reference)
        let foundIn = null;
        for (const [s, perms] of Object.entries(pluginPerms)) {
          if (perms.has(permCode)) { foundIn = s; break; }
        }
        if (foundIn) {
          warn(`${sub} 角色 ${role.code} 引用权限 ${permCode} 在 ${foundIn} 中 (非直接依赖)`);
        } else {
          err(`${sub} 角色 ${role.code} 引用不存在的权限: ${permCode}`);
          rolePermErrors++;
        }
      }
    }
  }
}
if (!rolePermErrors && warnings === 0) {
  ok('角色权限引用完整');
}

// ============================================================
console.log('\n=== 11. 命令引用的模型检查 ===');
// Check that commands reference models that exist in the same plugin or its dependencies
let cmdModelErrors = 0;
for (const sub of SUBS) {
  const cmdDir = path.join('plugins', sub, 'config/commands');
  const ownModels = new Set(
    readJSON(path.join('plugins', sub, 'config/models.json')).map(m => m.code)
  );
  // Add dependency models
  const depModels = new Set(ownModels);
  function addDepModels(s, visited = new Set()) {
    if (visited.has(s)) return;
    visited.add(s);
    for (const dep of (pluginDeps[s] || [])) {
      const models = readJSON(path.join('plugins', dep, 'config/models.json'));
      for (const m of models) depModels.add(m.code);
      addDepModels(dep, visited);
    }
  }
  addDepModels(sub);

  for (const file of listFiles(cmdDir)) {
    const cmd = readJSON(path.join(cmdDir, file));
    const targetModel = cmd.targetModelCode || cmd.modelCode;
    if (targetModel && !depModels.has(targetModel)) {
      err(`${sub} 命令 ${file} 引用模型 ${targetModel} 不在本插件或依赖中`);
      cmdModelErrors++;
    }
  }
}
if (!cmdModelErrors) ok('命令引用的模型均在本插件或依赖中');

// ============================================================
console.log('\n=== 12. plugin.json 结构检查 ===');
for (const sub of SUBS) {
  const pj = readJSON(path.join('plugins', sub, 'plugin.json'));
  const required = ['pluginId','namespace','version','dependencies','resourceDirs'];
  for (const key of required) {
    if (!(key in pj)) err(`${sub}/plugin.json 缺少字段: ${key}`);
  }
  if (pj.namespace !== 'pe') err(`${sub}/plugin.json namespace 应为 "pe", 实际: ${pj.namespace}`);
  // Check dependencies reference valid pluginIds
  for (const dep of (pj.dependencies || [])) {
    const depName = dep.replace('com.auraboot.', '');
    if (!SUBS.includes(depName)) err(`${sub} 依赖 ${dep} 不是有效的子插件`);
  }
}
ok('plugin.json 结构检查完成');

// ============================================================
console.log('\n=== 13. 绑定文件与模型一致性 ===');
let bindModelErrors = 0;
for (const sub of SUBS) {
  const ownModels = new Set(
    readJSON(path.join('plugins', sub, 'config/models.json')).map(m => m.code)
  );
  const bindings = listFiles(path.join('plugins', sub, 'config/bindings'));
  for (const f of bindings) {
    const modelCode = f.replace('.json', '');
    if (!ownModels.has(modelCode)) {
      err(`${sub} 绑定文件 ${f} 对应的模型 ${modelCode} 不在本插件中`);
      bindModelErrors++;
    }
  }
  // Check reverse: every model should have a binding
  for (const mc of ownModels) {
    if (!bindings.includes(`${mc}.json`)) {
      warn(`${sub} 模型 ${mc} 没有对应的绑定文件`);
    }
  }
}
if (!bindModelErrors) ok('绑定文件与模型一致');

// ============================================================
console.log('\n=== 14. 字段文件与绑定引用一致性 ===');
let fieldBindErrors = 0;
for (const sub of SUBS) {
  const fieldDir = path.join('plugins', sub, 'config/fields');
  const bindDir = path.join('plugins', sub, 'config/bindings');
  const fieldFiles = new Set(listFiles(fieldDir).map(f => f.replace('.json', '')));
  
  // Collect all field codes referenced in bindings
  const referencedFields = new Set();
  for (const bf of listFiles(bindDir)) {
    const binding = readJSON(path.join(bindDir, bf));
    const fields = Array.isArray(binding) ? binding : (binding.fields || []);
    for (const f of fields) {
      const code = f.fieldCode || f.code;
      if (code) referencedFields.add(code);
    }
  }
  
  // Fields in binding but not in fields dir
  const missingFieldFiles = [...referencedFields].filter(c => !fieldFiles.has(c));
  if (missingFieldFiles.length) {
    err(`${sub} 绑定引用了 ${missingFieldFiles.length} 个不存在的字段文件 (前3: ${missingFieldFiles.slice(0,3).join(', ')})`);
    fieldBindErrors++;
  }
  
  // Fields in dir but not referenced by any binding (orphans - warning only)
  const orphanFields = [...fieldFiles].filter(c => !referencedFields.has(c));
  if (orphanFields.length) {
    warn(`${sub} 有 ${orphanFields.length} 个字段文件未被绑定引用 (前3: ${orphanFields.slice(0,3).join(', ')})`);
  }
}
if (!fieldBindErrors) ok('绑定引用的字段文件均存在');

// ============================================================
console.log('\n========================================');
console.log(`验证完成: ${errors} 个错误, ${warnings} 个警告`);
if (errors > 0) {
  console.log('❌ 存在错误，需要修复');
  process.exit(1);
} else if (warnings > 0) {
  console.log('⚠️  有警告但无致命错误，可以继续导入');
} else {
  console.log('✅ 全部通过');
}
