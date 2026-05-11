import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import * as ReactRouter from 'react-router';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US' }),
}));

import TraceDetailPage from '../pages/ai-trace/$traceId';

type MockedUseParams = typeof ReactRouter.useParams & {
  mockReturnValue: (value: Record<string, string>) => void;
};

const useParamsMock = ReactRouter.useParams as MockedUseParams;

const originalFetch = globalThis.fetch;

function traceResponse(metadata: Record<string, unknown> | null, sessionId = 'RUN-agent-1') {
  return {
    trace: {
      traceId: 'trace-123',
      sessionId,
      name: 'chat',
      input: 'input',
      output: 'output',
      status: 'success',
      errorMessage: null,
      durationMs: 1200,
      totalInputTokens: 100,
      totalOutputTokens: 50,
      totalCost: 0.001,
      metadata,
      tags: null,
      startTime: '2026-05-10T08:00:00Z',
      endTime: '2026-05-10T08:00:01Z',
    },
    spans: [],
  };
}

function mockTraceFetch(payload: unknown) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    json: async () => payload,
  }) as unknown as typeof fetch;
}

describe('TraceDetailPage related run link', () => {
  beforeEach(() => {
    useParamsMock.mockReturnValue({ traceId: 'trace-123' });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
  });

  it('renders Open Run link for AgentRunService traces', async () => {
    mockTraceFetch(traceResponse({ agentCode: 'aurabot', taskPid: 'TASK-1' }, 'RUN-agent-1'));

    render(<TraceDetailPage />);

    const link = await screen.findByTestId('trace-related-run-link');
    expect(link).toHaveTextContent('Open Run');
    expect(link.getAttribute('href')).toBe('/admin/agent-runs?runId=RUN-agent-1');
    await waitFor(() => {
      expect(globalThis.fetch).toHaveBeenCalledWith('/api/ai/traces/trace-123');
    });
  });

  it('does not render Open Run link for plain chat provider traces', async () => {
    mockTraceFetch(traceResponse({ provider_code: 'openai' }, 'chat-session-1'));

    render(<TraceDetailPage />);

    await screen.findByText('chat');
    expect(screen.queryByTestId('trace-related-run-link')).toBeNull();
  });
});
