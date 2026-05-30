/**
 * Tests for the Dashboard semantic-layer pickers (PRD 16 W4 D4).
 *
 * Covers:
 *  1. encode/decode dimension helpers (pure, time-grain suffix)
 *  2. useSemanticModelMeta — fetches /api/semantic/meta, filters to the model
 *  3. SemanticMetricPicker — renders metric codes, toggles selection
 *  4. SemanticDimensionPicker — renders dims, grain dropdown for time dims
 */

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, renderHook, waitFor, fireEvent } from '@testing-library/react';
import {
  encodeDimension,
  decodeDimension,
  selectedValueFor,
} from '../SemanticDimensionPicker';
import { SemanticDimensionPicker } from '../SemanticDimensionPicker';
import { SemanticMetricPicker } from '../SemanticMetricPicker';
import { useSemanticModelMeta } from '../useMetaModels';
import type { SemanticMetricOption, SemanticDimensionOption } from '../types';

const META_RESPONSE = {
  code: '0',
  data: {
    models: [
      {
        code: 'sales_semantic',
        label: { 'zh-CN': '销售语义模型' },
        metrics: [
          { code: 'total_sales', type: 'simple', label: { 'zh-CN': '销售额' } },
          { code: 'avg_order_value', type: 'derived', label: { 'zh-CN': '客单价' } },
        ],
        dimensions: [
          { code: 'region', type: 'string', label: { 'zh-CN': '区域' } },
          {
            code: 'order_date',
            type: 'time',
            label: { 'zh-CN': '下单日期' },
            timeGrains: ['day', 'month', 'year'],
            primaryTime: true,
          },
        ],
      },
      { code: 'other_model', metrics: [{ code: 'x' }], dimensions: [] },
    ],
  },
};

function mockFetchOk(body: unknown) {
  return vi.fn().mockResolvedValue({ json: () => Promise.resolve(body) } as Response);
}

beforeEach(() => {
  vi.stubGlobal('fetch', mockFetchOk(META_RESPONSE));
});

afterEach(() => {
  vi.unstubAllGlobals();
});

// ---------------------------------------------------------------------------
// Case 1 — dimension encode/decode helpers
// ---------------------------------------------------------------------------
describe('dimension encode/decode helpers', () => {
  it('encodes a bare dimension without grain', () => {
    expect(encodeDimension('region')).toBe('region');
  });

  it('encodes a time dimension with grain suffix', () => {
    expect(encodeDimension('order_date', 'month')).toBe('order_date__month');
  });

  it('decodes a bare dimension', () => {
    expect(decodeDimension('region')).toEqual({ code: 'region' });
  });

  it('decodes a grain-suffixed dimension', () => {
    expect(decodeDimension('order_date__month')).toEqual({ code: 'order_date', grain: 'month' });
  });

  it('round-trips', () => {
    const v = encodeDimension('order_date', 'year');
    expect(decodeDimension(v)).toEqual({ code: 'order_date', grain: 'year' });
  });

  it('selectedValueFor matches by base code regardless of grain', () => {
    expect(selectedValueFor(['order_date__month', 'region'], 'order_date')).toBe(
      'order_date__month',
    );
    expect(selectedValueFor(['region'], 'order_date')).toBeUndefined();
  });
});

// ---------------------------------------------------------------------------
// Case 2 — useSemanticModelMeta hook
// ---------------------------------------------------------------------------
describe('useSemanticModelMeta', () => {
  it('returns empty lists when no model code given', () => {
    const { result } = renderHook(() => useSemanticModelMeta(undefined));
    expect(result.current.metrics).toEqual([]);
    expect(result.current.dimensions).toEqual([]);
  });

  it('fetches and filters metrics + dimensions for the model', async () => {
    const { result } = renderHook(() => useSemanticModelMeta('sales_semantic'));
    await waitFor(() => expect(result.current.metrics.length).toBe(2));
    expect(result.current.metrics.map((m: SemanticMetricOption) => m.code)).toEqual([
      'total_sales',
      'avg_order_value',
    ]);
    expect(result.current.metrics[0].name).toBe('销售额');
    expect(result.current.dimensions.map((d: SemanticDimensionOption) => d.code)).toEqual([
      'region',
      'order_date',
    ]);
    const timeDim = result.current.dimensions.find(
      (d: SemanticDimensionOption) => d.code === 'order_date',
    );
    expect(timeDim?.timeGrains).toEqual(['day', 'month', 'year']);
  });

  it('returns empty lists when model is not in the catalog', async () => {
    const { result } = renderHook(() => useSemanticModelMeta('missing_model'));
    await waitFor(() => expect((fetch as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0));
    expect(result.current.metrics).toEqual([]);
    expect(result.current.dimensions).toEqual([]);
  });
});

// ---------------------------------------------------------------------------
// Case 3 — SemanticMetricPicker
// ---------------------------------------------------------------------------
describe('SemanticMetricPicker', () => {
  it('prompts to pick a model when none selected', () => {
    render(<SemanticMetricPicker semanticModelCode={undefined} value={[]} onChange={vi.fn()} />);
    expect(screen.getByText('请先选择语义模型')).toBeInTheDocument();
  });

  it('lists the model metrics and toggles selection by code', async () => {
    const onChange = vi.fn();
    render(
      <SemanticMetricPicker semanticModelCode="sales_semantic" value={[]} onChange={onChange} />,
    );
    await waitFor(() => expect(screen.getByText('销售额')).toBeInTheDocument());
    fireEvent.click(screen.getByText('销售额').closest('label')!.querySelector('input')!);
    expect(onChange).toHaveBeenCalledWith(['total_sales']);
  });

  it('unchecks a selected metric', async () => {
    const onChange = vi.fn();
    render(
      <SemanticMetricPicker
        semanticModelCode="sales_semantic"
        value={['total_sales']}
        onChange={onChange}
      />,
    );
    await waitFor(() => expect(screen.getByText('销售额')).toBeInTheDocument());
    fireEvent.click(screen.getByText('销售额').closest('label')!.querySelector('input')!);
    expect(onChange).toHaveBeenCalledWith([]);
  });
});

// ---------------------------------------------------------------------------
// Case 4 — SemanticDimensionPicker
// ---------------------------------------------------------------------------
describe('SemanticDimensionPicker', () => {
  it('selecting a time dimension defaults to the first grain', async () => {
    const onChange = vi.fn();
    render(
      <SemanticDimensionPicker semanticModelCode="sales_semantic" value={[]} onChange={onChange} />,
    );
    await waitFor(() => expect(screen.getByText('下单日期')).toBeInTheDocument());
    fireEvent.click(screen.getByText('下单日期').closest('label')!.querySelector('input')!);
    expect(onChange).toHaveBeenCalledWith(['order_date__day']);
  });

  it('shows a grain dropdown for a selected time dimension and changes grain', async () => {
    const onChange = vi.fn();
    render(
      <SemanticDimensionPicker
        semanticModelCode="sales_semantic"
        value={['order_date__day']}
        onChange={onChange}
      />,
    );
    await waitFor(() => expect(screen.getByText('下单日期')).toBeInTheDocument());
    const grainSelect = screen.getByLabelText('order_date 粒度') as HTMLSelectElement;
    expect(grainSelect.value).toBe('day');
    fireEvent.change(grainSelect, { target: { value: 'month' } });
    expect(onChange).toHaveBeenCalledWith(['order_date__month']);
  });

  it('non-time dimension encodes as bare code', async () => {
    const onChange = vi.fn();
    render(
      <SemanticDimensionPicker semanticModelCode="sales_semantic" value={[]} onChange={onChange} />,
    );
    await waitFor(() => expect(screen.getByText('区域')).toBeInTheDocument());
    fireEvent.click(screen.getByText('区域').closest('label')!.querySelector('input')!);
    expect(onChange).toHaveBeenCalledWith(['region']);
  });
});
