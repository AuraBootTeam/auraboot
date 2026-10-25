import React from 'react';
import { render, renderHook, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { DEFAULT_DATE_TIME_FORMATS } from '~/shared/services/dateTimeFormatService';
import { useListCellRenderer } from '../useListCellRenderer';

const registry = vi.hoisted(() => ({ render: vi.fn(() => <span>Registry value</span>) }));
vi.mock('~/framework/meta/runtime/renderers/CellRendererRegistry', () => ({
  cellRendererRegistry: registry,
}));
type Options = Parameters<typeof useListCellRenderer>[0];
const t = (key: string) => key;
function options(overrides: Partial<Options> = {}): Options {
  return {
    runtime: null,
    pageContext: createExpressionContext(),
    modelFieldMap: new Map(),
    referenceDisplayCache: {},
    dictDataCache: { current: new Map() },
    dateTimeFormats: DEFAULT_DATE_TIME_FORMATS,
    effectiveTimezone: 'Asia/Shanghai',
    locale: 'en-US',
    t,
    ...overrides,
  };
}

beforeEach(() => registry.render.mockClear());

describe('list cell renderer', () => {
  it.each([null, undefined])(
    'renders a null placeholder before dictionaries or render expressions: %s',
    (value) => {
      const { result } = renderHook(() => useListCellRenderer(options()));
      render(<>{result.current({ field: 'name', dictCode: 'status' }, { name: value }, 0)}</>);
      expect(screen.getByText('-')).toBeVisible();
      expect(registry.render).not.toHaveBeenCalled();
    },
  );

  it('allows an explicitly nullable renderer to receive null', () => {
    const { result } = renderHook(() => useListCellRenderer(options()));
    result.current({ field: 'name', allowNullRenderer: true } as ColumnConfig, { name: null }, 0);
    expect(registry.render).toHaveBeenCalledWith(
      undefined,
      expect.objectContaining({ value: null }),
    );
  });

  it('keeps exact values ahead of aliases and supplies row, timezone, locale and expression context', () => {
    const opts = options();
    const record = { updated_at: 'exact', updatedAt: 'alias' };
    const { result } = renderHook(() => useListCellRenderer(opts));
    result.current({ field: 'updated_at', label: 'Updated' }, record, 7);
    expect(registry.render).toHaveBeenCalledWith(
      'datetime',
      expect.objectContaining({
        value: 'exact',
        record,
        rowIndex: 7,
        locale: 'en-US',
        expressionContext: opts.pageContext,
        column: expect.objectContaining({
          dateTimeFormats: DEFAULT_DATE_TIME_FORMATS,
          timezone: 'Asia/Shanghai',
        }),
      }),
    );
  });

  it('projects an API alias without modifying the original record', () => {
    const record = { updatedAt: '2026-10-19T08:00:00Z' };
    const { result } = renderHook(() => useListCellRenderer(options()));
    result.current({ field: 'updated_at' }, record, 0);
    expect(registry.render).toHaveBeenCalledWith(
      'datetime',
      expect.objectContaining({
        value: record.updatedAt,
        record: { ...record, updated_at: record.updatedAt },
      }),
    );
    expect(record).toEqual({ updatedAt: '2026-10-19T08:00:00Z' });
  });

  it('renders projected audit users when the raw internal identifier is absent', () => {
    const { result } = renderHook(() => useListCellRenderer(options()));
    result.current({ field: 'created_by' }, { created_by_display: 'Owned creator' }, 0);
    expect(registry.render).toHaveBeenCalledWith(
      'reference',
      expect.objectContaining({
        value: 'Owned creator',
        record: expect.objectContaining({ created_by: 'Owned creator' }),
      }),
    );
  });

  it('renders a dictionary label before invoking expressions or the registry', () => {
    const opts = options({
      dictDataCache: {
        current: new Map([
          [
            'stage',
            [{ value: 'approved', label: 'Approved decision', extension: { color: 'success' } }],
          ],
        ]),
      },
    });
    const { result } = renderHook(() => useListCellRenderer(opts));
    render(
      <>
        {result.current(
          { field: 'stage', dictCode: 'stage', render: 'ignored' },
          { stage: 'approved' },
          0,
        )}
      </>,
    );
    expect(screen.getByText('Approved decision')).toBeVisible();
    expect(registry.render).not.toHaveBeenCalled();
  });

  it.each([new Map(), new Map([['stage', [{ value: 'approved', label: 'Approved' }]]])])(
    'keeps an unknown dictionary value visible when metadata is missing or unmatched',
    (current) => {
      const { result } = renderHook(() =>
        useListCellRenderer(options({ dictDataCache: { current } })),
      );
      expect(result.current({ field: 'stage', dictCode: 'stage' }, { stage: 'pending' }, 0)).toBe(
        'pending',
      );
      expect(registry.render).not.toHaveBeenCalled();
    },
  );

  it('uses the current reference label cache and prefers a projected label on the record', () => {
    const column = {
      field: 'owner_id',
      refTarget: { modelCode: 'account', displayField: 'name', valueField: 'pid' },
    } as ColumnConfig;
    const opts = options({
      referenceDisplayCache: { 'owner_id|account|pid|name': { 'account-owned': 'Cached owner' } },
    });
    const { result, rerender } = renderHook((props: Options) => useListCellRenderer(props), {
      initialProps: opts,
    });
    result.current(column, { owner_id: 'account-owned' }, 0);
    expect(registry.render).toHaveBeenLastCalledWith(
      'reference',
      expect.objectContaining({
        record: { owner_id: 'account-owned', owner_id_display: 'Cached owner' },
      }),
    );
    rerender({
      ...opts,
      referenceDisplayCache: { 'owner_id|account|pid|name': { 'account-owned': 'New owner' } },
    });
    result.current(column, { owner_id: 'account-owned' }, 0);
    expect(registry.render).toHaveBeenLastCalledWith(
      'reference',
      expect.objectContaining({
        record: expect.objectContaining({ owner_id_display: 'New owner' }),
      }),
    );
    result.current(column, { owner_id: 'account-owned', owner_id_display: 'Projected owner' }, 0);
    expect(registry.render).toHaveBeenLastCalledWith(
      'reference',
      expect.objectContaining({
        record: expect.objectContaining({ owner_id_display: 'Projected owner' }),
      }),
    );
  });

  it.each([
    ['explicit', { valueType: 'currency' }, 'amount', 12, {}, 'currency'],
    ['metadata control', {}, 'amount', 12, { extension: { renderComponent: 'rating' } }, 'rating'],
    ['DSL control', { renderType: 'progress' }, 'amount', 12, {}, 'progress'],
    ['declared temporal', {}, 'owner_id', 'account-owned', { dataType: 'date' }, 'date'],
    ['reference suffix', {}, 'owner_id', 'account-owned', {}, 'reference'],
    ['date name', {}, 'created_at', '2026-10-19T08:00:00Z', {}, 'datetime'],
    ['boolean', {}, 'enabled', false, {}, 'boolean'],
    ['string boolean', {}, 'enabled', 'true', {}, 'boolean'],
  ])('preserves value-type precedence: %s', (_name, config, field, value, metadata, expected) => {
    const { result } = renderHook(() =>
      useListCellRenderer(options({ modelFieldMap: new Map([[field, metadata]]) })),
    );
    result.current({ field, ...config } as ColumnConfig, { [field]: value }, 0);
    expect(registry.render).toHaveBeenCalledWith(expected, expect.objectContaining({ value }));
  });

  it('keeps workspace presentation ahead of an explicit cell renderer', () => {
    const { result } = renderHook(() => useListCellRenderer(options()));
    result.current(
      {
        field: 'name',
        cellRenderer: 'badge',
        presentation: { title: true },
      } as unknown as ColumnConfig,
      { name: 'Owned value' },
      0,
    );
    expect(registry.render).toHaveBeenCalledWith(
      'workspace',
      expect.objectContaining({ value: 'Owned value' }),
    );
  });

  it('evaluates with the aliased row and sanitizes unsafe HTML before displaying it', () => {
    const evaluateTemplate = vi.fn(
      () => '<b>Owned value</b><img src=x onerror="alert(1)"><script>bad()</script>',
    );
    const getContext = vi.fn(() => ({ tenant: 'owned' }));
    const opts = options({
      runtime: {
        getContext,
        getEvaluator: () => ({ evaluateTemplate }),
      } as unknown as Options['runtime'],
    });
    const { result } = renderHook(() => useListCellRenderer(opts));
    const { container } = render(
      <>
        {result.current(
          { field: 'created_at', render: '{{row.created_at}}' },
          { createdAt: 'safe' },
          0,
        )}
      </>,
    );
    expect(evaluateTemplate).toHaveBeenCalledWith('{{row.created_at}}', {
      tenant: 'owned',
      row: { createdAt: 'safe', created_at: 'safe' },
    });
    expect(screen.getByText('Owned value')).toBeVisible();
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('img')).not.toHaveAttribute('onerror');
    expect(registry.render).not.toHaveBeenCalled();
  });

  it('preserves the existing registry path when an expression throws', () => {
    const error = vi.spyOn(console, 'error').mockImplementation(() => {});
    try {
      const opts = options({
        runtime: {
          getContext: () => ({}),
          getEvaluator: () => ({
            evaluateTemplate: () => {
              throw new Error('Invalid expression');
            },
          }),
        } as unknown as Options['runtime'],
      });
      const { result } = renderHook(() => useListCellRenderer(opts));
      result.current(
        { field: 'amount', valueType: 'currency', render: 'invalid' },
        { amount: 42 },
        0,
      );
      expect(error).toHaveBeenCalledWith('Render expression failed:', expect.any(Error));
      expect(registry.render).toHaveBeenCalledWith(
        'currency',
        expect.objectContaining({ value: 42 }),
      );
    } finally {
      error.mockRestore();
    }
  });

  it('retains callback identity for stable inputs and reads updated timezone and metadata after rerender', () => {
    const opts = options();
    const { result, rerender } = renderHook((props: Options) => useListCellRenderer(props), {
      initialProps: opts,
    });
    const callback = result.current;
    rerender(opts);
    expect(result.current).toBe(callback);
    rerender({
      ...opts,
      effectiveTimezone: 'UTC',
      modelFieldMap: new Map([['amount', { extension: { renderComponent: 'money' } }]]),
    });
    expect(result.current).not.toBe(callback);
    result.current({ field: 'amount' }, { amount: 88 }, 0);
    expect(registry.render).toHaveBeenCalledWith(
      'currency',
      expect.objectContaining({ column: expect.objectContaining({ timezone: 'UTC' }) }),
    );
  });
});
