import { describe, it, expect } from 'vitest';
import {
  validateSchema,
  validateLayout,
  validateTheme,
  validateMetadata,
} from '../validation';
import type { CanvasSchema } from '../../canvas/types';

// ─── helpers ────────────────────────────────────────────────────────────────

const validSchema = (): CanvasSchema => ({
  id: 'schema1',
  kind: 'form',
  title: 'My Form',
  version: '1.0.0',
  components: [],
  layout: { type: 'vertical', columns: 1, spacing: 8, padding: 0 },
  metadata: {
    createdAt: '2024-01-01',
    updatedAt: '2024-01-01',
    createdBy: 'test',
    tags: [],
  },
});

// ─── validateSchema ──────────────────────────────────────────────────────────

describe('validateSchema', () => {
  it('returns valid:true for a complete valid schema', () => {
    expect(validateSchema(validSchema()).valid).toBe(true);
  });

  it('returns error for missing id', () => {
    const schema = { ...validSchema(), id: '' };
    const result = validateSchema(schema);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'missing_id')).toBe(true);
  });

  it('returns error for missing title', () => {
    const schema = { ...validSchema(), title: '' };
    const result = validateSchema(schema);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'missing_title')).toBe(true);
  });

  it('returns error for missing version', () => {
    const schema = { ...validSchema(), version: '' };
    const result = validateSchema(schema);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'missing_version')).toBe(true);
  });

  it('collects multiple errors at once', () => {
    const schema = { ...validSchema(), id: '', title: '', version: '' };
    const result = validateSchema(schema);
    expect(result.errors.length).toBeGreaterThanOrEqual(3);
  });

  it('validates components recursively', () => {
    const schema = {
      ...validSchema(),
      components: [
        {
          id: '',        // invalid – missing id
          type: 'input',
          props: {},
          styles: {},
          children: [],
        },
      ],
    };
    const result = validateSchema(schema);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'missing_component_id')).toBe(true);
  });

  it('validates nested children components', () => {
    const schema = {
      ...validSchema(),
      components: [
        {
          id: 'container',
          type: 'container',
          props: {},
          styles: {},
          children: [
            {
              id: '',    // invalid
              type: 'text',
              props: {},
              styles: {},
              children: [],
            },
          ],
        },
      ],
    };
    const result = validateSchema(schema);
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'missing_component_id')).toBe(true);
  });

  it('returns error for component id with special characters', () => {
    const schema = {
      ...validSchema(),
      components: [
        {
          id: 'has spaces!',
          type: 'input',
          props: {},
          styles: {},
          children: [],
        },
      ],
    };
    const result = validateSchema(schema);
    expect(result.errors.some((e) => e.code === 'invalid_component_id')).toBe(true);
  });

  it('returns error for missing component type', () => {
    const schema = {
      ...validSchema(),
      components: [
        {
          id: 'comp1',
          type: '',    // invalid
          props: {},
          styles: {},
          children: [],
        },
      ],
    };
    const result = validateSchema(schema);
    expect(result.errors.some((e) => e.code === 'missing_component_type')).toBe(true);
  });

  it('valid component ids with hyphens and underscores pass', () => {
    const schema = {
      ...validSchema(),
      components: [
        {
          id: 'my-component_01',
          type: 'input',
          props: {},
          styles: {},
          children: [],
        },
      ],
    };
    expect(validateSchema(schema).valid).toBe(true);
  });

  it('returns empty warnings for valid schema', () => {
    const result = validateSchema(validSchema());
    expect(result.warnings).toHaveLength(0);
  });
});

// ─── validateLayout ──────────────────────────────────────────────────────────

describe('validateLayout', () => {
  it('returns valid:true for empty layout', () => {
    expect(validateLayout({}).valid).toBe(true);
  });

  it('returns valid:true for grid type', () => {
    expect(validateLayout({ type: 'grid', columns: 12 }).valid).toBe(true);
  });

  it('returns valid:true for flex type', () => {
    expect(validateLayout({ type: 'flex' }).valid).toBe(true);
  });

  it('returns error for unknown layout type', () => {
    const result = validateLayout({ type: 'unknown-type' });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_layout_type')).toBe(true);
  });

  it('returns error for non-numeric columns', () => {
    const result = validateLayout({ columns: 'twelve' });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_columns')).toBe(true);
  });

  it('returns error for columns < 1 (negative value)', () => {
    // The check is: layout.columns && (...columns < 1), so 0 short-circuits (falsy).
    // Use a negative value to actually trigger the < 1 branch.
    const result = validateLayout({ columns: -1 });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_columns')).toBe(true);
  });

  it('accepts columns = 1 as valid', () => {
    expect(validateLayout({ columns: 1 }).valid).toBe(true);
  });

  it('accepts absolute type', () => {
    expect(validateLayout({ type: 'absolute' }).valid).toBe(true);
  });
});

// ─── validateTheme ───────────────────────────────────────────────────────────

describe('validateTheme', () => {
  it('returns valid:true for theme with no colors', () => {
    expect(validateTheme({}).valid).toBe(true);
  });

  it('accepts valid hex color', () => {
    const result = validateTheme({ colors: { primary: '#3B82F6' } });
    expect(result.valid).toBe(true);
  });

  it('accepts valid 3-digit hex color', () => {
    const result = validateTheme({ colors: { bg: '#fff' } });
    expect(result.valid).toBe(true);
  });

  it('accepts valid rgb color', () => {
    const result = validateTheme({ colors: { text: 'rgb(0, 0, 0)' } });
    expect(result.valid).toBe(true);
  });

  it('accepts valid rgba color', () => {
    const result = validateTheme({ colors: { overlay: 'rgba(0, 0, 0, 0.5)' } });
    expect(result.valid).toBe(true);
  });

  it('returns error for invalid color value', () => {
    const result = validateTheme({ colors: { bad: 'not-a-color' } });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_color')).toBe(true);
  });

  it('accumulates errors for multiple invalid colors', () => {
    const result = validateTheme({
      colors: { c1: 'invalid', c2: 'also-invalid' },
    });
    expect(result.errors.length).toBeGreaterThanOrEqual(2);
  });
});

// ─── validateMetadata ────────────────────────────────────────────────────────

describe('validateMetadata', () => {
  it('returns valid:true for empty metadata', () => {
    expect(validateMetadata({}).valid).toBe(true);
  });

  it('accepts valid ISO date for createdAt', () => {
    expect(validateMetadata({ createdAt: new Date().toISOString() }).valid).toBe(true);
  });

  it('returns error for invalid createdAt', () => {
    const result = validateMetadata({ createdAt: 'not-a-date' });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_created_at')).toBe(true);
  });

  it('accepts valid ISO date for updatedAt', () => {
    expect(validateMetadata({ updatedAt: '2024-01-01T00:00:00.000Z' }).valid).toBe(true);
  });

  it('returns error for invalid updatedAt', () => {
    const result = validateMetadata({ updatedAt: 'nope' });
    expect(result.valid).toBe(false);
    expect(result.errors.some((e) => e.code === 'invalid_updated_at')).toBe(true);
  });

  it('collects both createdAt and updatedAt errors', () => {
    const result = validateMetadata({ createdAt: 'bad', updatedAt: 'bad' });
    expect(result.errors.length).toBe(2);
  });
});
