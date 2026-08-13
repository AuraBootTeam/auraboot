export type AuthoringSavePropertyOutcome = 'COMMITTED' | 'UNCHANGED' | 'CONFLICT';

/**
 * Classify one intended authoring property after a write response was interrupted.
 *
 * The server snapshot is authoritative. Matching Mine means the intended effect is already
 * persisted and must not be replayed; matching Base means the request did not land; any third
 * value requires explicit Base / Mine / Latest resolution.
 */
export function reconcileAuthoringSaveProperty(
  previousValue: unknown,
  intendedValue: unknown,
  latestValue: unknown,
): AuthoringSavePropertyOutcome {
  if (authoringValuesEqual(latestValue, intendedValue)) return 'COMMITTED';
  if (authoringValuesEqual(latestValue, previousValue)) return 'UNCHANGED';
  return 'CONFLICT';
}

/** Atomic Studio batches either materialize the whole intended document or remain unchanged. */
export function reconcileAuthoringStudioDocument(
  baseDocument: unknown,
  intendedDocument: unknown,
  latestDocument: unknown,
): AuthoringSavePropertyOutcome {
  return reconcileAuthoringSaveProperty(baseDocument, intendedDocument, latestDocument);
}

function authoringValuesEqual(left: unknown, right: unknown): boolean {
  return stableJsonValue(left) === stableJsonValue(right);
}

/**
 * Authoring documents are JSON values, so object member insertion order is not semantic.
 * Workbench edits can reinsert a previously absent property at the end of an object while
 * the authoritative snapshot materializer restores its canonical field order. Comparing raw
 * JSON.stringify output would therefore report a false conflict after a committed write.
 */
function stableJsonValue(value: unknown): string | undefined {
  const serialized = JSON.stringify(value);
  if (serialized === undefined) return undefined;
  return JSON.stringify(sortJsonObjectKeys(JSON.parse(serialized)));
}

function sortJsonObjectKeys(value: unknown): unknown {
  if (Array.isArray(value)) return value.map(sortJsonObjectKeys);
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .sort(([left], [right]) => left.localeCompare(right))
      .map(([key, child]) => [key, sortJsonObjectKeys(child)]),
  );
}
