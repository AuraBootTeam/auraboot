/**
 * Generate app/styles/tokens.theme.css from dsTokens (the single source of
 * truth). Run via `pnpm gen:tokens`. A drift test
 * (theme/__tests__/tokens-theme-css-drift.test.ts) fails CI if the committed
 * file diverges from `buildThemeCss(dsTokens)`.
 */
import { writeFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { buildThemeCss } from '../app/framework/meta/runtime/theme/tokens';

const here = dirname(fileURLToPath(import.meta.url));
const outFile = resolve(here, '../app/styles/tokens.theme.css');

writeFileSync(outFile, buildThemeCss(), 'utf8');
console.log(`[gen-tokens-css] wrote ${outFile}`);
