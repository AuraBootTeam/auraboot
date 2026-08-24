import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AddFavoriteModal } from '../AddFavoriteModal';

const mocks = vi.hoisted(() => ({
  locale: 'zh-CN',
  get: vi.fn(),
  listFavorites: vi.fn(),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (key: string) => key, locale: mocks.locale }),
}));

vi.mock('~/shared/services/http-client', () => ({ get: mocks.get }));

vi.mock('~/shared/services/engagementService', () => ({
  addFavorite: vi.fn(),
  removeFavorite: vi.fn(),
  listFavorites: mocks.listFavorites,
}));

describe('AddFavoriteModal localization fallbacks', () => {
  beforeEach(() => {
    mocks.locale = 'zh-CN';
    mocks.get.mockReset().mockResolvedValue({ code: 200, data: [] });
    mocks.listFavorites.mockReset().mockResolvedValue([]);
  });

  it('renders Chinese labels when the catalog has not loaded the optional keys', () => {
    render(<AddFavoriteModal open onClose={vi.fn()} onChanged={vi.fn()} />);

    expect(screen.getByRole('heading', { name: '添加快捷入口' })).toBeTruthy();
    expect(screen.getByPlaceholderText('搜索菜单项...')).toBeTruthy();
    expect(screen.getByRole('button', { name: '关闭' })).toBeTruthy();
  });

  it('keeps English fallbacks for non-Chinese locales', () => {
    mocks.locale = 'en-US';
    render(<AddFavoriteModal open onClose={vi.fn()} onChanged={vi.fn()} />);

    expect(screen.getByRole('heading', { name: 'Add Shortcuts' })).toBeTruthy();
    expect(screen.getByPlaceholderText('Search menu items...')).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Close' })).toBeTruthy();
  });
});
