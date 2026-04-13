import { beforeEach, describe, expect, it } from 'vitest';

import { loadRecent, saveRecent } from '~/components/CommandPalette';

describe('CommandPalette local storage recovery', () => {
  beforeEach(() => {
    window.localStorage.clear();
  });

  it('clears malformed recent-search cache instead of throwing', () => {
    window.localStorage.setItem('auraboot_recent_searches', '{broken-json');

    const result = loadRecent();

    expect(result).toEqual([]);
    expect(window.localStorage.getItem('auraboot_recent_searches')).toBeNull();
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
