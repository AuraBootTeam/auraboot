import { describe, expect, it } from 'vitest';
import { assertRssBudget, assertSteadyTotals, canonicalJson, sseEvents } from '../../tests/acceptance/agent-approval/steady-contract';

describe('bounded product qualification cannot report an early or empty green', () => {
  it('detects duplicate tool arguments regardless of object key order', () => {
    expect(canonicalJson({ model: 'orders', filter: { id: 1, state: 'OPEN' } }))
      .toBe(canonicalJson({ filter: { state: 'OPEN', id: 1 }, model: 'orders' }));
    expect(canonicalJson([1, 2])).not.toBe(canonicalJson([2, 1]));
    expect(() => canonicalJson(undefined)).toThrow();
  });
  it('requires the whole duration and exact workload', () => {
    expect(() => assertSteadyTotals(7_200_000, 960, 24, 1)).not.toThrow();
    for (const args of [[7_199_999, 960, 24, 1], [7_200_000, 959, 24, 1], [7_200_000, 960, 23, 1], [7_200_000, 960, 24, 0], [7_200_000, 961, 24, 1]]) {
      expect(() => assertSteadyTotals(...args as [number, number, number, number])).toThrow();
    }
  });
  it('rejects missing samples and a failing final resource window', () => {
    expect(assertRssBudget(Array(15).fill(100_000), Array(15).fill(200_000)).finalKiB).toBe(200_000);
    expect(() => assertRssBudget([], Array(15).fill(200_000))).toThrow();
    expect(() => assertRssBudget(Array(15).fill(100_000), Array(15).fill(500_000))).toThrow();
    expect(() => assertRssBudget(Array(15).fill(100_000), Array(15).fill(NaN))).toThrow();
  });
  it('decodes real SSE framing without treating malformed output as a completed turn', () => {
    expect(sseEvents('event: done\ndata: {"content":"answer"}\n\n')[0].data.content).toBe('answer');
    expect(sseEvents('event: tool_result\ndata: "{\\"success\\":true}"\n\n')[0].data.success).toBe(true);
    expect(() => sseEvents('event: done\ndata: []\n\n')).toThrow();
    expect(() => sseEvents('event: done\ndata: broken\n\n')).toThrow();
  });
});
