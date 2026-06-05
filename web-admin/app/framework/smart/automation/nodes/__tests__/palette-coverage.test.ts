import { describe, it, expect } from 'vitest';
import { automationNodes } from '../index';

/**
 * Palette coverage test — asserts the automation node registry contains exactly
 * the 18 canonical types expected by the golden E2E harness, and that each node
 * definition satisfies the minimal property contract (category, i18n label,
 * i18n description, configSchema array).
 */

const EXPECTED_TYPES = [
  'trigger-record-create',
  'trigger-record-update',
  'trigger-field-change',
  'trigger-state-change',
  'trigger-scheduled',
  'trigger-webhook',
  'trigger-bpm-event',
  'action-update-record',
  'action-create-record',
  'action-send-notification',
  'action-execute-command',
  'action-call-api',
  'action-send-webhook',
  'action-start-process',
  'action-llm-call',
  'control-condition',
  'control-loop',
  'control-delay',
] as const;

type ExpectedType = (typeof EXPECTED_TYPES)[number];

const VALID_CATEGORIES = new Set(['trigger', 'action', 'control']);

/** Category prefix inferred from the node type string (e.g. "trigger-..." → "trigger"). */
function expectedCategory(type: string): string {
  return type.split('-')[0];
}

describe('automationNodes palette coverage', () => {
  it('exports exactly 18 node definitions', () => {
    expect(automationNodes).toHaveLength(18);
  });

  it('contains exactly the 18 expected type values (no extras, no missing)', () => {
    const actualTypes = automationNodes.map((n) => n.type).sort();
    const expectedSorted = [...EXPECTED_TYPES].sort();
    expect(actualTypes).toEqual(expectedSorted);
  });

  describe.each(EXPECTED_TYPES)('node: %s', (type: ExpectedType) => {
    const node = automationNodes.find((n) => n.type === type);

    it('is present in automationNodes', () => {
      expect(node).toBeDefined();
    });

    it('category matches type prefix', () => {
      expect(node!.category).toBe(expectedCategory(type));
    });

    it('category is one of trigger | action | control', () => {
      expect(VALID_CATEGORIES.has(node!.category as string)).toBe(true);
    });

    it('label starts with $i18n:', () => {
      expect(typeof node!.label).toBe('string');
      expect(node!.label as string).toMatch(/^\$i18n:/);
    });

    it('description starts with $i18n:', () => {
      expect(typeof node!.description).toBe('string');
      expect(node!.description as string).toMatch(/^\$i18n:/);
    });

    it('configSchema is an array', () => {
      expect(Array.isArray(node!.configSchema)).toBe(true);
    });
  });
});
