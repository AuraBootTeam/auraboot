import React, { createContext, useContext, useState, useEffect, useCallback } from 'react';
import { ResultHelper } from '~/utils/type';

// Dedup set for missing-key warnings — module scope, lives for the session
const MISSING_KEY_WARNED = new Set<string>();

// RTL locale detection
const RTL_LOCALES = new Set([
  'ar',
  'ar-SA',
  'ar-EG',
  'ar-AE',
  'ar-MA',
  'he',
  'he-IL',
  'fa',
  'fa-IR',
  'ur',
  'ur-PK',
]);

export function isRTLLocale(locale: string): boolean {
  return RTL_LOCALES.has(locale) || RTL_LOCALES.has(locale.split('-')[0]);
}

// 创建 I18n 上下文
interface I18nContextType {
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
  locale: string;
  setLocale: (locale: string) => void;
  loading: boolean;
  recovering: boolean;
  isRTL: boolean;
}

const I18nContext = createContext<I18nContextType>({
  t: (key, _params, fallback) => fallback ?? key,
  locale: 'zh-CN',
  setLocale: () => {},
  loading: false,
  recovering: false,
  isRTL: false,
});

const LOCALE_STORAGE_KEY = 'locale';
const DEFAULT_LOCALE = 'zh-CN';
const I18N_CACHE_KEY_PREFIX = 'i18n_cache_';

// Get initial locale from localStorage or default
function getInitialLocale(): string {
  if (typeof window === 'undefined') return DEFAULT_LOCALE;
  return localStorage.getItem(LOCALE_STORAGE_KEY) || DEFAULT_LOCALE;
}

function getCachedTranslations(loc: string): Translations | null {
  if (typeof window === 'undefined') return null;
  try {
    const raw = localStorage.getItem(`${I18N_CACHE_KEY_PREFIX}${loc}`);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

function setCachedTranslations(loc: string, data: Translations): void {
  if (typeof window === 'undefined') return;
  try {
    localStorage.setItem(`${I18N_CACHE_KEY_PREFIX}${loc}`, JSON.stringify(data));
  } catch {
    /* localStorage full — ignore */
  }
}

type Translations = Record<string, any>;

interface I18nProviderProps {
  children: React.ReactNode;
  initialData?: Translations;
  initialLocale?: string;
}

// I18n 提供者组件
export function I18nProvider({ children, initialData = {}, initialLocale }: I18nProviderProps) {
  const isInitialDataEmpty = Object.keys(initialData).length === 0;
  const effectiveLocale = initialLocale || DEFAULT_LOCALE;

  const [translations, setTranslations] = useState<Translations>(() => {
    if (!isInitialDataEmpty) return initialData;
    return getCachedTranslations(effectiveLocale) || {};
  });
  const [locale, setLocaleState] = useState(effectiveLocale);
  const [loading, setLoading] = useState(false);
  const [recovering, setRecovering] = useState(isInitialDataEmpty);

  // Sync localStorage/cookie on mount if server provided a locale
  // Also cache SSR translations to localStorage
  useEffect(() => {
    // Cache successful SSR translations
    if (!isInitialDataEmpty) {
      setCachedTranslations(effectiveLocale, initialData);
    }

    const savedLocale = getInitialLocale();
    if (initialLocale && savedLocale !== initialLocale) {
      // Server locale (from cookie) takes precedence — sync localStorage
      localStorage.setItem(LOCALE_STORAGE_KEY, initialLocale);
    } else if (!initialLocale && savedLocale !== DEFAULT_LOCALE) {
      // No server locale but localStorage has a preference — set cookie for next SSR
      document.cookie = `locale=${savedLocale};path=/;max-age=${365 * 24 * 3600};SameSite=Lax`;
      setLocaleState(savedLocale);
    }
    // SSR already fetched correct locale data via initialData, no need to re-fetch
  }, []);

  // Client-side recovery when SSR failed to load translations
  useEffect(() => {
    if (!isInitialDataEmpty) return;
    let cancelled = false;
    (async () => {
      try {
        const res = await fetch(`/api/i18n/${effectiveLocale}`);
        const data = await res.json();
        if (!cancelled && ResultHelper.isSuccess(data) && data.data) {
          setTranslations(data.data);
          setCachedTranslations(effectiveLocale, data.data);
        }
      } catch (err) {
        console.error('i18n client-side recovery failed:', err);
      } finally {
        if (!cancelled) {
          setLoading(false);
          setRecovering(false);
        }
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  // Fetch translations for a given locale
  const fetchTranslations = useCallback(async (targetLocale: string) => {
    setLoading(true);
    try {
      const response = await fetch(`/api/i18n/${targetLocale}`);
      const data = await response.json();
      if (ResultHelper.isSuccess(data)) {
        setTranslations(data.data || {});
        setCachedTranslations(targetLocale, data.data || {});
      }
    } catch (error) {
      console.error('Failed to fetch translations:', error);
    } finally {
      setLoading(false);
    }
  }, []);

  // Handle locale change with persistence and data refresh
  const handleSetLocale = useCallback(
    async (newLocale: string) => {
      if (newLocale === locale) return;

      if (typeof window !== 'undefined') {
        // Persist to both localStorage and cookie (cookie for SSR access)
        localStorage.setItem(LOCALE_STORAGE_KEY, newLocale);
        document.cookie = `locale=${newLocale};path=/;max-age=${365 * 24 * 3600};SameSite=Lax`;
        // Reload to re-run server loaders with new locale
        window.location.reload();
        return;
      }

      setLocaleState(newLocale);
      await fetchTranslations(newLocale);
    },
    [locale, fetchTranslations],
  );

  // 翻译函数 - 支持嵌套 key 和参数替换
  const translate = (key: string, params?: Record<string, any>, fallback?: string): string => {
    // 0. 处理空值
    if (key == null || key === '') {
      return '';
    }

    // 1. 先直接查找顶级 key（flat 结构）
    let text: any = translations[key];

    // 2. 如果没找到，尝试逐级查找（嵌套结构，如 "model.store.name.label"）
    if (text === undefined) {
      const parts = key.split('.');
      let current: any = translations;
      for (const part of parts) {
        current = current?.[part];
        if (current === undefined) break;
      }
      text = current;
    }

    // 3. 如果仍未找到，返回 fallback 或 key 本身
    if (text === undefined || text === null) {
      if (
        process.env.NODE_ENV !== 'production' &&
        !loading &&
        !recovering &&
        !MISSING_KEY_WARNED.has(key)
      ) {
        MISSING_KEY_WARNED.add(key);
        console.warn(`[i18n] Missing translation key: "${key}" (locale: ${locale})`);
      }
      return fallback ?? key;
    }

    // 4. 如果找到的值是对象（多语言对象），提取当前语言
    if (typeof text === 'object') {
      text = text[locale] || text['zh-CN'] || text['en-US'] || key;
    }

    // 5. 参数替换 (use string split/join instead of RegExp to avoid special char issues)
    if (params && typeof text === 'string') {
      Object.entries(params).forEach(([paramKey, paramValue]) => {
        text = (text as string).split(`{${paramKey}}`).join(String(paramValue));
      });
    }

    return typeof text === 'string' ? text : String(text);
  };

  return (
    <I18nContext.Provider
      value={{
        t: translate,
        locale,
        setLocale: handleSetLocale,
        loading,
        recovering,
        isRTL: isRTLLocale(locale),
      }}
    >
      {recovering && Object.keys(translations).length === 0 && (
        <>
          <style>{`@keyframes i18n-loading { 0% { background-position: 200% 0; } 100% { background-position: -200% 0; } }`}</style>
          <div
            style={{
              position: 'fixed',
              top: 0,
              left: 0,
              right: 0,
              height: '3px',
              background: 'linear-gradient(90deg, #3b82f6 0%, #60a5fa 50%, #3b82f6 100%)',
              backgroundSize: '200% 100%',
              animation: 'i18n-loading 1.5s ease-in-out infinite',
              zIndex: 99999,
            }}
          />
        </>
      )}
      {children}
    </I18nContext.Provider>
  );
}

// 使用 I18n 的钩子
export function useI18n() {
  return useContext(I18nContext);
}
