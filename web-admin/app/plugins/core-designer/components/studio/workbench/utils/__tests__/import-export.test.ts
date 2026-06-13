import { describe, it, expect, beforeEach } from 'vitest';
import {
  exportSchema,
  importSchema,
  createSchemaTemplate,
  type ExportOptions,
} from '../import-export';
import { createDefaultSchema } from '../schemaUtils';
import type { CanvasSchema } from '../../canvas/types';

const validSchema = (): CanvasSchema => ({
  id: 'test-schema',
  kind: 'form',
  title: 'Test Schema',
  description: 'A test schema',
  version: '1.0.0',
  components: [],
  layout: { type: 'vertical', columns: 1, spacing: 8, padding: 0 },
  metadata: {
    createdAt: '2026-01-01T00:00:00Z',
    updatedAt: '2026-01-01T00:00:00Z',
    createdBy: 'test',
  },
});

// ─── exportSchema ────────────────────────────────────────────────────────────

describe('exportSchema – JSON', () => {
  it('exports to pretty-printed JSON by default', () => {
    const json = exportSchema(validSchema(), { format: 'json' });
    const parsed = JSON.parse(json);
    expect(parsed.id).toBe('test-schema');
    expect(parsed.title).toBe('Test Schema');
  });

  it('exports to minified JSON with minify:true', () => {
    const json = exportSchema(validSchema(), { format: 'json', minify: true });
    expect(json.includes('\n')).toBe(false);
    const parsed = JSON.parse(json);
    expect(parsed.id).toBe('test-schema');
  });

  it('excludes metadata by default (includeMetadata: false)', () => {
    const schema = { ...validSchema(), metadata: { createdAt: '2024-01-01', updatedAt: '2024-01-01', createdBy: 'test', tags: [] } };
    const json = exportSchema(schema, { format: 'json' });
    const parsed = JSON.parse(json);
    expect(parsed.metadata).toBeUndefined();
  });

  it('includes metadata when includeMetadata: true', () => {
    const schema = {
      ...validSchema(),
      metadata: { createdAt: '2024-01-01', updatedAt: '2024-01-01', createdBy: 'test', tags: [] },
    };
    const json = exportSchema(schema, { format: 'json', includeMetadata: true });
    const parsed = JSON.parse(json);
    expect(parsed.metadata).toBeDefined();
  });

  it('includes _comments field when includeComments: true', () => {
    const json = exportSchema(validSchema(), { format: 'json', includeComments: true });
    const parsed = JSON.parse(json);
    expect(parsed._comments).toBeDefined();
    expect(parsed._comments.version).toBe('Schema version');
  });
});

describe('exportSchema – YAML', () => {
  it('produces a string containing the title', () => {
    const yaml = exportSchema(validSchema(), { format: 'yaml' });
    expect(yaml).toContain('Test Schema');
  });

  it('produces a string containing the id', () => {
    const yaml = exportSchema(validSchema(), { format: 'yaml' });
    expect(yaml).toContain('test-schema');
  });
});

describe('exportSchema – XML', () => {
  it('starts with the XML declaration', () => {
    const xml = exportSchema(validSchema(), { format: 'xml' });
    expect(xml.startsWith('<?xml version="1.0"')).toBe(true);
  });

  it('wraps content in <schema> root tag', () => {
    const xml = exportSchema(validSchema(), { format: 'xml' });
    expect(xml).toContain('<schema>');
    expect(xml).toContain('</schema>');
  });

  it('includes the title field', () => {
    const xml = exportSchema(validSchema(), { format: 'xml' });
    expect(xml).toContain('Test Schema');
  });
});

describe('exportSchema – unsupported format', () => {
  it('throws an error for unknown format', () => {
    expect(() =>
      exportSchema(validSchema(), { format: 'csv' as any }),
    ).toThrow('Export failed');
  });
});

// ─── importSchema ────────────────────────────────────────────────────────────

describe('importSchema – JSON round-trip', () => {
  it('imports a valid JSON schema successfully', () => {
    const json = exportSchema(validSchema(), { format: 'json' });
    const result = importSchema(json, { validate: false });
    expect(result.success).toBe(true);
    expect(result.schema?.id).toBe('test-schema');
  });

  it('validates the imported schema when validate:true', () => {
    const json = exportSchema(validSchema(), { format: 'json' });
    const result = importSchema(json, { validate: true });
    expect(result.success).toBe(true);
    expect(result.validation?.valid).toBe(true);
  });

  it('returns failure when schema has missing fields (validate:true)', () => {
    const invalidJson = JSON.stringify({ id: '', title: '', version: '' });
    const result = importSchema(invalidJson, { validate: true });
    expect(result.success).toBe(false);
    expect(result.error).toBeDefined();
  });

  it('returns failure for unparseable input', () => {
    const result = importSchema('this is not json yaml or xml');
    expect(result.success).toBe(false);
    expect(result.error).toBeDefined();
  });
});

describe('importSchema – merge', () => {
  it('merges into base schema when merge:true', () => {
    const base: CanvasSchema = { ...validSchema(), title: 'Base' };
    const override = JSON.stringify({ ...validSchema(), title: 'Override' });
    const result = importSchema(override, { validate: false, merge: true, baseSchema: base });
    expect(result.success).toBe(true);
    expect(result.schema?.title).toBe('Override');
  });
});

describe('importSchema – YAML round-trip', () => {
  it('imports basic YAML (key: value lines)', () => {
    const yaml = 'id: yaml-schema\ntitle: YAML Test\nversion: 1.0.0\n';
    const result = importSchema(yaml, { validate: false });
    expect(result.success).toBe(true);
    expect(result.schema?.id).toBe('yaml-schema');
  });
});

// ─── createSchemaTemplate ────────────────────────────────────────────────────

describe('createSchemaTemplate', () => {
  it('basic template contains input and button components', () => {
    const schema = createSchemaTemplate('basic');
    const types = schema.components.map((c) => c.type);
    expect(types).toContain('input');
    expect(types).toContain('button');
  });

  it('advanced template contains a container with children', () => {
    const schema = createSchemaTemplate('advanced');
    const container = schema.components.find((c) => c.type === 'container');
    expect(container).toBeDefined();
    expect(container?.children?.length).toBeGreaterThan(0);
  });

  it('custom template returns the default schema', () => {
    const schema = createSchemaTemplate('custom');
    expect(schema.components).toHaveLength(0);
    expect(schema.version).toBe('1.0.0');
  });

  it('all templates have a valid id, title and version', () => {
    for (const type of ['basic', 'advanced', 'custom'] as const) {
      const schema = createSchemaTemplate(type);
      expect(schema.id).toBeTruthy();
      expect(schema.title).toBeTruthy();
      expect(schema.version).toBeTruthy();
    }
  });
});
