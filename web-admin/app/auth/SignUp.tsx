import {
  type ActionFunctionArgs,
  type LoaderFunctionArgs,
  Form,
  Link,
  useActionData,
  useSearchParams,
  redirect,
} from 'react-router';
import type { User } from '~/utils/type';
import { useEffect, useRef, useState } from 'react';

import { createUserSession, getTokenFromRequest } from '~/shared/services/session';
import { validateEmail } from '~/utils/utils';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export const loader = async ({ request }: LoaderFunctionArgs) => {
  const token = await getTokenFromRequest(request);
  if (token) {
    return redirect('/');
  }
  return {};
};

export const action = async ({ request }: ActionFunctionArgs) => {
  const formData = await request.formData();
  const email = formData.get('email');
  const password = formData.get('password');
  const displayName = formData.get('displayName');

  if (!validateEmail(email)) {
    return {
      errors: { email: 'Email is invalid', password: null, displayName: null },
      status: 400,
    };
  }

  if (typeof displayName !== 'string' || displayName.trim().length === 0) {
    return {
      errors: { email: null, password: null, displayName: 'Display name is required' },
      status: 400,
    };
  }

  if (typeof password !== 'string' || password.length === 0) {
    return {
      errors: { email: null, password: 'Password is required', displayName: null },
      status: 400,
    };
  }

  if (password.length < 6) {
    return {
      errors: { email: null, password: 'Password is too short', displayName: null },
      status: 400,
    };
  }

  const result = await post<User>(
    '/api/auth/register',
    {
      email: email as string,
      password: password as string,
      displayName: (displayName as string).trim(),
    },
    {},
    request,
  );

  if (!ResultHelper.isSuccess(result)) {
    return {
      errors: {
        email: result.desc,
        password: null,
      },
      status: 400,
    };
  } else {
    const user = result.data as User;
    const token = user.jwt ?? '';
    if (!token) {
      return {
        errors: { email: '注册失败，请稍后再试', password: null },
        status: 500,
      };
    }
    return createUserSession({
      request: request,
      token,
      remember: false,
      redirectTo: '/tenant-selection',
    });
  }
};

export default function Join() {
  const [searchParams] = useSearchParams();
  const actionData = useActionData<typeof action>();
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);
  const [isMobile, setIsMobile] = useState(false);

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
    if (actionData?.errors?.email) {
      emailRef.current?.focus();
    } else if (actionData?.errors?.password) {
      passwordRef.current?.focus();
    }
  }, [actionData]);

  return (
    <div
      className={`relative flex min-h-screen overflow-hidden ${
        isMobile
          ? 'items-center justify-center bg-gradient-to-br from-indigo-500 via-purple-600 to-blue-600 p-4'
          : 'bg-gradient-to-br from-indigo-50 via-purple-50 to-blue-50'
      }`}
    >
      {/* Decorative background */}
      <div className="pointer-events-none absolute inset-0 overflow-hidden">
        <div className="absolute -top-40 -right-40 h-80 w-80 rounded-full bg-gradient-to-br from-indigo-400/20 to-purple-600/20 blur-3xl"></div>
        <div className="absolute -bottom-40 -left-40 h-80 w-80 rounded-full bg-gradient-to-tr from-blue-400/20 to-pink-600/20 blur-3xl"></div>
      </div>

      {isMobile ? (
        /* Mobile: single card */
        <div className="relative z-10 w-full max-w-sm">
          <div className="flex min-h-[80vh] flex-col justify-center rounded-t-3xl rounded-b-lg border-t border-white/20 bg-white/95 p-6 shadow-2xl backdrop-blur-sm dark:bg-gray-800/95">
            <div className="relative z-10">
              <div className="mb-6 text-center">
                <h1 className="mb-2 text-2xl font-bold text-gray-900 dark:text-white">创建账号</h1>
                <p className="text-sm text-gray-600 dark:text-gray-400">注册以开始使用 AuraBoot</p>
              </div>
              <SignUpForm
                emailRef={emailRef}
                passwordRef={passwordRef}
                actionData={actionData}
                searchParams={searchParams}
                isMobile={true}
              />
            </div>
          </div>
        </div>
      ) : (
        /* Desktop: two-column layout */
        <div className="relative z-10 flex min-h-screen w-full">
          {/* Left: branding panel */}
          <div className="relative hidden items-center justify-center overflow-hidden bg-gradient-to-br from-indigo-600 via-purple-600 to-blue-700 p-12 lg:flex lg:w-1/2">
            <div className="pointer-events-none absolute inset-0 overflow-hidden">
              <div className="absolute top-20 left-20 h-32 w-32 rounded-full bg-white/10 blur-2xl"></div>
              <div className="absolute right-20 bottom-20 h-40 w-40 rounded-full bg-white/10 blur-2xl"></div>
            </div>
            <div className="relative z-10 max-w-md text-center text-white">
              <h2 className="mb-4 text-5xl font-bold tracking-tight">AuraBoot</h2>
              <p className="mb-8 text-lg text-white/80">快速构建企业级应用的低代码平台</p>
              <div className="space-y-4 text-left">
                <div className="flex items-center space-x-3">
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
                  <span className="text-white/90">可视化页面设计器</span>
                </div>
                <div className="flex items-center space-x-3">
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
                  <span className="text-white/90">灵活的数据模型管理</span>
                </div>
                <div className="flex items-center space-x-3">
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
                  <span className="text-white/90">企业级权限与流程引擎</span>
                </div>
              </div>
            </div>
          </div>

          {/* Right: form panel */}
          <div className="flex w-full items-center justify-center p-8 lg:w-1/2 lg:p-12">
            <div className="w-full max-w-md">
              <div className="mb-8 text-center">
                <div className="mb-4 lg:hidden">
                  <div className="mx-auto mb-4 flex h-16 w-16 items-center justify-center rounded-full bg-gradient-to-r from-indigo-500 to-purple-600">
                    <svg
                      className="h-8 w-8 text-white"
                      fill="none"
                      stroke="currentColor"
                      viewBox="0 0 24 24"
                    >
                      <path
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        strokeWidth={2}
                        d="M18 9v3m0 0v3m0-3h3m-3 0h-3m-2-5a4 4 0 11-8 0 4 4 0 018 0zM3 20a6 6 0 0112 0v1H3v-1z"
                      />
                    </svg>
                  </div>
                </div>
                <h1 className="mb-2 text-3xl font-bold text-gray-900 dark:text-white">创建账号</h1>
                <p className="text-gray-600 dark:text-gray-400">注册以开始使用 AuraBoot</p>
              </div>
              <SignUpForm
                emailRef={emailRef}
                passwordRef={passwordRef}
                actionData={actionData}
                searchParams={searchParams}
                isMobile={false}
              />
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function SignUpForm({
  emailRef,
  passwordRef,
  actionData,
  searchParams,
  isMobile,
}: {
  emailRef: React.RefObject<HTMLInputElement | null>;
  passwordRef: React.RefObject<HTMLInputElement | null>;
  actionData: any;
  searchParams: URLSearchParams;
  isMobile: boolean;
}) {
  return (
    <Form method="post" className={isMobile ? 'space-y-4' : 'space-y-6'}>
      <div>
        <label
          htmlFor="email"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          邮箱地址
        </label>
        <input
          ref={emailRef}
          id="email"
          required
          autoFocus={true}
          name="email"
          type="email"
          autoComplete="email"
          placeholder="请输入邮箱地址"
          aria-invalid={actionData?.errors?.email ? true : undefined}
          aria-describedby="email-error"
          className={`w-full border-2 border-gray-300 bg-gray-50 text-gray-900 placeholder-gray-500 shadow-sm transition-all duration-200 focus:border-indigo-500 focus:bg-white focus:ring-2 focus:ring-indigo-200 dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder-gray-400 dark:focus:bg-gray-600 dark:focus:ring-indigo-800 ${
            isMobile ? 'rounded-lg px-4 py-4 text-base' : 'rounded-xl px-4 py-3 text-base'
          }`}
        />
        {actionData?.errors?.email ? (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400" id="email-error">
            {actionData.errors.email}
          </div>
        ) : null}
      </div>

      <div>
        <label
          htmlFor="displayName"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          显示名称
        </label>
        <input
          id="displayName"
          required
          name="displayName"
          type="text"
          autoComplete="name"
          placeholder="请输入您的名称"
          maxLength={50}
          aria-invalid={actionData?.errors?.displayName ? true : undefined}
          aria-describedby="displayName-error"
          className={`w-full border-2 border-gray-300 bg-gray-50 text-gray-900 placeholder-gray-500 shadow-sm transition-all duration-200 focus:border-indigo-500 focus:bg-white focus:ring-2 focus:ring-indigo-200 dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder-gray-400 dark:focus:bg-gray-600 dark:focus:ring-indigo-800 ${
            isMobile ? 'rounded-lg px-4 py-4 text-base' : 'rounded-xl px-4 py-3 text-base'
          }`}
        />
        {actionData?.errors?.displayName ? (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400" id="displayName-error">
            {actionData.errors.displayName}
          </div>
        ) : null}
      </div>

      <div>
        <label
          htmlFor="password"
          className={`mb-2 block font-medium text-gray-700 dark:text-gray-300 ${isMobile ? 'text-sm' : 'text-base'}`}
        >
          密码
        </label>
        <input
          id="password"
          ref={passwordRef}
          name="password"
          type="password"
          autoComplete="new-password"
          placeholder="请输入密码（至少6位）"
          aria-invalid={actionData?.errors?.password ? true : undefined}
          aria-describedby="password-error"
          className={`w-full border-2 border-gray-300 bg-gray-50 text-gray-900 placeholder-gray-500 shadow-sm transition-all duration-200 focus:border-indigo-500 focus:bg-white focus:ring-2 focus:ring-indigo-200 dark:border-gray-600 dark:bg-gray-700 dark:text-white dark:placeholder-gray-400 dark:focus:bg-gray-600 dark:focus:ring-indigo-800 ${
            isMobile ? 'rounded-lg px-4 py-4 text-base' : 'rounded-xl px-4 py-3 text-base'
          }`}
        />
        {actionData?.errors?.password ? (
          <div className="mt-2 text-sm text-red-600 dark:text-red-400" id="password-error">
            {actionData.errors.password}
          </div>
        ) : null}
      </div>

      <input type="hidden" name="redirectTo" value={searchParams.get('redirectTo') ?? ''} />

      <button
        type="submit"
        className={`w-full bg-gradient-to-r from-indigo-500 to-purple-600 font-semibold text-white transition-all duration-200 focus:ring-4 focus:ring-indigo-300 focus:outline-none dark:focus:ring-indigo-800 ${
          isMobile
            ? 'rounded-lg px-6 py-4 text-base shadow-lg hover:from-indigo-600 hover:to-purple-700 active:scale-95'
            : 'transform rounded-xl px-6 py-3 text-lg shadow-lg hover:-translate-y-0.5 hover:from-indigo-600 hover:to-purple-700 hover:shadow-xl'
        }`}
      >
        创建账号
      </button>

      <div className={`text-center ${isMobile ? 'pt-4' : 'pt-6'}`}>
        <span className={`text-gray-600 dark:text-gray-400 ${isMobile ? 'text-sm' : 'text-base'}`}>
          已有账号？
        </span>
        <Link
          className={`ml-2 font-medium text-indigo-600 transition-colors duration-200 hover:text-indigo-700 dark:text-indigo-400 dark:hover:text-indigo-300 ${isMobile ? 'text-sm' : 'text-base'}`}
          to={`/login${searchParams.toString() ? `?${searchParams.toString()}` : ''}`}
        >
          立即登录
        </Link>
      </div>
    </Form>
  );
}
