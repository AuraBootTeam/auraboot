import type { AuthoringWriterLease } from './types';

export type WriterLeaseTakeoverReconciliation =
  | 'COMMITTED_HERE'
  | 'COMMITTED_ELSEWHERE'
  | 'UNCHANGED';

export function reconcileWriterLeaseTakeover(
  observedLeaseRevision: number,
  authoritativeLease?: AuthoringWriterLease,
): WriterLeaseTakeoverReconciliation {
  if (!authoritativeLease || authoritativeLease.revision <= observedLeaseRevision) {
    return 'UNCHANGED';
  }
  return authoritativeLease.status === 'OWNED' ? 'COMMITTED_HERE' : 'COMMITTED_ELSEWHERE';
}

export function describeWriterLeaseTakeoverFailure(
  failure: unknown,
  authoritativeReloaded: boolean,
): string {
  const message = failure instanceof Error ? failure.message : '无法接管 ChangeSet 编辑权';
  if (!isNetworkFailure(message)) return message;
  return authoritativeReloaded
    ? '网络中断，未取得编辑权；当前仍为只读。请恢复网络后重试，当前页面未被覆盖。'
    : '网络中断，无法确认编辑权状态；为安全起见当前仍为只读。请恢复网络后刷新页面。';
}

function isNetworkFailure(message: string): boolean {
  return /(network error|failed to fetch|load failed|network request failed|timeout)/i.test(
    message,
  );
}
