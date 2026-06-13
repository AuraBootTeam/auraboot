/**
 * aiLockedFields — derive AI-locked field codes from a DSL form schema and
 * partition an AI-returned field map by lock state.
 *
 * A field marked `props.aiLocked: true` (authored in the unified designer's
 * field inspector, D5) must never be overwritten by an AI fill. These pure
 * helpers are consumed at every AI-fill apply seam (DslFormFillProvider.applyFields
 * and the AuraBot form-fill handler) so the lock holds regardless of fill source.
 */

interface MaybeFieldNode {
  field?: unknown;
  props?: { aiLocked?: unknown } | null;
  aiLocked?: unknown;
  [key: string]: unknown;
}

function isAiLocked(node: MaybeFieldNode): boolean {
  return node?.props?.aiLocked === true || node?.aiLocked === true;
}

/**
 * Walk a DSL schema (PageSchemaV3 or any nested block tree) and return the
 * de-duplicated list of field codes whose node is marked AI-locked.
 */
export function collectAiLockedFieldCodes(schema: unknown): string[] {
  const locked = new Set<string>();
  const visit = (node: unknown): void => {
    if (Array.isArray(node)) {
      node.forEach(visit);
      return;
    }
    if (!node || typeof node !== 'object') return;
    const n = node as MaybeFieldNode;
    if (typeof n.field === 'string' && n.field && isAiLocked(n)) {
      locked.add(n.field);
    }
    for (const value of Object.values(n)) {
      if (value && typeof value === 'object') visit(value);
    }
  };
  visit(schema);
  return Array.from(locked);
}

export interface FieldLockPartition {
  /** Values safe to apply (their field code is not locked). */
  applied: Record<string, unknown>;
  /** Field codes that were present in the input but skipped because locked. */
  skipped: string[];
}

/**
 * Partition an AI-returned `field code -> value` map into the values that may
 * be applied and the codes that were skipped because they are locked.
 */
export function partitionFieldsByLock(
  fields: Record<string, unknown>,
  lockedFieldCodes: Iterable<string>,
): FieldLockPartition {
  const locked =
    lockedFieldCodes instanceof Set ? lockedFieldCodes : new Set(lockedFieldCodes);
  const applied: Record<string, unknown> = {};
  const skipped: string[] = [];
  for (const [code, value] of Object.entries(fields)) {
    if (locked.has(code)) {
      skipped.push(code);
    } else {
      applied[code] = value;
    }
  }
  return { applied, skipped };
}
