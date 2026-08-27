import { createCookieSessionStorage, redirect } from 'react-router';

import {
  JWT_TOKEN_KEY,
  REMEMBER_KEY,
  TOKEN_EXPIRY_KEY,
  REFRESH_TOKEN_KEY,
} from '~/constants/AuthConstant';
import { post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

const processEnv = typeof process !== 'undefined' ? process.env : undefined;
const NODE_ENV = processEnv?.NODE_ENV ?? 'development';
const SESSION_SECRET = processEnv?.SESSION_SECRET;

if (!SESSION_SECRET && NODE_ENV === 'production') {
  throw new Error('SESSION_SECRET environment variable must be set in production');
}

export const sessionStorage = createCookieSessionStorage({
  cookie: {
    name: '__session',
    httpOnly: true,
    path: '/',
    sameSite: 'lax',
    secrets: [SESSION_SECRET || 'dev-only-secret-do-not-use-in-production'],
    secure: NODE_ENV === 'production',
  },
});

/** Renew once the access token has at most this much time left. */
const RENEW_BEFORE_MS = 12 * 60 * 60 * 1000;

/**
 * Reads the `exp` claim from a JWT payload without verifying the signature.
 * Server-side only: used to persist the token deadline next to the token so the
 * loader can decide when to renew. The backend just issued/verified this token,
 * so reading the expiry from the payload is safe here.
 */
export function readJwtExp(token: string): number | null {
  try {
    const payloadPart = token.split('.')[1];
    if (!payloadPart) return null;
    const base64 = payloadPart.replace(/-/g, '+').replace(/_/g, '/');
    const payload = JSON.parse(Buffer.from(base64, 'base64').toString('utf8')) as {
      exp?: number;
    };
    return typeof payload.exp === 'number' && Number.isFinite(payload.exp) ? payload.exp : null;
  } catch {
    return null;
  }
}

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
  const tokenExp = readJwtExp(token);
  if (tokenExp != null) {
    session.set(TOKEN_EXPIRY_KEY, String(tokenExp));
  }
  session.set(REMEMBER_KEY, remember ? '1' : '0');

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

export interface SessionRenewalResult {
  renewed: boolean;
  setCookie?: string;
}

/**
 * Whether a session is close enough to its token deadline that a renewal should
 * be attempted. Pure and unit-tested; the default threshold is 12 hours.
 */
export function shouldAttemptRenewal(
  expiryEpochSeconds: number | null,
  nowMs: number,
  thresholdMs: number,
): boolean {
  if (expiryEpochSeconds == null || !Number.isFinite(expiryEpochSeconds)) return false;
  const expiryMs = expiryEpochSeconds * 1000;
  return expiryMs - nowMs <= thresholdMs;
}

/**
 * Best-effort sliding-session renewal, called from the root loader after the
 * authenticated user resolves. When the token is inside the renewal window it
 * asks the backend for a fresh token and returns a Set-Cookie header for the
 * updated httpOnly session cookie. The previous token remains valid until its
 * natural expiry, so a dropped Set-Cookie on a redirect cannot strand the user.
 *
 * Any failure is deliberately non-fatal: the current token remains valid until
 * its real deadline and the normal 401 → login redirect takes over then.
 */
export async function maybeRenewSession(request: Request): Promise<SessionRenewalResult> {
  const session = await getSessionFromRequest(request);
  const token = session.get(JWT_TOKEN_KEY) as string | undefined;
  if (!token) return { renewed: false };

  const expiryRaw = session.get(TOKEN_EXPIRY_KEY);
  const expiry = expiryRaw == null ? null : Number(expiryRaw);
  if (!shouldAttemptRenewal(expiry, Date.now(), RENEW_BEFORE_MS)) {
    return { renewed: false };
  }

  const result = await post<{ jwt?: string }>(
    '/api/auth/renew',
    {},
    { token, timeout: 10_000 },
    request,
  );
  const renewedJwt = result.data?.jwt;
  if (!ResultHelper.isSuccess(result) || !renewedJwt) {
    console.warn('[session] token renewal skipped:', result.code, result.message);
    return { renewed: false };
  }

  const renewedExp = readJwtExp(renewedJwt);
  session.set(JWT_TOKEN_KEY, renewedJwt);
  if (renewedExp != null) {
    session.set(TOKEN_EXPIRY_KEY, String(renewedExp));
  }
  const remember = session.get(REMEMBER_KEY) === '1';
  const setCookie = await sessionStorage.commitSession(session, {
    maxAge: remember ? 60 * 60 * 24 * 7 : undefined,
  });
  return { renewed: true, setCookie };
}

/** Renew once the access token has at most this much time left. */
export async function logout(request: Request) {
  const session = await getSessionFromRequest(request);
  const token = session.get(JWT_TOKEN_KEY);

  if (token) {
    const backendUrl =
      typeof process !== 'undefined'
        ? process.env.SPRING_BOOT_URL || 'http://127.0.0.1:6443'
        : '';
    if (backendUrl) {
      try {
        await fetch(`${backendUrl}/api/user/sessions/current`, {
          method: 'DELETE',
          headers: {
            Authorization: `Bearer ${token}`,
          },
        });
      } catch (error) {
        console.warn('Failed to revoke backend session during logout', error);
      }
    }
  }

  // 清除所有token相关信息
  session.unset(JWT_TOKEN_KEY);
  session.unset(REFRESH_TOKEN_KEY);
  session.unset(TOKEN_EXPIRY_KEY);
  session.unset(REMEMBER_KEY);

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

export async function getSessionFromRequest(request: Request) {
  const cookie = request.headers.get('Cookie');
  return sessionStorage.getSession(cookie);
}
