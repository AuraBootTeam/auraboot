/**
 * Design-system canonical tokens (dsTokens) — values are the single source of
 * truth for the UX Design System Standard.
 *
 * Spec: auraboot-enterprise/docs/standards/core/ux-design-system.md §1
 * Reference impl: auraboot-enterprise/docs/mockups/ux-design-system/index.html
 *
 * dsTokens is additive — the legacy `designTokens` + `resolveToken` DSL `$path`
 * resolver are left untouched (covered by tokens.test.ts).
 */

import { describe, it, expect } from 'vitest';
import { dsTokens } from '~/framework/meta/runtime/theme/tokens';

describe('dsTokens — canonical design-system values', () => {
  it('neutral text scale matches standard §1', () => {
    expect(dsTokens.color.text).toBe('#1A1A1E');
    expect(dsTokens.color.text2).toBe('#5A5E66');
    expect(dsTokens.color.text3).toBe('#9A9DA5');
  });

  it('surfaces & borders match standard §1', () => {
    expect(dsTokens.color.border).toBe('#ECEDEF');
    expect(dsTokens.color.borderStrong).toBe('#E2E3E6');
    expect(dsTokens.color.bg).toBe('#F7F7F8');
    expect(dsTokens.color.panel).toBe('#FFFFFF');
    expect(dsTokens.color.subtle).toBe('#FAFAFB');
    expect(dsTokens.color.hover).toBe('#F3F4F6');
    expect(dsTokens.color.selection).toBe('#EEF4FF');
  });

  it('accent triad matches standard §1', () => {
    expect(dsTokens.color.accent).toBe('#2563EB');
    expect(dsTokens.color.accentHover).toBe('#1D4ED8');
    expect(dsTokens.color.accentWeak).toBe('#EFF4FF');
  });

  it('5 semantic status colors match standard §1.3 (fg + bg)', () => {
    expect(dsTokens.status.gray).toEqual({ fg: '#71717A', bg: '#F1F1F3' });
    expect(dsTokens.status.blue).toEqual({ fg: '#2563EB', bg: '#EAF1FE' });
    expect(dsTokens.status.amber).toEqual({ fg: '#C2750A', bg: '#FBF1E2' });
    expect(dsTokens.status.green).toEqual({ fg: '#15A34A', bg: '#E7F6ED' });
    expect(dsTokens.status.red).toEqual({ fg: '#DC2626', bg: '#FCECEC' });
  });

  it('4px spacing grid includes the 12/20 steps the legacy scale was missing', () => {
    expect(dsTokens.space[1]).toBe('4px');
    expect(dsTokens.space[2]).toBe('8px');
    expect(dsTokens.space[3]).toBe('12px');
    expect(dsTokens.space[4]).toBe('16px');
    expect(dsTokens.space[5]).toBe('20px');
    expect(dsTokens.space[6]).toBe('24px');
    expect(dsTokens.space[8]).toBe('32px');
  });

  it('radius scale: control 6 / card 8 / card-lg 10 / pill', () => {
    expect(dsTokens.radius.control).toBe('6px');
    expect(dsTokens.radius.card).toBe('8px');
    expect(dsTokens.radius.cardLg).toBe('10px');
    expect(dsTokens.radius.pill).toBe('9999px');
  });

  it('control heights: sm 28 / md 32 / lg 40 / field 34 (standard §2)', () => {
    expect(dsTokens.control.sm).toBe('28px');
    expect(dsTokens.control.md).toBe('32px');
    expect(dsTokens.control.lg).toBe('40px');
    expect(dsTokens.control.field).toBe('34px');
  });

  it('type scale: size + weight per standard §1 / mockup', () => {
    expect(dsTokens.textScale.title).toEqual({ size: '20px', weight: '660' });
    expect(dsTokens.textScale.section).toEqual({ size: '15px', weight: '620' });
    expect(dsTokens.textScale.body).toEqual({ size: '13.5px', weight: '400' });
    expect(dsTokens.textScale.aux).toEqual({ size: '12.5px', weight: '400' });
    expect(dsTokens.textScale.eyebrow).toEqual({ size: '11.5px', weight: '600' });
  });

  it('font families: UI sans stack + mono with tabular numerals', () => {
    expect(dsTokens.font.ui).toContain('PingFang SC');
    expect(dsTokens.font.ui).toContain('-apple-system');
    expect(dsTokens.font.mono).toContain('ui-monospace');
    expect(dsTokens.font.mono).toContain('SF Mono');
  });

  it('shadows: card / pop / toast match standard §1', () => {
    expect(dsTokens.shadow.card).toBe('0 1px 2px rgba(16,18,23,.03)');
    expect(dsTokens.shadow.pop).toBe(
      '0 1px 2px rgba(16,18,23,.04), 0 8px 24px -6px rgba(16,18,23,.14)',
    );
    expect(dsTokens.shadow.toast).toBe('0 6px 20px -4px rgba(16,18,23,.22)');
  });

  it('focus ring = 0 0 0 3px accent-weak (standard §1/§2 authoritative; mockup uses 2px)', () => {
    expect(dsTokens.focusRing).toBe('0 0 0 3px #EFF4FF');
  });

  it('disabled opacity unified at .5 (standard §1)', () => {
    expect(dsTokens.disabledOpacity).toBe('0.5');
  });
});
