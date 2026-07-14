#!/usr/bin/env node
/**
 * check-renderer-design-tokens.mjs
 *
 * Guards §1.2 "all components reference tokens": workbench block renderers must
 * not hardcode raw Tailwind color scales (bg-emerald-50, text-blue-700, …).
 * They must use design tokens instead (bg-panel, text-text-2, text-status-<tone>,
 * bg-status-<tone>-bg, border-border, rounded-card, …).
 *
 * Why a gate and not prose: §1.2 has said this for a long time; ~18 renderers
 * drifted anyway. A prose rule doesn't fail the build; a red gate does.
 *
 * Exemptions are EXPLICIT, never by wording:
 *   - Whole-file: add the file to ALLOWLIST below with a reason (genuine
 *     non-status palettes: syntax highlight, avatar seed colors, domain viewers).
 *   - Single line: end the line with `// tokens-allow: <reason>`.
 *
 * Self-test (must stay true): reverting any aligned renderer to a pastel fill
 * makes this exit non-zero. A gate you've never seen go red is not a gate — see
 * the sample-injection check the CI wrapper runs.
 *
 * Usage: node scripts/check-renderer-design-tokens.mjs
 * Exit 0 = clean, 1 = violations.
 */
import { readdirSync, readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const BLOCKS_DIR = join(
  HERE,
  '..',
  'web-admin',
  'app',
  'framework',
  'meta',
  'rendering',
  'blocks',
);

// Whole-file exemptions — genuine non-status palettes only. Each needs a reason.
const ALLOWLIST = {
  'GerberViewerBlockRenderer.tsx': 'PCBA gerber layer palette — physical copper/silk colors, not UI status',
  'CodeSnippetBlockRenderer.tsx': 'syntax-highlight token palette, not UI status',
};

// Raw Tailwind color-scale utilities that must not appear (unless exempt).
const HARDCODED = /\b(?:bg|text|border|ring|decoration|fill|stroke|from|to|via)-(?:emerald|amber|rose|blue|violet|green|red|gray|slate|zinc|sky|indigo|orange|yellow|purple|pink|teal|cyan|lime|fuchsia)-[0-9]{2,3}\b/;

function scan() {
  const files = readdirSync(BLOCKS_DIR).filter(
    (f) => f.endsWith('.tsx') && !f.endsWith('.test.tsx'),
  );
  const violations = [];
  for (const file of files) {
    if (ALLOWLIST[file]) continue;
    const lines = readFileSync(join(BLOCKS_DIR, file), 'utf8').split('\n');
    lines.forEach((line, i) => {
      if (line.includes('tokens-allow:')) return;
      const m = line.match(HARDCODED);
      if (m) violations.push({ file, line: i + 1, hit: m[0], text: line.trim().slice(0, 100) });
    });
  }
  return violations;
}

const violations = scan();
if (violations.length === 0) {
  console.log('✓ renderer design tokens: no hardcoded Tailwind color scales');
  process.exit(0);
}
console.error(`✗ renderer design tokens: ${violations.length} hardcoded color(s) — use design tokens or an explicit exemption\n`);
for (const v of violations) {
  console.error(`  ${v.file}:${v.line}  ${v.hit}\n    ${v.text}`);
}
console.error(
  '\nFix: map to tokens (bg-panel / text-text-2 / text-status-<tone> / bg-status-<tone>-bg / border-border / rounded-card).',
);
console.error('Or, if genuinely non-status (syntax/avatar/domain), add `// tokens-allow: <reason>` on the line or the file to ALLOWLIST.');
process.exit(1);
