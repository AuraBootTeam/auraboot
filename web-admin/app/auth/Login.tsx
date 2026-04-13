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

import type { User } from '~/utils/type';

import { createUserSession, getTokenFromRequest, sessionStorage } from '~/shared/services/session';
import { safeRedirect, validateEmail } from '~/utils/utils';
import { post, fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
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
  WECHAT: 'auth.social.wechat',
  OIDC: 'auth.social.oidc',
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
      return { errors: { general: 'Missing token' }, status: 400 };
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

  return { errors: { general: 'Unsupported login method' }, status: 400 };
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
    return { errors: { email: 'Email is invalid', password: null }, status: 400 };
  }

  if (typeof password !== 'string' || password.length === 0) {
    return { errors: { email: null, password: 'Password is required' }, status: 400 };
  }

  if (password.length < 6) {
    return { errors: { email: null, password: 'Password is too short' }, status: 400 };
  }

  const result = await post<User>(
    '/api/auth/login',
    { email: email as string, password: password as string },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return { errors: { general: 'auth.error.invalidCredentials' }, status: 400 };
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
    return { errors: { mobile: 'auth.error.invalidMobile', code: null }, status: 400 };
  }
  if (!code || code.trim().length < 4) {
    return { errors: { mobile: null, code: 'auth.error.codeRequired' }, status: 400 };
  }

  const result = await post<User>(
    '/api/auth/login/sms',
    { mobile: mobile.trim(), code: code.trim() },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return {
      errors: { mobile: null, code: result.desc || result.message || 'auth.error.codeInvalid' },
      status: 400,
    };
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
    return { errors: { email: 'Email is invalid', code: null }, status: 400 };
  }
  if (!code || code.trim().length < 4) {
    return { errors: { email: null, code: 'auth.error.codeRequired' }, status: 400 };
  }

  const result = await post<User>(
    '/api/auth/login/email-code',
    { email: email.trim(), code: code.trim() },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return {
      errors: { email: null, code: result.desc || result.message || 'auth.error.codeInvalid' },
      status: 400,
    };
  }

  return completeLogin(result.data as User, request, redirectTo, remember);
}

function completeLogin(user: User, request: Request, redirectTo: string, remember: boolean) {
  const token = user.jwt ?? '';
  if (!token) {
    return { errors: { general: 'Login failed, please retry later' }, status: 500 };
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

export default function LoginPage() {
  const { t } = useI18n();
  const [searchParams] = useSearchParams();
  const redirectTo = searchParams.get('redirectTo') || '/';
  const actionData = useActionData<typeof action>();
  const loaderData = useLoaderData<typeof loader>();
  const channels = (loaderData as any)?.channels || ['email_password'];

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

  const formContent = (
    <div className="relative z-10">
      <div className="mb-6 text-center">
        <h1
          className={`mb-2 font-bold text-gray-900 dark:text-white ${isMobile ? 'text-2xl' : 'text-3xl lg:text-4xl'}`}
        >
          {t('auth.welcome')}
        </h1>
        <p
          className={`text-gray-600 dark:text-gray-400 ${isMobile ? 'text-sm' : 'text-base lg:text-lg'}`}
        >
          {t('auth.selectMethod')}
        </p>
      </div>

      {/* Tab Selector — only show if more than 1 tab channel */}
      {tabChannels.length > 1 && (
        <div
          role="tablist"
          aria-label="login channels"
          data-testid="login-channel-tabs"
          className="mb-6 flex rounded-lg bg-gray-100 p-1 dark:bg-gray-700"
        >
          {tabChannels.map((ch: string) => (
            <button
              key={ch}
              type="button"
              role="tab"
              aria-selected={activeTab === ch}
              data-testid={`login-tab-${ch.toLowerCase()}`}
              onClick={() => setActiveTab(ch)}
              className={`flex-1 rounded-md py-2 text-sm font-medium transition-all duration-200 ${
                activeTab === ch
                  ? 'bg-white text-blue-600 shadow-sm dark:bg-gray-600 dark:text-blue-400'
                  : 'text-gray-500 hover:text-gray-700 dark:text-gray-400 dark:hover:text-gray-300'
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
          searchParams={searchParams}
          email={email}
          setEmail={setEmail}
          password={password}
          setPassword={setPassword}
          remember={remember}
          setRemember={setRemember}
          isMobile={isMobile}
          t={t}
        />
      )}
      {activeTab === 'sms' && (
        <SmsLoginForm
          actionData={actionData}
          redirectTo={redirectTo}
          remember={remember}
          setRemember={setRemember}
          isMobile={isMobile}
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
          isMobile={isMobile}
          t={t}
        />
      )}

      {/* Social Login Buttons */}
      {socialChannels.length > 0 && (
        <div className="mt-6">
          <div className="relative">
            <div className="absolute inset-0 flex items-center">
              <div className="w-full border-t border-gray-300 dark:border-gray-600"></div>
            </div>
            <div className="relative flex justify-center text-sm">
              <span className="bg-white px-4 text-gray-500 dark:bg-gray-800 dark:text-gray-400">
                {t('auth.socialLogin')}
              </span>
            </div>
          </div>
          <div className="mt-4 flex justify-center gap-4">
            {socialChannels.map((ch: string) => (
              <SocialLoginButton key={ch} provider={ch} />
            ))}
          </div>
        </div>
      )}

      {/* Sign up link */}
      <div className={`text-center ${isMobile ? 'pt-4' : 'pt-6 lg:pt-8'}`}>
        <span
          className={`text-gray-600 dark:text-gray-400 ${isMobile ? 'text-sm' : 'text-base lg:text-lg'}`}
        >
          {t('auth.noAccount')}
        </span>
        <Link
          className={`ml-2 font-medium text-blue-600 transition-colors duration-200 hover:text-blue-700 dark:text-blue-400 dark:hover:text-blue-300 ${isMobile ? 'text-sm' : 'text-base lg:text-lg'}`}
          to={{ pathname: '/signup', search: searchParams.toString() }}
        >
          {t('auth.registerNow')}
        </Link>
      </div>
    </div>
  );

  return (
    <div
      data-testid="login-page-root"
      data-hydrated={hydrated ? 'true' : 'false'}
      className={`relative flex min-h-screen overflow-hidden ${
        isMobile
          ? 'items-center justify-center bg-gradient-to-br from-blue-500 via-indigo-600 to-purple-600 p-4'
          : 'bg-gradient-to-br from-blue-50 via-indigo-50 to-purple-50 dark:from-gray-900 dark:via-gray-800 dark:to-gray-900'
      }`}
    >
      {/* Decorative background */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-gradient-to-br from-blue-400/20 to-indigo-600/20 blur-3xl"></div>
        <div className="absolute -bottom-40 -left-40 h-80 w-80 rounded-full bg-gradient-to-tr from-purple-400/20 to-pink-600/20 blur-3xl"></div>
      </div>

      {isMobile ? (
        <div className="relative z-10 w-full max-w-sm">
          <div className="flex min-h-[80vh] flex-col justify-center rounded-t-3xl rounded-b-lg border-t border-white/20 bg-white/95 p-6 shadow-2xl backdrop-blur-sm dark:bg-gray-800/95">
            {formContent}
          </div>
        </div>
      ) : (
        <div className="relative z-10 flex min-h-screen w-full">
          {/* Left: branding panel */}
          <div className="relative hidden items-center justify-center overflow-hidden bg-gradient-to-br from-blue-600 via-indigo-600 to-purple-700 p-12 lg:flex lg:w-1/2">
            <div className="pointer-events-none absolute inset-0 overflow-hidden">
              <div className="absolute top-20 left-20 h-32 w-32 rounded-full bg-white/10 blur-2xl"></div>
              <div className="absolute right-20 bottom-20 h-40 w-40 rounded-full bg-white/10 blur-2xl"></div>
              <div className="absolute top-1/2 left-1/2 h-96 w-96 -translate-x-1/2 -translate-y-1/2 rounded-full bg-white/5 blur-3xl"></div>
            </div>
            <div className="relative z-10 max-w-md text-center text-white">
              <h2 className="mb-4 text-5xl font-bold tracking-tight">AuraBoot</h2>
              <p className="mb-8 text-lg text-white/80">{t('auth.tagline')}</p>
              <div className="space-y-4 text-left">
                {[
                  t('auth.feature.designer'),
                  t('auth.feature.dataModel'),
                  t('auth.feature.workflow'),
                ].map((text) => (
                  <div key={text} className="flex items-center space-x-3">
                    <div className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg bg-white/20">
                      <svg
                        className="h-4 w-4 text-white"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M5 13l4 4L19 7"
                        />
                      </svg>
                    </div>
                    <span className="text-white/90">{text}</span>
                  </div>
                ))}
              </div>
            </div>
          </div>

          {/* Right: form panel */}
          <div className="flex w-full items-center justify-center bg-white/50 p-8 backdrop-blur-sm lg:w-1/2 lg:p-12 dark:bg-gray-800/50">
            <div className="w-full max-w-md">{formContent}</div>
          </div>
        </div>
      )}
    </div>
  );
}

// ============================================================
// Sub-components
// ============================================================

function EmailPasswordForm({
  emailRef,
  passwordRef,
  actionData,
  redirectTo,
  searchParams,
  email,
  setEmail,
  password,
  setPassword,
  remember,
  setRemember,
  isMobile,
  t,
}: {
  emailRef: React.RefObject<HTMLInputElement | null>;
  passwordRef: React.RefObject<HTMLInputElement | null>;
  actionData: any;
  redirectTo: string;
  searchParams: URLSearchParams;
  email: string;
  setEmail: (v: string) => void;
  password: string;
  setPassword: (v: string) => void;
  remember: boolean;
  setRemember: (v: boolean) => void;
  isMobile: boolean;
  t: (key: string) => string;
}) {
  const inputCls = `w-full border-2 border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white placeholder-gray-500 dark:placeholder-gray-400 transition-all duration-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:focus:ring-blue-800 focus:bg-white dark:focus:bg-gray-600 shadow-sm ${
    isMobile
      ? 'rounded-lg px-4 py-4 text-base'
      : 'rounded-xl px-4 py-3 lg:px-6 lg:py-4 text-base lg:text-lg'
  }`;

  return (
    <Form
      method="post"
      action="/login"
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
      className={isMobile ? 'space-y-4' : 'space-y-6'}
    >
      <input type="hidden" name="channelCode" value="email_password" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      {actionData?.errors?.general && (
        <div className="flex items-center gap-3 rounded-xl border border-red-200 bg-red-50 p-4 dark:border-red-800 dark:bg-red-900/20">
          <svg
            className="h-5 w-5 flex-shrink-0 text-red-500 dark:text-red-400"
            fill="none"
            stroke="currentColor"
            viewBox="0 0 24 24"
          >
            <path
              strokeLinecap="round"
              strokeLinejoin="round"
              strokeWidth={2}
              d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-2.5L13.732 4c-.77-.833-1.964-.833-2.732 0L4.082 16.5c-.77.833.192 2.5 1.732 2.5z"
            />
          </svg>
          <p className="text-sm font-medium text-red-700 dark:text-red-300">
            {t(actionData.errors.general)}
          </p>
        </div>
      )}

      <div>
        <label
          htmlFor="email"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.email')}
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
          placeholder={t('auth.emailPlaceholder')}
          aria-invalid={actionData?.errors?.email ? true : undefined}
          className={inputCls}
        />
        {actionData?.errors?.email && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.email}
          </div>
        )}
      </div>

      <div>
        <label
          htmlFor="password"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.password')}
        </label>
        <input
          ref={passwordRef}
          id="password"
          name="password"
          type="password"
          autoComplete="current-password"
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          placeholder={t('auth.passwordPlaceholder')}
          aria-invalid={actionData?.errors?.password ? true : undefined}
          className={inputCls}
        />
        {actionData?.errors?.password && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.password}
          </div>
        )}
      </div>

      <div className="flex items-center">
        <input
          id="remember"
          name="remember"
          type="checkbox"
          checked={remember}
          onChange={(e) => setRemember(e.target.checked)}
          className="h-5 w-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500 dark:border-gray-600"
        />
        <label htmlFor="remember" className="ml-3 block text-sm text-gray-700 dark:text-gray-300">
          {t('auth.rememberMe')}
        </label>
        <Link
          to="/forgot-password"
          className="ml-auto text-sm font-medium text-blue-600 hover:text-blue-700 dark:text-blue-400"
        >
          {t('auth.forgotPassword')}
        </Link>
      </div>

      <button
        type="submit"
        className={`w-full rounded-xl bg-gradient-to-r from-blue-500 to-indigo-600 font-semibold text-white shadow-lg transition-all duration-200 hover:from-blue-600 hover:to-indigo-700 hover:shadow-xl focus:ring-4 focus:ring-blue-300 focus:outline-none dark:focus:ring-blue-800 ${
          isMobile
            ? 'px-6 py-4 text-base active:scale-95'
            : 'transform px-6 py-4 text-lg hover:-translate-y-0.5'
        }`}
      >
        {t('auth.loginNow')}
      </button>
    </Form>
  );
}

function SmsLoginForm({
  actionData,
  redirectTo,
  remember,
  setRemember,
  isMobile,
  t,
}: {
  actionData: any;
  redirectTo: string;
  remember: boolean;
  setRemember: (v: boolean) => void;
  isMobile: boolean;
  t: (key: string) => string;
}) {
  const [mobile, setMobile] = useState('');
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);

  const inputCls = `w-full border-2 border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white placeholder-gray-500 dark:placeholder-gray-400 transition-all duration-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:focus:ring-blue-800 focus:bg-white dark:focus:bg-gray-600 shadow-sm ${
    isMobile
      ? 'rounded-lg px-4 py-4 text-base'
      : 'rounded-xl px-4 py-3 lg:px-6 lg:py-4 text-base lg:text-lg'
  }`;

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
    <Form method="post" action="/login" className={isMobile ? 'space-y-4' : 'space-y-6'}>
      <input type="hidden" name="channelCode" value="sms" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      <div>
        <label
          htmlFor="mobile"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.mobile')}
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
          placeholder={t('auth.mobilePlaceholder')}
          className={inputCls}
        />
        {actionData?.errors?.mobile && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.mobile}
          </div>
        )}
      </div>

      <div>
        <label
          htmlFor="sms-code"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.verifyCode')}
        </label>
        <div className="flex gap-3">
          <input
            id="sms-code"
            name="code"
            type="text"
            inputMode="numeric"
            maxLength={6}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('auth.codePlaceholder')}
            className={inputCls}
          />
          <button
            type="button"
            onClick={sendCode}
            disabled={countdown > 0 || sending || mobile.trim().length < 10}
            className={`flex-shrink-0 rounded-xl border-2 px-4 text-sm font-medium transition-all duration-200 ${
              countdown > 0 || sending
                ? 'cursor-not-allowed border-gray-300 text-gray-400 dark:border-gray-600 dark:text-gray-500'
                : 'border-blue-500 text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20'
            }`}
          >
            {countdown > 0 ? `${countdown}s` : sending ? t('auth.sending') : t('auth.getCode')}
          </button>
        </div>
        {actionData?.errors?.code && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.code}
          </div>
        )}
      </div>

      <div className="flex items-center">
        <input
          id="sms-remember"
          name="remember"
          type="checkbox"
          checked={remember}
          onChange={(e) => setRemember(e.target.checked)}
          className="h-5 w-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500 dark:border-gray-600"
        />
        <label
          htmlFor="sms-remember"
          className="ml-3 block text-sm text-gray-700 dark:text-gray-300"
        >
          {t('auth.rememberMe')}
        </label>
      </div>

      <button
        type="submit"
        className={`w-full rounded-xl bg-gradient-to-r from-blue-500 to-indigo-600 font-semibold text-white shadow-lg transition-all duration-200 hover:from-blue-600 hover:to-indigo-700 hover:shadow-xl focus:ring-4 focus:ring-blue-300 focus:outline-none dark:focus:ring-blue-800 ${
          isMobile
            ? 'px-6 py-4 text-base active:scale-95'
            : 'transform px-6 py-4 text-lg hover:-translate-y-0.5'
        }`}
      >
        {t('auth.loginNow')}
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
  isMobile,
  t,
}: {
  actionData: any;
  redirectTo: string;
  email: string;
  setEmail: (v: string) => void;
  remember: boolean;
  setRemember: (v: boolean) => void;
  isMobile: boolean;
  t: (key: string) => string;
}) {
  const [code, setCode] = useState('');
  const [countdown, setCountdown] = useState(0);
  const [sending, setSending] = useState(false);

  const inputCls = `w-full border-2 border-gray-300 dark:border-gray-600 bg-gray-50 dark:bg-gray-700 text-gray-900 dark:text-white placeholder-gray-500 dark:placeholder-gray-400 transition-all duration-200 focus:border-blue-500 focus:ring-2 focus:ring-blue-200 dark:focus:ring-blue-800 focus:bg-white dark:focus:bg-gray-600 shadow-sm ${
    isMobile
      ? 'rounded-lg px-4 py-4 text-base'
      : 'rounded-xl px-4 py-3 lg:px-6 lg:py-4 text-base lg:text-lg'
  }`;

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
    <Form method="post" action="/login" className={isMobile ? 'space-y-4' : 'space-y-6'}>
      <input type="hidden" name="channelCode" value="email_code" />
      <input type="hidden" name="redirectTo" value={redirectTo} />

      <div>
        <label
          htmlFor="ec-email"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.email')}
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
          placeholder={t('auth.emailPlaceholder')}
          className={inputCls}
        />
        {actionData?.errors?.email && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.email}
          </div>
        )}
      </div>

      <div>
        <label
          htmlFor="ec-code"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          {t('auth.verifyCode')}
        </label>
        <div className="flex gap-3">
          <input
            id="ec-code"
            name="code"
            type="text"
            inputMode="numeric"
            maxLength={6}
            required
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('auth.codePlaceholder')}
            className={inputCls}
          />
          <button
            type="button"
            onClick={sendCode}
            disabled={countdown > 0 || sending || !validateEmail(email)}
            className={`flex-shrink-0 rounded-xl border-2 px-4 text-sm font-medium transition-all duration-200 ${
              countdown > 0 || sending
                ? 'cursor-not-allowed border-gray-300 text-gray-400 dark:border-gray-600 dark:text-gray-500'
                : 'border-blue-500 text-blue-600 hover:bg-blue-50 dark:hover:bg-blue-900/20'
            }`}
          >
            {countdown > 0 ? `${countdown}s` : sending ? t('auth.sending') : t('auth.getCode')}
          </button>
        </div>
        {actionData?.errors?.code && (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400">
            {actionData.errors.code}
          </div>
        )}
      </div>

      <div className="flex items-center">
        <input
          id="ec-remember"
          name="remember"
          type="checkbox"
          checked={remember}
          onChange={(e) => setRemember(e.target.checked)}
          className="h-5 w-5 rounded border-gray-300 text-blue-600 focus:ring-blue-500 dark:border-gray-600"
        />
        <label
          htmlFor="ec-remember"
          className="ml-3 block text-sm text-gray-700 dark:text-gray-300"
        >
          {t('auth.rememberMe')}
        </label>
      </div>

      <button
        type="submit"
        className={`w-full rounded-xl bg-gradient-to-r from-blue-500 to-indigo-600 font-semibold text-white shadow-lg transition-all duration-200 hover:from-blue-600 hover:to-indigo-700 hover:shadow-xl focus:ring-4 focus:ring-blue-300 focus:outline-none dark:focus:ring-blue-800 ${
          isMobile
            ? 'px-6 py-4 text-base active:scale-95'
            : 'transform px-6 py-4 text-lg hover:-translate-y-0.5'
        }`}
      >
        {t('auth.loginNow')}
      </button>
    </Form>
  );
}

function SocialLoginButton({ provider }: { provider: string }) {
  const { t } = useI18n();
  const handleClick = useCallback(async () => {
    try {
      const redirectUri = `${window.location.origin}/login/social/${provider.toLowerCase()}/callback`;
      const result = await fetchResult<{ authorizeUrl: string }>(
        `/api/auth/login/social/${provider.toLowerCase()}`,
        {
          method: 'get',
          params: { redirectUri },
        },
      );
      if (ResultHelper.isSuccess(result) && result.data?.authorizeUrl) {
        window.location.href = result.data.authorizeUrl;
      }
    } catch {
      /* ignore */
    }
  }, [provider]);

  const icons: Record<string, React.ReactNode> = {
    WECHAT: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
        <path d="M8.691 2.188C3.891 2.188 0 5.476 0 9.53c0 2.212 1.17 4.203 3.002 5.55a.59.59 0 0 1 .213.665l-.39 1.48c-.019.07-.048.141-.048.213 0 .163.13.295.29.295a.326.326 0 0 0 .167-.054l1.903-1.114a.864.864 0 0 1 .717-.098 10.16 10.16 0 0 0 2.837.403c.276 0 .543-.027.811-.05-.857-2.578.157-4.972 1.932-6.446 1.703-1.415 3.882-1.98 5.853-1.838-.576-3.583-4.196-6.348-8.596-6.348zM5.785 5.991c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178A1.17 1.17 0 0 1 4.623 7.17c0-.651.52-1.18 1.162-1.18zm5.813 0c.642 0 1.162.529 1.162 1.18a1.17 1.17 0 0 1-1.162 1.178 1.17 1.17 0 0 1-1.162-1.178c0-.651.52-1.18 1.162-1.18zm5.34 2.867c-1.797-.052-3.746.512-5.28 1.786-1.72 1.428-2.687 3.72-1.78 6.22.942 2.453 3.666 4.229 6.884 4.229.826 0 1.622-.12 2.361-.336a.722.722 0 0 1 .598.082l1.584.926a.272.272 0 0 0 .14.045.245.245 0 0 0 .241-.245c0-.06-.024-.12-.04-.178l-.325-1.233a.492.492 0 0 1 .178-.554C23.362 18.48 24 17.168 24 15.753c0-2.967-2.443-5.476-6.062-5.895a9.9 9.9 0 0 0-1-.001zm-1.834 2.686c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.97-.982zm4.857 0c.535 0 .969.44.969.982a.976.976 0 0 1-.969.983.976.976 0 0 1-.969-.983c0-.542.434-.982.969-.982z" />
      </svg>
    ),
    GOOGLE: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
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
    APPLE: (
      <svg className="h-6 w-6" viewBox="0 0 24 24" fill="currentColor">
        <path d="M17.05 20.28c-.98.95-2.05.88-3.08.4-1.09-.5-2.08-.48-3.24 0-1.44.62-2.2.44-3.06-.4C2.79 15.25 3.51 7.59 9.05 7.31c1.35.07 2.29.74 3.08.8 1.18-.24 2.31-.93 3.57-.84 1.51.12 2.65.72 3.4 1.8-3.12 1.87-2.38 5.98.48 7.13-.57 1.5-1.31 2.99-2.54 4.09zM12.03 7.25c-.15-2.23 1.66-4.07 3.74-4.25.29 2.58-2.34 4.5-3.74 4.25z" />
      </svg>
    ),
    OIDC: (
      <svg
        className="h-6 w-6"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth={2}
      >
        <path
          strokeLinecap="round"
          strokeLinejoin="round"
          d="M19 21V5a2 2 0 00-2-2H7a2 2 0 00-2 2v16m14 0H5m14 0h2m-16 0H3m2 0v-2m14 2v-2M9 7h6m-6 4h6m-6 4h4"
        />
      </svg>
    ),
  };

  return (
    <button
      type="button"
      onClick={handleClick}
      className="flex h-12 w-12 items-center justify-center rounded-full border-2 border-gray-200 bg-white text-gray-600 shadow-sm transition-all duration-200 hover:border-blue-400 hover:text-blue-600 hover:shadow-md dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300 dark:hover:border-blue-400 dark:hover:text-blue-400"
      title={SOCIAL_I18N_KEYS[provider] ? t(SOCIAL_I18N_KEYS[provider]) : provider}
    >
      {icons[provider] || <span className="text-xs font-bold">{provider.substring(0, 2)}</span>}
    </button>
  );
}
