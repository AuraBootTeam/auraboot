/**
 * Property-panel render coverage (Phase 3 Task 3.4).
 *
 * Asserts that the shared PropertyFieldRenderer — the renderer the automation flow
 * designer's FlowPropertyPanel delegates to (via PropertyField) — renders a control for
 * EVERY configSchema field `type` the 18 automation palette nodes actually use, and that
 * no automation node introduces a field type the renderer cannot render. This is the
 * front half of the 18-node-type coverage (the back half is the behavioral fire matrix in
 * tests/e2e/automation/automation-golden.spec.ts).
 *
 * Services + i18n are stubbed so the render is hermetic (no network); the per-type field
 * components (selects, expression editor, …) have their own unit tests.
 */
import { describe, it, expect, vi } from 'vitest';
import { render } from '@testing-library/react';
import React from 'react';

// Hermetic i18n.
vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (k: unknown) => (typeof k === 'string' ? k : ''),
  getLocalizedText: (t: unknown) => (typeof t === 'string' ? t : ''),
  useLocalizedText: () => (t: unknown) => String(t ?? ''),
}));
vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US', t: (k: string, _p?: unknown, fb?: string) => fb ?? k }),
}));
vi.mock('sonner', () => ({ toast: { error: vi.fn(), success: vi.fn() } }));

// Resource/dict lookups resolve to empty so the *-select fields render a (zero-option)
// control instead of hitting the network.
vi.mock('~/shared/services/resourceSelectService', () => ({
  fetchPageOptions: vi.fn().mockResolvedValue([]),
  fetchDashboardOptions: vi.fn().mockResolvedValue([]),
  fetchProcessOptions: vi.fn().mockResolvedValue([]),
  fetchAutomationOptions: vi.fn().mockResolvedValue([]),
  fetchCommandOptions: vi.fn().mockResolvedValue([]),
  fetchModelOptions: vi.fn().mockResolvedValue([]),
  fetchFieldOptions: vi.fn().mockResolvedValue([]),
  fetchDictOptions: vi.fn().mockResolvedValue([]),
  fetchSemanticModelOptions: vi.fn().mockResolvedValue([]),
}));
vi.mock('~/shared/services/dictService', () => ({
  dictService: { findAll: vi.fn().mockResolvedValue([]) },
}));

import { PropertyFieldRenderer } from '~/shared/designer';
import { automationNodes } from '../index';

// The field types the automation palette nodes use (kept in sync with the configSchemas).
const EXPECTED_AUTOMATION_FIELD_TYPES = [
  'boolean',
  'command-select',
  'expression',
  'field-select',
  'json',
  'model-select',
  'multiselect',
  'number',
  'process-select',
  'rule-binding',
  'select',
  'text',
  'textarea',
] as const;

function stubAdapter(value: unknown = undefined) {
  return { value, setValue: vi.fn(), error: undefined, required: false, disabled: false };
}

function schemaFor(type: string) {
  const base: Record<string, unknown> = { key: `f_${type}`, label: type, type };
  if (type === 'select' || type === 'multiselect') {
    base.options = [
      { label: 'A', value: 'a' },
      { label: 'B', value: 'b' },
    ];
  }
  return base as any;
}

describe('Automation property-panel render coverage (Phase 3 Task 3.4)', () => {
  it('the automation nodes use exactly the expected set of configSchema field types (no unhandled type slips in)', () => {
    const used = new Set<string>();
    for (const node of automationNodes) {
      for (const field of node.configSchema ?? []) {
        used.add(field.type);
      }
    }
    // Every used type must be in the expected set (a new/typo'd type fails here loudly)…
    for (const t of used) {
      expect(EXPECTED_AUTOMATION_FIELD_TYPES, `automation field type '${t}' is not in the expected set`).toContain(t);
    }
    // …and every expected type must still be exercised by at least one node (catches a
    // node losing a field type, which would silently drop coverage).
    for (const t of EXPECTED_AUTOMATION_FIELD_TYPES) {
      expect(used.has(t), `expected automation field type '${t}' is no longer used by any node`).toBe(true);
    }
  });

  it.each(EXPECTED_AUTOMATION_FIELD_TYPES)(
    'PropertyFieldRenderer renders a control for the %s field type',
    (type) => {
      const { container } = render(
        <PropertyFieldRenderer schema={schemaFor(type)} adapter={stubAdapter()} />,
      );
      // The renderer must produce SOME element for the type — i.e. it routes to a real
      // field control, not the unknown/empty fallback.
      expect(container.firstChild, `PropertyFieldRenderer rendered nothing for type '${type}'`).not.toBeNull();
    },
  );
});
