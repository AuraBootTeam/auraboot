import { describe, expect, it } from 'vitest';
import { SSR_LOADER_FETCH_TIMEOUT_MS, fetchTimeoutSignal } from '../fetchTimeout';

describe('fetchTimeoutSignal', () => {
  it('returns a non-aborted AbortSignal', () => {
    const signal = fetchTimeoutSignal();
    expect(signal).toBeInstanceOf(AbortSignal);
    expect(signal?.aborted).toBe(false);
  });

  it('defaults the deadline to 10 seconds', () => {
    expect(SSR_LOADER_FETCH_TIMEOUT_MS).toBe(10_000);
  });

  it('falls back to undefined when AbortSignal.timeout is unavailable', () => {
    const original = AbortSignal.timeout;
    Object.defineProperty(AbortSignal, 'timeout', { value: undefined, configurable: true });
    try {
      expect(fetchTimeoutSignal()).toBeUndefined();
    } finally {
      Object.defineProperty(AbortSignal, 'timeout', { value: original, configurable: true });
    }
  });
});
