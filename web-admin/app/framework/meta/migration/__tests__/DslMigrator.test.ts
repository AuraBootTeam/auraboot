import { describe, it, expect } from 'vitest';
import { DslMigrator } from '../DslMigrator';
import { CURRENT_SCHEMA_VERSION } from '../schema-version';

describe('DslMigrator', () => {
  describe('detectVersion', () => {
    it('returns explicit schemaVersion', () => {
      expect(DslMigrator.detectVersion({ schemaVersion: 3 })).toBe(3);
    });

    it('detects v1 from pageType', () => {
      expect(DslMigrator.detectVersion({ pageType: 'LIST' })).toBe(1);
    });

    it('detects v2 from dslSchema', () => {
      expect(DslMigrator.detectVersion({ kind: 'list', dslSchema: {} })).toBe(2);
    });

    it('detects v3 from blocks array', () => {
      expect(DslMigrator.detectVersion({ kind: 'list', blocks: [] })).toBe(3);
    });

    it('defaults to 1 for unknown structure', () => {
      expect(DslMigrator.detectVersion({})).toBe(1);
    });
  });

  describe('migrate', () => {
    it('migrates v1 to current version', () => {
      const v1 = { pageType: 'LIST', pageCategory: 'STANDARD', dslSchema: { blocks: [{ blockType: 'table' }] } };
      const result = DslMigrator.migrate(v1);
      expect(result.schemaVersion).toBe(CURRENT_SCHEMA_VERSION);
      expect(result.kind).toBe('list');
      expect(result.pageType).toBeUndefined();
      expect(result.pageCategory).toBeUndefined();
      expect(result.dslSchema).toBeUndefined();
      expect(result.blocks).toEqual([{ blockType: 'table' }]);
    });

    it('migrates v2 to current version', () => {
      const v2 = { kind: 'form', schemaVersion: 2, dslSchema: { blocks: [{ blockType: 'form-section' }], layout: { type: 'stack' } } };
      const result = DslMigrator.migrate(v2);
      expect(result.schemaVersion).toBe(CURRENT_SCHEMA_VERSION);
      expect(result.blocks).toEqual([{ blockType: 'form-section' }]);
      expect(result.layout).toBeDefined();
      expect(result.dslSchema).toBeUndefined();
    });

    it('migrates v3 to current version', () => {
      const v3 = { kind: 'list', schemaVersion: 3, blocks: [{ blockType: 'table' }], layout: { type: 'stack' } };
      const result = DslMigrator.migrate(v3);
      expect(result.schemaVersion).toBe(CURRENT_SCHEMA_VERSION);
    });

    it('returns current version DSL unchanged', () => {
      const current = { kind: 'list', schemaVersion: CURRENT_SCHEMA_VERSION, blocks: [], layout: { type: 'grid' } };
      const result = DslMigrator.migrate(current);
      expect(result.schemaVersion).toBe(CURRENT_SCHEMA_VERSION);
      expect(result).toEqual(current);
    });

    it('throws on newer version', () => {
      const future = { schemaVersion: CURRENT_SCHEMA_VERSION + 1 };
      expect(() => DslMigrator.migrate(future)).toThrow('newer than app version');
    });

    it('bumps schemaVersion to 4 in v3→v4 without touching layout', () => {
      const v3 = { kind: 'list', schemaVersion: 3, blocks: [], layout: { type: 'stack' } };
      const result = DslMigrator.migrate(v3);
      expect(result.schemaVersion).toBe(4);
      expect(result.layout.type).toBe('stack');
    });
  });

  describe('needsMigration', () => {
    it('returns true for old version', () => {
      expect(DslMigrator.needsMigration({ schemaVersion: 1 })).toBe(true);
    });

    it('returns false for current version', () => {
      expect(DslMigrator.needsMigration({ schemaVersion: CURRENT_SCHEMA_VERSION })).toBe(false);
    });
  });
});
