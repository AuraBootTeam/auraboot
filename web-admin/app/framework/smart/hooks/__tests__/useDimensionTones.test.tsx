/**
 * useDimensionTones: the colours dictionary items declare (`extension.color`), next to the labels.
 * A status table draws the same 色点 + 文字 as a list page from exactly this data.
 */
import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { fetchResultMock, internalRequestMock } = vi.hoisted(() => ({
  fetchResultMock: vi.fn(),
  internalRequestMock: vi.fn(),
}));

vi.mock('~/shared/services/http-client', () => ({ fetchResult: fetchResultMock }));
vi.mock('~/shared/services/http-client/internal-api', () => ({ internalRequest: internalRequestMock }));
// the adapter only reshapes the payload; hand the items through untouched
vi.mock('~/shared/services/dict-adapter', () => ({ dictionaryData: (value: { items: unknown }) => value }));

import { __resetDimensionLabelCaches, useDimensionLabels, useDimensionTones } from '../useDimensionLabels';
import type { ChartDataSource } from '../../types/chart';

const runStatusDictionary = {
  code: '0',
  data: {
    items: [
      { value: 'succeeded', label: '成功', extension: { color: 'green' } },
      { value: 'failed', label: '失败', extension: { color: 'red' } },
      // no colour: a plain state, shown as text
      { value: 'planned', label: '已规划' },
    ],
  },
};

const runModel = {
  type: 'aggregate',
  modelCode: 'orb_deployment_run',
  metrics: [],
} as unknown as ChartDataSource;

describe('useDimensionTones', () => {
  beforeEach(() => {
    __resetDimensionLabelCaches();
    fetchResultMock.mockReset();
    internalRequestMock.mockReset();
  });

  it('lists only the values whose dictionary item declares a colour, while every label is still there', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: [{ code: 'orb_drun_status', dictCode: 'orb_drun_status' }] });
    internalRequestMock.mockResolvedValue(runStatusDictionary);

    const { result } = renderHook(() => ({
      tones: useDimensionTones(runModel, ['orb_drun_status']),
      labels: useDimensionLabels(runModel, ['orb_drun_status']),
    }));

    await waitFor(() => expect(Object.keys(result.current.tones)).toHaveLength(1));
    expect(result.current.tones.orb_drun_status).toEqual({ succeeded: 'green', failed: 'red' });
    expect(result.current.labels.orb_drun_status).toEqual({ succeeded: '成功', failed: '失败', planned: '已规划' });
  });

  it('fetches the dictionary once although the labels and the tones are asked for separately', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: [{ code: 'orb_drun_status', dictCode: 'orb_drun_status' }] });
    internalRequestMock.mockResolvedValue(runStatusDictionary);

    const { result } = renderHook(() => ({
      tones: useDimensionTones(runModel, ['orb_drun_status']),
      labels: useDimensionLabels(runModel, ['orb_drun_status']),
    }));

    await waitFor(() => expect(Object.keys(result.current.labels)).toHaveLength(1));
    expect(internalRequestMock).toHaveBeenCalledTimes(1);
    // internalRequest(method, path template, { path: { code } })
    expect(internalRequestMock.mock.calls[0].slice(0, 3)).toEqual([
      'get',
      '/api/meta/dict/by-code/{code}/data',
      { path: { code: 'orb_drun_status' } },
    ]);
  });

  it('lets a column declare its dictionary when the column has no field behind it (a named-query alias)', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: [] });
    internalRequestMock.mockResolvedValue({
      code: '0',
      data: { items: [{ value: 'running', label: '运行', extension: { color: 'blue' } }] },
    });
    const namedQuery = {
      type: 'namedQuery',
      queryCode: 'orb_slot_current_release',
      dimensionDicts: { intent: 'orb_slot_lifecycle_intent' },
    } as unknown as ChartDataSource;

    const { result } = renderHook(() => useDimensionTones(namedQuery, ['intent']));

    await waitFor(() => expect(result.current.intent).toEqual({ running: 'blue' }));
    // a source without a model never asks for field metadata
    expect(fetchResultMock).not.toHaveBeenCalled();
    expect(internalRequestMock.mock.calls[0][2]).toEqual({ path: { code: 'orb_slot_lifecycle_intent' } });
  });

  it('a dictionary that cannot be loaded yields no tones instead of failing the table', async () => {
    fetchResultMock.mockResolvedValue({ code: '0', data: [{ code: 'orb_drun_status', dictCode: 'orb_drun_status' }] });
    internalRequestMock.mockRejectedValue(new Error('dictionary unavailable'));

    const { result } = renderHook(() => useDimensionTones(runModel, ['orb_drun_status']));

    await act(async () => {
      await Promise.resolve();
    });
    // the hook returns the tone map itself here (no wrapper object)
    await waitFor(() => expect(result.current).toEqual({}));
  });
});
