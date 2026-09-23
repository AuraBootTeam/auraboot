import { afterEach, describe, expect, it, vi } from 'vitest';
import { createLatestRequestGate, createRefreshController } from '../asyncControl';

afterEach(() => vi.useRealTimers());

describe('createRefreshController', () => {
  it('collapses overlapping requests into one trailing refresh', async () => {
    let release!: () => void;
    const first = new Promise<void>((resolve) => {
      release = resolve;
    });
    const task = vi
      .fn()
      .mockImplementationOnce(() => first)
      .mockResolvedValue(undefined);
    const controller = createRefreshController(task);

    void controller.request();
    await Promise.resolve();
    void controller.request();
    void controller.request();
    expect(task).toHaveBeenCalledTimes(1);

    release();
    await first;
    await vi.waitFor(() => expect(task).toHaveBeenCalledTimes(2));
    controller.dispose();
  });

  it('debounces realtime event bursts', async () => {
    vi.useFakeTimers();
    const task = vi.fn().mockResolvedValue(undefined);
    const controller = createRefreshController(task);

    controller.schedule(750);
    controller.schedule(750);
    controller.schedule(750);
    await vi.advanceTimersByTimeAsync(749);
    expect(task).not.toHaveBeenCalled();
    await vi.advanceTimersByTimeAsync(1);
    expect(task).toHaveBeenCalledTimes(1);
    controller.dispose();
  });
});

describe('createLatestRequestGate', () => {
  it('invalidates older detail requests and cancelled work', () => {
    const gate = createLatestRequestGate();
    const first = gate.start();
    const second = gate.start();
    expect(gate.isCurrent(first)).toBe(false);
    expect(gate.isCurrent(second)).toBe(true);
    gate.cancel();
    expect(gate.isCurrent(second)).toBe(false);
  });
});
