import React, { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { useAuth } from '~/contexts/AuthContext';
import { tenantPreferenceService } from '~/shared/services/tenantPreferenceService';

/**
 * TenantThemeContext — per-tenant visual skin.
 *
 * Reads the `ui.theme` tenant preference (a flat map of design-token overrides)
 * and applies it as inline CSS custom properties on <html>. Inline style beats
 * the stylesheet `:root`/`.dark` declarations, so tenant colors win in both
 * light and dark mode without touching the committed dsTokens defaults.
 *
 * Overridable keys are whitelisted — arbitrary CSS injection is not possible.
 * `brandName` (non-CSS) updates the document title so the school name shows in
 * the browser tab; topbar logo swap stays out of scope for V4.
 *
 * Theme keys → CSS custom properties (names follow buildThemeCss in
 * app/framework/meta/runtime/theme/tokens.ts):
 *   accent, accentHover, accentWeak, selection, bg, panel, subtle, hover,
 *   border, borderStrong, text, text2, text3,
 *   radiusControl, radiusCard, radiusCardLg, shadowCard, shadowPop, focusRing
 * Non-CSS keys:
 *   brandName (document title), brandMark (emoji shown by XyBrandMark)
 */

export interface TenantTheme {
  [key: string]: string | undefined;
}

const THEME_VAR_MAP: Record<string, string> = {
  accent: '--color-accent',
  accentHover: '--color-accent-hover',
  accentWeak: '--color-accent-weak',
  selection: '--color-selection',
  bg: '--color-bg',
  panel: '--color-panel',
  subtle: '--color-subtle',
  hover: '--color-hover',
  border: '--color-border',
  borderStrong: '--color-border-strong',
  text: '--color-text',
  text2: '--color-text-2',
  text3: '--color-text-3',
  radiusControl: '--radius-control',
  radiusCard: '--radius-card',
  radiusCardLg: '--radius-card-lg',
  shadowCard: '--shadow-card',
  shadowPop: '--shadow-pop',
  focusRing: '--shadow-focus',
};

const THEME_FLAG_ATTR = 'data-tenant-theme';

function applyTheme(theme: TenantTheme | null) {
  if (typeof document === 'undefined') return;
  const root = document.documentElement;
  if (!theme || Object.keys(theme).length === 0) {
    Object.values(THEME_VAR_MAP).forEach((cssVar) => root.style.removeProperty(cssVar));
    root.removeAttribute(THEME_FLAG_ATTR);
    return;
  }
  for (const [key, cssVar] of Object.entries(THEME_VAR_MAP)) {
    const value = theme[key];
    if (value) root.style.setProperty(cssVar, value);
    else root.style.removeProperty(cssVar);
  }
  root.setAttribute(THEME_FLAG_ATTR, '1');
  if (theme.brandName) {
    document.title = `${theme.brandName} · 工作台`;
  }
}

const TenantThemeContext = createContext<TenantTheme | null>(null);

export function TenantThemeProvider({ children }: { children: ReactNode }) {
  const { isAuthenticated } = useAuth();
  const [theme, setTheme] = useState<TenantTheme | null>(null);

  useEffect(() => {
    let cancelled = false;
    if (!isAuthenticated) {
      setTheme(null);
      applyTheme(null);
      return () => {
        cancelled = true;
      };
    }
    tenantPreferenceService
      .get<TenantTheme>('ui.theme')
      .then((value) => {
        if (cancelled) return;
        setTheme(value ?? null);
        applyTheme(value ?? null);
      })
      .catch(() => {
        if (!cancelled) {
          setTheme(null);
          applyTheme(null);
        }
      });
    return () => {
      cancelled = true;
    };
  }, [isAuthenticated]);

  return <TenantThemeContext.Provider value={theme}>{children}</TenantThemeContext.Provider>;
}

/** Raw ui.theme map (may include brandName/brandMark). Null when unset. */
export function useTenantTheme(): TenantTheme | null {
  return useContext(TenantThemeContext);
}
