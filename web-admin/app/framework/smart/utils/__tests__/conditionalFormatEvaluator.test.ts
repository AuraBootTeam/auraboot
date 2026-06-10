/**
 * Unit tests for conditionalFormatEvaluator
 * Covers evaluateConditionalFormats and buildConditionalStyle across all branches.
 */
import { describe, expect, it } from 'vitest';
import {
  evaluateConditionalFormats,
  buildConditionalStyle,
} from '../conditionalFormatEvaluator';
import type { ConditionalFormatRule, ConditionalFormatStyle } from '~/framework/smart/types/savedView';

// ---------------------------------------------------------------------------
// Helper to build a rule quickly
// ---------------------------------------------------------------------------
function rule(
  fieldCode: string,
  operator: ConditionalFormatRule['operator'],
  value: string | undefined,
  style: ConditionalFormatStyle,
): ConditionalFormatRule {
  return { fieldCode, operator, value, style };
}

const RED_STYLE: ConditionalFormatStyle = { backgroundColor: '#ff0000' };
const BLUE_STYLE: ConditionalFormatStyle = { textColor: '#0000ff' };
const BOLD_STYLE: ConditionalFormatStyle = { bold: true };

describe('evaluateConditionalFormats', () => {
  // ── edge: empty / undefined rules ─────────────────────────────────────────

  it('returns null when rules is undefined', () => {
    expect(evaluateConditionalFormats(undefined, { status: 'ACTIVE' })).toBeNull();
  });

  it('returns null when rules is an empty array', () => {
    expect(evaluateConditionalFormats([], { status: 'ACTIVE' })).toBeNull();
  });

  // ── isNull / isNotNull ─────────────────────────────────────────────────────

  it('isNull matches null value', () => {
    const rules = [rule('field', 'isNull', undefined, RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: null })).toEqual(RED_STYLE);
  });

  it('isNull matches undefined value', () => {
    const rules = [rule('field', 'isNull', undefined, RED_STYLE)];
    expect(evaluateConditionalFormats(rules, {})).toEqual(RED_STYLE);
  });

  it('isNull matches empty-string value', () => {
    const rules = [rule('field', 'isNull', undefined, RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: '' })).toEqual(RED_STYLE);
  });

  it('isNull does NOT match non-empty value', () => {
    const rules = [rule('field', 'isNull', undefined, RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: 'x' })).toBeNull();
  });

  it('isNotNull matches non-empty value', () => {
    const rules = [rule('field', 'isNotNull', undefined, BLUE_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: 'x' })).toEqual(BLUE_STYLE);
  });

  it('isNotNull does NOT match null', () => {
    const rules = [rule('field', 'isNotNull', undefined, BLUE_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: null })).toBeNull();
  });

  // ── eq / ne ───────────────────────────────────────────────────────────────

  it('eq matches string equality', () => {
    const rules = [rule('status', 'eq', 'ACTIVE', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { status: 'ACTIVE' })).toEqual(RED_STYLE);
  });

  it('eq does NOT match different string', () => {
    const rules = [rule('status', 'eq', 'ACTIVE', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { status: 'INACTIVE' })).toBeNull();
  });

  it('ne matches when values differ', () => {
    const rules = [rule('status', 'ne', 'ACTIVE', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { status: 'INACTIVE' })).toEqual(RED_STYLE);
  });

  it('ne does NOT match equal values', () => {
    const rules = [rule('status', 'ne', 'ACTIVE', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { status: 'ACTIVE' })).toBeNull();
  });

  // ── gt / gte / lt / lte — numeric path ───────────────────────────────────

  it('gt matches when record value is numerically greater', () => {
    const rules = [rule('amount', 'gt', '100', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { amount: 200 })).toEqual(RED_STYLE);
  });

  it('gt does NOT match when value is equal', () => {
    const rules = [rule('amount', 'gt', '100', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { amount: 100 })).toBeNull();
  });

  it('gte matches equal numeric values', () => {
    const rules = [rule('amount', 'gte', '100', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { amount: 100 })).toEqual(RED_STYLE);
  });

  it('lt matches numerically smaller value', () => {
    const rules = [rule('score', 'lt', '50', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { score: 30 })).toEqual(RED_STYLE);
  });

  it('lte matches equal and smaller numeric values', () => {
    const rules = [rule('score', 'lte', '50', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { score: 50 })).toEqual(RED_STYLE);
    expect(evaluateConditionalFormats(rules, { score: 10 })).toEqual(RED_STYLE);
  });

  // ── gt / lt — string fallback (non-numeric) ───────────────────────────────

  it('gt uses string comparison when values are not numeric', () => {
    const rules = [rule('code', 'gt', 'B', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { code: 'C' })).toEqual(RED_STYLE);
    expect(evaluateConditionalFormats(rules, { code: 'A' })).toBeNull();
  });

  it('lt uses string comparison when values are not numeric', () => {
    const rules = [rule('code', 'lt', 'B', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { code: 'A' })).toEqual(RED_STYLE);
  });

  // ── like ──────────────────────────────────────────────────────────────────

  it('like matches case-insensitively', () => {
    const rules = [rule('name', 'like', 'widget', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { name: 'Dashboard Widget' })).toEqual(RED_STYLE);
  });

  it('like does NOT match when substring absent', () => {
    const rules = [rule('name', 'like', 'xyz', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { name: 'Dashboard Widget' })).toBeNull();
  });

  // ── unknown operator ──────────────────────────────────────────────────────

  it('unknown operator returns false (no match)', () => {
    // @ts-expect-error testing unknown operator
    const rules = [rule('field', 'between', '1', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: '5' })).toBeNull();
  });

  // ── guard: missing fieldCode ───────────────────────────────────────────────

  it('rule with empty fieldCode always returns false', () => {
    const rules = [rule('', 'eq', 'x', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: 'x' })).toBeNull();
  });

  // ── guard: null/undefined ruleValue for non-null operators ────────────────

  it('returns null when ruleValue is undefined for eq operator', () => {
    const rules = [rule('field', 'eq', undefined, RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: 'x' })).toBeNull();
  });

  it('returns null when record value is null for eq operator', () => {
    const rules = [rule('field', 'eq', 'x', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { field: null })).toBeNull();
  });

  // ── first-match priority ──────────────────────────────────────────────────

  it('returns the first matching rule style (top-to-bottom priority)', () => {
    const rules = [
      rule('score', 'gte', '90', RED_STYLE),
      rule('score', 'gte', '50', BLUE_STYLE),
    ];
    // 95 >= 90 so first rule matches
    expect(evaluateConditionalFormats(rules, { score: 95 })).toEqual(RED_STYLE);
    // 70 < 90 so first rule fails, second rule matches
    expect(evaluateConditionalFormats(rules, { score: 70 })).toEqual(BLUE_STYLE);
  });

  // ── no matching rule ──────────────────────────────────────────────────────

  it('returns null when no rule matches', () => {
    const rules = [rule('score', 'gt', '100', RED_STYLE)];
    expect(evaluateConditionalFormats(rules, { score: 50 })).toBeNull();
  });
});

describe('buildConditionalStyle', () => {
  it('returns undefined for null style', () => {
    expect(buildConditionalStyle(null)).toBeUndefined();
  });

  it('maps backgroundColor', () => {
    const css = buildConditionalStyle({ backgroundColor: '#ff0000' });
    expect(css).toEqual({ backgroundColor: '#ff0000' });
  });

  it('maps textColor to color', () => {
    const css = buildConditionalStyle({ textColor: '#0000ff' });
    expect(css).toEqual({ color: '#0000ff' });
  });

  it('maps bold to fontWeight 700', () => {
    const css = buildConditionalStyle({ bold: true });
    expect(css).toEqual({ fontWeight: 700 });
  });

  it('bold: false does NOT set fontWeight', () => {
    const css = buildConditionalStyle({ bold: false });
    expect(css).toBeUndefined();
  });

  it('returns undefined when style has no active fields', () => {
    const css = buildConditionalStyle({} as ConditionalFormatStyle);
    expect(css).toBeUndefined();
  });

  it('combines all three fields', () => {
    const css = buildConditionalStyle({
      backgroundColor: '#ff0',
      textColor: '#00f',
      bold: true,
    });
    expect(css).toEqual({ backgroundColor: '#ff0', color: '#00f', fontWeight: 700 });
  });
});
