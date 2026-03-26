import { createCookieSessionStorage, redirect } from 'react-router';
import type { Result, User } from '~/utils/type';

import { JWT_TOKEN_KEY, TOKEN_EXPIRY_KEY, REFRESH_TOKEN_KEY } from '~/constants/AuthConstant';

const SESSION_SECRET = process.env.SESSION_SECRET;
if (!SESSION_SECRET && process.env.NODE_ENV === 'production') {
  throw new Error('SESSION_SECRET environment variable must be set in production');
}

export const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: process.env.NODE_ENV === 'production',
  },
});

// 修改为存储JWT token的函数
export async function createUserSession({
  request,
  token,
  // refreshToken,
  remember,
  redirectTo,
}: {
  request: Request;
  token: string;
  // refreshToken?: string;
  remember: boolean;
  redirectTo: string;
}) {
  const session = await getSessionFromRequest(request);
  session.set(JWT_TOKEN_KEY, token);

  return redirect(redirectTo, {
    headers: {
      'Set-Cookie': await sessionStorage.commitSession(session, {
        maxAge: remember
          ? 60 * 60 * 24 * 7 // 7 days
          : undefined,
      }),
    },
  });
}

export async function logout(request: Request) {
  const session = await getSessionFromRequest(request);

  // 清除所有token相关信息
  session.unset(JWT_TOKEN_KEY);
  session.unset(REFRESH_TOKEN_KEY);
  session.unset(TOKEN_EXPIRY_KEY);

  return redirect('/login', {
    headers: {
      'Set-Cookie': await sessionStorage.destroySession(session),
    },
  });
}

// 从session中获取JWT token - 改为导出函数  run in server side
export async function getTokenFromRequest(request: Request): Promise<string | null> {
  const session = await getSessionFromRequest(request);
  const token = session.get(JWT_TOKEN_KEY);
  return token || null;
}

/**
 * Require authentication — redirects to /login if no token present.
 * Use in loader/action functions that require an authenticated user.
 */
export async function requireAuth(request: Request): Promise<string> {
  const token = await getTokenFromRequest(request);
  if (!token) {
    const url = new URL(request.url);
    throw redirect(`/login?redirectTo=${encodeURIComponent(url.pathname + url.search)}`);
  }
  return token;
}

async function getSessionFromRequest(request: Request) {
  const cookie = request.headers.get('Cookie');
  return sessionStorage.getSession(cookie);
}
