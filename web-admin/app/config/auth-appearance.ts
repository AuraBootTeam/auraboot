import { validateAuthAppearance } from './auth-appearance-contract.mjs';

export type AuthLocale = 'zh-CN' | 'en-US';
export type AuthContentField = 'badge' | 'headline' | 'headlineEm' | 'lead' | 'features' | 'loginTitle' | 'loginLead' | 'mobileLead';
export type AuthContent<T = string> = { mode: 'default' } | { mode: 'hidden' } | { mode: 'custom'; values: Partial<Record<AuthLocale, T>> };
export interface AuthAppearance {
  version: 1;
  template: 'split' | 'centered' | 'background';
  defaultLocale: AuthLocale;
  brandPosition?: 'left' | 'right';
  cardPosition?: 'left' | 'center' | 'right';
  content?: Partial<Record<Exclude<AuthContentField, 'features'>, AuthContent>> & { features?: AuthContent<string[]> };
  images?: {
    mode: 'none' | 'illustration' | 'background';
    logoUrl?: string; darkLogoUrl?: string; heroUrl?: string; darkHeroUrl?: string;
    backgroundUrl?: string; darkBackgroundUrl?: string;
    focusX?: number; focusY?: number; mobileFocusX?: number; mobileFocusY?: number; overlay?: number;
  };
  theme?: { accent?: string; background?: string; radius?: 'square' | 'soft' | 'rounded'; mode?: 'light' | 'dark' | 'auto' };
  helpUrl?: string;
}

export function resolveAuthAppearance(value: unknown): AuthAppearance | undefined {
  if (value === undefined) return undefined;
  return validateAuthAppearance(value) as AuthAppearance;
}

/** Explicit hidden content never falls back to the platform or legacy story. */
export function resolveAuthContent<T>(field: AuthContent<T> | undefined, locale: string, defaultLocale: AuthLocale, fallback: T): T | undefined {
  if (!field || field.mode === 'default') return fallback;
  if (field.mode === 'hidden') return undefined;
  return field.values[locale as AuthLocale] ?? field.values[defaultLocale];
}

function luminance(hex: string): number {
  const rgb = hex.slice(1).match(/../g)!.map(value => parseInt(value, 16) / 255).map(value => value <= 0.04045 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4);
  return rgb[0] * 0.2126 + rgb[1] * 0.7152 + rgb[2] * 0.0722;
}

/** Choose a readable foreground even for very pale customer brand colors. */
export function authForeground(background: string): '#000000' | '#ffffff' {
  const light = luminance(background);
  return (light + 0.05) / 0.05 > 1.05 / (light + 0.05) ? '#000000' : '#ffffff';
}
