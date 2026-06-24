import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  DEFAULT_DRAFT_MAX_AGE_MS,
  clearDraft,
  draftKey,
  hasMeaningfulValues,
  loadDraft,
  saveDraft,
} from '../formDraftStore';

/**
 * Minimal in-memory Storage fake so the pure store is testable without jsdom /
 * a real localStorage. Mirrors the Web Storage interface surface the store uses.
 */
function createFakeStorage(seed: Record<string, string> = {}): Storage {
  const map = new Map<string, string>(Object.entries(seed));
  return {
    get length() {
      return map.size;
    },
    clear: () => map.clear(),
    getItem: (key: string) => (map.has(key) ? map.get(key)! : null),
    key: (index: number) => Array.from(map.keys())[index] ?? null,
    removeItem: (key: string) => {
      map.delete(key);
    },
    setItem: (key: string, value: string) => {
      map.set(key, value);
    },
  };
}

describe('draftKey', () => {
  it('composes a stable, namespaced key for create forms (no recordPid)', () => {
    expect(draftKey('crm_account', 'crm_account_form')).toBe(
      'aura_form_draft:crm_account:crm_account_form:new',
    );
  });

  it('scopes edit drafts by recordPid so different records do not collide', () => {
    const create = draftKey('crm_account', 'crm_account_form');
    const editA = draftKey('crm_account', 'crm_account_form', '01ABC');
    const editB = draftKey('crm_account', 'crm_account_form', '01XYZ');
    expect(editA).toBe('aura_form_draft:crm_account:crm_account_form:01ABC');
    expect(create).not.toBe(editA);
    expect(editA).not.toBe(editB);
  });

  it('falls back gracefully when pageKey is missing', () => {
    expect(draftKey('crm_account', undefined)).toBe('aura_form_draft:crm_account:_:new');
  });
});

describe('hasMeaningfulValues', () => {
  it('returns false for null / empty objects', () => {
    expect(hasMeaningfulValues(null as any)).toBe(false);
    expect(hasMeaningfulValues({})).toBe(false);
  });

  it('returns false when every value is empty (null / "" / [] / undefined)', () => {
    expect(hasMeaningfulValues({ a: '', b: null, c: undefined, d: [] })).toBe(false);
  });

  it('returns true when at least one field has a non-empty value', () => {
    expect(hasMeaningfulValues({ name: 'Acme', note: '' })).toBe(true);
    expect(hasMeaningfulValues({ count: 0 })).toBe(true);
    expect(hasMeaningfulValues({ flag: false })).toBe(true);
    expect(hasMeaningfulValues({ tags: ['x'] })).toBe(true);
  });
});

describe('saveDraft / loadDraft round-trip', () => {
  let storage: Storage;
  beforeEach(() => {
    storage = createFakeStorage();
  });

  it('saves meaningful values and loads them back', () => {
    const key = draftKey('crm_account', 'crm_account_form');
    const saved = saveDraft(storage, key, { name: 'Acme', status: 'active' }, 1_000);
    expect(saved).toBe(true);

    const loaded = loadDraft(storage, key, { now: 1_500 });
    expect(loaded).not.toBeNull();
    expect(loaded!.values).toEqual({ name: 'Acme', status: 'active' });
    expect(loaded!.savedAt).toBe(1_000);
  });

  it('does not save an all-empty form (hasMeaningfulValues guard)', () => {
    const key = draftKey('crm_account', 'crm_account_form');
    const saved = saveDraft(storage, key, { name: '', note: null }, 1_000);
    expect(saved).toBe(false);
    expect(storage.getItem(key)).toBeNull();
    expect(loadDraft(storage, key, { now: 1_500 })).toBeNull();
  });

  it('returns null when no draft exists', () => {
    expect(loadDraft(storage, draftKey('x', 'y'), { now: 1 })).toBeNull();
  });
});

describe('loadDraft expiry (TTL)', () => {
  it('returns null and clears storage for drafts older than maxAgeMs', () => {
    const storage = createFakeStorage();
    const key = draftKey('crm_account', 'crm_account_form');
    saveDraft(storage, key, { name: 'Acme' }, 0);

    const now = DEFAULT_DRAFT_MAX_AGE_MS + 1;
    expect(loadDraft(storage, key, { now })).toBeNull();
    // expired draft is proactively cleaned up
    expect(storage.getItem(key)).toBeNull();
  });

  it('respects a custom maxAgeMs', () => {
    const storage = createFakeStorage();
    const key = draftKey('crm_account', 'crm_account_form');
    saveDraft(storage, key, { name: 'Acme' }, 0);

    expect(loadDraft(storage, key, { now: 500, maxAgeMs: 1_000 })).not.toBeNull();
    expect(loadDraft(storage, key, { now: 2_000, maxAgeMs: 1_000 })).toBeNull();
  });

  it('keeps drafts that are exactly at the boundary', () => {
    const storage = createFakeStorage();
    const key = draftKey('crm_account', 'crm_account_form');
    saveDraft(storage, key, { name: 'Acme' }, 0);
    expect(
      loadDraft(storage, key, {
        now: DEFAULT_DRAFT_MAX_AGE_MS,
        maxAgeMs: DEFAULT_DRAFT_MAX_AGE_MS,
      }),
    ).not.toBeNull();
  });
});

describe('loadDraft corruption handling', () => {
  it('returns null and clears the key for non-JSON payloads', () => {
    const key = draftKey('crm_account', 'crm_account_form');
    const storage = createFakeStorage({ [key]: '{not valid json' });
    expect(loadDraft(storage, key, { now: 1 })).toBeNull();
    expect(storage.getItem(key)).toBeNull();
  });

  it('returns null for JSON that is missing the expected shape', () => {
    const key = draftKey('crm_account', 'crm_account_form');
    const storage = createFakeStorage({ [key]: JSON.stringify({ foo: 'bar' }) });
    expect(loadDraft(storage, key, { now: 1 })).toBeNull();
    expect(storage.getItem(key)).toBeNull();
  });

  it('returns null when values is not an object', () => {
    const key = draftKey('crm_account', 'crm_account_form');
    const storage = createFakeStorage({
      [key]: JSON.stringify({ savedAt: 1, values: 'oops' }),
    });
    expect(loadDraft(storage, key, { now: 2 })).toBeNull();
    expect(storage.getItem(key)).toBeNull();
  });
});

describe('clearDraft', () => {
  it('removes a saved draft', () => {
    const storage = createFakeStorage();
    const key = draftKey('crm_account', 'crm_account_form');
    saveDraft(storage, key, { name: 'Acme' }, 1);
    expect(storage.getItem(key)).not.toBeNull();
    clearDraft(storage, key);
    expect(storage.getItem(key)).toBeNull();
  });

  it('is a no-op when there is nothing to clear', () => {
    const storage = createFakeStorage();
    expect(() => clearDraft(storage, draftKey('x', 'y'))).not.toThrow();
  });
});

describe('storage failure resilience', () => {
  it('saveDraft returns false (does not throw) when setItem throws (quota)', () => {
    const storage = createFakeStorage();
    vi.spyOn(storage, 'setItem').mockImplementation(() => {
      throw new Error('QuotaExceededError');
    });
    expect(saveDraft(storage, draftKey('x', 'y'), { a: 1 }, 1)).toBe(false);
  });

  it('loadDraft returns null (does not throw) when getItem throws', () => {
    const storage = createFakeStorage();
    vi.spyOn(storage, 'getItem').mockImplementation(() => {
      throw new Error('SecurityError');
    });
    expect(loadDraft(storage, draftKey('x', 'y'), { now: 1 })).toBeNull();
  });

  it('tolerates a null storage (SSR / disabled)', () => {
    expect(saveDraft(null, draftKey('x', 'y'), { a: 1 }, 1)).toBe(false);
    expect(loadDraft(null, draftKey('x', 'y'), { now: 1 })).toBeNull();
    expect(() => clearDraft(null, draftKey('x', 'y'))).not.toThrow();
  });
});
