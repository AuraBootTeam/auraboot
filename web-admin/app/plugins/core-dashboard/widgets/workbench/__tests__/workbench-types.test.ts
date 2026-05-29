import { describe, it, expect } from 'vitest';
import type { StatItem } from '../workbench-types';

describe('StatItem.series', () => {
  it('accepts an optional series with daily points', () => {
    const item: StatItem = {
      value: 241,
      label: 'workbench.stats.inbox_pending',
      series: {
        period: 'day',
        points: [220, 225, 223, 232, 235, 240, 241],
      },
    };
    expect(item.series?.points).toHaveLength(7);
    expect(item.series?.period).toBe('day');
  });

  it('allows series to be omitted', () => {
    const item: StatItem = { value: 0, label: 'workbench.stats.bpm_running' };
    expect(item.series).toBeUndefined();
  });
});
