import { describe, expect, it } from 'vitest';
import { redactSensitiveUrl } from '../sensitive-url';
describe('credential URL diagnostics', () => {
  it('masks share tokens and encoded credential values while preserving useful nonsecret query context', () => {
    expect(
      redactSensitiveUrl('/api/views/shared/private-token?password=p%26secret&mode=read'),
    ).toBe('/api/views/shared/[REDACTED]?password=[REDACTED]&mode=read');
    expect(redactSensitiveUrl('http://runtime.invalid/share/private-token')).toBe(
      'http://runtime.invalid/share/[REDACTED]',
    );
    expect(redactSensitiveUrl('/api/example?access_token=private&limit=10')).toBe(
      '/api/example?access_token=[REDACTED]&limit=10',
    );
  });
  it('does not rewrite ordinary paths or turn a log projection into a transport mutation', () => {
    const url = '/api/views/personal?modelCode=ab_team&pageKey=ab_team_list';
    expect(redactSensitiveUrl(url)).toBe(url);
  });
});
