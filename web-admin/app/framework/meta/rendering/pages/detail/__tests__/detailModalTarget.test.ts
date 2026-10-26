import { describe, expect, it } from 'vitest';
import { resolveDetailModalTarget } from '../detailModalTarget';
describe('ordinary detail modal target', () => {
  it('keeps a public model/PID and uses the same loader route with explicit modal mode', () => {
    expect(resolveDetailModalTarget('/p/anc_alert/view/case-A')).toEqual({
      tableName: 'anc_alert',
      recordPid: 'case-A',
      width: 900,
      path: '/p/anc_alert/view/case-A?presentation=modal',
    });
  });
  it.each([
    'https://example.com/p/a/view/A',
    '/api/dynamic/a/A',
    '/p/a/new',
    '/p/a/view/',
    '/p/a/view/undefined',
    '/p/a/view/null',
    '/p/a/view/%2Fsecret',
    '/p/a/view/A?width=5000',
    '/p/a/view/A#tab',
  ])('rejects a non-standard or ambiguous target: %s', (path) =>
    expect(() => resolveDetailModalTarget(path)).toThrow(),
  );
  it.each([0, -1, NaN, Infinity, '900', {}])('rejects an invalid width %s', (width) => {
    expect(() => resolveDetailModalTarget('/p/a/view/A', width)).toThrow();
  });
});
