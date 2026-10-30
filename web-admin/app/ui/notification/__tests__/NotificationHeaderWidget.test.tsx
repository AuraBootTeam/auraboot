import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationHeaderWidget } from '../NotificationHeaderWidget';

const http = vi.hoisted(() => ({ fetchResult: vi.fn() }));
vi.mock('~/shared/services/http-client', () => http);
vi.mock('../NotificationDropdown', () => ({ NotificationDropdown: ({ unreadCount, onMarkAllRead }: { unreadCount: number; onMarkAllRead: () => Promise<void> }) =>
  <><output data-testid="count">{unreadCount}</output><button onClick={() => void onMarkAllRead().catch(() => {})}>Mark all</button></> }));

describe('notification count identity and ordering', () => {
  beforeEach(() => vi.clearAllMocks());

  it('does not fabricate a zero badge when marking all fails and verification is unavailable', async () => {
    http.fetchResult.mockResolvedValueOnce({ code: '0', data: { count: 7 } })
      .mockResolvedValueOnce({ code: 'FORBIDDEN', data: null }).mockRejectedValueOnce(new Error('offline'));
    render(<NotificationHeaderWidget />);
    await waitFor(() => expect(screen.getByTestId('count')).toHaveTextContent('7'));
    fireEvent.click(screen.getByRole('button'));
    await waitFor(() => expect(http.fetchResult).toHaveBeenCalledTimes(3));
    expect(screen.getByTestId('count')).toHaveTextContent('7');
  });

  it('uses the shared header frame without opening a second EventSource', async () => {
    const eventSource = vi.fn();vi.stubGlobal('EventSource', eventSource);
    http.fetchResult.mockResolvedValue({ code: '0', data: { count: 1 } });
    const view = render(<NotificationHeaderWidget />);
    await act(async () => {});
    view.rerender(<NotificationHeaderWidget streamCount={4} />);
    expect(screen.getByTestId('count')).toHaveTextContent('4');
    expect(eventSource).not.toHaveBeenCalled();vi.unstubAllGlobals();
  });

  it('cannot overwrite a newer stream frame with a delayed initial response', async () => {
    let resolve!: (value: unknown) => void;
    http.fetchResult.mockReturnValue(new Promise(done => { resolve = done; }));
    const view = render(<NotificationHeaderWidget />);
    view.rerender(<NotificationHeaderWidget streamCount={7} />);
    await act(async () => resolve({ code: '0', data: { count: 2 } }));
    expect(screen.getByTestId('count')).toHaveTextContent('7');
  });

  it('discards the prior identity response after a keyed remount', async () => {
    let old!: (value: unknown) => void;
    http.fetchResult.mockReturnValueOnce(new Promise(done => { old = done; }))
      .mockResolvedValueOnce({ code: '0', data: { count: 3 } });
    const view = render(<NotificationHeaderWidget key="tenant-a:actor" />);
    view.rerender(<NotificationHeaderWidget key="tenant-b:actor" />);
    await act(async () => {});
    await act(async () => old({ code: '0', data: { count: 99 } }));
    expect(screen.getByTestId('count')).toHaveTextContent('3');
  });
});
