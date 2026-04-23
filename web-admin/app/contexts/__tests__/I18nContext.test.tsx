import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { I18nProvider, useI18n } from '../I18nContext';

function TranslationProbe({ i18nKey }: { i18nKey: string }) {
  const { t } = useI18n();
  return <span data-testid="translation">{t(i18nKey)}</span>;
}

describe('I18nContext missing-key warnings', () => {
  const originalFetch = globalThis.fetch;
  const originalLocalStorage = window.localStorage;

  beforeEach(() => {
    const store = new Map<string, string>();
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: {
        getItem: vi.fn((key: string) => store.get(key) ?? null),
        setItem: vi.fn((key: string, value: string) => {
          store.set(key, value);
        }),
        removeItem: vi.fn((key: string) => {
          store.delete(key);
        }),
        clear: vi.fn(() => {
          store.clear();
        }),
      },
    });
    vi.restoreAllMocks();
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    Object.defineProperty(window, 'localStorage', {
      configurable: true,
      value: originalLocalStorage,
    });
  });

  it('does not warn while client-side i18n recovery is still in progress', async () => {
    globalThis.fetch = vi.fn(
      () =>
        new Promise(() => {
          // Keep recovery pending for this assertion.
        }),
    ) as unknown as typeof fetch;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <TranslationProbe i18nKey="search.placeholder" />
      </I18nProvider>,
    );

    expect(screen.getByTestId('translation')).toHaveTextContent('search.placeholder');
    await waitFor(() => {
      expect(globalThis.fetch).toHaveBeenCalledWith('/api/i18n/zh-CN');
    });
    expect(warnSpy).not.toHaveBeenCalled();
  });

  it('warns after recovery finishes and the key is still missing', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      json: async () => ({
        code: '0',
        data: {},
      }),
    }) as unknown as typeof fetch;
    const warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {});

    render(
      <I18nProvider initialData={{}} initialLocale="zh-CN">
        <TranslationProbe i18nKey="search.placeholder" />
      </I18nProvider>,
    );

    await waitFor(() => {
      expect(warnSpy).toHaveBeenCalledWith(
        '[i18n] Missing translation key: "search.placeholder" (locale: zh-CN)',
      );
    });
  });
});
