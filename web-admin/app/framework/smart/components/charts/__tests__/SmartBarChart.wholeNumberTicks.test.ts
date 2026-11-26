/**
 * SmartBarChart: a value axis that counts things keeps whole-number ticks.
 */
import { describe, expect, it } from 'vitest';
import type { EChartsOption } from 'echarts';
import { withWholeNumberTicks } from '../SmartBarChart';

const vertical = (values: number[]): EChartsOption => ({
  xAxis: { type: 'category', data: values.map((_, i) => `c${i}`) },
  yAxis: { type: 'value' },
  series: [{ type: 'bar', data: values }],
});

describe('withWholeNumberTicks', () => {
  it('tells the value axis to keep whole-number intervals when every plotted value is whole', () => {
    const next = withWholeNumberTicks(vertical([1, 6]));
    expect(next.yAxis).toEqual({ type: 'value', minInterval: 1 });
    expect(next.xAxis).toEqual({ type: 'category', data: ['c0', 'c1'] });
  });

  it('a single count of 2 no longer gets a 0 / 0.5 / 1 / 1.5 / 2 axis', () => {
    const next = withWholeNumberTicks(vertical([2]));
    expect((next.yAxis as { minInterval?: number }).minInterval).toBe(1);
  });

  it('limits the value axis wherever it is (horizontal bars put it on x)', () => {
    const option: EChartsOption = { xAxis: { type: 'value' }, yAxis: { type: 'category', data: ['a'] }, series: [{ type: 'bar', data: [3] }] };
    expect(withWholeNumberTicks(option).xAxis).toEqual({ type: 'value', minInterval: 1 });
    expect(withWholeNumberTicks(option).yAxis).toEqual({ type: 'category', data: ['a'] });
  });

  it('leaves the option untouched when one value has a fraction', () => {
    const option = vertical([1, 2.5]);
    expect(withWholeNumberTicks(option)).toBe(option);
  });

  it('leaves the option untouched when nothing is plotted', () => {
    const option = vertical([]);
    expect(withWholeNumberTicks(option)).toBe(option);
    expect(withWholeNumberTicks({ xAxis: { type: 'value' }, yAxis: {}, series: [] })).toEqual({ xAxis: { type: 'value' }, yAxis: {}, series: [] });
  });

  it('never overrides a minimum interval the caller already set', () => {
    const option: EChartsOption = { ...vertical([4]), yAxis: { type: 'value', minInterval: 5 } };
    expect(withWholeNumberTicks(option)).toBe(option);
  });

  it('does not mutate the option it was given', () => {
    const option = vertical([7]);
    withWholeNumberTicks(option);
    expect(option.yAxis).toEqual({ type: 'value' });
  });
});
