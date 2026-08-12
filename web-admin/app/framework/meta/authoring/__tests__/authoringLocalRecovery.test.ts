import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  clearAuthoringRecoveriesForActor,
  clearInlineAuthoringRecovery,
  clearStudioAuthoringRecovery,
  listInlineAuthoringRecoveries,
  readInlineAuthoringRecovery,
  readStudioAuthoringRecovery,
  storeInlineAuthoringRecovery,
  storeStudioAuthoringRecovery,
} from '../authoringLocalRecovery';

describe('authoring local recovery', () => {
  beforeEach(() => {
    Object.defineProperty(window, 'localStorage', {
      value: createStorage(),
      configurable: true,
    });
    Object.defineProperty(window, 'sessionStorage', {
      value: createStorage(),
      configurable: true,
    });
    vi.restoreAllMocks();
  });

  it('persists inline recovery across a browser-session boundary', () => {
    expect(storeInline('session-a')).toBe(true);

    window.sessionStorage.clear();

    expect(readInlineAuthoringRecovery('42', 'page-a')).toMatchObject({
      kind: 'INLINE',
      sessionPid: 'session-a',
      state: 'UNKNOWN_OUTCOME',
    });
  });

  it('isolates actor, page and writer session without same-page tab overwrite', () => {
    const now = vi.spyOn(Date, 'now').mockReturnValue(1_000);
    storeInline('session-a');
    now.mockReturnValue(2_000);
    storeInline('session-b');

    expect(listInlineAuthoringRecoveries('42', 'page-a')).toHaveLength(2);
    expect(readInlineAuthoringRecovery('42', 'page-a')?.sessionPid).toBe('session-b');
    expect(readInlineAuthoringRecovery('42', 'page-a', 'session-a')?.sessionPid).toBe('session-a');
    expect(readInlineAuthoringRecovery('43', 'page-a')).toBeNull();
    expect(readInlineAuthoringRecovery('42', 'page-b')).toBeNull();

    clearInlineAuthoringRecovery('42', 'page-a', 'session-a');
    expect(readInlineAuthoringRecovery('42', 'page-a', 'session-a')).toBeNull();
    expect(readInlineAuthoringRecovery('42', 'page-a', 'session-b')).not.toBeNull();
  });

  it('supports session-only tenants and disabled tenants explicitly', () => {
    expect(storeInline('session-only', 'SESSION_ONLY')).toBe(true);
    expect(readInlineAuthoringRecovery('42', 'page-a', 'session-only')).toBeNull();
    expect(
      readInlineAuthoringRecovery('42', 'page-a', 'session-only', 'SESSION_ONLY'),
    ).not.toBeNull();

    expect(storeInline('disabled', 'DISABLED')).toBe(false);
    expect(readInlineAuthoringRecovery('42', 'page-a', 'disabled')).toBeNull();
    expect(window.localStorage.length).toBe(0);
  });

  it('stores a Studio Mine with Base and isolates it by actor and session', () => {
    storeStudioAuthoringRecovery({
      actorId: '42',
      sessionPid: 'session-a',
      pagePid: 'page-a',
      baseRevision: 3,
      state: 'DIRTY',
      baseSnapshot: { id: 'page-a', blocks: [] },
      mineDocument: studioDocument('Mine'),
    });

    window.sessionStorage.clear();
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

  it('accepts a 23-hour offline age and rejects expired or corrupt records fail closed', () => {
    vi.spyOn(Date, 'now').mockReturnValue(1_000);
    storeInline('session-a');
    vi.spyOn(Date, 'now').mockReturnValue(23 * 60 * 60 * 1000);
    expect(readInlineAuthoringRecovery('42', 'page-a')).not.toBeNull();

    vi.spyOn(Date, 'now').mockReturnValue(25 * 60 * 60 * 1000);
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();

    window.localStorage.setItem(
      'auraboot:authoring-recovery:v2:inline:42:page-a:session-corrupt',
      '{bad json',
    );
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();
    expect(window.localStorage.length).toBe(0);
  });

  it('reports persistent-storage refusal and leaves no false recovery claim', () => {
    vi.spyOn(window.localStorage, 'setItem').mockImplementationOnce(() => {
      throw new DOMException('Quota exceeded', 'QuotaExceededError');
    });

    expect(storeInline('session-a')).toBe(false);
    expect(readInlineAuthoringRecovery('42', 'page-a')).toBeNull();
  });

  it('purges both persistent and session-only material when policy changes to disabled', () => {
    storeInline('persistent');
    storeInline('session-only', 'SESSION_ONLY');
    storeStudioAuthoringRecovery(
      {
        actorId: '42',
        sessionPid: 'studio-a',
        pagePid: 'page-a',
        baseRevision: 3,
        state: 'DIRTY',
        baseSnapshot: { id: 'page-a', blocks: [] },
        mineDocument: studioDocument('Mine'),
      },
      'SESSION_ONLY',
    );

    clearAuthoringRecoveriesForActor('42');

    expect(window.localStorage.length).toBe(0);
    expect(window.sessionStorage.length).toBe(0);
  });
});

function storeInline(
  sessionPid: string,
  policy: 'PERSISTENT' | 'SESSION_ONLY' | 'DISABLED' = 'PERSISTENT',
) {
  return storeInlineAuthoringRecovery(
    {
      actorId: '42',
      sessionPid,
      pagePid: 'page-a',
      baseRevision: 7,
      state: 'UNKNOWN_OUTCOME',
      edits: [pendingEdit()],
    },
    policy,
  );
}

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

function createStorage(): Storage {
  const entries = new Map<string, string>();
  return {
    get length() {
      return entries.size;
    },
    clear: () => entries.clear(),
    getItem: (key) => entries.get(key) ?? null,
    key: (index) => [...entries.keys()][index] ?? null,
    removeItem: (key) => entries.delete(key),
    setItem: (key, value) => entries.set(key, String(value)),
  };
}
