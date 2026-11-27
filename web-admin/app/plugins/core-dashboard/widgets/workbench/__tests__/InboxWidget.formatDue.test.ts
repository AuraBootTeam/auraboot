import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { formatDue } from '../InboxWidget';

// The due column of the home page inbox read `Today` and `1d ago` on a Chinese stack:
// the label followed no language until the widget passed its locale (CRM journey walkthrough,
// 2026-11-26).

type InboxItem = Parameters<typeof formatDue>[0];
const item = (createdAt: string): InboxItem => ({ createdAt } as InboxItem);

describe('formatDue', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-11-26T12:00:00Z'));
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('reads the age of an item in Chinese for a Chinese locale', () => {
    expect(formatDue(item('2026-11-26T06:00:00Z'), 'zh-CN')).toBe('今天');
    expect(formatDue(item('2026-11-25T06:00:00Z'), 'zh-CN')).toBe('1 天前');
    expect(formatDue(item('2026-11-23T06:00:00Z'), 'zh-CN')).toBe('3 天前');
    expect(formatDue(item('2026-11-12T06:00:00Z'), 'zh-CN')).toBe('2 周前');
    expect(formatDue(item(''), 'zh-CN')).toBe('—');
  });

  it('reads it in English for every other locale and for no locale', () => {
    expect(formatDue(item('2026-11-26T06:00:00Z'), 'en-US')).toBe('Today');
    expect(formatDue(item('2026-11-25T06:00:00Z'))).toBe('1d ago');
    expect(formatDue(item('2026-11-23T06:00:00Z'))).toBe('3d ago');
    expect(formatDue(item('2026-11-12T06:00:00Z'))).toBe('2w ago');
  });
});
