import { Link } from 'react-router';
import AuthAppearanceShell from './AuthAppearanceShell';
import IcpComplianceFooter from './IcpComplianceFooter';
import { useRootLoaderData } from '~/root-data';
import { COMMUNITY_BRANDING } from '~/config/branding';
import { useI18n } from '~/contexts/I18nContext';

/** Recovery remains administrator-mediated; appearance does not enable reset. */
export default function AuthRecoveryHelp({ testId }: { testId: string }) {
  const branding = useRootLoaderData()?.branding ?? COMMUNITY_BRANDING;
  const { t, locale } = useI18n();
  const zh = locale.startsWith('zh');
  const help = <div data-testid={testId}>
    <h2 className="mb-2 text-2xl font-semibold">{t('auth.appearance.resetUnavailable', undefined, zh ? '暂不支持自助重置密码' : 'Password reset unavailable')}</h2>
    <p className="mb-6">{t('auth.appearance.resetHelp', undefined, zh ? '请联系管理员设置或重置密码。' : 'Contact your tenant administrator to set or reset your password.')}</p>
    <Link to="/login" className="auth-primary-action inline-block px-6 py-2">{t('auth.appearance.backToLogin', undefined, zh ? '返回登录' : 'Back to Login')}</Link>
  </div>;
  if (branding.authAppearance) return <><AuthAppearanceShell branding={branding} appearance={branding.authAppearance}>{help}</AuthAppearanceShell><IcpComplianceFooter /></>;
  return <div className="flex min-h-screen items-center justify-center bg-gray-50"><div className="w-full max-w-md rounded-lg bg-white p-8 text-center shadow-md">{help}</div></div>;
}
