import { render, act, waitFor } from '@testing-library/react';
import { beforeEach, afterEach, expect, it, vi } from 'vitest';
import { useAnalyticsResultView } from '../useAnalyticsResultView';

const { post, report } = vi.hoisted(() => ({ post: vi.fn(), report: vi.fn() }));
vi.mock('~/shared/services/http-client', () => ({ post }));
vi.mock('~/shared/observability/clientErrorReporter', () => ({ reportClientError: report }));
let intersect: (entries: { isIntersecting: boolean }[]) => void;
function Result() {
  const ref = useAnalyticsResultView('analysis');
  return <div ref={ref}>Result</div>;
}
beforeEach(() => {
  post.mockReset().mockResolvedValue({ code: '0' });
  report.mockReset();
  vi.stubGlobal(
    'IntersectionObserver',
    class {
      constructor(callback: typeof intersect) {
        intersect = callback;
      }
      observe() {}
      disconnect() {}
    },
  );
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
});
afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it('records only when the result becomes visible, once across intersection changes', async () => {
  render(<Result />);
  act(() => intersect([{ isIntersecting: false }]));
  expect(post).not.toHaveBeenCalled();
  act(() => intersect([{ isIntersecting: true }]));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
  act(() => intersect([{ isIntersecting: true }]));
  expect(post).toHaveBeenCalledTimes(1);
  expect(post).toHaveBeenCalledWith('/api/analytics/results/analysis/view', {});
});

it('does not count an intersecting result in a background tab', async () => {
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('hidden');
  render(<Result />);
  act(() => intersect([{ isIntersecting: true }]));
  expect(post).not.toHaveBeenCalled();
  vi.spyOn(document, 'visibilityState', 'get').mockReturnValue('visible');
  act(() => document.dispatchEvent(new Event('visibilitychange')));
  await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
});

it('reports failed telemetry without repeatedly posting on rerender', async () => {
  post.mockResolvedValue({ code: '400' });
  const { rerender } = render(<Result />);
  act(() => intersect([{ isIntersecting: true }]));
  await waitFor(() =>
    expect(report).toHaveBeenCalledWith(expect.objectContaining({ kind: 'telemetry' })),
  );
  rerender(<Result />);
  act(() => intersect([{ isIntersecting: true }]));
  expect(post).toHaveBeenCalledTimes(1);
});
