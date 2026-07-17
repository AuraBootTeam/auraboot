/**
 * Tests for the DataSourceConfig `api` data source type and the named-query
 * parameter editor (armory-show G5).
 *
 * The runtime (useChartData / SmartTableChart / SmartNumberCard) consumes an api
 * source as a GET to `url` with `params` as query params, so the Designer must let
 * users author exactly those two. Named queries carry a `parameters` map that
 * previously had no UI.
 *
 * Verifies:
 *  - the type dropdown offers `api`
 *  - switching to api renders the url field + api params editor, and hides
 *    aggregate/limit controls that the api branch ignores
 *  - typing a url writes `dataSource.url`
 *  - api params editor writes into `dataSource.params`
 *  - the named-query params editor writes into `dataSource.parameters`
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { DataSourceConfig } from '../DataSourceConfig';
import type { ChartDataSource } from '~/framework/smart/types/chart';

function routedFetch() {
  // No model/query metadata needed for these paths — return empty success.
  return Promise.resolve({ json: () => Promise.resolve({ code: '0', data: [] }) } as Response);
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(routedFetch));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DataSourceConfig — api data source', () => {
  it('offers the api option in the type dropdown', () => {
    render(<DataSourceConfig value={{ type: 'aggregate' }} onChange={vi.fn()} />);
    const select = screen.getByTestId('dashboard-datasource-type-select') as HTMLSelectElement;
    const options = Array.from(select.options).map((o) => o.value);
    expect(options).toContain('api');
  });

  it('switching to api emits an api-typed data source with url + params scaffolding', () => {
    const onChange = vi.fn();
    render(<DataSourceConfig value={{ type: 'aggregate', modelCode: 'order' }} onChange={onChange} />);

    fireEvent.change(screen.getByTestId('dashboard-datasource-type-select'), {
      target: { value: 'api' },
    });

    const arg = onChange.mock.calls.at(-1)![0] as ChartDataSource;
    expect(arg.type).toBe('api');
    expect(arg.params).toEqual({});
    // aggregate-only fields dropped
    expect(arg.modelCode).toBeUndefined();
  });

  it('renders the api url field + params editor and hides aggregate/limit controls', () => {
    render(<DataSourceConfig value={{ type: 'api', url: '', params: {} }} onChange={vi.fn()} />);

    expect(screen.getByTestId('dashboard-datasource-api-url')).toBeInTheDocument();
    expect(screen.getByTestId('dashboard-datasource-api-params')).toBeInTheDocument();
    // aggregate mode toggle and static json must not show for api
    expect(screen.queryByTestId('datasource-mode-raw')).toBeNull();
    expect(screen.queryByTestId('dashboard-datasource-static-json')).toBeNull();
    // Filters/limit are not consumed by the api branch → hidden
    expect(screen.queryByText('返回行数限制')).toBeNull();
  });

  it('typing a url writes dataSource.url', () => {
    const onChange = vi.fn();
    render(<DataSourceConfig value={{ type: 'api', url: '', params: {} }} onChange={onChange} />);

    fireEvent.change(screen.getByTestId('dashboard-datasource-api-url'), {
      target: { value: '/api/metrics/daily' },
    });

    const arg = onChange.mock.calls.at(-1)![0] as ChartDataSource;
    expect(arg.url).toBe('/api/metrics/daily');
  });

  it('adding an api param writes into dataSource.params', () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig value={{ type: 'api', url: '/api/x', params: {} }} onChange={onChange} />,
    );

    fireEvent.click(screen.getByTestId('dashboard-datasource-api-params-add'));
    fireEvent.change(screen.getByTestId('dashboard-datasource-api-params-key'), {
      target: { value: 'range' },
    });
    fireEvent.change(screen.getByTestId('dashboard-datasource-api-params-value'), {
      target: { value: '7d' },
    });

    const arg = onChange.mock.calls.at(-1)![0] as ChartDataSource;
    expect(arg.params).toEqual({ range: '7d' });
  });
});

describe('DataSourceConfig — named query parameters editor', () => {
  it('renders the params editor for a named query source', () => {
    render(
      <DataSourceConfig
        value={{ type: 'namedQuery', queryCode: 'sales_by_region', parameters: {} }}
        onChange={vi.fn()}
      />,
    );
    expect(screen.getByTestId('dashboard-datasource-namedquery-params')).toBeInTheDocument();
  });

  it('adding a named-query parameter writes into dataSource.parameters', () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig
        value={{ type: 'namedQuery', queryCode: 'sales_by_region', parameters: {} }}
        onChange={onChange}
      />,
    );

    fireEvent.click(screen.getByTestId('dashboard-datasource-namedquery-params-add'));
    fireEvent.change(screen.getByTestId('dashboard-datasource-namedquery-params-key'), {
      target: { value: 'region' },
    });
    fireEvent.change(screen.getByTestId('dashboard-datasource-namedquery-params-value'), {
      target: { value: 'east' },
    });

    const arg = onChange.mock.calls.at(-1)![0] as ChartDataSource;
    expect(arg.parameters).toEqual({ region: 'east' });
  });
});
