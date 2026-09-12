import React from 'react';
import type { ChartDataSource } from '~/framework/smart/types/chart';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';

vi.mock('~/plugins/core-dashboard/services/dashboardService', () => ({
  dashboardService: { create: vi.fn().mockResolvedValue({ pid: 'saved-dashboard', code: 'saved-dashboard-code' }) },
}));
import { dashboardService } from '~/plugins/core-dashboard/services/dashboardService';

import { ChatBiResultCard } from '../ChatBiResultCard';

describe('ChatBiResultCard', () => {
  it('renders raw records as a table when columns metadata is absent', () => {
    render(
      <ChatBiResultCard
        result={{
          total: 1,
          records: [
            {
              product_id: '01PRODUCT',
              supplier_id: '01SUPPLIER',
              supplier_name: 'Shenzhen Precision Components',
              unit_price: 8.75,
            },
          ],
        }}
      />,
    );

    expect(screen.getByText('数据查询')).toBeInTheDocument();
    expect(screen.getByText('supplier_name')).toBeInTheDocument();
    expect(screen.getByText('Shenzhen Precision Components')).toBeInTheDocument();
    expect(screen.getByText('01SUPPLIER')).toBeInTheDocument();
  });
});

// A saved chart must execute the exact same filtered query after reload.
it('persists the full query instead of broadening a filtered result', async () => {
  const dataSource: ChartDataSource = {
    type: 'aggregate' as const,
    modelCode: 'orders',
    semanticModelCode: 'sales',
    dimensions: ['region'],
    metrics: [{ field: 'total_sales', aggregation: 'sum' }],
    filters: [{ field: 'region', operator: 'eq', value: 'East' }],
    orderBy: [{ field: 'total_sales', direction: 'desc' }],
    limit: 5,
  };
  render(
    <ChatBiResultCard
      result={{
        modelCode: 'orders',
        dimensions: dataSource.dimensions,
        metrics: dataSource.metrics,
        dataSource,
        chartType: 'table',
        columns: ['region', 'total_sales'],
        records: [{ region: 'East', total_sales: 120 }],
      }}
    />,
  );
  fireEvent.click(screen.getByTestId('chatbi-save-dashboard'));
  await waitFor(() => expect(dashboardService.create).toHaveBeenCalled());
  expect(
    vi.mocked(dashboardService.create).mock.calls.at(-1)?.[0].widgets?.[0].config.dataSource,
  ).toEqual(dataSource);
});
