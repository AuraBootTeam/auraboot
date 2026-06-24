import { describe, expect, it } from 'vitest';
import { resolveRouteRecordPid } from '../p.$pageKey.view';

describe('dynamic detail route params', () => {
  it('accepts the current route-manifest recordId param', () => {
    expect(resolveRouteRecordPid({ recordId: '01KREC' })).toBe('01KREC');
  });

  it('keeps compatibility with recordPid route params', () => {
    expect(resolveRouteRecordPid({ recordPid: '01KPID' })).toBe('01KPID');
  });
});
