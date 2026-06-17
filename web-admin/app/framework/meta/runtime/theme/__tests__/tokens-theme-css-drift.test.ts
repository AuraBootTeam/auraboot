/**
 * Drift gate: the committed app/styles/tokens.theme.css (consumed by Tailwind v4
 * at build time) MUST equal buildThemeCss(dsTokens). If this fails, run
 * `pnpm gen:tokens` to regenerate — this keeps dsTokens the single source of
 * truth and prevents hand-edits to the generated CSS from silently diverging.
 */

import { readFileSync } from 'node:fs';
import { resolve } from 'node:path';
import { describe, it, expect } from 'vitest';
import { buildThemeCss } from '~/framework/meta/runtime/theme/tokens';

const THEME_CSS_PATH = resolve(process.cwd(), 'app/styles/tokens.theme.css');

describe('tokens.theme.css drift gate', () => {
  it('committed theme CSS matches buildThemeCss(dsTokens) — run `pnpm gen:tokens` if this fails', () => {
    const committed = readFileSync(THEME_CSS_PATH, 'utf8');
    expect(committed).toBe(buildThemeCss());
  });
});
