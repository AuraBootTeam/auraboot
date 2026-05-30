import {
  type ActionFunctionArgs,
  type LoaderFunctionArgs,
  Form,
  Link,
  useSearchParams,
  useActionData,
  useLoaderData,
  data,
  redirect,
} from 'react-router';
import { useCallback, useEffect, useRef, useState } from 'react';
import { createUserSession, getTokenFromRequest, sessionStorage } from '~/shared/services/session';
import { safeRedirect, validateEmail } from '~/utils/utils';
import { post, fetchResult } from '~/shared/services/http-client';
import { ResultHelper, type User } from '~/utils/type';
import { getUserInfo } from '~/shared/services/userService';
import { useI18n } from '~/contexts/I18nContext';

const REMEMBER_KEY = 'auth.remember';
const REMEMBER_EMAIL_KEY = 'auth.rememberedEmail';
const REMEMBER_PWD_KEY = 'auth.rememberedPwd';

const CHANNEL_I18N_KEYS: Record<string, string> = {
  EMAIL_PASSWORD: 'auth.channel.emailPassword',
  email_password: 'auth.channel.emailPassword',
  SMS: 'auth.channel.sms',
  sms: 'auth.channel.sms',
  EMAIL_CODE: 'auth.channel.emailCode',
  email_code: 'auth.channel.emailCode',
};

const SOCIAL_CHANNELS = ['wechat', 'google', 'apple', 'oidc'] as const;
const SOCIAL_I18N_KEYS: Record<string, string> = {
  wechat: 'auth.social.wechat',
  google: 'auth.social.google',
  apple: 'auth.social.apple',
  oidc: 'auth.social.oidc',
};

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  if (token) {
    const { user } = await getUserInfo(request);
    if (user) {
      return redirect('/');
    }
    const session = await sessionStorage.getSession(request.headers.get('Cookie'));
    return data(
      { channels: ['email_password'] },
      {
        headers: {
          'Set-Cookie': await sessionStorage.destroySession(session),
        },
      },
    );
  }

  // Fetch available login channels (public endpoint)
  let channels: string[] = ['email_password'];
  try {
    const result = await fetchResult<string[]>('/api/auth/login/channels', {}, request);
    if (ResultHelper.isSuccess(result) && Array.isArray(result.data) && result.data.length > 0) {
      channels = result.data;
    }
  } catch {
    // fallback to email/password only
  }

  return { channels };
};

function isMobileDevice(userAgent: string): boolean {
  return /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(userAgent);
}

export const action = async ({ request }: ActionFunctionArgs) => {
  const formData = await request.formData();
  const intent = formData.get('intent') as string;

  // Handle social OAuth callback — token already obtained by callback page
  if (intent === 'social-callback') {
    const token = formData.get('token') as string;
    const redirectTo = safeRedirect(formData.get('redirectTo'), '/');
    if (!token) {
      return data({ errors: { general: 'Missing token' } }, { status: 400 });
    }
    return createUserSession({
      request,
      token,
      remember: true,
      redirectTo,
    });
  }

  const channelCode = (formData.get('channelCode') as string) || 'email_password';
  const redirectTo = safeRedirect(formData.get('redirectTo'), '/');
  const remember = formData.get('remember');

  if (channelCode === 'email_password') {
    return handleEmailPasswordLogin(formData, request, redirectTo, remember === 'on');
  } else if (channelCode === 'sms') {
    return handleSmsLogin(formData, request, redirectTo, remember === 'on');
  } else if (channelCode === 'email_code') {
    return handleEmailCodeLogin(formData, request, redirectTo, remember === 'on');
  }

  return data({ errors: { general: 'Unsupported login method' } }, { status: 400 });
};

async function handleEmailPasswordLogin(
  formData: FormData,
  request: Request,
  redirectTo: string,
  remember: boolean,
) {
  const email = formData.get('email');
  const password = formData.get('password');

  if (!validateEmail(email)) {
    return data({ errors: { email: 'Email is invalid', password: null } }, { status: 400 });
  }

  if (typeof password !== 'string' || password.length === 0) {
    return data({ errors: { email: null, password: 'Password is required' } }, { status: 400 });
  }

  if (password.length < 6) {
    return data({ errors: { email: null, password: 'Password is too short' } }, { status: 400 });
  }

  const result = await post<User>(
    '/api/auth/login',
    { email: email as string, password: password as string },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return data({ errors: { general: 'auth.error.invalidCredentials' } }, { status: 400 });
  }

  return completeLogin(result.data as User, request, redirectTo, remember);
}

async function handleSmsLogin(
  formData: FormData,
  request: Request,
  redirectTo: string,
  remember: boolean,
) {
  const mobile = formData.get('mobile') as string;
  const code = formData.get('code') as string;

  if (!mobile || mobile.trim().length < 10) {
    return data({ errors: { mobile: 'auth.error.invalidMobile', code: null } }, { status: 400 });
  }
  if (!code || code.trim().length < 4) {
    return data({ errors: { mobile: null, code: 'auth.error.codeRequired' } }, { status: 400 });
  }

  const result = await post<User>(
    '/api/auth/login/sms',
    { mobile: mobile.trim(), code: code.trim() },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return data(
      { errors: { mobile: null, code: result.desc || result.message || 'auth.error.codeInvalid' } },
      { status: 400 },
    );
  }

  return completeLogin(result.data as User, request, redirectTo, remember);
}

async function handleEmailCodeLogin(
  formData: FormData,
  request: Request,
  redirectTo: string,
  remember: boolean,
) {
  const email = formData.get('email') as string;
  const code = formData.get('code') as string;

  if (!validateEmail(email)) {
    return data({ errors: { email: 'Email is invalid', code: null } }, { status: 400 });
  }
  if (!code || code.trim().length < 4) {
    return data({ errors: { email: null, code: 'auth.error.codeRequired' } }, { status: 400 });
  }

  const result = await post<User>(
    '/api/auth/login/email-code',
    { email: email.trim(), code: code.trim() },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return data(
      { errors: { email: null, code: result.desc || result.message || 'auth.error.codeInvalid' } },
      { status: 400 },
    );
  }

  return completeLogin(result.data as User, request, redirectTo, remember);
}

function completeLogin(user: User, request: Request, redirectTo: string, remember: boolean) {
  const token = user.jwt ?? '';
  if (!token) {
    return data({ errors: { general: 'Login failed, please retry later' } }, { status: 500 });
  }
  const tenantId = user.tenantId;
  const mustChangePassword = (user as any).mustChangePassword;
  if (!tenantId) {
    return createUserSession({
      request,
      token,
      remember,
      redirectTo: mustChangePassword
        ? '/personal/profile?forceChangePassword=true'
        : '/tenant-selection',
    });
  }
  const userAgent = request.headers.get('User-Agent') || '';
  const isMobile = isMobileDevice(userAgent);
  let finalRedirectTo = isMobile && redirectTo === '/' ? '/h5-scan' : redirectTo;
  if (mustChangePassword) {
    finalRedirectTo = '/personal/profile?forceChangePassword=true';
  }
  return createUserSession({
    request,
    token,
    remember,
    redirectTo: finalRedirectTo,
  });
}

// ============================================================
// Capability rows shown on the left brand panel (desktop only)
// ============================================================
interface CapabilityRow {
  key: string;
  title: string;
  desc: string;
  icon: React.ReactNode;
}

export default function LoginPage() {
  const { t } = useI18n();
  const [searchParams] = useSearchParams();
  const redirectTo = searchParams.get('redirectTo') || '/';
  const actionData = useActionData<typeof action>();
  const loaderData = useLoaderData<typeof loader>();
  const rawChannels = Array.isArray((loaderData as any)?.channels)
    ? ((loaderData as any).channels as unknown[])
    : ['email_password'];
  const channels: string[] = Array.from(
    new Set(rawChannels.map((channel) => String(channel || '').toLowerCase()).filter(Boolean)),
  );

  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const [isMobile, setIsMobile] = useState(false);
  const [hydrated, setHydrated] = useState(false);
  const [remember, setRemember] = useState(false);
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');

  // Determine which tab channels and social channels are available
  const tabChannels = channels.filter((c: string) => !SOCIAL_CHANNELS.includes(c as any));
  const socialChannels = channels.filter((c: string) => SOCIAL_CHANNELS.includes(c as any));
  const [activeTab, setActiveTab] = useState<string>(tabChannels[0] || 'email_password');

  useEffect(() => {
    if (tabChannels.length === 0) return;
    if (!tabChannels.includes(activeTab)) {
      setActiveTab(tabChannels[0] || 'email_password');
    }
  }, [activeTab, tabChannels]);

  useEffect(() => {
    const checkDevice = () => {
      const isMobileDevice = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(
        navigator.userAgent,
      );
      setIsMobile(isMobileDevice || window.innerWidth <= 768);
    };
    checkDevice();
    window.addEventListener('resize', checkDevice);
    return () => window.removeEventListener('resize', checkDevice);
  }, []);

  useEffect(() => {
    setHydrated(true);
  }, []);

  useEffect(() => {
    if (typeof window === 'undefined') return;
    const savedRemember = window.localStorage.getItem(REMEMBER_KEY);
    if (savedRemember === 'true') {
      setRemember(true);
      const savedEmail = window.localStorage.getItem(REMEMBER_EMAIL_KEY);
      const savedPwd = window.localStorage.getItem(REMEMBER_PWD_KEY);
      if (savedEmail) setEmail(savedEmail);
      if (savedPwd) {
        try {
          setPassword(atob(savedPwd));
        } catch {
          /* ignore corrupt data */
        }
      }
    }
  }, []);

  useEffect(() => {
    const errors = actionData?.errors as Record<string, string> | undefined;
    if (errors?.email) emailRef.current?.focus();
    else if (errors?.password) passwordRef.current?.focus();
  }, [actionData]);

  const tiles: CapabilityRow[] = [
    {
      key: 'designer',
      title: t('auth.feature.designer', undefined, '可视化页面设计器'),
      desc: t('auth.feature.designer.desc', undefined, '拖拽式可视化搭建,所见即所得'),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5">
          <rect x="3" y="3" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
          <rect x="14" y="3" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
          <rect x="3" y="14" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
          <rect x="14" y="14" width="7" height="7" rx="1.5" stroke="currentColor" strokeWidth="1.8" />
        </svg>
      ),
    },
    {
      key: 'dataModel',
      title: t('auth.feature.dataModel', undefined, '灵活数据模型'),
      desc: t('auth.feature.dataModel.desc', undefined, '动态建模,字段级权限与审计'),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5">
          <ellipse cx="12" cy="6" rx="8" ry="3" stroke="currentColor" strokeWidth="1.8" />
          <path d="M4 6v6c0 1.66 3.58 3 8 3s8-1.34 8-3V6M4 12v6c0 1.66 3.58 3 8 3s8-1.34 8-3v-6" stroke="currentColor" strokeWidth="1.8" />
        </svg>
      ),
    },
    {
      key: 'workflow',
      title: t('auth.feature.workflow', undefined, '工作流自动化'),
      desc: t('auth.feature.workflow.desc', undefined, 'BPMN 编排 + 自动化规则引擎'),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5">
          <circle cx="6" cy="6" r="2.5" stroke="currentColor" strokeWidth="1.8" />
          <circle cx="18" cy="6" r="2.5" stroke="currentColor" strokeWidth="1.8" />
          <circle cx="12" cy="18" r="2.5" stroke="currentColor" strokeWidth="1.8" />
          <path d="M8 7l3 9M16 7l-3 9" stroke="currentColor" strokeWidth="1.8" />
        </svg>
      ),
    },
    {
      key: 'aiAgent',
      title: t('auth.feature.aiAgent', undefined, 'AI Agent'),
      desc: t('auth.feature.aiAgent.desc', undefined, '租户内运行,可调用全部业务能力'),
      icon: (
        <svg viewBox="0 0 24 24" fill="none" className="h-5 w-5">
          <path d="M12 3l2 4 4 1-3 3 1 4-4-2-4 2 1-4-3-3 4-1 2-4z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
        </svg>
      ),
    },
  ];

  const oidcChannel = socialChannels.find((c: string) => c === 'oidc');
  const iconSocialChannels = socialChannels.filter((c: string) => c !== 'oidc');

  const card = (
    <div className="w-full max-w-[404px]">
      <div className="mb-8">
        <h1 className="text-[28px] font-extrabold tracking-tight text-[#15131C] lg:text-[30px] dark:text-white">
          {t('auth.welcome') || '欢迎回来'}
        </h1>
        <p className="mt-2 text-[15px] text-[#8A8694] dark:text-gray-400">
          {t('auth.welcomeSub', undefined, '登录以继续使用 AuraBoot 工作台')}
        </p>
      </div>

      {/* Tab Selector — only show if more than 1 tab channel */}
      {tabChannels.length > 1 && (
        <div
          role="tablist"
          aria-label="login channels"
          data-testid="login-channel-tabs"
          className="mb-6 flex rounded-xl bg-[#EEEDF2] p-1 dark:bg-gray-700/60"
        >
          {tabChannels.map((ch: string) => (
            <button
              key={ch}
              type="button"
              role="tab"
              aria-selected={activeTab === ch}
              data-testid={`login-tab-${ch.toLowerCase()}`}
              onClick={() => setActiveTab(ch)}
              className={`flex-1 rounded-lg py-2 text-sm font-semibold transition-all duration-150 ${
                activeTab === ch
                  ? 'bg-white text-[#4B3FE4] shadow-sm dark:bg-gray-600 dark:text-[#a99dff]'
                  : 'text-[#8A8694] hover:text-[#54505E] dark:text-gray-400 dark:hover:text-gray-300'
              }`}
            >
              {t(CHANNEL_I18N_KEYS[ch] || ch)}
            </button>
          ))}
        </div>
      )}

      {/* Active Form */}
      {activeTab === 'email_password' && (
        <EmailPasswordForm
          emailRef={emailRef}
          passwordRef={passwordRef}
          actionData={actionData}
          redirectTo={redirectTo}
          email={email}
          setEmail={setEmail}
          password={password}
          setPassword={setPassword}
          remember={remember}
          setRemember={setRemember}
          t={t}
        />
      )}
      {activeTab === 'sms' && (
        <SmsLoginForm
          actionData={actionData}
          redirectTo={redirectTo}
          remember={remember}
          setRemember={setRemember}
          t={t}
        />
      )}
      {activeTab === 'email_code' && (
        <EmailCodeLoginForm
          actionData={actionData}
          redirectTo={redirectTo}
          email={email}
          setEmail={setEmail}
          remember={remember}
          setRemember={setRemember}
          t={t}
        />
      )}

      {/* Social / SSO login (conditional) */}
      {socialChannels.length > 0 && (
        <div className="mt-6">
          <div className="flex items-center gap-3.5 text-[12.5px] text-[#B4B0BE] dark:text-gray-500">
            <span className="h-px flex-1 bg-[#E8E6EF] dark:bg-gray-700" />
            {t('auth.or', undefined, '或')}
            <span className="h-px flex-1 bg-[#E8E6EF] dark:bg-gray-700" />
          </div>

          {oidcChannel && (
            <button
              type="button"
              data-testid="login-sso-oidc"
              onClick={() => startSocialLogin('oidc')}
              className="mt-5 flex h-[50px] w-full items-center justify-center gap-2.5 rounded-[13px] border-[1.5px] border-[#E4E2EC] bg-white text-[14.5px] font-semibold text-[#54505E] transition hover:border-[#CFCCDA] hover:bg-[#FAFAFD] dark:border-gray-600 dark:bg-gray-700 dark:text-gray-200 dark:hover:bg-gray-600"
            >
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}>
                <path d="M12 2L4 6v6c0 5 3.5 9 8 10 4.5-1 8-5 8-10V6l-8-4z" strokeLinejoin="round" />
                <path d="M9 12l2 2 4-4" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
              {t('auth.ssoLogin', undefined, '使用企业 SSO 登录')}
            </button>
          )}

          {iconSocialChannels.length > 0 && (
            <div className="mt-3 flex justify-center gap-2.5">
              {iconSocialChannels.map((ch: string) => (
                <SocialLoginButton key={ch} provider={ch} />
              ))}
            </div>
          )}
        </div>
      )}

      {/* Sign up link */}
      <div className="mt-7 text-center text-[14px] text-[#8A8694] dark:text-gray-400">
        {t('auth.noAccount') || 'No account yet?'}{' '}
        <Link
          className="font-semibold text-[#4B3FE4] transition-colors hover:text-[#3B33C9] dark:text-[#a99dff] dark:hover:text-[#c4baff]"
          to={{ pathname: '/signup', search: searchParams.toString() }}
        >
          {t('auth.registerNow') || 'Sign Up'}
        </Link>
      </div>
    </div>
  );

  return (
    <div
      data-testid="login-page-root"
      data-hydrated={hydrated ? 'true' : 'false'}
      className="relative min-h-[calc(100vh-4rem)] bg-white dark:bg-gray-900"
    >
      {isMobile ? (
        // Mobile: single column with brand on top, card centered
        <div className="relative flex min-h-[calc(100vh-4rem)] flex-col items-center justify-center bg-[#F7F8FB] px-4 py-8 sm:px-6 dark:bg-gray-900">
          <div
            aria-hidden="true"
            className="pointer-events-none absolute inset-0 bg-[radial-gradient(600px_400px_at_50%_0%,rgba(124,92,255,0.10),transparent_60%)]"
          />
          <div className="relative mb-6 flex items-center gap-2.5">
            <img
              src="/android-chrome-192x192.png"
              alt="AuraBoot"
              className="h-9 w-9 rounded-lg shadow-sm"
            />
            <span className="text-xl font-semibold tracking-tight text-gray-900 dark:text-white">
              AuraBoot
            </span>
          </div>
          <div className="relative w-full max-w-md rounded-2xl border border-gray-200/80 bg-white p-6 shadow-[0_24px_60px_-20px_rgba(30,40,80,0.18),0_6px_16px_rgba(30,40,80,0.06)] dark:border-gray-700 dark:bg-gray-800">
            {card}
          </div>
        </div>
      ) : (
        // Desktop: full-bleed 2-col, left brand region + right form region
        <div className="grid min-h-[calc(100vh-4rem)] lg:grid-cols-[1.15fr_1fr]">
          {/* Left: brand / positioning region */}
          <section className="flex flex-col justify-center bg-white px-8 py-12 lg:px-14 xl:px-20 2xl:px-24 dark:bg-gray-900">
            <div className="flex w-full max-w-[600px] flex-col">
              {/* Badge */}
              <span className="inline-flex items-center gap-2 self-start rounded-full bg-[#F3F2FE] px-3.5 py-1.5 text-[13px] font-semibold text-[#4B3FE4] dark:bg-[#4B3FE4]/15 dark:text-[#a99dff]">
                <span className="h-1.5 w-1.5 rounded-full bg-[#4B3FE4]" />
                {t('auth.badge', undefined, 'AI 原生 · 企业应用运行时')}
              </span>

              {/* Headline with brand-colored highlight word */}
              <h1 className="mt-8 max-w-[580px] text-balance text-[34px] font-extrabold leading-[1.16] tracking-tight text-[#15131C] lg:text-[44px] xl:text-[50px] dark:text-white">
                {t('auth.headline.pre', undefined, '把企业系统,建成')}
                <span className="mx-2 text-[#4B3FE4] dark:text-[#8d7fff]">
                  {t('auth.headline.em', undefined, 'AI 能直接操作')}
                </span>
                {t('auth.headline.post', undefined, '的样子')}
              </h1>

              {/* Lead */}
              <p className="mt-5 max-w-[480px] text-[15px] leading-[1.7] text-[#54505E] xl:text-[17px] dark:text-gray-400">
                {t(
                  'auth.lead',
                  undefined,
                  '可视化建模、页面与流程编排,统一沉淀为命令能力——人来点、AI 也能调;一次配置,Web、移动端与小程序全端交付。',
                )}
              </p>

              {/* Feature list */}
              <div className="mt-10 max-w-[540px]">
                {tiles.map((tile) => (
                  <div
                    key={tile.key}
                    className="flex items-start gap-4 border-t border-[#EEEDF2] py-5 last:border-b dark:border-gray-800"
                  >
                    <div className="flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-[#F3F2FE] text-[#4B3FE4] dark:bg-[#4B3FE4]/15 dark:text-[#a99dff]">
                      {tile.icon}
                    </div>
                    <div>
                      <div className="text-base font-bold text-[#15131C] dark:text-white">
                        {tile.title}
                      </div>
                      <div className="mt-1 text-[13.5px] text-[#8A8694] dark:text-gray-500">
                        {tile.desc}
                      </div>
                    </div>
                  </div>
                ))}
              </div>

              {/* Trust pillars */}
              <div className="mt-9 flex flex-wrap items-center gap-x-4 gap-y-2 text-[13.5px] font-medium text-[#54505E] dark:text-gray-400">
                <span>{t('auth.pillar.model', undefined, '模型驱动')}</span>
                <span className="h-1 w-1 rounded-full bg-[#D6D3E0] dark:bg-gray-600" />
                <span>{t('auth.pillar.command', undefined, '命令治理')}</span>
                <span className="h-1 w-1 rounded-full bg-[#D6D3E0] dark:bg-gray-600" />
                <span>{t('auth.pillar.plugin', undefined, '插件交付')}</span>
              </div>
            </div>
          </section>

          {/* Right: form region */}
          <section className="flex items-center justify-center border-l border-[#EEEDF2] bg-[#F7F7FB] px-6 py-14 lg:px-12 xl:px-16 dark:border-gray-800 dark:bg-gray-800">
            <div className="w-full max-w-[404px]">{card}</div>
          </section>
        </div>
      )}
    </div>
  );
}

// ============================================================
// Sub-components
// ============================================================

const INPUT_CLS =
  'h-[52px] w-full rounded-[13px] border-[1.5px] border-[#E4E2EC] bg-white px-4 text-[15px] text-[#15131C] placeholder-[#B4B0BE] outline-none transition focus:border-[#4B3FE4] focus:ring-4 focus:ring-[#4B3FE4]/15 dark:border-gray-600 dark:bg-gray-700/60 dark:text-white dark:placeholder-gray-500';

const SUBMIT_CLS =
  'h-[52px] w-full rounded-[13px] bg-[#4B3FE4] text-[15.5px] font-bold text-white shadow-[0_8px_22px_-8px_rgba(75,63,228,0.6)] transition-all duration-150 hover:-translate-y-px hover:bg-[#3B33C9] hover:shadow-[0_12px_26px_-8px_rgba(75,63,228,0.65)] focus:outline-none focus:ring-4 focus:ring-[#4B3FE4]/30';

const LABEL_CLS = 'mb-2 block text-[13px] font-semibold text-[#54505E] dark:text-gray-300';

const SECONDARY_BTN_CLS =
  'flex-shrink-0 rounded-[11px] border px-3.5 text-xs font-semibold transition-colors';

// Start an OAuth/SSO social login by resolving the provider authorize URL.
async function startSocialLogin(provider: string) {
  try {
    const redirectUri = `${window.location.origin}/login/social/${provider.toLowerCase()}/callback`;
    const result = await fetchResult<{ authorizeUrl: string }>(
      `/api/auth/login/social/${provider.toLowerCase()}`,
      { method: 'get', params: { redirectUri } },
    );
    if (ResultHelper.isSuccess(result) && result.data?.authorizeUrl) {
      window.location.href = result.data.authorizeUrl;
    }
  } catch {
    /* ignore */
  }
}

function ErrorBanner({ message, t }: { message: string; t: (key: string) => string }) {
  return (
    <div
      role="alert"
      data-testid="login-error"
      className="flex items-center gap-2.5 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 dark:border-red-800 dark:bg-red-900/20 dark:text-red-300"
    >
      <svg className="h-4 w-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          strokeWidth={2}
          d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
        />
      </svg>
      <p className="font-medium">
        {message === 'auth.error.invalidCredentials'
          ? t('auth.error.invalidCredentials') || 'Invalid email or password, please try again'
          : t(message) || message}
      </p>
    </div>
  );
}

function EmailPasswordForm({
  emailRef,
  passwordRef,
  actionData,
  redirectTo,
  email,
  setEmail,
  password,
  setPassword,
  remember,
  setRemember,
  t,
}: {
  emailRef: React.RefObject<HTMLInputElement | null>;
  passwordRef: React.RefObject<HTMLInputElement | null>;
  actionData: any;
  redirectTo: string;
  email: string;
  setEmail: (v: string) => void;
  password: string;
  setPassword: (v: string) => void;
  remember: boolean;
  setRemember: (v: boolean) => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const [showPwd, setShowPwd] = useState(false);
  return (
    <Form
      method="post"
      action="/login"
      reloadDocument
      onSubmit={(e) => {
        if (typeof window === 'undefined') return;
        const formData = new FormData(e.currentTarget);
        const trimmedEmail = (formData.get('email') as string)?.trim() || '';
        const pwd = (formData.get('password') as string) || '';
        const isRemember = (e.currentTarget.elements.namedItem('remember') as HTMLInputElement)
          ?.checked;
        if (isRemember && trimmedEmail) {
          window.localStorage.setItem(REMEMBER_KEY, 'true');
          window.localStorage.setItem(REMEMBER_EMAIL_KEY, trimmedEmail);
          window.localStorage.setItem(REMEMBER_PWD_KEY, btoa(pwd));
        } else {
          window.localStorage.removeItem(REMEMBER_KEY);
          window.localStorage.removeItem(REMEMBER_EMAIL_KEY);
          window.localStorage.removeItem(REMEMBER_PWD_KEY);
        }
      }}
      className="space-y-5"
    >
      <input type="hidden" name="channelCode" value="email_password" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      {actionData?.errors?.general && <ErrorBanner message={actionData.errors.general} t={t} />}

      <div>
        <label htmlFor="email" className={LABEL_CLS}>
          {t('auth.email') || 'Email'}
        </label>
        <input
          ref={emailRef}
          id="email"
          required
          autoFocus
          name="email"
          type="email"
          autoComplete="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder={t('auth.emailPlaceholder') || 'you@company.com'}
          aria-invalid={actionData?.errors?.email ? true : undefined}
          className={INPUT_CLS}
        />
        {actionData?.errors?.email && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.email}
          </div>
        )}
      </div>

      <div>
        <label htmlFor="password" className={LABEL_CLS}>
          {t('auth.password') || 'Password'}
        </label>
        <div className="relative">
          <input
            ref={passwordRef}
            id="password"
            name="password"
            type={showPwd ? 'text' : 'password'}
            autoComplete="current-password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            placeholder={t('auth.passwordPlaceholder') || '••••••••'}
            aria-invalid={actionData?.errors?.password ? true : undefined}
            className={`${INPUT_CLS} pr-12`}
          />
          <button
            type="button"
            data-testid="login-toggle-password"
            onClick={() => setShowPwd((v) => !v)}
            aria-label={t(showPwd ? 'auth.hidePassword' : 'auth.showPassword', undefined, showPwd ? '隐藏密码' : '显示密码')}
            className="absolute top-1/2 right-2.5 flex h-9 w-9 -translate-y-1/2 items-center justify-center rounded-lg text-[#9A96A4] transition-colors hover:bg-[#F1F0F8] hover:text-[#4B3FE4] dark:hover:bg-gray-600"
          >
            {showPwd ? (
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}>
                <path d="M3 3l18 18M10.6 10.6a2 2 0 002.8 2.8" strokeLinecap="round" strokeLinejoin="round" />
                <path d="M9.4 5.2A9.5 9.5 0 0112 5c5 0 9 4.5 9 7a12 12 0 01-2.2 3M6.3 6.3A12.4 12.4 0 003 12c0 2.5 4 7 9 7a9.6 9.6 0 004.2-1" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            ) : (
              <svg className="h-5 w-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.8}>
                <path d="M2.5 12S6 5 12 5s9.5 7 9.5 7-3.5 7-9.5 7-9.5-7-9.5-7z" strokeLinejoin="round" />
                <circle cx="12" cy="12" r="2.8" />
              </svg>
            )}
          </button>
        </div>
        {actionData?.errors?.password && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.password}
          </div>
        )}
      </div>

      <div className="flex items-center justify-between">
        <label className="inline-flex cursor-pointer items-center gap-2.5 text-[14px] text-[#54505E] select-none dark:text-gray-400">
          <input
            id="remember"
            name="remember"
            type="checkbox"
            checked={remember}
            onChange={(e) => setRemember(e.target.checked)}
            className="h-[18px] w-[18px] rounded-[6px] border-[1.5px] border-[#CFCCDA] text-[#4B3FE4] focus:ring-[#4B3FE4] dark:border-gray-600"
          />
          {t('auth.rememberMe') || 'Remember me'}
        </label>
        <Link
          to="/forgot-password"
          className="text-[14px] font-medium text-[#4B3FE4] hover:underline dark:text-[#a99dff]"
        >
          {t('auth.forgotPassword') || 'Forgot?'}
        </Link>
      </div>

      <button type="submit" className={SUBMIT_CLS}>
        {t('auth.loginNow') || 'Sign In'}
      </button>
    </Form>
  );
}

function SmsLoginForm({
  actionData,
  redirectTo,
  remember,
  setRemember,
  t,
}: {
  actionData: any;
  redirectTo: string;
  remember: boolean;
  setRemember: (v: boolean) => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const [mobile, setMobile] = useState('');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = setTimeout(() => setCountdown(countdown - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown]);

  const sendCode = useCallback(async () => {
    if (!mobile || mobile.trim().length < 10) return;
    setSending(true);
    try {
      const result = await fetchResult<void>('/api/auth/verify-code/send', {
        method: 'post',
        params: { target: mobile.trim(), type: 'login' },
      });
      if (ResultHelper.isSuccess(result)) {
        setCountdown(60);
      }
    } catch {
      /* ignore */
    }
    setSending(false);
  }, [mobile]);

  return (
    <Form method="post" action="/login" reloadDocument className="space-y-5">
      <input type="hidden" name="channelCode" value="sms" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      <div>
        <label htmlFor="mobile" className={LABEL_CLS}>
          {t('auth.mobile') || 'Mobile'}
        </label>
        <input
          id="mobile"
          name="mobile"
          type="tel"
          autoComplete="tel"
          required
          autoFocus
          value={mobile}
          onChange={(e) => setMobile(e.target.value)}
          placeholder={t('auth.mobilePlaceholder') || 'Enter your mobile number'}
          className={INPUT_CLS}
        />
        {actionData?.errors?.mobile && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.mobile}
          </div>
        )}
      </div>

      <div>
        <label htmlFor="sms-code" className={LABEL_CLS}>
          {t('auth.verifyCode') || 'Verification Code'}
        </label>
        <div className="flex gap-2">
          <input
            id="sms-code"
            name="code"
            type="text"
            inputMode="numeric"
            maxLength={6}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('auth.codePlaceholder') || '6-digit code'}
            className={INPUT_CLS}
          />
          <button
            type="button"
            data-testid="email-code-send"
            onClick={sendCode}
            disabled={countdown > 0 || sending || mobile.trim().length < 10}
            className={`${SECONDARY_BTN_CLS} h-[52px] ${
              countdown > 0 || sending || mobile.trim().length < 10
                ? 'cursor-not-allowed border-[#E4E2EC] text-[#B4B0BE] dark:border-gray-700 dark:text-gray-500'
                : 'border-[#4B3FE4] text-[#4B3FE4] hover:bg-[#F3F2FE] dark:hover:bg-[#4B3FE4]/15'
            }`}
          >
            {countdown > 0
              ? `${countdown}s`
              : sending
                ? t('auth.sending') || 'Sending...'
                : t('auth.getCode') || 'Get Code'}
          </button>
        </div>
        {actionData?.errors?.code && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.code}
          </div>
        )}
      </div>

      <div className="flex items-center">
        <label className="inline-flex cursor-pointer items-center gap-2.5 text-[14px] text-[#54505E] select-none dark:text-gray-400">
          <input
            id="sms-remember"
            name="remember"
            type="checkbox"
            checked={remember}
            onChange={(e) => setRemember(e.target.checked)}
            className="h-[18px] w-[18px] rounded-[6px] border-[1.5px] border-[#CFCCDA] text-[#4B3FE4] focus:ring-[#4B3FE4] dark:border-gray-600"
          />
          {t('auth.rememberMe') || 'Remember me'}
        </label>
      </div>

      <button type="submit" className={SUBMIT_CLS}>
        {t('auth.loginNow') || 'Sign In'}
      </button>
    </Form>
  );
}

function EmailCodeLoginForm({
  actionData,
  redirectTo,
  email,
  setEmail,
  remember,
  setRemember,
  t,
}: {
  actionData: any;
  redirectTo: string;
  email: string;
  setEmail: (v: string) => void;
  remember: boolean;
  setRemember: (v: boolean) => void;
  t: (key: string, params?: Record<string, any>, fallback?: string) => string;
}) {
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);

  useEffect(() => {
    if (countdown <= 0) return;
    const timer = setTimeout(() => setCountdown(countdown - 1), 1000);
    return () => clearTimeout(timer);
  }, [countdown]);

  const sendCode = useCallback(async () => {
    if (!validateEmail(email)) return;
    setSending(true);
    try {
      const result = await fetchResult<void>('/api/auth/verify-code/send', {
        method: 'post',
        params: { target: email.trim(), type: 'login' },
      });
      if (ResultHelper.isSuccess(result)) {
        setCountdown(60);
      }
    } catch {
      /* ignore */
    }
    setSending(false);
  }, [email]);

  return (
    <Form method="post" action="/login" reloadDocument className="space-y-5">
      <input type="hidden" name="channelCode" value="email_code" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      <div>
        <label htmlFor="ec-email" className={LABEL_CLS}>
          {t('auth.email') || 'Email'}
        </label>
        <input
          id="ec-email"
          name="email"
          type="email"
          autoComplete="email"
          required
          autoFocus
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          placeholder={t('auth.emailPlaceholder') || 'you@company.com'}
          className={INPUT_CLS}
        />
        {actionData?.errors?.email && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.email}
          </div>
        )}
      </div>

      <div>
        <label htmlFor="ec-code" className={LABEL_CLS}>
          {t('auth.verifyCode') || 'Verification Code'}
        </label>
        <div className="flex gap-2">
          <input
            id="ec-code"
            name="code"
            type="text"
            inputMode="numeric"
            maxLength={6}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('auth.codePlaceholder') || '6-digit code'}
            className={INPUT_CLS}
          />
          <button
            type="button"
            onClick={sendCode}
            disabled={countdown > 0 || sending || !validateEmail(email)}
            className={`${SECONDARY_BTN_CLS} h-[52px] ${
              countdown > 0 || sending || !validateEmail(email)
                ? 'cursor-not-allowed border-[#E4E2EC] text-[#B4B0BE] dark:border-gray-700 dark:text-gray-500'
                : 'border-[#4B3FE4] text-[#4B3FE4] hover:bg-[#F3F2FE] dark:hover:bg-[#4B3FE4]/15'
            }`}
          >
            {countdown > 0
              ? `${countdown}s`
              : sending
                ? t('auth.sending') || 'Sending...'
                : t('auth.getCode') || 'Get Code'}
          </button>
        </div>
        {actionData?.errors?.code && (
          <div className="mt-1.5 text-xs text-red-600 dark:text-red-400">
            {actionData.errors.code}
          </div>
        )}
      </div>

      <div className="flex items-center">
        <label className="inline-flex cursor-pointer items-center gap-2.5 text-[14px] text-[#54505E] select-none dark:text-gray-400">
          <input
            id="ec-remember"
            name="remember"
            type="checkbox"
            checked={remember}
            onChange={(e) => setRemember(e.target.checked)}
            className="h-[18px] w-[18px] rounded-[6px] border-[1.5px] border-[#CFCCDA] text-[#4B3FE4] focus:ring-[#4B3FE4] dark:border-gray-600"
          />
          {t('auth.rememberMe') || 'Remember me'}
        </label>
      </div>

      <button type="submit" className={SUBMIT_CLS}>
        {t('auth.loginNow') || 'Sign In'}
      </button>
    </Form>
  );
}

function SocialLoginButton({ provider }: { provider: string }) {
  const { t } = useI18n();
  const handleClick = useCallback(() => startSocialLogin(provider), [provider]);

  const icons: Record<string, React.ReactNode> = {
    wechat: (
      <svg className="h-5 w-5" viewBox="0 0 24 24" fill="#07C160">
        <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.045.245.245 0 0 0 .241-.245c0-.06-.024-.12-.04-.178l-.325-1.233a.492.492 0 0 1 .178-.554C23.362 18.48 24 17.168 24 15.753c0-2.967-2.443-5.476-6.062-5.895a9.9 9.9 0 0 0-1-.001zm-1.834 2.686c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.857 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.969-.982z" />
      </svg>
    ),
    google: (
      <svg className="h-5 w-5" viewBox="0 0 24 24">
        <path
          d="M22.56 12.25c0-.78-.07-1.53-.2-2.25H12v4.26h5.92a5.06 5.06 0 0 1-2.2 3.32v2.77h3.57c2.08-1.92 3.28-4.74 3.28-8.1z"
          fill="#4285F4"
        />
        <path
          d="M12 23c2.97 0 5.46-.98 7.28-2.66l-3.57-2.77c-.98.66-2.23 1.06-3.71 1.06-2.86 0-5.29-1.93-6.16-4.53H2.18v2.84C3.99 20.53 7.7 23 12 23z"
          fill="#34A853"
        />
        <path
          d="M5.84 14.09c-.22-.66-.35-1.36-.35-2.09s.13-1.43.35-2.09V7.07H2.18C1.43 8.55 1 10.22 1 12s.43 3.45 1.18 4.93l2.85-2.22.81-.62z"
          fill="#FBBC05"
        />
        <path
          d="M12 5.38c1.62 0 3.06.56 4.21 1.64l3.15-3.15C17.45 2.09 14.97 1 12 1 7.7 1 3.99 3.47 2.18 7.07l3.66 2.84c.87-2.6 3.3-4.53 6.16-4.53z"
          fill="#EA4335"
        />
      </svg>
    ),
    apple: (
      <svg className="h-5 w-5" viewBox="0 0 24 24" fill="currentColor">
        <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
      </svg>
    ),
    oidc: (
      <svg
        className="h-5 w-5"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={1.8}
      >
        <path d="M12 2L4 6v6c0 5 3.5 9 8 10 4.5-1 8-5 8-10V6l-8-4z" strokeLinejoin="round" />
        <path d="M9 12l2 2 4-4" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
    ),
  };

  const title = SOCIAL_I18N_KEYS[provider] ? t(SOCIAL_I18N_KEYS[provider]) : provider;

  return (
    <button
      type="button"
      onClick={handleClick}
      className="inline-flex h-11 w-11 items-center justify-center rounded-xl border-[1.5px] border-[#E4E2EC] bg-white text-[#54505E] transition-colors hover:border-[#CFCCDA] hover:bg-[#FAFAFD] hover:text-[#15131C] dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:bg-gray-600"
      title={title || provider}
      aria-label={title || provider}
    >
      {icons[provider.toLowerCase()] || (
        <span className="text-[10px] font-bold uppercase">{provider.substring(0, 2)}</span>
      )}
    </button>
  );
}
