/**
 * Generic JSON diff utility for comparing version snapshots.
 * Produces a list of field-level changes between two snapshots.
 */

export type DiffType = 'added' | 'removed' | 'changed' | 'unchanged';

export interface DiffEntry {
  /** Dot-notation path to the field (e.g., "widgets.0.config.title") */
  path: string;
  /** Display-friendly field name */
  label: string;
  /** Type of change */
  type: DiffType;
  /** Old value (version A) - undefined for 'added' */
  oldValue?: unknown;
  /** New value (version B) - undefined for 'removed' */
  newValue?: unknown;
}

/**
 * Compare two JSON snapshots and produce a flat list of differences.
 * Only goes 2 levels deep by default to keep output manageable.
 */
export function diffSnapshots(
  snapshotA: Record<string, unknown> | null | undefined,
  snapshotB: Record<string, unknown> | null | undefined,
): DiffEntry[] {
  const a = snapshotA || {};
  const b = snapshotB || {};
  const entries: DiffEntry[] = [];

  const allKeys = new Set([...Object.keys(a), ...Object.keys(b)]);

  for (const key of allKeys) {
    const valA = a[key];
    const valB = b[key];

    if (!(key in a)) {
      entries.push({
        path: key,
        label: key,
        type: 'added',
        newValue: valB,
      });
    } else if (!(key in b)) {
      entries.push({
        path: key,
        label: key,
        type: 'removed',
        oldValue: valA,
      });
    } else if (isDeepEqual(valA, valB)) {
      entries.push({
        path: key,
        label: key,
        type: 'unchanged',
        oldValue: valA,
        newValue: valB,
      });
    } else {
      entries.push({
        path: key,
        label: key,
        type: 'changed',
        oldValue: valA,
        newValue: valB,
      });
    }
  }

  return entries;
}

/**
 * Get only the changed entries (added, removed, changed).
 */
export function getChangedEntries(entries: DiffEntry[]): DiffEntry[] {
  return entries.filter((e) => e.type !== 'unchanged');
}

/**
 * Format a value for display in diff view.
 */
export function formatDiffValue(value: unknown): string {
  if (value === null || value === undefined) return '(empty)';
  if (typeof value === 'string') return value || '(empty string)';
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  if (Array.isArray(value)) return `[${value.length} items]`;
  if (typeof value === 'object') {
    const keys = Object.keys(value);
    if (keys.length === 0) return '{}';
    return `{${keys.length} fields}`;
  }
  return String(value);
}

/**
 * Format a value as pretty JSON for expanded view.
 */
export function formatDiffValueExpanded(value: unknown): string {
  if (value === null || value === undefined) return 'null';
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

/**
 * Deep equality check for JSON-compatible values.
 */
function isDeepEqual(a: unknown, b: unknown): boolean {
  if (a === b) return true;
  if (a === null || b === null) return false;
  if (typeof a !== typeof b) return false;

  if (Array.isArray(a) && Array.isArray(b)) {
    if (a.length !== b.length) return false;
    return a.every((item, i) => isDeepEqual(item, b[i]));
  }

  if (typeof a === 'object' && typeof b === 'object') {
    const aObj = a as Record<string, unknown>;
    const bObj = b as Record<string, unknown>;
    const aKeys = Object.keys(aObj);
    const bKeys = Object.keys(bObj);
    if (aKeys.length !== bKeys.length) return false;
    return aKeys.every((key) => key in bObj && isDeepEqual(aObj[key], bObj[key]));
  }

  return false;
}
