#!/usr/bin/env node
/**
 * Backfill agent_hint and cmd_risk_level for all core plugin command JSON files.
 *
 * Usage: node scripts/backfill-agent-hints.mjs [--dry-run]
 *
 * Risk level assignment:
 *   - QUERY → L0
 *   - CREATE (no sideEffects) → L1
 *   - UPDATE (no sideEffects) → L1
 *   - STATE_TRANSITION (no sideEffects) → L1
 *   - CREATE/UPDATE/STATE_TRANSITION with sideEffects → L2
 *   - Commands with external side effects (SEND_EMAIL, WEBHOOK, etc.) → L3
 *   - DELETE / BULK_DELETE → L4
 *
 * agent_hint generation:
 *   - Built from type + modelCode + stateField + fromStates/toState + sideEffects + inputFields
 */

import fs from 'fs';
import path from 'path';
import { fileURLToPath } from 'url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PLUGINS_DIR = path.join(__dirname, '..', 'plugins');
const DRY_RUN = process.argv.includes('--dry-run');

const CORE_PLUGINS = ['crm', 'sales', 'procurement', 'inventory', 'finance'];

const EXTERNAL_SIDE_EFFECT_TYPES = new Set([
  'SEND_EMAIL', 'SEND_NOTIFICATION', 'WEBHOOK', 'HTTP_CALL', 'EXTERNAL_API'
]);

function determineRiskLevel(cmd) {
  const type = cmd.type || '';
  const sideEffects = cmd.sideEffects || [];

  if (type === 'QUERY') return 'L0';
  if (type === 'DELETE' || type === 'BULK_DELETE') return 'L4';

  // Check for external side effects → L3
  for (const se of sideEffects) {
    const actions = se.actions || [];
    for (const action of actions) {
      if (EXTERNAL_SIDE_EFFECT_TYPES.has(action.type)) return 'L3';
    }
  }

  // Has side effects → L2
  if (sideEffects.length > 0) return 'L2';

  // Simple write → L1
  if (['CREATE', 'UPDATE', 'STATE_TRANSITION', 'BATCH'].includes(type)) return 'L1';

  return 'L1'; // default
}

function generateAgentHint(cmd) {
  const type = cmd.type || 'UNKNOWN';
  const modelCode = cmd.modelCode || '';
  const modelName = modelCode.replace(/_/g, ' ');

  const parts = [];

  switch (type) {
    case 'CREATE': {
      parts.push(`Create a new ${modelName} record.`);
      const inputFields = cmd.inputFields || [];
      if (inputFields.length > 0) {
        const fieldNames = inputFields.slice(0, 5).map(f => {
          // Extract meaningful part: crm_lead_company → company
          const segments = f.split('_');
          return segments.slice(Math.min(2, segments.length - 1)).join('_');
        });
        parts.push(`Key inputs: ${fieldNames.join(', ')}.`);
      }
      const autoSetFields = cmd.autoSetFields || {};
      const autoKeys = Object.keys(autoSetFields);
      if (autoKeys.length > 0) {
        const autoNames = autoKeys.map(f => {
          const segments = f.split('_');
          return segments.slice(Math.min(2, segments.length - 1)).join('_');
        });
        parts.push(`Auto-generated: ${autoNames.join(', ')}.`);
      }
      break;
    }
    case 'UPDATE': {
      parts.push(`Update an existing ${modelName} record.`);
      const inputFields = cmd.inputFields || [];
      if (inputFields.length > 0) {
        const fieldNames = inputFields.slice(0, 5).map(f => {
          const segments = f.split('_');
          return segments.slice(Math.min(2, segments.length - 1)).join('_');
        });
        parts.push(`Editable fields: ${fieldNames.join(', ')}.`);
      }
      break;
    }
    case 'DELETE':
    case 'BULK_DELETE': {
      parts.push(`Delete ${modelName} record(s). This is irreversible.`);
      const preconditions = cmd.preconditions || [];
      if (preconditions.length > 0) {
        const conds = preconditions.map(p => {
          const field = (p.field || '').split('_').slice(-1)[0];
          return `${field} ${p.operator || '='} ${JSON.stringify(p.value)}`;
        });
        parts.push(`Preconditions: ${conds.join('; ')}.`);
      }
      break;
    }
    case 'STATE_TRANSITION': {
      const stateField = cmd.stateField || '';
      const fromStates = cmd.fromStates || [];
      const toState = cmd.toState || '';
      const fieldShort = stateField.split('_').slice(-1)[0];
      parts.push(`Transition ${modelName} ${fieldShort} from ${fromStates.join('/')} to ${toState}.`);
      break;
    }
    case 'QUERY': {
      parts.push(`Query ${modelName} records.`);
      break;
    }
    default: {
      parts.push(`Execute ${type} operation on ${modelName}.`);
    }
  }

  // Add side effects info
  const sideEffects = cmd.sideEffects || [];
  if (sideEffects.length > 0) {
    const seDescs = [];
    for (const se of sideEffects) {
      for (const action of (se.actions || [])) {
        if (action.type === 'CREATE_RECORD') {
          seDescs.push(`creates ${(action.modelCode || '').replace(/_/g, ' ')} record`);
        } else if (action.type === 'UPDATE_RECORD') {
          seDescs.push(`updates ${(action.modelCode || '').replace(/_/g, ' ')}`);
        } else if (action.type === 'AGGREGATE') {
          seDescs.push(`recalculates aggregates on ${(action.modelCode || '').replace(/_/g, ' ')}`);
        }
      }
    }
    if (seDescs.length > 0) {
      parts.push(`Side effects: ${seDescs.join(', ')}.`);
    }
  }

  return parts.join(' ');
}

function processCommandFile(filePath) {
  const content = fs.readFileSync(filePath, 'utf-8');
  let data;
  try {
    data = JSON.parse(content);
  } catch (e) {
    console.warn(`  SKIP (parse error): ${filePath}`);
    return { modified: false };
  }

  const commands = Array.isArray(data) ? data : [data];
  let modified = false;
  let hintsAdded = 0;
  let risksAdded = 0;

  for (const cmd of commands) {
    // Add agent_hint if not present
    if (!cmd.agent_hint) {
      cmd.agent_hint = generateAgentHint(cmd);
      hintsAdded++;
      modified = true;
    }

    // Add cmd_risk_level if not present
    if (!cmd.cmd_risk_level) {
      cmd.cmd_risk_level = determineRiskLevel(cmd);
      risksAdded++;
      modified = true;
    }
  }

  if (modified && !DRY_RUN) {
    const output = Array.isArray(data)
      ? JSON.stringify(data, null, 2)
      : JSON.stringify(data, null, 2);
    fs.writeFileSync(filePath, output + '\n', 'utf-8');
  }

  return { modified, hintsAdded, risksAdded };
}

// Main
let totalFiles = 0;
let totalModified = 0;
let totalHints = 0;
let totalRisks = 0;

for (const plugin of CORE_PLUGINS) {
  const commandsDir = path.join(PLUGINS_DIR, plugin, 'config', 'commands');
  if (!fs.existsSync(commandsDir)) {
    console.log(`Plugin ${plugin}: no commands directory`);
    continue;
  }

  const files = fs.readdirSync(commandsDir).filter(f => f.endsWith('.json'));
  console.log(`\nPlugin: ${plugin} (${files.length} command files)`);

  for (const file of files) {
    const filePath = path.join(commandsDir, file);
    totalFiles++;

    const result = processCommandFile(filePath);
    if (result.modified) {
      totalModified++;
      totalHints += result.hintsAdded;
      totalRisks += result.risksAdded;
      console.log(`  ${DRY_RUN ? '[DRY-RUN] ' : ''}${file}: +${result.hintsAdded} hints, +${result.risksAdded} risks`);
    }
  }
}

console.log(`\n=== Summary ===`);
console.log(`Files scanned: ${totalFiles}`);
console.log(`Files modified: ${totalModified}`);
console.log(`agent_hint added: ${totalHints}`);
console.log(`cmd_risk_level added: ${totalRisks}`);
if (DRY_RUN) console.log('(DRY RUN - no files were changed)');
