import React from 'react';
import { render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ChartWrapper } from '../shared/ChartWrapper';

// ChartWrapper's not-configured / error / empty placeholders were hardcoded Chinese; they now go
// through useSmartText('$i18n:chart.*', '<English fallback>'). With no i18n provider in the test
// env, st() returns the fallback — these assert the placeholders render (and didn't regress).

const mockUseChartData = vi.fn();
vi.mock('~/framework/smart/hooks/useChartData', () => ({
  useChartData: (...args: unknown[]) => mockUseChartData(...args),
}));

const aggregateSource = {
  type: 'aggregate' as const,
  modelCode: 'crm_account',
  metrics: [{ field: 'id', aggregation: 'count' as const, alias: 'count' }],
};
const child = () => <div data-testid="chart-content" />;

describe('ChartWrapper i18n placeholder states', () => {
  beforeEach(() => {
    mockUseChartData.mockReset();
    mockUseChartData.mockReturnValue({ data: { rows: [{}] }, loading: false, error: null, refetch: vi.fn() });
  });

  it('renders the localized not-configured placeholder', () => {
    render(<ChartWrapper title="T">{child}</ChartWrapper>);
    expect(screen.getByText('Configure a data source')).toBeInTheDocument();
  });

  it('renders the localized error placeholder', () => {
    mockUseChartData.mockReturnValue({ data: null, loading: false, error: new Error('boom'), refetch: vi.fn() });
    render(<ChartWrapper title="T" dataSource={aggregateSource}>{child}</ChartWrapper>);
    expect(screen.getByText('Failed to load data')).toBeInTheDocument();
  });

  it('renders the localized empty placeholder', () => {
    mockUseChartData.mockReturnValue({ data: { rows: [] }, loading: false, error: null, refetch: vi.fn() });
    render(<ChartWrapper title="T" dataSource={aggregateSource}>{child}</ChartWrapper>);
    expect(screen.getByText('No data')).toBeInTheDocument();
  });
});
