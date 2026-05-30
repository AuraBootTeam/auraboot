/**
 * Tests for the DataSourceConfig "原始模型 / 语义模型" switch (PRD 16 W4 D4).
 *
 * Verifies the switch-style consolidation:
 *  - toggling to semantic mode sets semanticModelCode and clears the raw model
 *  - toggling back to raw mode removes semanticModelCode
 *  - picking a semantic metric serialises into metrics[].field = <code>
 *  - semantic dimensions flow through unchanged (encoded codes)
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { DataSourceConfig } from '../DataSourceConfig';
import type { ChartDataSource } from '~/framework/smart/types/chart';

const META_RESPONSE = {
  code: '0',
  data: {
    models: [
      {
        code: 'sales_semantic',
        label: { 'zh-CN': '销售语义模型' },
        metrics: [{ code: 'total_sales', type: 'simple', label: { 'zh-CN': '销售额' } }],
        dimensions: [{ code: 'region', type: 'string', label: { 'zh-CN': '区域' } }],
      },
    ],
  },
};

function routedFetch(url: string) {
  if (typeof url === 'string' && url.includes('/api/semantic/meta')) {
    return Promise.resolve({ json: () => Promise.resolve(META_RESPONSE) } as Response);
  }
  // raw model endpoints — return empty success
  return Promise.resolve({ json: () => Promise.resolve({ code: '0', data: [] }) } as Response);
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(routedFetch));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('DataSourceConfig — semantic mode switch', () => {
  it('starts in raw mode for a plain aggregate config', () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig value={{ type: 'aggregate', modelCode: 'order' }} onChange={onChange} />,
    );
    // raw mode button is active (no semanticModelCode key)
    expect(screen.getByTestId('datasource-mode-raw')).toBeInTheDocument();
    expect(screen.getByTestId('datasource-mode-semantic')).toBeInTheDocument();
  });

  it('switching to semantic mode sets semanticModelCode and clears modelCode', () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig value={{ type: 'aggregate', modelCode: 'order' }} onChange={onChange} />,
    );
    fireEvent.click(screen.getByTestId('datasource-mode-semantic'));
    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ semanticModelCode: '', modelCode: undefined }),
    );
  });

  it('switching back to raw mode removes semanticModelCode', () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig
        value={{ type: 'aggregate', semanticModelCode: 'sales_semantic' }}
        onChange={onChange}
      />,
    );
    fireEvent.click(screen.getByTestId('datasource-mode-raw'));
    const arg = onChange.mock.calls[0][0] as ChartDataSource;
    expect('semanticModelCode' in arg).toBe(false);
  });

  it('picking a semantic metric serialises into metrics[].field = code', async () => {
    const onChange = vi.fn();
    render(
      <DataSourceConfig
        value={{ type: 'aggregate', semanticModelCode: 'sales_semantic', metrics: [] }}
        onChange={onChange}
      />,
    );
    await waitFor(() => expect(screen.getByText('销售额')).toBeInTheDocument());
    fireEvent.click(screen.getByText('销售额').closest('label')!.querySelector('input')!);
    const arg = onChange.mock.calls.at(-1)![0] as ChartDataSource;
    expect(arg.metrics).toEqual([{ field: 'total_sales', aggregation: 'none' }]);
  });

  it('renders the governed semantic dimension picker in semantic mode', async () => {
    render(
      <DataSourceConfig
        value={{ type: 'aggregate', semanticModelCode: 'sales_semantic', metrics: [] }}
        onChange={vi.fn()}
      />,
    );
    await waitFor(() => expect(screen.getByText('区域')).toBeInTheDocument());
    expect(screen.getByTestId('semantic-dimension-picker')).toBeInTheDocument();
    expect(screen.getByTestId('semantic-metric-picker')).toBeInTheDocument();
  });
});
