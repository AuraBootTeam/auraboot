/**
 * DslMigrator — Upgrades page DSL schemas to the current version.
 *
 * Usage:
 *   const migrated = DslMigrator.migrate(rawDsl);
 *   // migrated.schemaVersion === CURRENT_SCHEMA_VERSION
 *
 * Throws if:
 *   - schemaVersion > CURRENT_SCHEMA_VERSION (from a newer app version)
 *   - Migration function throws (corrupt data)
 */

import { CURRENT_SCHEMA_VERSION } from './schema-version';
import { migrateV1toV2 } from './migrations/v1-to-v2';
import { migrateV2toV3 } from './migrations/v2-to-v3';
import { migrateV3toV4 } from './migrations/v3-to-v4';

type MigrationFn = (dsl: Record<string, any>) => Record<string, any>;

const MIGRATIONS: Record<number, MigrationFn> = {
  1: migrateV1toV2,
  2: migrateV2toV3,
  3: migrateV3toV4,
};

export class DslMigrator {
  /**
   * Detect the schema version of a raw DSL object.
   * Returns the explicit schemaVersion, or infers from structure.
   */
  static detectVersion(dsl: Record<string, any>): number {
    if (typeof dsl.schemaVersion === 'number') return dsl.schemaVersion;

    // Infer from structure
    if (dsl.pageType) return 1;
    if (dsl.dslSchema) return 2;
    if (Array.isArray(dsl.blocks)) return 3; // could be 3 or 4, default to 3
    return 1; // unknown structure, start from v1
  }

  /**
   * Migrate a DSL object to the current schema version.
   * Throws if the DSL is from a newer version or migration fails.
   */
  static migrate(dsl: Record<string, any>): Record<string, any> {
    let version = DslMigrator.detectVersion(dsl);
    let result = { ...dsl };

    if (version > CURRENT_SCHEMA_VERSION) {
      throw new Error(
        `DSL schemaVersion ${version} is newer than app version ${CURRENT_SCHEMA_VERSION}. ` +
        `Update the application to load this page.`
      );
    }

    if (version === CURRENT_SCHEMA_VERSION) {
      result.schemaVersion = version;
      return result;
    }

    // Run migrations sequentially
    while (version < CURRENT_SCHEMA_VERSION) {
      const migrationFn = MIGRATIONS[version];
      if (!migrationFn) {
        throw new Error(
          `No migration defined for schemaVersion ${version} → ${version + 1}. ` +
          `This is a bug in the migration registry.`
        );
      }
      result = migrationFn(result);
      version = result.schemaVersion;
    }

    return result;
  }

  /**
   * Check if a DSL needs migration.
   */
  static needsMigration(dsl: Record<string, any>): boolean {
    return DslMigrator.detectVersion(dsl) < CURRENT_SCHEMA_VERSION;
  }
}
