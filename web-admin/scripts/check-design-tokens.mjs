#!/usr/bin/env node
/**
 * UX Design System token gate (G1).
 *
 * Spec: auraboot-enterprise/docs/standards/core/ux-design-system.md §0/§1.
 * Single source of visual tokens = app/framework/meta/runtime/theme/tokens.ts →
 * app/styles/tokens.theme.css (generated). Components must reference semantic
 * tokens, never hardcode colors/sizes.
 *
 * HARD failures (block):
 *   1. Raw hex color literals (`#rgb` / `#rrggbb`) in app/ui components — outside
 *      the allowlist (color-picker / avatar generation / the token source).
 *   2. Arbitrary-value tailwind colors (`text-[#…]`, `bg-[#…]`, `border-[#…]`).
 *
 * RATCHETS (no-regression):
 *   - palette utilities (`bg-gray-500`, `text-blue-600`, …) that bypass semantic
 *     tokens must not exceed PALETTE_BASELINE (G1 burn-down).
 *   - hardcoded user-facing strings in `placeholder` / `title` / `aria-label`
 *     attributes (English ≥4 chars or any CJK) must not exceed I18N_BASELINE
 *     (G2: i18n — new strings must go through `t()` / `$i18n:` / LocalizedText).
 *   Drive both down via the T3 sweep (see TOKENS-BURNDOWN.md) and lower the baselines.
 *
 * Usage: node scripts/check-design-tokens.mjs    # exit 1 on any hard violation
 *        node scripts/check-design-tokens.mjs --update-baseline  # print current
 */
import { readFileSync, readdirSync, statSync } from 'fs';
import { resolve, dirname, relative } from 'path';
import { fileURLToPath } from 'url';

const __dirname = dirname(fileURLToPath(import.meta.url));
const WEB_ADMIN = resolve(__dirname, '..');
// Scanned design-system surfaces: the component library + the DSL renderer layer.
const SCAN_ROOTS = ['app/ui', 'app/framework/meta/rendering'].map((r) => resolve(WEB_ADMIN, r));

// Ratchet baselines — current combined counts. LOWER as sweeps land; never raise.
const PALETTE_BASELINE = 1278; // G1: palette utilities bypassing semantic tokens
const I18N_BASELINE = 111; // G2: hardcoded user-facing placeholder/title/aria-label strings

const EXT = new Set(['.ts', '.tsx']);
const IGNORE_DIR = new Set(['node_modules', 'build', 'dist', '.git']);
const isTest = (p) => /\.test\.|__tests__/.test(p);

// Files where a hex literal is the legitimate domain value, not styling.
const HEX_ALLOW = [/ColorPickerField\.tsx$/, /avatar-utils\.ts$/, /runtime\/theme\/tokens\.ts$/];

const RAW_HEX = /(?<![\w[])#[0-9a-fA-F]{3}(?:[0-9a-fA-F]{3})?\b/;
const ARBITRARY_COLOR =
  /\b(?:text|bg|border|ring|fill|stroke|divide|from|to|via)-\[#[0-9a-fA-F]{3,8}\]/;
const PALETTE =
  /\b(?:text|bg|border|ring|divide|from|to|via)-(?:gray|slate|zinc|neutral|stone|blue|sky|indigo|red|rose|green|emerald|amber|yellow|orange|cyan|teal|violet|purple)-[0-9]{2,3}\b/g;
// G2: user-facing string literal in placeholder/title/aria-label (English ≥4 or CJK).
const I18N_HARDCODE =
  /(?:placeholder|title|aria-label)="(?:[^"]*[一-鿿][^"]*|[A-Za-z][A-Za-z .,!?'/-]{3,})"/g;

function walk(p, out) {
  let st;
  try {
    st = statSync(p);
  } catch {
    return;
  }
  if (st.isDirectory()) {
    if (IGNORE_DIR.has(p.split('/').pop())) return;
    for (const e of readdirSync(p)) walk(resolve(p, e), out);
  } else if (EXT.has(p.slice(p.lastIndexOf('.'))) && !isTest(p)) {
    out.push(p);
  }
}

const files = [];
for (const root of SCAN_ROOTS) walk(root, files);

const violations = [];
let paletteCount = 0;
let i18nCount = 0;

for (const file of files) {
  const rel = relative(WEB_ADMIN, file);
  const src = readFileSync(file, 'utf8');
  const hexAllowed = HEX_ALLOW.some((re) => re.test(file));

  src.split('\n').forEach((line, i) => {
    if (!hexAllowed && RAW_HEX.test(line)) {
      violations.push(`${rel}:${i + 1}  raw hex literal — use a semantic token / var(--color-*)`);
    }
    if (ARBITRARY_COLOR.test(line)) {
      violations.push(`${rel}:${i + 1}  arbitrary tailwind color — use a semantic color utility`);
    }
    // Count palette utilities outside dark: variants (the burn-down surface).
    // Walk back to the start of the utility token so we catch dark: anywhere in
    // a stacked variant chain (dark:hover:bg-gray-700, not just immediate dark:).
    for (const m of line.matchAll(PALETTE)) {
      let s = m.index;
      while (s > 0 && !/[\s"'`={]/.test(line[s - 1])) s -= 1;
      const token = line.slice(s, m.index + m[0].length);
      if (!token.includes('dark:')) paletteCount += 1;
    }
    for (const _ of line.matchAll(I18N_HARDCODE)) i18nCount += 1;
  });
}

if (process.argv.includes('--update-baseline')) {
  console.log(`palette-utility count (app/ui, light): ${paletteCount}`);
  console.log(`hardcoded i18n attr count (app/ui): ${i18nCount}`);
  process.exit(0);
}

let failed = false;
if (violations.length > 0) {
  console.error(`\n[design-tokens] ${violations.length} hard violation(s):`);
  for (const v of violations) console.error('  ✗ ' + v);
  failed = true;
}

if (paletteCount > PALETTE_BASELINE) {
  console.error(
    `\n[design-tokens] palette-utility ratchet regressed: ${paletteCount} > baseline ${PALETTE_BASELINE}.`,
  );
  console.error('  Map new palette colors to semantic tokens (see TOKENS-BURNDOWN.md).');
  failed = true;
} else {
  console.log(
    `[design-tokens] palette-utility burn-down: ${paletteCount} / baseline ${PALETTE_BASELINE} (ok)` +
      (paletteCount < PALETTE_BASELINE ? ` — lower the baseline to ${paletteCount}.` : ''),
  );
}

if (i18nCount > I18N_BASELINE) {
  console.error(
    `\n[design-tokens] i18n ratchet regressed: ${i18nCount} hardcoded placeholder/title/aria-label strings > baseline ${I18N_BASELINE}.`,
  );
  console.error('  New user-facing strings must go through t() / $i18n: / LocalizedText.');
  failed = true;
} else {
  console.log(
    `[design-tokens] i18n hardcode burn-down: ${i18nCount} / baseline ${I18N_BASELINE} (ok)` +
      (i18nCount < I18N_BASELINE ? ` — lower the baseline to ${i18nCount}.` : ''),
  );
}

if (failed) {
  console.error('\nSpec: docs/standards/core/ux-design-system.md §0/§1.\n');
  process.exit(1);
}
console.log('[design-tokens] OK — no raw hex / arbitrary color in app/ui.');
