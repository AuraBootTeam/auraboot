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
import { render, screen, renderHook, waitFor, fireEvent, act } from '@testing-library/react';
import {
  encodeDimension,
  decodeDimension,
  selectedValueFor,
} from '../SemanticDimensionPicker';
import { SemanticDimensionPicker } from '../SemanticDimensionPicker';
import { SemanticMetricPicker } from '../SemanticMetricPicker';
import { useSemanticModelMeta, useSemanticModels } from '../useMetaModels';
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
  return vi.fn().mockResolvedValue({ ok: true, status: 200, json: () => Promise.resolve(body) } as Response);
}

beforeEach(() => {
  vi.stubGlobal('fetch', mockFetchOk(META_RESPONSE));
});

describe('semantic model catalog failure boundaries', () => {
  for (const [status, kind] of [[401, 'denied'], [403, 'denied'], [500, 'failed']] as const) {
    it(`rejects HTTP ${status} even when the body resembles success`, async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, json: async () => META_RESPONSE }));
      const { result } = renderHook(() => useSemanticModels());
      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(result.current.error?.kind).toBe(kind);
      expect(result.current.models).toEqual([]);
    });
  }
  for (const body of [{ code: '1', message: 'private SQL detail', data: META_RESPONSE.data },
    { code: '0', data: { models: {} } }, { code: '0', data: { models: [null] } }]) {
    it('reports an invalid catalog instead of presenting an empty success', async () => {
      vi.stubGlobal('fetch', mockFetchOk(body));
      const { result } = renderHook(() => useSemanticModels());
      await waitFor(() => expect(result.current.isLoading).toBe(false));
      expect(result.current.error?.kind).toBe('failed');
      expect(result.current.models).toEqual([]);
    });
  }
  it('accepts a real successful empty catalog without fabricating an error', async () => {
    vi.stubGlobal('fetch', mockFetchOk({ code: '0', data: { models: [] } }));
    const { result } = renderHook(() => useSemanticModels());
    await waitFor(() => expect(result.current.isLoading).toBe(false));
    expect(result.current.error).toBeNull(); expect(result.current.models).toEqual([]);
  });
  it('invalidates obsolete options and pending responses when explicitly reloading', async () => {
    let obsolete!: (value: Response) => void;
    const fetchMock = mockFetchOk(META_RESPONSE)
      .mockImplementationOnce(() => new Promise<Response>(resolve => { obsolete = resolve; }));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = renderHook(() => useSemanticModels());
    act(() => result.current.refetch());
    await waitFor(() => expect(result.current.models[0]?.code).toBe('sales_semantic'));
    await act(async () => obsolete({ ok: false, status: 403, json: async () => ({}) } as Response));
    expect(result.current.error).toBeNull();
    expect(result.current.models[0]?.code).toBe('sales_semantic');
    expect(fetchMock).toHaveBeenCalledTimes(2);
  });
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


describe('semantic metadata failure boundaries', () => {
  type MetaState = ReturnType<typeof useSemanticModelMeta> & { error?: { kind: string } | null };
  for (const [status, kind] of [[403, 'denied'], [401, 'denied'], [500, 'failed']] as const) {
    it(`rejects HTTP ${status} even if its body claims success`, async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status, json: async () => META_RESPONSE }));
      const { result } = renderHook(() => useSemanticModelMeta('sales_semantic'));
      await waitFor(() => expect((result.current as MetaState).error?.kind).toBe(kind));
      expect(result.current.metrics).toEqual([]);
      expect(result.current.dimensions).toEqual([]);
      expect(result.current.isLoading).toBe(false);
    });
  }
  for (const body of [{ code: '1', message: 'private SQL detail' }, { code: '0', data: { models: {} } }]) {
    it(`rejects an unsuccessful or malformed catalog: ${JSON.stringify(body)}`, async () => {
      vi.stubGlobal('fetch', mockFetchOk(body));
      const { result } = renderHook(() => useSemanticModelMeta('sales_semantic'));
      await waitFor(() => expect((result.current as MetaState).error?.kind).toBe('failed'));
      expect(result.current.metrics).toEqual([]);
      expect(result.current.dimensions).toEqual([]);
    });
  }
  it('distinguishes a catalog omission from a genuine empty model', async () => {
    const { result } = renderHook(() => useSemanticModelMeta('not_in_catalog'));
    await waitFor(() => expect((result.current as MetaState).error?.kind).toBe('unavailable'));
    expect(result.current.metrics).toEqual([]);
  });
  it('clears old options when the new model metadata request fails', async () => {
    const fetchMock = mockFetchOk(META_RESPONSE);
    fetchMock.mockResolvedValueOnce({ ok: true, status: 200, json: async () => META_RESPONSE } as Response)
      .mockRejectedValueOnce(new Error('private transport detail'));
    vi.stubGlobal('fetch', fetchMock);
    const { result, rerender } = renderHook(({ code }: { code: string }) => useSemanticModelMeta(code), { initialProps: { code: 'sales_semantic' } });
    await waitFor(() => expect(result.current.metrics).toHaveLength(2));
    rerender({ code: 'new_model' });
    expect(result.current.metrics).toEqual([]);
    expect(result.current.dimensions).toEqual([]);
    await waitFor(() => expect((result.current as MetaState).error?.kind).toBe('failed'));
  });
  it('clears loading when selection is removed during a request', async () => {
    let resolve!: (r: Response) => void;
    vi.stubGlobal('fetch', vi.fn().mockReturnValue(new Promise<Response>(done => { resolve = done; })));
    const { result, rerender } = renderHook(({ code }: { code: string | undefined }) => useSemanticModelMeta(code), { initialProps: { code: 'sales_semantic' as string | undefined } });
    expect(result.current.isLoading).toBe(true);
    rerender({ code: undefined });
    expect(result.current.isLoading).toBe(false);
    await act(async () => { resolve({ ok: true, status: 200, json: async () => META_RESPONSE } as Response); });
    expect(result.current.metrics).toEqual([]);
    expect(result.current.dimensions).toEqual([]);
  });
  for (const [name, Picker] of [['metric', SemanticMetricPicker], ['dimension', SemanticDimensionPicker]] as const) {
    it(`shows a safe permission message for the ${name} picker`, async () => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403, json: async () => ({ code: '1', message: 'private SQL detail' }) }));
      render(<Picker semanticModelCode="sales_semantic" value={[]} onChange={vi.fn()} />);
      const alert = await screen.findByRole('alert');
      expect(alert).toHaveTextContent('无权读取语义模型');
      expect(alert).not.toHaveTextContent('private SQL detail');
      expect(screen.queryByText(/暂无指标|暂无维度/)).not.toBeInTheDocument();
    });
    it(`retries a failed ${name} lookup against the real hook request path`, async () => {
      const fetchMock = mockFetchOk(META_RESPONSE).mockRejectedValueOnce(new Error('private transport detail'));
      vi.stubGlobal('fetch', fetchMock);
      render(<Picker semanticModelCode="sales_semantic" value={[]} onChange={vi.fn()} />);
      expect(await screen.findByRole('alert')).toHaveTextContent('无法加载语义模型');
      fireEvent.click(screen.getByRole('button', { name: '重试' }));
      await screen.findByText(name === 'metric' ? '销售额' : '区域');
      expect(fetchMock).toHaveBeenCalledTimes(2);
      expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    });
  }
});
