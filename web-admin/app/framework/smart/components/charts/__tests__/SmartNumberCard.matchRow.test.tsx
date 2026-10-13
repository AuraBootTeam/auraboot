import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SmartNumberCard } from '../SmartNumberCard';

const mockUseChartData = vi.fn();

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

  it('cards sharing one field read different rows via matchRow instead of both reading the first row', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [
          { metric_code: 'm14_due_collection_rate', value: 98.6 },
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
          { field: 'value', label: 'M14', matchRow: { metric_code: 'm14_due_collection_rate' }, format: 'percent' },
          { field: 'value', label: 'M15', matchRow: { metric_code: 'm15_overdue_receivables' }, format: 'currency' },
        ]}
      />,
    );

    expect(screen.getByText('M14').parentElement?.textContent).toContain('98.6');
    expect(screen.getByText('M15').parentElement?.textContent).toContain('4,200');
  });

  it('a matchRow with no matching row renders the em dash instead of a foreign row value', () => {
    mockUseChartData.mockReturnValue({
      data: {
        rows: [{ metric_code: 'm14_due_collection_rate', value: 98.6 }],
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
          { field: 'value', label: 'M14', matchRow: { metric_code: 'm14_due_collection_rate' } },
          { field: 'value', label: 'M99', matchRow: { metric_code: 'does_not_exist' } },
        ]}
      />,
    );

    expect(screen.getByText('M14').parentElement?.textContent).toContain('98.6');
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
