/**
 * SmartNumberCard caption: the line under the value must say something the eyebrow above has not
 * already said. (A KPI card used to read「已接入应用 / 9 / ● 已接入应用」.)
 */
import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartNumberCard } from '../SmartNumberCard';

const mockUseChartData = vi.fn();
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: { en: string }) => value.en }));
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const countSource = { type: 'aggregate' as const, modelCode: 'orb_application', metrics: [{ field: 'pid', aggregation: 'count' as const, alias: 'count' }] };
const oneRow = (value: number) => ({
  data: { rows: [{ count: value }], summary: {}, meta: { dimensions: [], metrics: ['count'] } },
  loading: false,
  error: null,
  refetch: vi.fn(),
});

describe('SmartNumberCard caption', () => {
  beforeEach(() => mockUseChartData.mockReset());

  it('prints the title once when there is nothing else to say', () => {
    mockUseChartData.mockReturnValue(oneRow(9));
    render(<SmartNumberCard title="已接入应用" dataSource={countSource} />);
    expect(screen.getAllByText('已接入应用')).toHaveLength(1);
    expect(screen.getByText('9')).toBeInTheDocument();
  });

  it('uses a configured description as the caption', () => {
    mockUseChartData.mockReturnValue(oneRow(9));
    render(<SmartNumberCard title="已接入应用" description="已纳入交付治理的应用目录" dataSource={countSource} />);
    expect(screen.getAllByText('已接入应用')).toHaveLength(1);
    expect(screen.getByText('已纳入交付治理的应用目录')).toBeInTheDocument();
  });

  it('keeps the title as the caption when a separate label sits above the value', () => {
    mockUseChartData.mockReturnValue(oneRow(9));
    render(<SmartNumberCard title="已接入应用" label="应用" dataSource={countSource} />);
    expect(screen.getAllByText('应用')).toHaveLength(1);
    expect(screen.getAllByText('已接入应用')).toHaveLength(1);
  });

  it('still shows the colour dot next to the value when there is no caption', () => {
    mockUseChartData.mockReturnValue(oneRow(9));
    const { container } = render(<SmartNumberCard title="已接入应用" dataSource={countSource} />);
    // the legend dot (8×8); the thin accent bar under the eyebrow is a different element
    expect(container.querySelectorAll('.h-2.w-2.rounded-full')).toHaveLength(1);
    expect(screen.queryByText('已接入应用', { selector: 'span:not(.truncate)' })).toBeNull();
  });
});
