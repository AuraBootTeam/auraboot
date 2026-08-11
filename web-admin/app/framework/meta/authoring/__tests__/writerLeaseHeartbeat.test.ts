import { describe, expect, it } from 'vitest';
import {
  AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS,
  shouldRenewAuthoringWriterLease,
} from '../writerLeaseHeartbeat';

describe('writerLeaseHeartbeat', () => {
  it('renews only inside the safety window and fails safe for an invalid deadline', () => {
    const now = Date.parse('2026-08-11T12:00:00.000Z');
    expect(
      shouldRenewAuthoringWriterLease(
        new Date(now + AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS + 1).toISOString(),
        now,
      ),
    ).toBe(false);
    expect(
      shouldRenewAuthoringWriterLease(
        new Date(now + AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS).toISOString(),
        now,
      ),
    ).toBe(true);
    expect(shouldRenewAuthoringWriterLease('invalid-deadline', now)).toBe(true);
  });
});
