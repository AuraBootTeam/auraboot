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

vi.mock('~/plugins/core-designer/components/flow-designer-sdk/utils', () => ({
  humanizeType: (type: string) =>
    type
      .replace(/[-_]+/g, ' ')
      .replace(/\b\w/g, (c: string) => c.toUpperCase()),
}));

vi.mock('~/utils/i18n', () => ({
  useSmartText:
    () =>
    (key: string, fallback?: string): string => {
      // Simulate i18n resolution: strip the $i18n: prefix and return a
      // human-readable label so tests can assert on visible text.
      if (typeof key === 'string' && key.startsWith('$i18n:')) {
        const resolved = key.slice(6).split('.').pop() ?? '';
        return resolved;
      }
      return fallback ?? (typeof key === 'string' ? key : '');
    },
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
  // Real backend value (snake_case); maps to automation.trigger.recordCreate
  triggerType: 'on_record_create',
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
    await waitFor(() => screen.getByRole('button', { name: /recordCreate/ }));

    // Expand the entry
    fireEvent.click(screen.getByRole('button', { name: /recordCreate/ }));

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

    await waitFor(() => screen.getByRole('button', { name: /recordCreate/ }));
    fireEvent.click(screen.getByRole('button', { name: /recordCreate/ }));

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
          // Real backend value: create_record → maps to automation.action.createRecord
          actionResults: [
            { sequence: 1, actionType: 'create_record', status: 'success', durationMs: 50 },
          ],
        },
      }),
    } as Response);

    renderDialog();

    await waitFor(() => screen.getByRole('button', { name: /recordCreate/ }));
    fireEvent.click(screen.getByRole('button', { name: /recordCreate/ }));

    // create_record → ACTION_TYPE_I18N_KEYS → $i18n:automation.action.createRecord
    // → mock strips prefix and returns last segment: 'createRecord'
    await waitFor(() =>
      expect(screen.getByText('createRecord')).toBeInTheDocument(),
    );
    expect(screen.queryByText(/Failed to load details/)).not.toBeInTheDocument();
  });
});
