import { afterEach, describe, expect, it, vi } from 'vitest';
import { startActiveSessionRenewal } from '../active-session';
afterEach(() => vi.useRealTimers());
describe('active session renewal', () => {
  it('throttles active visible tabs and stops when idle or hidden', async () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01'));
    Object.defineProperty(document, 'visibilityState', {value:'visible', configurable:true});
    const renew = vi.fn().mockResolvedValue(undefined);
    const stop = startActiveSessionRenewal(window, document, renew);
    await vi.advanceTimersByTimeAsync(0); expect(renew).toHaveBeenCalledTimes(1);
    window.dispatchEvent(new Event('keydown')); expect(renew).toHaveBeenCalledTimes(1);
    await vi.advanceTimersByTimeAsync(20*60_000); expect(renew).toHaveBeenCalledTimes(2);
    window.dispatchEvent(new Event('focus')); await vi.advanceTimersByTimeAsync(0);
    expect(renew).toHaveBeenCalledTimes(3);
    Object.defineProperty(document, 'visibilityState', {value:'hidden', configurable:true});
    await vi.advanceTimersByTimeAsync(10*60_000); expect(renew).toHaveBeenCalledTimes(3);
    stop(); window.dispatchEvent(new Event('focus')); expect(renew).toHaveBeenCalledTimes(3);
  });
  it('does not overlap requests', () => {
    vi.useFakeTimers(); vi.setSystemTime(new Date('2026-01-01'));
    Object.defineProperty(document, 'visibilityState', {value:'visible', configurable:true});
    const renew = vi.fn(() => new Promise<void>(() => {}));
    const stop = startActiveSessionRenewal(window, document, renew);
    vi.advanceTimersByTime(6*60_000); window.dispatchEvent(new Event('focus'));
    expect(renew).toHaveBeenCalledTimes(1); stop();
  });
});
