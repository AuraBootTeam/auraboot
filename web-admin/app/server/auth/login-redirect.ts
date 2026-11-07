/** Preserve local business return paths without allowing browser-normalized external redirects. */
export function safeLoginRedirect(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/') || value.startsWith('//')
      || /[\\\u0000-\u001f\u007f]/.test(value)) {
    return '/';
  }
  const origin = 'https://auraboot.invalid';
  try {
    const target = new URL(value, origin);
    if (target.origin !== origin) return '/';
    return target.pathname + target.search + target.hash;
  } catch {
    return '/';
  }
}
