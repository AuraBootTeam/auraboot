import { beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return {
    ...actual,
    useNavigate: () => vi.fn(),
  };
});

vi.mock('~/root-data', () => ({
  useRootLoaderData: () => ({ menus: [] }),
}));

import { loadRecent, saveRecent, toRecordHit } from '~/ui/CommandPalette';

describe('CommandPalette local storage recovery', () => {
  const storage = new Map<string, string>();

  beforeEach(() => {
    storage.clear();
    Object.defineProperty(window, 'localStorage', {
      value: {
        getItem: (key: string) => storage.get(key) ?? null,
        setItem: (key: string, value: string) => {
          storage.set(key, value);
        },
        removeItem: (key: string) => {
          storage.delete(key);
        },
        clear: () => {
          storage.clear();
        },
      },
      configurable: true,
    });
  });

  it('clears malformed recent-search cache instead of throwing', () => {
    window.localStorage.setItem('auraboot_recent_searches', '{broken-json');

    const result = loadRecent();

    expect(result).toEqual([]);
    expect(window.localStorage.getItem('auraboot_recent_searches')).toBeNull();
  });

  it('returns empty recents when localStorage is unavailable', () => {
    Object.defineProperty(window, 'localStorage', {
      value: undefined,
      configurable: true,
    });

    expect(loadRecent()).toEqual([]);
    expect(() => saveRecent(['a'])).not.toThrow();
  });

  it('persists only the latest 8 recent searches', () => {
    saveRecent(['1', '2', '3', '4', '5', '6', '7', '8', '9']);

    expect(JSON.parse(window.localStorage.getItem('auraboot_recent_searches') || '[]')).toEqual([
      '1',
      '2',
      '3',
      '4',
      '5',
      '6',
      '7',
      '8',
    ]);
  });
});

describe('CommandPalette public record identity', () => {
  it('builds record navigation from pid only', () => {
    expect(
      toRecordHit(
        { pid: '01KPUBLIC', id: 9_007_199_254_740_993n, crm_acc_name: 'Acme' },
        'crm_account_common',
        'Accounts',
      ),
    ).toEqual({
      pid: '01KPUBLIC',
      displayText: 'Acme',
      modelCode: 'crm_account_common',
      modelName: 'Accounts',
      path: '/p/crm_account_common/view/01KPUBLIC',
    });
  });

  it('fails closed when a search response exposes only an internal id', () => {
    expect(toRecordHit({ id: 42, crm_acc_name: 'Internal only' }, 'crm_account_common', 'Accounts'))
      .toBeNull();
  });
});
