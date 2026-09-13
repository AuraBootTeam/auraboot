import { act, renderHook, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { ReportQueryError } from '../ReportQueryError';
import { useReportQuery } from '../useReportQuery';
import { fetchReportData } from '../fetchReportData';
import { createEmptyReport } from '../../types';

vi.mock('../fetchReportData', () => ({ fetchReportData: vi.fn() }));
const fetchData = vi.mocked(fetchReportData);
type Data = Awaited<ReturnType<typeof fetchReportData>>;
function deferred() {
  let resolve!: (value: Data) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<Data>((yes, no) => {
    resolve = yes;
    reject = no;
  });
  return { promise, resolve, reject };
}

beforeEach(() => vi.resetAllMocks());
describe('report query snapshots', () => {
  it('commits submitted inputs with data and keeps them after draft edits or failure', async () => {
    const report = createEmptyReport('Query');
    fetchData.mockResolvedValueOnce({ rows: [{ value: 'A' }] });
    const { result } = renderHook(() => useReportQuery(report));
    await waitFor(() => expect(result.current.canExport).toBe(true));
    act(() => result.current.setDraft({ input: 'B' }));
    expect(result.current.appliedParameters).toEqual({});
    const next = deferred();
    fetchData.mockReturnValueOnce(next.promise);
    act(() => result.current.apply());
    expect(result.current.canExport).toBe(false);
    act(() => result.current.setDraft({ input: 'C' }));
    await act(async () => next.resolve({ rows: [{ value: 'B' }] }));
    expect(result.current.appliedParameters).toEqual({ input: 'B' });
    expect(result.current.draft).toEqual({ input: 'C' });
    expect(result.current.dataSets.rows).toEqual([{ value: 'B' }]);
    fetchData.mockRejectedValueOnce(new Error('denied'));
    await act(async () => result.current.apply());
    expect(result.current.failed).toBe(true);
    expect(result.current.appliedParameters).toEqual({ input: 'B' });
    expect(result.current.canExport).toBe(true);
  });

  it('ignores a late response for a superseded definition', async () => {
    const first = deferred(),
      second = deferred();
    fetchData.mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise);
    const a = createEmptyReport('A'),
      b = createEmptyReport('B');
    const { result, rerender } = renderHook(
      ({ report }: { report: ReturnType<typeof createEmptyReport> }) => useReportQuery(report),
      {
        initialProps: { report: a },
      },
    );
    rerender({ report: b });
    await act(async () => second.resolve({ rows: [{ value: 'B' }] }));
    await act(async () => first.resolve({ rows: [{ value: 'A' }] }));
    expect(result.current.dataSets.rows).toEqual([{ value: 'B' }]);
    expect(result.current.canExport).toBe(true);
  });
});

it('clears successful data after access denial and restores it only after a successful query', async () => {
  const report = createEmptyReport('Protected');
  fetchData.mockResolvedValueOnce({ rows: [{ secret: 'visible' }] });
  const { result } = renderHook(() => useReportQuery(report));
  await waitFor(() => expect(result.current.hasResult).toBe(true));
  fetchData.mockRejectedValueOnce(new ReportQueryError('access', 'denied'));
  await act(async () => result.current.apply());
  expect(result.current.failure).toBe('access');
  expect(result.current.dataSets).toEqual({});
  expect(result.current.hasResult).toBe(false);
  expect(result.current.canExport).toBe(false);
  fetchData.mockResolvedValueOnce({ rows: [{ secret: 'restored' }] });
  await act(async () => result.current.apply());
  expect(result.current.failure).toBeNull();
  expect(result.current.dataSets.rows).toEqual([{ secret: 'restored' }]);
  expect(result.current.canExport).toBe(true);
});
