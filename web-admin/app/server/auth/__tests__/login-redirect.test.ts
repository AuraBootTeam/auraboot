import { describe, expect, it } from 'vitest';
import { safeLoginRedirect } from '../login-redirect';

describe('login return-path boundary', () => {
  it.each(['/p/project?tab=details#materials', '/tenant-selection', '/'])
    ('preserves a local business path %s', (path) => expect(safeLoginRedirect(path)).toBe(path));

  it.each([undefined, null, 42, 'https://evil.example', '//evil.example', '/\\evil.example',
    '/\r\n//evil.example', '/\t/evil.example', 'javascript:alert(1)'])
    ('rejects non-local, normalized-authority and control-character input', (value) => {
      expect(safeLoginRedirect(value)).toBe('/');
    });

  it('normalizes valid encoded business queries without changing origin', () => {
    const result = safeLoginRedirect('/p/project?search=annual plan');
    const target = new URL(result, 'https://app.example');
    expect(target.origin).toBe('https://app.example');
    expect(target.searchParams.get('search')).toBe('annual plan');
  });
});
