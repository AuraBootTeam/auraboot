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
  return JSON.stringify(left) === JSON.stringify(right);
}
