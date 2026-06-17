/**
 * buildThemeCss — derives the Tailwind v4 `@theme` block + companion `:root`
 * custom properties from dsTokens, so dsTokens stays the single source feeding
 * both utilities (bg-accent, rounded-control, …) and CSS variables.
 *
 * Spec: auraboot-enterprise/docs/standards/core/ux-design-system.md §1
 */

import { describe, it, expect } from 'vitest';
import { buildThemeCss, dsTokens } from '~/framework/meta/runtime/theme/tokens';

describe('buildThemeCss', () => {
  const css = buildThemeCss(dsTokens);

  it('opens with an auto-generated banner and a @theme block', () => {
    expect(css).toContain('AUTO-GENERATED');
    expect(css).toContain('@theme {');
  });

  it('emits font-family tokens (→ font-ui / font-mono utilities)', () => {
    expect(css).toContain(`--font-ui: ${dsTokens.font.ui};`);
    expect(css).toContain(`--font-mono: ${dsTokens.font.mono};`);
  });

  it('emits neutral + accent colors as --color-* (→ bg-/text-/border- utilities)', () => {
    expect(css).toContain('--color-text: #1A1A1E;');
    expect(css).toContain('--color-text-2: #5A5E66;');
    expect(css).toContain('--color-text-3: #9A9DA5;');
    expect(css).toContain('--color-border: #ECEDEF;');
    expect(css).toContain('--color-border-strong: #E2E3E6;');
    expect(css).toContain('--color-bg: #F7F7F8;');
    expect(css).toContain('--color-panel: #FFFFFF;');
    expect(css).toContain('--color-subtle: #FAFAFB;');
    expect(css).toContain('--color-hover: #F3F4F6;');
    expect(css).toContain('--color-selection: #EEF4FF;');
    expect(css).toContain('--color-accent: #2563EB;');
    expect(css).toContain('--color-accent-hover: #1D4ED8;');
    expect(css).toContain('--color-accent-weak: #EFF4FF;');
  });

  it('emits the 5 semantic status colors with fg + bg variants', () => {
    expect(css).toContain('--color-status-gray: #71717A;');
    expect(css).toContain('--color-status-gray-bg: #F1F1F3;');
    expect(css).toContain('--color-status-blue: #2563EB;');
    expect(css).toContain('--color-status-blue-bg: #EAF1FE;');
    expect(css).toContain('--color-status-amber: #C2750A;');
    expect(css).toContain('--color-status-amber-bg: #FBF1E2;');
    expect(css).toContain('--color-status-green: #15A34A;');
    expect(css).toContain('--color-status-green-bg: #E7F6ED;');
    expect(css).toContain('--color-status-red: #DC2626;');
    expect(css).toContain('--color-status-red-bg: #FCECEC;');
  });

  it('emits radius tokens (→ rounded-control / rounded-card / …)', () => {
    expect(css).toContain('--radius-control: 6px;');
    expect(css).toContain('--radius-card: 8px;');
    expect(css).toContain('--radius-card-lg: 10px;');
    expect(css).toContain('--radius-pill: 9999px;');
  });

  it('emits shadows incl. focus ring (→ shadow-focus utility)', () => {
    expect(css).toContain('--shadow-card: 0 1px 2px rgba(16,18,23,.03);');
    expect(css).toContain(
      '--shadow-pop: 0 1px 2px rgba(16,18,23,.04), 0 8px 24px -6px rgba(16,18,23,.14);',
    );
    expect(css).toContain('--shadow-toast: 0 6px 20px -4px rgba(16,18,23,.22);');
    expect(css).toContain('--shadow-focus: 0 0 0 3px #EFF4FF;');
  });

  it('emits type scale as --text-* with paired --font-weight modifiers', () => {
    expect(css).toContain('--text-title: 20px;');
    expect(css).toContain('--text-title--font-weight: 660;');
    expect(css).toContain('--text-section: 15px;');
    expect(css).toContain('--text-section--font-weight: 620;');
    expect(css).toContain('--text-body: 13.5px;');
    expect(css).toContain('--text-eyebrow: 11.5px;');
    expect(css).toContain('--text-eyebrow--font-weight: 600;');
  });

  it('emits a :root companion with control heights, spacing grid, disabled opacity', () => {
    expect(css).toContain(':root {');
    expect(css).toContain('--ds-control-sm: 28px;');
    expect(css).toContain('--ds-control-md: 32px;');
    expect(css).toContain('--ds-control-lg: 40px;');
    expect(css).toContain('--ds-control-field: 34px;');
    expect(css).toContain('--ds-space-3: 12px;');
    expect(css).toContain('--ds-space-5: 20px;');
    expect(css).toContain('--ds-disabled-opacity: 0.5;');
  });

  it('is deterministic — same input yields byte-identical output', () => {
    expect(buildThemeCss(dsTokens)).toBe(css);
  });
});
