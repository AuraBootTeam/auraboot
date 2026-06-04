/**
 * Tests for ExecutionLogDialog log-detail error surfacing.
 *
 * Verifies that when expanding a log entry fails, the error is surfaced to the
 * user instead of being silently swallowed (§8 / §10).
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import React from 'react';

// ---------------------------------------------------------------------------
// Mock Dialog UI (uses Radix which needs full DOM) and i18n
// ---------------------------------------------------------------------------

vi.mock('~/ui/ui/dialog', () => ({
  Dialog: ({ children, open }: any) => (open ? <div>{children}</div> : null),
  DialogContent: ({ children, 'data-testid': testId }: any) => (
    <div data-testid={testId}>{children}</div>
  ),
  DialogHeader: ({ children }: any) => <div>{children}</div>,
  DialogTitle: ({ children }: any) => <h2>{children}</h2>,
  DialogDescription: ({ children }: any) => <p>{children}</p>,
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText: () => (key: string, fallback?: string) => fallback ?? key,
}));

// ---------------------------------------------------------------------------
// Mock global fetch
// ---------------------------------------------------------------------------

const fetchMock = vi.fn();
global.fetch = fetchMock;

import { ExecutionLogDialog } from '../ExecutionLogDialog';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

const SAMPLE_LOG = {
  pid: 'log-001',
  status: 'success',
  triggerType: 'manual',
  startedAt: '2024-01-01T10:00:00Z',
  durationMs: 120,
  actionResults: undefined,
};

function renderDialog() {
  return render(
    <ExecutionLogDialog
      open={true}
      onOpenChange={vi.fn()}
      automationId="auto-1"
      automationName="My Automation"
      token="test-token"
    />,
  );
}

beforeEach(() => {
  fetchMock.mockReset();
});

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('ExecutionLogDialog — error surfacing', () => {
  it('shows error in dialog when the log list fetch fails', async () => {
    fetchMock.mockRejectedValue(new Error('Connection refused'));

    renderDialog();

    await waitFor(() =>
      expect(screen.getByText(/Connection refused/)).toBeInTheDocument(),
    );
  });

  it('shows error when log list returns non-OK HTTP status', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 503 } as Response);

    renderDialog();

    await waitFor(() =>
      expect(screen.getByText(/Failed to load logs/)).toBeInTheDocument(),
    );
  });

  it('surfaces detail load error when expanding a log entry with a failed fetch', async () => {
    // First fetch: log list success
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        data: [SAMPLE_LOG],
      }),
    } as Response);

    // Second fetch: expanding the entry → detail fetch fails
    fetchMock.mockRejectedValueOnce(new Error('Detail fetch failed'));

    renderDialog();

    // Wait for log list to render
    await waitFor(() => screen.getByRole('button', { name: /manual/ }));

    // Expand the entry
    fireEvent.click(screen.getByRole('button', { name: /manual/ }));

    await waitFor(() =>
      expect(screen.getByText('Detail fetch failed')).toBeInTheDocument(),
    );
  });

  it('surfaces detail load error when expanding a log entry with a non-OK HTTP status', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: [SAMPLE_LOG] }),
    } as Response);

    fetchMock.mockResolvedValueOnce({
      ok: false,
      status: 404,
    } as Response);

    renderDialog();

    await waitFor(() => screen.getByRole('button', { name: /manual/ }));
    fireEvent.click(screen.getByRole('button', { name: /manual/ }));

    await waitFor(() =>
      expect(screen.getByText(/Failed to load details \(HTTP 404\)/)).toBeInTheDocument(),
    );
  });

  it('does NOT show an error when the detail fetch succeeds', async () => {
    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({ data: [SAMPLE_LOG] }),
    } as Response);

    fetchMock.mockResolvedValueOnce({
      ok: true,
      json: async () => ({
        data: {
          ...SAMPLE_LOG,
          actionResults: [
            { sequence: 1, actionType: 'createRecord', status: 'success', durationMs: 50 },
          ],
        },
      }),
    } as Response);

    renderDialog();

    await waitFor(() => screen.getByRole('button', { name: /manual/ }));
    fireEvent.click(screen.getByRole('button', { name: /manual/ }));

    await waitFor(() =>
      expect(screen.getByText('createRecord')).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Failed to load details/)).not.toBeInTheDocument();
  });
});
