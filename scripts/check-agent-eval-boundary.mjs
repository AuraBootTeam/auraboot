#!/usr/bin/env node
// OSS agent/RAG boundary gate.
//
// Principle (AGENTS §2): the OSS aurabot core is the AI *mechanism* (conversation,
// tool SPI, eval engine, RAG framework, LLM factory) and must be content-free —
// ZERO vertical business eval cases / prompt constants / RAG seeds / config
// defaults baked in. Business AI content rides in plugins (agent-definitions.json
// evalCases[], knowledge ingestion, …) and is injected on demand at import time.
// Pattern + how-to: docs/plugin-development/agent-capabilities-in-plugins.md.
//
// What this gate catches: a **quoted full business command code** —
// "<vertical>:<verb>" (crm:/qc:/pe:/mfg:) or "iot_<x>:<verb>" — appearing in OSS
// core agent/RAG main sources. That is the strongest machine-checkable proxy for
// "business content leaked into core": it flags eval cases, prompt string
// constants, RAG seeds, and config defaults that name a plugin-owned command.
// Quote-anchored, so it never false-positives on incidental substrings like
// "type:" / "shape:" / "envelope:".
//
// LIMITATION (honest): a pure natural-language business prompt that names NO
// command code cannot be machine-detected here — that still relies on review
// (AGENTS §2). Genuine exceptions can be marked with a `boundary-allow` comment.
//
// Complements check-oss-boundary.sh (which catches `import ...enterprise...`).

import { execSync } from 'node:child_process';
import fs from 'node:fs';

const ROOTS = [
  'platform/src/main/java/com/auraboot/framework/agent',
  'platform/src/main/java/com/auraboot/framework/rag',
];

// Quoted full business command code. The leading `"` anchors it to a string
// literal that STARTS with the command code, so "type:"/"shape:" never match.
const BIZ_CMD = /"(crm|qc|pe|mfg):[a-z][a-zA-Z0-9_]*"|"iot_[a-z][a-zA-Z0-9_]*:[a-z][a-zA-Z0-9_]*"/;
const COMMENT = /^\s*(\/\/|\*|\/\*)/;

const present = ROOTS.filter((r) => fs.existsSync(r));
if (present.length === 0) {
  console.error('check-agent-eval-boundary: no scan roots found (run from the OSS repo root)');
  process.exit(2);
}

const hits = [];
for (const root of present) {
  // Broad candidate grep (any quoted prefix), then filter precisely in JS.
  const out = execSync(`grep -rnE '"(crm|qc|pe|mfg):|"iot_[a-z]' ${root} || true`, { encoding: 'utf8' });
  for (const line of out.split('\n').filter(Boolean)) {
    const content = line.split(':').slice(2).join(':'); // strip "file:lineno:"
    if (COMMENT.test(content)) continue;       // javadoc/comment examples are fine
    if (/boundary-allow/.test(content)) continue; // explicit, documented exemption
    if (!BIZ_CMD.test(line)) continue;         // must be a full quoted command code
    hits.push(line);
  }
}

if (hits.length) {
  console.error(
    'OSS agent/RAG boundary violation — vertical business command codes must live in plugins, not the OSS core:\n'
    + hits.join('\n')
    + '\n\nMove eval cases to the plugin\'s agent-definitions.json evalCases[]; move business'
    + ' prompts/knowledge to plugin config. See docs/plugin-development/agent-capabilities-in-plugins.md.'
  );
  process.exit(1);
}
console.log('agent/rag boundary OK');
