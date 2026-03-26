const fs = require("fs");
const path = require("path");

const PLUGINS_DIR = path.join(__dirname, "..", "plugins");

function readI18n(pluginName) {
  const filePath = path.join(PLUGINS_DIR, pluginName, "config/i18n.json");
  if (fs.existsSync(filePath)) {
    return JSON.parse(fs.readFileSync(filePath, "utf8"));
  }
  return [];
}

function writeI18n(pluginName, data) {
  const filePath = path.join(PLUGINS_DIR, pluginName, "config/i18n.json");
  fs.writeFileSync(filePath, JSON.stringify(data, null, 2) + "\n", "utf8");
  console.log(`  Written ${data.length} entries to ${pluginName}/config/i18n.json`);
}

const base = readI18n("pcba-base");
console.log("Total pcba-base entries:", base.length);

// Build a lookup map
const baseMap = {};
base.forEach(e => { baseMap[e.key] = e; });

// ===== FIELD MIGRATION RULES =====
// Migrated fields → target plugin:
//   pcba-industry: pe_prd_lot_policy, pe_prd_lead_time_days, pe_prd_moq, pe_prod_trace_level, pe_prod_msl_level
//   inventory: pe_wh_lot_tracking, pe_wh_pick_strategy, pe_wh_sn_tracking
//   pcba-crm: pe_rfq_date, pe_rfq_due_date, pe_rfq_remark, pe_rfq_total_amount, pe_opp_owner

const fieldMigrations = {
  "pcba-industry": ["pe_prd_lot_policy", "pe_prd_lead_time_days", "pe_prd_moq", "pe_prod_trace_level", "pe_prod_msl_level"],
  "inventory": ["pe_wh_lot_tracking", "pe_wh_pick_strategy", "pe_wh_sn_tracking"],
  "pcba-crm": ["pe_rfq_date", "pe_rfq_due_date", "pe_rfq_remark", "pe_rfq_total_amount", "pe_opp_owner"],
};

// ===== MENU MIGRATION =====
// All menu/dashboard/role text → pcba-solution
const menuTarget = "pcba-solution";

// ===== Collect entries per target =====
const toAdd = {}; // pluginName → array of entries

function addEntry(plugin, entry) {
  if (!toAdd[plugin]) toAdd[plugin] = [];
  toAdd[plugin].push(entry);
}

// 1. Field entries
for (const [plugin, fieldCodes] of Object.entries(fieldMigrations)) {
  for (const fieldCode of fieldCodes) {
    // Match field.{fieldCode}.* entries
    const prefix = "field." + fieldCode + ".";
    base.forEach(e => {
      if (e.key.startsWith(prefix)) {
        addEntry(plugin, e);
      }
    });
  }
}

// 2. Menu entries → pcba-solution
base.forEach(e => {
  if (e.key.startsWith("menu.")) {
    addEntry(menuTarget, e);
  }
});

// ===== Report what we found =====
console.log("\n=== Entries to migrate ===");
for (const [plugin, entries] of Object.entries(toAdd)) {
  console.log(`\n${plugin}: ${entries.length} entries`);
  entries.forEach(e => console.log(`  ${e.key}`));
}

// ===== Now merge into target plugins =====
console.log("\n=== Merging into target plugins ===");
for (const [plugin, newEntries] of Object.entries(toAdd)) {
  const existing = readI18n(plugin);
  const existingKeys = new Set(existing.map(e => e.key));

  let added = 0;
  let skipped = 0;
  for (const entry of newEntries) {
    if (existingKeys.has(entry.key)) {
      skipped++;
      console.log(`  SKIP (duplicate): ${entry.key} in ${plugin}`);
    } else {
      existing.push(entry);
      existingKeys.add(entry.key);
      added++;
    }
  }

  console.log(`\n${plugin}: added=${added}, skipped=${skipped}`);
  if (added > 0) {
    writeI18n(plugin, existing);
  }
}

// ===== Summary of remaining entries (not migrated) =====
const migratedKeys = new Set();
for (const entries of Object.values(toAdd)) {
  entries.forEach(e => migratedKeys.add(e.key));
}
const remaining = base.filter(e => !migratedKeys.has(e.key));
console.log(`\n=== Remaining in pcba-base: ${remaining.length} of ${base.length} ===`);
