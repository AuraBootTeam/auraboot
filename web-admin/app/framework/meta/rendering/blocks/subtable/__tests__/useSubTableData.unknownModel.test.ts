/**
 * A sub-table for a model that an optional companion plugin brings has no records on an
 * installation without that plugin: the request answers "Model not found". That is an empty
 * table, not an error. Before this, the CRM account detail showed the red "Business error" under
 * its 关联项目 tab and the quote summary under 转化合同.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { renderHook, waitFor } from '@testing-library/react';
import type { Dispatch, SetStateAction } from 'react';

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

import { fetchResult } from '~/shared/services/http-client';
import { executeFetch } from '~/shared/services/http-client/FetchExecutor';
import { ErrorCodes, ResultHelper } from '~/utils/type';
import { useSubTableData } from '../useSubTableData';
import { isUnknownModelResult } from '../subTableUnknownModel';

const mockFetchResult = vi.mocked(fetchResult);

// The bytes the 5501 stack answers for this request, captured on 2026-11-26 (HTTP 400). The
// same stack answers 200 {"code":"0",…} for a model that is installed.
const NOT_INSTALLED_BODY =
  '{"code":"40000","message":"Business error","data":null,"context":"Model not found: req_requirement_set_pcba_bom","timestamp":1795629154772}';
// The same answer as the platform's HTTP client hands it to the hook: the detail stays in the
// top-level context, the message stays generic.
const NOT_INSTALLED = {
  code: '40000',
  message: 'Business error',
  data: null,
  context: 'Model not found: req_requirement_set_pcba_bom',
};
const OTHER_FAILURE = { code: '40000', message: 'Business error', data: null, context: 'Invalid filter' };
const RECORDS = [{ pid: '01MREQ1' }, { pid: '01MREQ2' }];

function renderSubTableData() {
  const setRefreshCounter = vi.fn();
  const setLoading = vi.fn();
  const setError = vi.fn<Dispatch<SetStateAction<string | null>>>();
  const setRows = vi.fn<Dispatch<SetStateAction<Record<string, any>[]>>>();
  const utils = renderHook(() =>
    useSubTableData({
      setRefreshCounter,
      parentRecordPid: '01MACCOUNT',
      parentRecordData: undefined,
      config: { childModel: 'req_requirement_set_pcba_bom', parentField: 'bom_project_customer_id' } as never,
      token: 'token',
      t: ((key: string) => key) as never,
      refreshCounter: 0,
      setLoading,
      setError,
      setRows,
    }),
  );
  return { setRefreshCounter, setLoading, setError, setRows, ...utils };
}

describe('useSubTableData with a model that is not installed', () => {
  beforeEach(() => {
    mockFetchResult.mockReset();
    vi.spyOn(console, 'error').mockImplementation(() => {});
  });

  it('leaves the table empty and raises no error', async () => {
    mockFetchResult.mockResolvedValue(NOT_INSTALLED as never);
    const { setLoading, setError, setRows } = renderSubTableData();

    await waitFor(() => expect(setLoading).toHaveBeenLastCalledWith(false));
    expect(setRows).toHaveBeenCalledWith([]);
    // setError(null) only clears a previous error; no error is raised.
    expect(setError.mock.calls.filter(([value]: [SetStateAction<string | null>]) => value != null)).toEqual([]);
  });

  it('still raises the error for any other failure', async () => {
    mockFetchResult.mockResolvedValue(OTHER_FAILURE as never);
    const { setLoading, setError } = renderSubTableData();

    await waitFor(() => expect(setLoading).toHaveBeenLastCalledWith(false));
    expect(setError).toHaveBeenCalledWith('Business error');
  });

  it('loads the records when the model is installed', async () => {
    mockFetchResult.mockResolvedValue({ code: ErrorCodes.SUCCESS, message: 'ok', data: { records: RECORDS } } as never);
    const { setLoading, setError, setRows } = renderSubTableData();

    await waitFor(() => expect(setLoading).toHaveBeenLastCalledWith(false));
    expect(setRows).toHaveBeenLastCalledWith(RECORDS);
    expect(setError.mock.calls.filter(([value]: [SetStateAction<string | null>]) => value != null)).toEqual([]);
  });
});

describe('the bytes of the 5501 stack through the platform HTTP client', () => {
  const originalFetch = globalThis.fetch;

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('reach the hook as the answer the guard reads', async () => {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: false,
      status: 400,
      statusText: 'Bad Request',
      json: () => Promise.resolve(JSON.parse(NOT_INSTALLED_BODY)),
    });

    const result = await executeFetch('/api/dynamic/req_requirement_set_pcba_bom/list', { method: 'get' });

    expect(ResultHelper.isSuccess(result)).toBe(false);
    expect(result).toMatchObject({ code: '40000', message: 'Business error', data: null });
    expect(result.context).toBe('Model not found: req_requirement_set_pcba_bom');
    expect(isUnknownModelResult(result)).toBe(true);
  });
});

describe('isUnknownModelResult', () => {
  it('recognises the answer of a missing model', () => {
    expect(isUnknownModelResult(NOT_INSTALLED)).toBe(true);
    expect(isUnknownModelResult({ context: ' model not found: x ' })).toBe(true);
  });

  it('does not take another failure, a non-string context or an empty value for a missing model', () => {
    expect(isUnknownModelResult(OTHER_FAILURE)).toBe(false);
    // The message is the generic one on purpose: the detail is only in the context.
    expect(isUnknownModelResult({ context: 42 })).toBe(false);
    expect(isUnknownModelResult({ context: 'Model not configured' })).toBe(false);
    expect(isUnknownModelResult({ message: 'Model not found: x' })).toBe(false);
    expect(isUnknownModelResult(null)).toBe(false);
    expect(isUnknownModelResult(undefined)).toBe(false);
  });
});
