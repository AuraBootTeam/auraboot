import { describe, expect, it } from 'vitest';
import { hasIcon } from '../icon-resolver';

describe('icon-resolver aliases', () => {
  it('resolves legacy menu icon names that otherwise render duplicate text glyphs', () => {
    expect(hasIcon('partition')).toBe(true);
    expect(hasIcon('IconDashboard')).toBe(true);
    expect(hasIcon('global')).toBe(true);
    expect(hasIcon('translation')).toBe(true);
  });
});
