import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import AuthAppearanceShell from '../AuthAppearanceShell';
import { COMMUNITY_BRANDING } from '~/config/branding';
import type { AuthAppearance } from '~/config/auth-appearance';
vi.mock('~/contexts/I18nContext', () => ({ useI18n: () => ({ locale: 'en-US', t: (_key: string, _params: unknown, fallback: string) => fallback }) }));
const applicationTheme = vi.hoisted(() => ({ isDark: false }));
vi.mock('~/contexts/ThemeContext', () => ({ useTheme: () => applicationTheme }));
const base: AuthAppearance = { version: 1, template: 'split', defaultLocale: 'zh-CN' };
const brand = { ...COMMUNITY_BRANDING, mode: 'commercial' as const, productName: 'School', loginBadge: 'Legacy badge', loginHeadline: 'Legacy title', loginLead: 'Legacy lead' };
function show(appearance: AuthAppearance, previewTheme?: 'light' | 'dark') {
  return render(<AuthAppearanceShell branding={brand} appearance={appearance} previewTheme={previewTheme}><form><input aria-label="Account" /><button type="submit">Sign in</button></form></AuthAppearanceShell>);
}
describe('authentication appearance presentation', () => {
  it('uses English default brand copy when English translations are unavailable', () => {
    render(<AuthAppearanceShell branding={COMMUNITY_BRANDING} appearance={base}><form /></AuthAppearanceShell>);
    expect(screen.getByText('AI-native enterprise application runtime')).toBeVisible();
    expect(screen.getByText('Turn business capabilities into commands that teams and AI can use across every client.')).toBeVisible();
  });
  it('renders legacy story and the real form inside split mode', () => {
    show(base);
    expect(screen.getByText('Legacy badge')).toBeVisible();
    expect(screen.getByText('Legacy title')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeVisible();
  });
  it('never restores hidden legacy content', () => {
    show({ ...base, content: { badge: { mode: 'hidden' }, headline: { mode: 'hidden' }, headlineEm: { mode: 'hidden' }, lead: { mode: 'hidden' }, features: { mode: 'hidden' } } });
    expect(screen.queryByText('Legacy badge')).not.toBeInTheDocument();
    expect(screen.queryByText('Legacy title')).not.toBeInTheDocument();
    expect(screen.queryByRole('list')).not.toBeInTheDocument();
  });
  it('resolves current language and the explicit language fallback', () => {
    show({ ...base, content: { headline: { mode: 'custom', values: { 'zh-CN': '学校', 'en-US': 'School day' } }, lead: { mode: 'custom', values: { 'zh-CN': '欢迎' } } } });
    expect(screen.getByText('School day')).toBeVisible();
    expect(screen.getByText('欢迎')).toBeVisible();
  });
  it('uses dark assets in preview without changing the application theme', () => {
    const { container } = show({ ...base, images: { mode: 'illustration', heroUrl: '/light.webp', darkHeroUrl: '/dark.webp', logoUrl: '/logo.svg', darkLogoUrl: '/logo-dark.svg' } }, 'dark');
    expect(container.querySelector('.auth-brand-hero')).toHaveAttribute('src', '/dark.webp');
    expect(container.querySelector('.auth-brand-mark img')).toHaveAttribute('src', '/logo-dark.svg');
    expect(document.documentElement).not.toHaveClass('dark');
  });
  it('keeps the form and brand name usable after broken image resources', () => {
    const { container } = show({ ...base, template: 'background', images: { mode: 'background', backgroundUrl: '/missing.webp' } });
    const img = container.querySelector('.auth-background img')!;
    fireEvent.error(img);
    expect(container.querySelector('.auth-background img')).toBeNull();
    expect(screen.getByText('School')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled();
  });
  it('does not render the story in centered mode', () => {
    show({ ...base, template: 'centered' });
    expect(screen.queryByText('Legacy title')).not.toBeInTheDocument();
    expect(screen.getByText('School')).toBeVisible();
  });
  it('honors a forced light authentication theme while the application is dark', () => {
    applicationTheme.isDark = true;
    try {
      const { container } = show({ ...base, theme: { mode: 'light', background: '#fafafa' } });
      expect(container.querySelector('[data-auth-theme]')).toHaveAttribute('data-auth-theme', 'light');
      expect((container.firstChild as HTMLElement).style.getPropertyValue('--auth-bg')).toBe('#fafafa');
      expect(applicationTheme.isDark).toBe(true);
    } finally { applicationTheme.isDark = false; }
  });

});
