import { describe, expect, it } from 'vitest';
import {
  describeWriterLeaseTakeoverFailure,
  reconcileWriterLeaseTakeover,
} from '../writerLeaseTakeover';

describe('writerLeaseTakeover', () => {
  it('distinguishes a committed local takeover from another winner and an unchanged lease', () => {
    expect(
      reconcileWriterLeaseTakeover(4, {
        status: 'OWNED',
        revision: 5,
        leasedUntil: '2026-08-12T12:05:00Z',
      }),
    ).toBe('COMMITTED_HERE');
    expect(
      reconcileWriterLeaseTakeover(4, {
        status: 'HELD_BY_OTHER_SESSION',
        revision: 5,
        leasedUntil: '2026-08-12T12:05:00Z',
      }),
    ).toBe('COMMITTED_ELSEWHERE');
    expect(
      reconcileWriterLeaseTakeover(4, {
        status: 'HELD_BY_OTHER_SESSION',
        revision: 4,
        leasedUntil: '2026-08-12T12:05:00Z',
      }),
    ).toBe('UNCHANGED');
  });

  it('normalizes a network partition but preserves a specific service failure', () => {
    expect(
      describeWriterLeaseTakeoverFailure(new Error('Network error: Failed to fetch'), true),
    ).toBe('网络中断，未取得编辑权；当前仍为只读。请恢复网络后重试，当前页面未被覆盖。');
    expect(
      describeWriterLeaseTakeoverFailure(new Error('Network error: Failed to fetch'), false),
    ).toBe('网络中断，无法确认编辑权状态；为安全起见当前仍为只读。请恢复网络后刷新页面。');
    expect(describeWriterLeaseTakeoverFailure(new Error('Permission denied'), true)).toBe(
      'Permission denied',
    );
  });
});
