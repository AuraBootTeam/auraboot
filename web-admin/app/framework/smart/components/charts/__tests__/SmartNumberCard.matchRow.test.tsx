import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartNumberCard } from '../SmartNumberCard';

const mockUseChartData = vi.fn();
vi.mock('~/utils/i18n', () => ({ useSmartText: () => (value: { en: string }) => value.en }));

vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const nqSource = {
  type: 'namedQuery' as const,
  queryCode: 'amos_receivables_state_query',
};

describe('SmartNumberCard matchRow selection', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
  });

  it('explains denied data access without exposing the infrastructure error', () => {
    mockUseChartData.mockReturnValue({ data: null, loading: false, error: new Error('Access forbidden') });
    render(<SmartNumberCard title="Delivery" dataSource={nqSource} />);
    expect(screen.getByRole('alert')).toHaveTextContent('Ask an administrator to check source data permissions.');
    expect(screen.queryByText('Access forbidden')).not.toBeInTheDocument();
  });

  it('cards sharing one field read different rows via matchRow instead of both reading the first row', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [
          { metric_code: 'm14_due_collection_rate', value: 98 },
          { metric_code: 'm15_overdue_receivables', value: 4200 },
        ],
        summary: {},
        meta: { dimensions: [], metrics: [] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartNumberCard
        title="Receivables KPI"
        dataSource={nqSource}
        cards={[
          { field: 'value', label: 'M14', matchRow: { metric_code: 'm14_due_collection_rate' }, format: 'number' },
          { field: 'value', label: 'M15', matchRow: { metric_code: 'm15_overdue_receivables' }, format: 'number' },
        ]}
      />,
    );

    expect(screen.getByText('M14').parentElement?.textContent).toContain('98');
    expect(screen.getByText('M15').parentElement?.textContent).toContain('4,200');
  });

  it('a matchRow with no matching row renders the em dash instead of a foreign row value', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ metric_code: 'm14_due_collection_rate', value: 98 }],
        summary: {},
        meta: { dimensions: [], metrics: [] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartNumberCard
        title="Receivables KPI"
        dataSource={nqSource}
        cards={[
          { field: 'value', label: 'M14', matchRow: { metric_code: 'm14_due_collection_rate' }, format: 'number' },
          { field: 'value', label: 'M99', matchRow: { metric_code: 'does_not_exist' }, format: 'number' },
        ]}
      />,
    );

    expect(screen.getByText('M14').parentElement?.textContent).toContain('98');
    expect(screen.getByText('M99').parentElement?.textContent).toContain('—');
  });

  it('cards without matchRow keep reading the first row', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [
          { total: 11 },
          { total: 22 },
        ],
        summary: {},
        meta: { dimensions: [], metrics: [] },
      },
      loading: false,
      error: null,
      refetch: vi.fn(),
    });

    render(
      <SmartNumberCard
        title="Totals"
        dataSource={nqSource}
        cards={[{ field: 'total', label: 'Total' }]}
      />,
    );

    expect(screen.getByText('Total').parentElement?.textContent).toContain('11');
  });
});


describe('SmartNumberCard atomic metric values', () => {
  it.each([
    { amount: 36000, format: 'currency' as const, precision: 2, expected: '¥36,000.00' },
    { amount: -123456789.12, format: 'currency' as const, precision: 2, expected: '-¥123,456,789.12' },
    { amount: 0.6825, format: 'percent' as const, precision: 2, expected: '68.25%' },
  ])('keeps $expected readable as one complete metric', ({ amount, format, precision, expected }) => {
    mockUseChartData.mockReturnValue({ data: { rows: [{ amount }], meta: { metrics: ['amount'] } }, loading: false, error: null });
    const view = render(<SmartNumberCard title="Delivery" dataSource={nqSource} format={format} precision={precision} />);
    const value = screen.getByText(expected);
    expect(value).toHaveAttribute('title', expected);
    expect(value).toHaveStyle({ whiteSpace: 'nowrap', overflowX: 'auto' });
    expect(value.style.overflowWrap).not.toBe('anywhere');
    view.unmount();
  });
  it('keeps multi-card currency intact without losing each card drill-down', () => {
    mockUseChartData.mockReturnValue({ data: { rows: [{ amount: 36000 }], meta: { metrics: ['amount'] } }, loading: false, error: null });
    const onDrillDown = vi.fn();
    render(<SmartNumberCard title="Delivery" dataSource={nqSource} precision={2} cards={[{ field: 'amount', label: 'Accepted', format: 'currency', drillDown: { enabled: true, action: 'navigate', targetPage: '/p/delivery' } }]} onDrillDown={onDrillDown} />);
    const value = screen.getByText('¥36,000.00');
    expect(value).toHaveStyle({ whiteSpace: 'nowrap', overflowX: 'auto' });
    expect(value).toHaveAttribute('title', '¥36,000.00');
    screen.getByTestId('number-card-amount-drilldown').click();
    expect(onDrillDown).toHaveBeenCalledOnce();
  });

});
