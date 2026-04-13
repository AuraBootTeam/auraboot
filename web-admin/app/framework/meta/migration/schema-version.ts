/**
 * Current DSL schema version.
 * Increment when the DSL format changes.
 *
 * History:
 *   1: Original format (pageType string, nested dslSchema)
 *   2: page-type-unification (pageType → kind enum)
 *   3: page-kind-unification (dslSchema nested → flat blocks + layout)
 *   4: Grid canvas (BlockLayoutConfig adds col field)
 */
export const CURRENT_SCHEMA_VERSION = 4;
