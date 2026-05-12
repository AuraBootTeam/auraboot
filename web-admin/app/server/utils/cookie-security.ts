const RESERVED_COOKIE_NAMES = new Set(['__proto__', 'prototype', 'constructor']);

export function parseCookieHeader(cookieHeader: string): Map<string, string> {
  const cookies = new Map<string, string>();

  cookieHeader.split(';').forEach((cookie) => {
    const [name, ...rest] = cookie.trim().split('=');
    if (!name || rest.length === 0 || RESERVED_COOKIE_NAMES.has(name)) {
      return;
    }
    cookies.set(name, decodeURIComponent(rest.join('=')));
  });

  return cookies;
}
