import { useEffect, useRef, useState, type CSSProperties, type ReactNode } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { useTheme } from '~/contexts/ThemeContext';
import { type BrandingConfig } from '~/config/branding';
import { authForeground, resolveAuthContent, type AuthAppearance } from '~/config/auth-appearance';
import './auth-appearance.css';

type AuthStyle = CSSProperties & Record<`--auth-${string}`, string>;
export interface AuthAppearanceShellProps {
  branding: BrandingConfig;
  appearance: AuthAppearance;
  children: ReactNode;
  displayName?: string;
  previewTheme?: 'light' | 'dark';
}

/** Pure presentation shared by live authentication and the draft preview. */
export default function AuthAppearanceShell({ branding, appearance, children, displayName, previewTheme }: AuthAppearanceShellProps) {
  const { t, locale } = useI18n();
  const zh = locale.startsWith('zh');
  const { isDark } = useTheme();
  const dark = previewTheme ? previewTheme === 'dark' : appearance.theme?.mode === 'dark' || (appearance.theme?.mode !== 'light' && isDark);
  const content = appearance.content;
  const copy = (key: 'badge' | 'headline' | 'headlineEm' | 'lead' | 'mobileLead', fallback: string) => resolveAuthContent(content?.[key], locale, appearance.defaultLocale, fallback);
  const badge = copy('badge', branding.loginBadge ?? t('auth.badge', undefined, zh ? 'AI 原生 · 企业应用运行时' : 'AI-native enterprise application runtime'));
  const headline = copy('headline', branding.loginHeadline ?? t('auth.headline.pre', undefined, zh ? '配置即应用,' : 'Configure your application,'));
  const emphasis = copy('headlineEm', branding.loginHeadlineEm ?? t('auth.headline.em', undefined, zh ? 'AI 即战力' : 'put AI to work'));
  const lead = copy('lead', branding.loginLead ?? t('auth.lead', undefined, zh ? '把每个业务能力沉淀为命令——人能点、AI 能调、全端可交付。' : 'Turn business capabilities into commands that teams and AI can use across every client.'));
  const mobileLead = copy('mobileLead', '');
  const features = resolveAuthContent(content?.features, locale, appearance.defaultLocale, branding.loginFeatures ?? [
    t('auth.feature.designer', undefined, '拖拽即页面'),
    t('auth.feature.dataModel', undefined, '建模即数据'),
    t('auth.feature.workflow', undefined, '画布即流程'),
  ]);
  const images = appearance.images;
  const logo = (dark ? images?.darkLogoUrl : undefined) ?? images?.logoUrl ?? branding.logoUrl;
  const hero = (dark ? images?.darkHeroUrl : undefined) ?? images?.heroUrl;
  const background = (dark ? images?.darkBackgroundUrl : undefined) ?? images?.backgroundUrl;
  const accent = appearance.theme?.accent ?? '#35745b';
  const surface = dark ? '#111827' : (appearance.theme?.background ?? '#f6f8f3');
  const style: AuthStyle = {
    '--auth-accent': accent,
    '--auth-on-accent': authForeground(accent),
    '--auth-bg': surface,
    '--auth-bg-text': authForeground(surface),
    '--auth-focus-x': `${images?.focusX ?? 50}%`,
    '--auth-focus-y': `${images?.focusY ?? 50}%`,
    '--auth-mobile-focus-x': `${images?.mobileFocusX ?? images?.focusX ?? 50}%`,
    '--auth-mobile-focus-y': `${images?.mobileFocusY ?? images?.focusY ?? 50}%`,
    '--auth-overlay': String(images?.overlay ?? 0.35),
    '--auth-radius': appearance.theme?.radius === 'square' ? 'var(--radius-control, 6px)' : appearance.theme?.radius === 'rounded' ? 'var(--radius-card-lg, 16px)' : 'var(--radius-card, 10px)',
  };

  return (
    <div className="auth-appearance" style={style} data-auth-template={appearance.template} data-auth-theme={dark ? 'dark' : 'light'} data-brand-position={appearance.brandPosition ?? 'left'} data-card-position={appearance.cardPosition ?? 'center'}>
      {appearance.template === 'background' && background && <div className="auth-background" aria-hidden="true"><DecorativeImage src={background} /><div className="auth-background-overlay" /></div>}
      <div className="auth-appearance-layout">
        {appearance.template === 'split' && <section className="auth-brand-story">
          <BrandMark logo={logo} name={displayName ?? branding.productName} />
          {badge && <p className="auth-brand-badge">{badge}</p>}
          {(headline || emphasis) && <h1 className="auth-brand-headline">{headline}{emphasis && <span>{emphasis}</span>}</h1>}
          {lead && <p className="auth-brand-lead">{lead}</p>}
          {images?.mode === 'illustration' && hero && <DecorativeImage className="auth-brand-hero" src={hero} />}
          {features && <ul className="auth-brand-features">{features.map(feature => <li key={feature}>{feature}</li>)}</ul>}
        </section>}
        <section className="auth-form-region">
          <div className="auth-card">
            <div className="auth-compact-brand"><BrandMark logo={logo} name={displayName ?? branding.productName} />{mobileLead && <p>{mobileLead}</p>}</div>
            <div className="auth-appearance-form">{children}</div>
            {appearance.helpUrl && <a className="auth-help" href={appearance.helpUrl}>{t('auth.appearance.help', undefined, locale.startsWith('zh') ? '获取帮助' : 'Get help')}</a>}
          </div>
        </section>
      </div>
    </div>
  );
}

function BrandMark({ logo, name }: { logo: string; name: string }) {
  return <div className="auth-brand-mark"><DecorativeImage src={logo} /><span>{name}</span></div>;
}

function DecorativeImage({ src, className }: { src: string; className?: string }) {
  const [failedSource, setFailedSource] = useState<string>();
  const element = useRef<HTMLImageElement>(null);
  useEffect(() => {
    // A cached image can fail before React hydrates and attaches onError.
    if (element.current?.complete && element.current.naturalWidth === 0) setFailedSource(src);
  }, [src]);
  return failedSource === src ? null : <img ref={element} src={src} className={className} alt="" aria-hidden="true" onError={() => setFailedSource(src)} />;
}
