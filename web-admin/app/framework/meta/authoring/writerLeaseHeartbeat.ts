export const AUTHORING_WRITER_LEASE_HEARTBEAT_MS = 60_000;
export const AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS = 120_000;

export function shouldRenewAuthoringWriterLease(
  leasedUntil: string,
  now: number = Date.now(),
): boolean {
  const deadline = new Date(leasedUntil).getTime();
  return !Number.isFinite(deadline) || deadline - now <= AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS;
}

export function shouldRenewAuthoringWriterLeaseInForeground(
  leasedUntil: string,
  visibilityState: DocumentVisibilityState,
  hasFocus: boolean,
  now: number = Date.now(),
): boolean {
  return (
    visibilityState === 'visible' && hasFocus && shouldRenewAuthoringWriterLease(leasedUntil, now)
  );
}
