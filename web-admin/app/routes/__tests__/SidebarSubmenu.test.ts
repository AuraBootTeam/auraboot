import { describe, expect, it } from 'vitest';
import { resolveMenuLabel } from '../SidebarSubmenu';

describe('resolveMenuLabel', () => {
  it('falls back to menu name when translation key is missing', () => {
    const t = (key: string, _params?: Record<string, any>, fallback?: string) =>
      fallback ?? key;

    expect(resolveMenuLabel(t, { nameKey: 'menu.ai_center', name: 'AI 中心' })).toBe(
      'AI 中心',
    );
  });

  it('uses translated text when available', () => {
    const t = (key: string, _params?: Record<string, any>, fallback?: string) =>
      key === 'menu.ai_center' ? 'AI Center' : (fallback ?? key);

    expect(resolveMenuLabel(t, { nameKey: 'menu.ai_center', name: 'AI 中心' })).toBe(
      'AI Center',
    );
  });

  it('does not surface raw menu keys when the key is missing but a display name exists', () => {
    const t = (key: string, _params?: Record<string, any>, fallback?: string) =>
      fallback ?? key;

    expect(resolveMenuLabel(t, { nameKey: 'menu.wd_root', name: '请假 Demo' })).toBe(
      '请假 Demo',
    );
  });
});
