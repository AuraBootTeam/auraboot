/**
 * T3 dark-mode token overrides. The @theme utilities reference var(--color-*),
 * so the generated `.dark { … }` block switches bg-panel / text-text / bg-accent
 * / status colors under `darkMode: 'class'`. Light @theme block is unchanged.
 */
import { describe, it, expect } from 'vitest';
import { dsTokens, buildThemeCss } from '~/framework/meta/runtime/theme/tokens';

describe('dsTokens.dark — dark palette', () => {
  it('neutral + accent dark values (grounded in app.css .dark conventions)', () => {
    expect(dsTokens.dark.color.text).toBe('#F9FAFB');
    expect(dsTokens.dark.color.bg).toBe('#111827');
    expect(dsTokens.dark.color.panel).toBe('#1F2937');
    expect(dsTokens.dark.color.border).toBe('#374151');
    expect(dsTokens.dark.color.accent).toBe('#60A5FA');
  });

  it('5 status colors have dark fg + bg variants', () => {
    expect(dsTokens.dark.status.green).toEqual({ fg: '#34D399', bg: '#14271C' });
    expect(dsTokens.dark.status.red).toEqual({ fg: '#F87171', bg: '#2A1515' });
  });
});

describe('buildThemeCss — .dark override block', () => {
  const css = buildThemeCss();

  it('emits a .dark block overriding the semantic color vars', () => {
    expect(css).toContain('.dark {');
    expect(css).toContain('--color-panel: #1F2937;');
    expect(css).toContain('--color-text: #F9FAFB;');
    expect(css).toContain('--color-accent: #60A5FA;');
    expect(css).toContain('--color-status-green: #34D399;');
    expect(css).toContain('--color-status-green-bg: #14271C;');
  });

  it('keeps the light @theme values intact (light unchanged)', () => {
    expect(css).toContain('--color-panel: #FFFFFF;'); // light @theme
    expect(css).toContain('--color-accent: #2563EB;'); // light @theme
  });
});
