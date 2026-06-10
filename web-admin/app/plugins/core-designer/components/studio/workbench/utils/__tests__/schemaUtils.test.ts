import { describe, it, expect } from 'vitest';
import {
  createDefaultSchema,
  createDefaultLayout,
  createDefaultTheme,
  cloneSchema,
  mergeSchema,
  findComponent,
  getComponentPath,
  removeComponent,
  updateComponent,
} from '../schemaUtils';
import type { CanvasSchema, Block } from '../../canvas/types';

// ─── helpers ────────────────────────────────────────────────────────────────

const makeSchema = (overrides: Partial<CanvasSchema> = {}): CanvasSchema => ({
  ...createDefaultSchema(),
  id: 'test-schema',
  kind: 'form',
  title: 'Test',
  version: '1.0.0',
  ...overrides,
});

const makeBlock = (id: string, children: Block[] = []): Block => ({
  id,
  type: 'input',
  props: {},
  styles: {},
  children,
});

// ─── createDefaultSchema ────────────────────────────────────────────────────

describe('createDefaultSchema', () => {
  it('returns an object with required fields', () => {
    const schema = createDefaultSchema();
    expect(schema.id).toBeTruthy();
    expect(schema.title).toBeTruthy();
    expect(schema.version).toBe('1.0.0');
    expect(schema.kind).toBe('form');
  });

  it('generates ids with the schema_ prefix', () => {
    // IDs are generated from Date.now() which may be identical in fast test runs.
    // We verify the format rather than strict uniqueness.
    const a = createDefaultSchema();
    expect(a.id).toMatch(/^schema_\d+$/);
  });

  it('includes an empty components array', () => {
    const schema = createDefaultSchema();
    expect(Array.isArray(schema.components)).toBe(true);
    expect(schema.components).toHaveLength(0);
  });

  it('includes layout with type grid', () => {
    const schema = createDefaultSchema();
    expect(schema.layout?.type).toBe('grid');
  });

  it('includes theme with primaryColor', () => {
    const schema = createDefaultSchema();
    expect(schema.theme?.primaryColor).toBeDefined();
  });

  it('includes metadata with createdAt', () => {
    const schema = createDefaultSchema();
    expect(schema.metadata?.createdAt).toBeDefined();
    // createdAt should be a parseable date
    expect(isNaN(new Date(schema.metadata!.createdAt!).getTime())).toBe(false);
  });
});

// ─── createDefaultLayout ────────────────────────────────────────────────────

describe('createDefaultLayout', () => {
  it('creates grid layout with 12 columns', () => {
    const layout = createDefaultLayout();
    expect(layout.type).toBe('grid');
    expect(layout.columns).toBe(12);
  });

  it('includes responsive breakpoints', () => {
    const layout = createDefaultLayout();
    expect(layout.breakpoints).toBeDefined();
    expect(layout.breakpoints?.xs).toBeDefined();
    expect(layout.breakpoints?.xl).toBeDefined();
  });

  it('responsive is true by default', () => {
    expect(createDefaultLayout().responsive).toBe(true);
  });
});

// ─── createDefaultTheme ─────────────────────────────────────────────────────

describe('createDefaultTheme', () => {
  it('includes primaryColor', () => {
    const theme = createDefaultTheme();
    expect(theme.primaryColor).toMatch(/^#[0-9A-Fa-f]{6}$/);
  });

  it('includes color palette', () => {
    const theme = createDefaultTheme();
    expect(theme.colors?.primary).toBeDefined();
    expect(theme.colors?.error).toBeDefined();
  });

  it('includes fonts', () => {
    const theme = createDefaultTheme();
    expect(theme.fonts?.primary).toBeDefined();
  });

  it('includes spacing scale', () => {
    const theme = createDefaultTheme();
    expect(typeof theme.spacing?.md).toBe('number');
  });
});

// ─── cloneSchema ────────────────────────────────────────────────────────────

describe('cloneSchema', () => {
  it('produces a deep copy (not the same reference)', () => {
    const schema = makeSchema();
    const clone = cloneSchema(schema);
    expect(clone).not.toBe(schema);
  });

  it('nested components are deep-copied', () => {
    const block = makeBlock('comp1');
    const schema = makeSchema({ components: [block] });
    const clone = cloneSchema(schema);
    expect(clone.components[0]).not.toBe(schema.components[0]);
  });

  it('cloned values equal the original', () => {
    const schema = makeSchema({ title: 'Clone Test' });
    const clone = cloneSchema(schema);
    expect(clone.title).toBe('Clone Test');
    expect(clone.id).toBe(schema.id);
  });

  it('mutations to clone do not affect original', () => {
    const schema = makeSchema();
    const clone = cloneSchema(schema);
    clone.title = 'Mutated';
    expect(schema.title).toBe('Test');
  });
});

// ─── mergeSchema ────────────────────────────────────────────────────────────

describe('mergeSchema', () => {
  it('merges top-level scalar fields', () => {
    const base = makeSchema({ title: 'Base' });
    const merged = mergeSchema(base, { title: 'Merged' });
    expect(merged.title).toBe('Merged');
  });

  it('uses base components when source has none', () => {
    const block = makeBlock('b1');
    const base = makeSchema({ components: [block] });
    const merged = mergeSchema(base, {});
    expect(merged.components).toHaveLength(1);
  });

  it('replaces components from source when source has components', () => {
    const base = makeSchema({ components: [makeBlock('old')] });
    const merged = mergeSchema(base, { components: [makeBlock('new')] });
    expect(merged.components[0].id).toBe('new');
  });

  it('merges layout shallowly', () => {
    const base = makeSchema();
    const merged = mergeSchema(base, { layout: { type: 'flex' } as any });
    expect(merged.layout?.type).toBe('flex');
  });

  it('keeps base layout when source has no layout', () => {
    const base = makeSchema();
    const merged = mergeSchema(base, {});
    expect(merged.layout?.type).toBe('grid');
  });

  it('merges theme partially', () => {
    const base = makeSchema();
    const merged = mergeSchema(base, {
      theme: { primaryColor: '#000000' } as any,
    });
    expect(merged.theme?.primaryColor).toBe('#000000');
  });

  it('merges metadata partially', () => {
    const base = makeSchema();
    const merged = mergeSchema(base, {
      metadata: { createdBy: 'user123' } as any,
    });
    expect(merged.metadata?.createdBy).toBe('user123');
  });
});

// ─── findComponent ───────────────────────────────────────────────────────────

describe('findComponent', () => {
  it('finds a top-level component by id', () => {
    const schema = makeSchema({ components: [makeBlock('comp1'), makeBlock('comp2')] });
    const found = findComponent(schema, 'comp1');
    expect(found).not.toBeNull();
    expect(found!.id).toBe('comp1');
  });

  it('returns null when component does not exist', () => {
    const schema = makeSchema({ components: [makeBlock('comp1')] });
    expect(findComponent(schema, 'nonexistent')).toBeNull();
  });

  it('finds a nested component', () => {
    const schema = makeSchema({
      components: [
        makeBlock('parent', [makeBlock('child')]),
      ],
    });
    const found = findComponent(schema, 'child');
    expect(found).not.toBeNull();
    expect(found!.id).toBe('child');
  });

  it('finds deeply nested component', () => {
    const schema = makeSchema({
      components: [
        makeBlock('l1', [
          makeBlock('l2', [
            makeBlock('l3'),
          ]),
        ]),
      ],
    });
    expect(findComponent(schema, 'l3')).not.toBeNull();
  });

  it('returns null for empty components array', () => {
    const schema = makeSchema({ components: [] });
    expect(findComponent(schema, 'x')).toBeNull();
  });
});

// ─── getComponentPath ────────────────────────────────────────────────────────

describe('getComponentPath', () => {
  it('returns path array for top-level component', () => {
    const schema = makeSchema({ components: [makeBlock('comp1')] });
    const path = getComponentPath(schema, 'comp1');
    expect(path).toContain('components');
    expect(path).toContain('0');
  });

  it('returns path for nested component', () => {
    const schema = makeSchema({
      components: [makeBlock('parent', [makeBlock('child')])],
    });
    const path = getComponentPath(schema, 'child');
    expect(path.length).toBeGreaterThan(2);
  });

  it('returns empty array when component not found', () => {
    const schema = makeSchema({ components: [] });
    expect(getComponentPath(schema, 'ghost')).toHaveLength(0);
  });
});

// ─── removeComponent ────────────────────────────────────────────────────────

describe('removeComponent', () => {
  it('removes a top-level component', () => {
    const schema = makeSchema({
      components: [makeBlock('c1'), makeBlock('c2')],
    });
    const updated = removeComponent(schema, 'c1');
    expect(updated.components).toHaveLength(1);
    expect(updated.components[0].id).toBe('c2');
  });

  it('does not mutate the original schema', () => {
    const schema = makeSchema({ components: [makeBlock('c1')] });
    removeComponent(schema, 'c1');
    expect(schema.components).toHaveLength(1);
  });

  it('removes a nested component', () => {
    const schema = makeSchema({
      components: [makeBlock('parent', [makeBlock('child')])],
    });
    const updated = removeComponent(schema, 'child');
    const parent = findComponent(updated, 'parent');
    expect(parent?.children).toHaveLength(0);
  });

  it('returns schema unchanged when component not found', () => {
    const schema = makeSchema({ components: [makeBlock('c1')] });
    const updated = removeComponent(schema, 'ghost');
    expect(updated.components).toHaveLength(1);
  });
});

// ─── updateComponent ────────────────────────────────────────────────────────

describe('updateComponent', () => {
  it('updates props of a component', () => {
    const schema = makeSchema({
      components: [{ ...makeBlock('c1'), props: { label: 'Old' } }],
    });
    const updated = updateComponent(schema, 'c1', { props: { label: 'New' } });
    const comp = findComponent(updated, 'c1');
    expect(comp?.props.label).toBe('New');
  });

  it('does not mutate the original schema', () => {
    const schema = makeSchema({
      components: [makeBlock('c1')],
    });
    updateComponent(schema, 'c1', { type: 'button' });
    expect(findComponent(schema, 'c1')?.type).toBe('input');
  });

  it('updates a nested component', () => {
    const schema = makeSchema({
      components: [makeBlock('parent', [makeBlock('child')])],
    });
    const updated = updateComponent(schema, 'child', { type: 'button' });
    const child = findComponent(updated, 'child');
    expect(child?.type).toBe('button');
  });

  it('leaves schema unchanged when component not found', () => {
    const schema = makeSchema({ components: [makeBlock('c1')] });
    const updated = updateComponent(schema, 'ghost', { type: 'button' });
    expect(findComponent(updated, 'c1')?.type).toBe('input');
  });
});
