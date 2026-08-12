import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearInlineAuthoringRecovery,
  clearStudioAuthoringRecovery,
  readInlineAuthoringRecovery,
  readStudioAuthoringRecovery,
  storeInlineAuthoringRecovery,
  storeStudioAuthoringRecovery,
} from '../authoringLocalRecovery';

describe('authoring local recovery', () => {
  beforeEach(() => {
    window.sessionStorage.clear();
    vi.restoreAllMocks();
  });

  it('isolates inline dirty recovery by actor and page and only clears the expected session', () => {
    storeInlineAuthoringRecovery({
      actorId: '42',
      sessionPid: 'session-a',
      pagePid: 'page-a',
      baseRevision: 7,
      state: 'UNKNOWN_OUTCOME',
      edits: [pendingEdit()],
    });

    expect(readInlineAuthoringRecovery('42', 'page-a')).toMatchObject({
      kind: 'INLINE',
      sessionPid: 'session-a',
      state: 'UNKNOWN_OUTCOME',
    });
    expect(readInlineAuthoringRecovery('43', 'page-a')).toBeNull();
    expect(readInlineAuthoringRecovery('42', 'page-b')).toBeNull();

    clearInlineAuthoringRecovery('42', 'page-a', 'newer-session');
    expect(readInlineAuthoringRecovery('42', 'page-a')).not.toBeNull();
    clearInlineAuthoringRecovery('42', 'page-a', 'session-a');
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();
  });

  it('stores a Studio Mine with its Base and isolates it by actor and session', () => {
    storeStudioAuthoringRecovery({
      actorId: '42',
      sessionPid: 'session-a',
      pagePid: 'page-a',
      baseRevision: 3,
      state: 'DIRTY',
      baseSnapshot: { id: 'page-a', blocks: [] },
      mineDocument: studioDocument('Mine'),
    });

    expect(readStudioAuthoringRecovery('42', 'session-a')).toMatchObject({
      kind: 'STUDIO',
      baseRevision: 3,
      mineDocument: { title: 'Mine' },
    });
    expect(readStudioAuthoringRecovery('43', 'session-a')).toBeNull();
    expect(readStudioAuthoringRecovery('42', 'session-b')).toBeNull();

    clearStudioAuthoringRecovery('42', 'session-a');
    expect(readStudioAuthoringRecovery('42', 'session-a')).toBeNull();
  });

  it('rejects expired or corrupt recovery records instead of restoring untrusted state', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000);
    storeInlineAuthoringRecovery({
      actorId: '42',
      sessionPid: 'session-a',
      pagePid: 'page-a',
      baseRevision: 7,
      state: 'DIRTY',
      edits: [pendingEdit()],
    });
    vi.spyOn(Date, 'now').mockReturnValue(25 * 60 * 60 * 1000);
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();

    window.sessionStorage.setItem('auraboot:authoring-recovery:v1:inline:42:page-a', '{bad json');
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();
  });

  it('reports storage refusal so the UI cannot claim crash recovery is available', () => {
    vi.spyOn(window.sessionStorage, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('Quota exceeded', 'QuotaExceededError');
    });

    expect(
      storeInlineAuthoringRecovery({
        actorId: '42',
        sessionPid: 'session-a',
        pagePid: 'page-a',
        baseRevision: 7,
        state: 'DIRTY',
        edits: [pendingEdit()],
      }),
    ).toBe(false);
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();
  });
});

function pendingEdit() {
  return {
    key: 'block-a:/props/density',
    baseRevision: 7,
    blockId: 'block-a',
    blockLabel: '订单表格',
    manifestChecksum: 'checksum-a',
    property: {
      propertyPath: '/props/density',
      allowedOperations: ['REPLACE'],
      route: 'INLINE',
      risk: 'L1',
      effectTags: ['PRESENTATION'],
      reversibility: 'REVERSIBLE',
      protectedSemantic: false,
      rolePreviewRequired: false,
    },
    operation: 'REPLACE' as const,
    previousValue: 'comfortable',
    value: 'compact',
  };
}

function studioDocument(name: string) {
  return {
    schemaVersion: 3 as const,
    id: 'page-a',
    pageKey: 'orders',
    title: name,
    kind: 'list' as const,
    blocks: [],
  };
}
