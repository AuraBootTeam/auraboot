import { describe, expect, it } from 'vitest';
import {
  reconcileAuthoringSaveProperty,
  reconcileAuthoringStudioDocument,
} from '../saveReconciliation';

describe('authoring save reconciliation', () => {
  it('deduplicates an intended property that the authoritative snapshot already contains', () => {
    expect(reconcileAuthoringSaveProperty('comfortable', 'compact', 'compact')).toBe('COMMITTED');
  });

  it('keeps an unchanged property retryable and sends a third value to conflict resolution', () => {
    expect(reconcileAuthoringSaveProperty('comfortable', 'compact', 'comfortable')).toBe(
      'UNCHANGED',
    );
    expect(reconcileAuthoringSaveProperty('comfortable', 'compact', 'spacious')).toBe('CONFLICT');
  });

  it('only accepts an atomic Studio batch when the complete authoritative document matches Mine', () => {
    const base = { blocks: [{ id: 'table-1', title: 'Base' }] };
    const mine = { blocks: [{ id: 'table-1', title: 'Mine' }] };

    expect(reconcileAuthoringStudioDocument(base, mine, mine)).toBe('COMMITTED');
    expect(reconcileAuthoringStudioDocument(base, mine, base)).toBe('UNCHANGED');
    expect(
      reconcileAuthoringStudioDocument(base, mine, {
        blocks: [{ id: 'table-1', title: 'Latest' }],
      }),
    ).toBe('CONFLICT');
  });

  it('ignores JSON object insertion order while preserving array order', () => {
    const base = { blocks: [{ id: 'table', columns: ['name', 'status'] }] };
    const mine = {
      blocks: [{ id: 'table', title: 'Production exceptions', columns: ['name', 'status'] }],
    };
    const committedWithCanonicalKeyOrder = {
      blocks: [{ columns: ['name', 'status'], id: 'table', title: 'Production exceptions' }],
    };
    const reorderedColumns = {
      blocks: [{ columns: ['status', 'name'], id: 'table', title: 'Production exceptions' }],
    };

    expect(
      reconcileAuthoringStudioDocument(base, mine, committedWithCanonicalKeyOrder),
    ).toBe('COMMITTED');
    expect(reconcileAuthoringStudioDocument(base, mine, reorderedColumns)).toBe('CONFLICT');
  });
});
