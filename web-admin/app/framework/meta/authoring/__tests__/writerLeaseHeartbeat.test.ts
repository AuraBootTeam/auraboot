import { describe, expect, it } from 'vitest';
import {
  AUTHORING_WRITER_LEASE_RENEW_WINDOW_MS,
  shouldRenewAuthoringWriterLease,
  shouldRenewAuthoringWriterLeaseInForeground,
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

  it('attempts renewal inside the safety window only while the document is visible and focused', () => {
    const now = Date.parse('2026-08-11T12:00:00.000Z');
    const deadline = new Date(now + 30_000).toISOString();
    expect(shouldRenewAuthoringWriterLeaseInForeground(deadline, 'visible', true, now)).toBe(true);
    expect(shouldRenewAuthoringWriterLeaseInForeground(deadline, 'hidden', true, now)).toBe(false);
    expect(shouldRenewAuthoringWriterLeaseInForeground(deadline, 'visible', false, now)).toBe(
      false,
    );
  });
});
