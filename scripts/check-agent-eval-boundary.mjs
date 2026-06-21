#!/usr/bin/env node
// Fails if OSS framework/agent MAIN sources hardcode eval cases referencing vertical command prefixes.
//
// Vertical prefixes represent plugin-owned tool namespaces (crm:, qc:, iot_, pe:, mfg:).
// Eval cases that reference these must live in plugin config (agent-definitions.json evalCases[]),
// not in the OSS core — they are loaded from DB via CapabilityEvalService.loadRegisteredCases.
//
// Complements check-oss-boundary.sh (which catches "import ...enterprise..." Java imports).
//
// No exclusions: all vertical archetype eval cases have been migrated out of the OSS core
// (device → pcba-manufacturing, pcba-quality → quality) or removed (cs/competitive orphans);
// AgentArchetypeEvalCases.java is deleted. The OSS framework/agent main tree must hold ZERO
// vertical eval cases — any new one belongs in its plugin's agent-definitions.json evalCases[].

import { execSync } from 'node:child_process';
import fs from 'node:fs';

const ROOT = 'platform/src/main/java/com/auraboot/framework/agent';

if (!fs.existsSync(ROOT)) {
  console.error(`check-agent-eval-boundary: scan root not found: ${ROOT} (run from the OSS repo root)`);
  process.exit(2);
}
const PREFIXES = ['crm:', 'qc:', 'iot_', 'pe:', 'mfg:'];

let hits = [];
for (const p of PREFIXES) {
  try {
    const out = execSync(`grep -rn "${p}" ${ROOT} || true`, { encoding: 'utf8' });
    out.split('\n').filter(Boolean)
       .filter(l => /expectedToolCodes|forbiddenToolCodes|CapabilityEvalCase|EvalCase/.test(l))
       .forEach(l => hits.push(l));
  } catch {}
}
if (hits.length) {
  console.error('OSS agent eval boundary violation — vertical eval cases must live in plugins:\n' + hits.join('\n'));
  process.exit(1);
}
console.log('agent-eval boundary OK');
