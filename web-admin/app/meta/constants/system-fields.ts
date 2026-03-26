/**
 * Canonical system / infrastructure field codes.
 *
 * Mirrors `SystemFieldConstants.java` on the backend.
 * Import from here instead of re-declaring per-file.
 */

/** All DDL infrastructure columns for mt_* */
export const ALL_INFRASTRUCTURE_FIELDS = new Set([
  'id',
  'pid',
  'created_at',
  'created_by',
  'updated_at',
  'updated_by',
  'tenant_id',
]);

/** Fields hidden in create forms (= ALL_INFRASTRUCTURE) */
export const FORM_HIDDEN_FIELDS = ALL_INFRASTRUCTURE_FIELDS;
