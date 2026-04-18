/**
 * GAP-261 lock-in: `enableMultiView` is persisted as a FLAT key on
 * `schema.extension.enableMultiView` — never nested under `render`,
 * never under `extension.render.enableMultiView`, never anywhere else.
 *
 * These tests guard the two bridge helpers extracted from
 * `PageDesignerEditorImpl`:
 *   - `applySettingsToSchema` (write path: SettingsPanel → schema)
 *   - `readEnableMultiView`   (read path:  schema → SettingsPanel)
 *
 * A previous regression nested the flag under `render`, silently losing
 * user settings on reload. The component now delegates to these helpers
 * and this unit locks in the shape; any re-nesting will break it.
 */
import { describe, it, expect } from 'vitest';
import {
  applySettingsToSchema,
  readEnableMultiView,
} from '~/plugins/core-designer/components/studio/workbench/PageDesignerEditorImpl';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

function baseSchema(overrides: Partial<PageSchema> = {}): PageSchema {
  return {
    schemaVersion: 2,
    kind: 'list',
    modelCode: 'leave_request',
    blocks: [],
    layout: { type: 'stack' },
    profile: 'admin',
    ...overrides,
  } as unknown as PageSchema;
}

describe('PageDesignerEditorImpl — enableMultiView DSL contract (GAP-261)', () => {
  describe('applySettingsToSchema (write path)', () => {
    it('writes enableMultiView as a flat key on extension (true)', () => {
      const input = baseSchema();
      const next = applySettingsToSchema(input, { page: { enableMultiView: true } });
      // Flat shape: extension.enableMultiView
      expect(next.extension).toBeDefined();
      expect(next.extension?.enableMultiView).toBe(true);
    });

    it('writes enableMultiView as a flat key on extension (false)', () => {
      const input = baseSchema();
      const next = applySettingsToSchema(input, { page: { enableMultiView: false } });
      expect(next.extension?.enableMultiView).toBe(false);
    });

    it('does NOT nest enableMultiView under extension.render', () => {
      const input = baseSchema();
      const next = applySettingsToSchema(input, { page: { enableMultiView: true } });
      const ext = next.extension as Record<string, unknown> | undefined;
      // If a future refactor re-introduces the `render` nesting, this fails loudly.
      expect(ext?.render).toBeUndefined();
      const render = ext?.render as Record<string, unknown> | undefined;
      expect(render?.enableMultiView).toBeUndefined();
    });

    it('preserves other extension keys untouched', () => {
      const input = baseSchema({
        extension: {
          customApi: { list: '/api/foo' },
          someOtherFlag: 42,
        },
      } as unknown as Partial<PageSchema>);
      const next = applySettingsToSchema(input, { page: { enableMultiView: true } });
      const ext = next.extension as Record<string, unknown>;
      expect(ext.customApi).toEqual({ list: '/api/foo' });
      expect(ext.someOtherFlag).toBe(42);
      expect(ext.enableMultiView).toBe(true);
    });

    it('overwrites an existing flat enableMultiView value', () => {
      const input = baseSchema({
        extension: { enableMultiView: true },
      } as unknown as Partial<PageSchema>);
      const next = applySettingsToSchema(input, { page: { enableMultiView: false } });
      expect(next.extension?.enableMultiView).toBe(false);
    });

    it('returns a new schema object (immutability)', () => {
      const input = baseSchema();
      const next = applySettingsToSchema(input, { page: { enableMultiView: true } });
      expect(next).not.toBe(input);
      // Original schema extension is not mutated.
      expect(input.extension).toBeUndefined();
    });
  });

  describe('readEnableMultiView (read path)', () => {
    it('reads flat extension.enableMultiView = true correctly', () => {
      const schema = baseSchema({
        extension: { enableMultiView: true },
      } as unknown as Partial<PageSchema>);
      expect(readEnableMultiView(schema)).toBe(true);
    });

    it('reads flat extension.enableMultiView = false correctly', () => {
      const schema = baseSchema({
        extension: { enableMultiView: false },
      } as unknown as Partial<PageSchema>);
      expect(readEnableMultiView(schema)).toBe(false);
    });

    it('returns false when extension is missing', () => {
      const schema = baseSchema();
      expect(readEnableMultiView(schema)).toBe(false);
    });

    it('returns false when flag is absent from extension', () => {
      const schema = baseSchema({
        extension: { customApi: { list: '/api/x' } },
      } as unknown as Partial<PageSchema>);
      expect(readEnableMultiView(schema)).toBe(false);
    });

    it('returns false for null/undefined schema', () => {
      expect(readEnableMultiView(null)).toBe(false);
      expect(readEnableMultiView(undefined)).toBe(false);
    });

    it('does NOT read from legacy nested extension.render.enableMultiView', () => {
      // A DSL produced by a buggy pre-GAP-261 build might have the nested shape.
      // The reader must NOT honour it — the flat key is the only source of truth,
      // and missing flat key = false (forces user to re-toggle, which then writes
      // to the correct flat location via applySettingsToSchema).
      const schema = baseSchema({
        extension: { render: { enableMultiView: true } } as unknown,
      } as unknown as Partial<PageSchema>);
      expect(readEnableMultiView(schema)).toBe(false);
    });
  });

  describe('round-trip (write → read)', () => {
    it('write(true) → read → true', () => {
      const next = applySettingsToSchema(baseSchema(), { page: { enableMultiView: true } });
      expect(readEnableMultiView(next)).toBe(true);
    });
    it('write(false) → read → false', () => {
      const next = applySettingsToSchema(baseSchema(), { page: { enableMultiView: false } });
      expect(readEnableMultiView(next)).toBe(false);
    });
  });
});
