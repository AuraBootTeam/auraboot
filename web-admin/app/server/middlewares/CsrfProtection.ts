import type { NextFunction, Request, Response } from 'express';
import { JWT_TOKEN_KEY } from '~/constants/AuthConstant';
import { sessionStorage } from '~/shared/services/session';

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS']);

export function requestOrigin(req: Request): string | null {
  const forwardedHost = req.get('x-forwarded-host')?.split(',')[0]?.trim();
  const host = forwardedHost || req.get('host');
  const forwardedProtocol = req.get('x-forwarded-proto')?.split(',')[0]?.trim();
  const protocol = forwardedProtocol || req.protocol;
  return host ? `${protocol}://${host}` : null;
}

export function hasSameOrigin(req: Request): boolean {
  const origin = req.get('origin');
  const expected = requestOrigin(req);
  return Boolean(origin && expected && origin === expected);
}

async function hasCookieSession(req: Request): Promise<boolean> {
  const cookie = req.headers.cookie;
  if (!cookie || !cookie.includes('__session=')) return false;
  const session = await sessionStorage.getSession(cookie);
  return Boolean(session.get(JWT_TOKEN_KEY));
}

/**
 * Cookie-authenticated mutations must originate from this BFF origin. Explicit
 * bearer clients remain supported because they are not vulnerable to ambient
 * browser-cookie CSRF and are used by SDK/SSR paths.
 */
export async function enforceCsrfProtection(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  if (SAFE_METHODS.has(req.method.toUpperCase()) || req.headers.authorization) {
    next();
    return;
  }

  try {
    if (!(await hasCookieSession(req)) || hasSameOrigin(req)) {
      next();
      return;
    }
  } catch {
    // A malformed/undecryptable cookie is not an authenticated session.
    next();
    return;
  }

  res.status(403).json({
    code: 'csrf_origin_rejected',
    message: 'Authenticated write requests must originate from this application.',
  });
}
