/**
 * AgentRunDetailDrawerLiveStream.test.tsx
 *
 * Pins the E.1 Phase 1 Live Stream tab on {@link AgentRunDetailDrawer}:
 *   - tabHidden_whenRunHasNoLlmActions: tab not rendered for non-AI runs
 *   - tabVisible_andEventSourceMounts_forLlmRun: tab shows + EventSource opens
 *   - chunksAccumulateBySeq: streamed delta payloads concatenate in order
 *   - droppedBadgeShows_whenDoneCarriesNonZeroDroppedCount: red badge appears
 *
 * Uses an in-memory EventSource mock so chunk events are deterministic
 * without spinning up an SSE server.
 */

import React from 'react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, waitFor, act } from '@testing-library/react';

const { getAgentRunDetailMock } = vi.hoisted(() => ({
  getAgentRunDetailMock: vi.fn(),
}));

vi.mock('../services/agentRunsApi', () => ({
  listAgentRuns: vi.fn(),
  getAgentRunDetail: (...args: unknown[]) => getAgentRunDetailMock(...args),
}));

import AgentRunDetailDrawer from '../components-internal/AgentRunDetailDrawer';

// ---- EventSource mock ------------------------------------------------------

type Listener = (event: MessageEvent) => void;

class MockEventSource {
  static instances: MockEventSource[] = [];
  url: string;
  readyState: number = 1;
  withCredentials: boolean;
  private listeners: Map<string, Listener[]> = new Map();
  onerror: ((this: EventSource, ev: Event) => unknown) | null = null;
  static OPEN = 1;
  static CLOSED = 2;

  constructor(url: string, init?: { withCredentials?: boolean }) {
    this.url = url;
    this.withCredentials = init?.withCredentials ?? false;
    MockEventSource.instances.push(this);
  }

  addEventListener(type: string, fn: Listener) {
    const arr = this.listeners.get(type) ?? [];
    arr.push(fn);
    this.listeners.set(type, arr);
  }

  emit(type: string, data: string) {
    const arr = this.listeners.get(type) ?? [];
    arr.forEach((fn) => fn(new MessageEvent(type, { data })));
  }

  close() {
    this.readyState = MockEventSource.CLOSED;
  }
}

(globalThis as unknown as { EventSource: typeof MockEventSource }).EventSource =
  MockEventSource;

// ---- Helpers ---------------------------------------------------------------

function buildDetail(
  overrides: {
    actions?: unknown[];
    traceId?: string | null;
    conversationTurn?: unknown;
    resultContracts?: unknown[];
  } = {},
) {
  return {
    run: {
      runId: 'RUN-test-1',
      agentCode: 'workflow',
      runStatus: 'running',
      parentRunId: null,
      subtaskOrigin: null,
      costUsd: 0,
      durationMs: 0,
      createdAt: '2026-05-07T10:00:00Z',
      completedAt: null,
      intentSummary: null,
    },
    actions: overrides.actions ?? [],
    interruptLog: [],
    childRuns: [],
    bif: null,
    traceId: overrides.traceId ?? null,
    conversationTurn: overrides.conversationTurn ?? null,
    resultContracts: overrides.resultContracts ?? [],
  };
}

function llmAction(stepIndex: number, code = 'llm_call') {
  return {
    pid: `act-${stepIndex}`,
    stepIndex,
    actionCode: code,
    actionType: 'llm_call',
    actionStatus: 'running',
    intentSummary: null,
    errorMessage: null,
    costUsd: 0,
    beforeSnapshot: null,
    afterSnapshot: null,
    fieldChanges: null,
    resultContractId: `rc-act-${stepIndex}`,
  };
}

function conversationTurn() {
  return {
    runId: 'RUN-test-1',
    taskPid: 'TASK-test-1',
    turnId: 'TURN-test-1',
    conversationId: 9001,
    inboundMessageId: 900101,
    outboundMessageId: 900102,
    triageBucket: 'acp_run',
    triageConfidence: '0.91',
    triageReasonCodes: '["skill:stats"]',
    userMessage: '统计客户信息',
    finalResponse: '客户信息统计完成',
    outcomeStatus: 'success',
    startedAt: '2026-05-07T10:00:00Z',
    completedAt: '2026-05-07T10:00:03Z',
    resultContractIds: ['rc-act-1'],
    messages: [
      {
        messageId: 900101,
        conversationId: 9001,
        senderType: 'human',
        senderId: 1,
        seq: 1,
        messageType: 'text',
        content: '统计客户信息',
        cardPayload: null,
        clientMsgId: 'in-TURN-test-1',
        triageBucket: 'acp_run',
        triageConfidence: '0.91',
        triageReasonCodes: '["skill:stats"]',
        thinkingContent: null,
        thinkingSignature: null,
        createdAt: '2026-05-07T10:00:00Z',
      },
      {
        messageId: 900102,
        conversationId: 9001,
        senderType: 'agent',
        senderId: 2,
        seq: 2,
        messageType: 'ai_response',
        content: '客户信息统计完成',
        cardPayload: null,
        clientMsgId: 'out-TURN-test-1',
        triageBucket: null,
        triageConfidence: null,
        triageReasonCodes: null,
        thinkingContent: 'thinking trace',
        thinkingSignature: 'sig-test',
        createdAt: '2026-05-07T10:00:03Z',
      },
    ],
  };
}

function resultContractItem() {
  return {
    contractId: 'rc-act-1',
    actionPid: 'act-1',
    source: 'ab_agent_action',
    emittedAt: '2026-05-07T10:00:02Z',
    contract: {
      outputType: 'structured_result',
      renderHint: 'summary',
      actionability: 'read_only',
      textSummary: 'Customer stats ready',
      skillCode: 'platform.execute_sql',
      durationMs: 12,
      status: 'success',
      data: {
        totalCustomers: 42,
      },
    },
  };
}

// ---- Specs -----------------------------------------------------------------

describe('AgentRunDetailDrawer · Live Stream tab (E.1)', () => {
  beforeEach(() => {
    MockEventSource.instances.length = 0;
    getAgentRunDetailMock.mockReset();
  });

  afterEach(() => {
    // Defensive: close any still-open mock sources between specs.
    MockEventSource.instances.forEach((es) => es.close());
  });

  it('hides Live Stream tab when run has no LLM actions', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({
        actions: [
          {
            ...llmAction(1, 'send_email'),
            actionType: 'send_email',
          },
        ],
      }),
    );

    render(<AgentRunDetailDrawer runId="RUN-1" onClose={() => {}} onSelectRun={() => {}} />);

    await screen.findByTestId('drawer-section-metadata');
    expect(screen.queryByTestId('drawer-tab-live-stream')).toBeNull();
  });

  it('renders Open Trace deep link when detail has traceId', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({ traceId: 'trace-123' }),
    );

    render(<AgentRunDetailDrawer runId="RUN-trace" onClose={() => {}} onSelectRun={() => {}} />);

    const link = await screen.findByTestId('open-trace-link');
    expect(link).toHaveAttribute('href', '/aurabot/traces/trace-123');
    expect(link).toHaveTextContent('Open Trace');
  });

  it('shows Live Stream tab and mounts EventSource when run contains llm_call', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({ actions: [llmAction(1)] }),
    );

    render(<AgentRunDetailDrawer runId="RUN-2" onClose={() => {}} onSelectRun={() => {}} />);

    const tab = await screen.findByTestId('drawer-tab-live-stream');
    fireEvent.click(tab);

    await screen.findByTestId('drawer-section-live-stream');
    await waitFor(() => expect(MockEventSource.instances.length).toBe(1));
    expect(MockEventSource.instances[0].url).toContain(
      '/api/admin/automation-runs/RUN-2/llm-stream',
    );
    expect(MockEventSource.instances[0].url).toContain('nodeId=llm_call');
  });

  it('accumulates chunks by seq order into the <pre> output', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({ actions: [llmAction(1)] }),
    );

    render(<AgentRunDetailDrawer runId="RUN-3" onClose={() => {}} onSelectRun={() => {}} />);
    fireEvent.click(await screen.findByTestId('drawer-tab-live-stream'));
    await waitFor(() => expect(MockEventSource.instances.length).toBe(1));
    const es = MockEventSource.instances[0];

    act(() => {
      es.emit('chunk', JSON.stringify({ seq: 0, delta: 'Hello, ', done: false }));
      es.emit('chunk', JSON.stringify({ seq: 1, delta: 'world', done: false }));
      es.emit('chunk', JSON.stringify({ seq: 2, delta: '!', done: false }));
    });

    const out = await screen.findByTestId('live-stream-output');
    expect(out.textContent).toContain('Hello, world!');
  });

  it('renders red dropped-badge when done envelope reports droppedCount > 0', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({ actions: [llmAction(1)] }),
    );

    render(<AgentRunDetailDrawer runId="RUN-4" onClose={() => {}} onSelectRun={() => {}} />);
    fireEvent.click(await screen.findByTestId('drawer-tab-live-stream'));
    await waitFor(() => expect(MockEventSource.instances.length).toBe(1));
    const es = MockEventSource.instances[0];

    act(() => {
      es.emit('chunk', JSON.stringify({ seq: 0, delta: 'partial', done: false }));
      es.emit('done', JSON.stringify({ droppedCount: 7 }));
    });

    const badge = await screen.findByTestId('live-stream-dropped-badge');
    expect(badge.textContent).toContain('7');
    expect(badge.textContent?.toLowerCase()).toContain('dropped');
  });

  it('renders full conversation turn replay tape', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({ conversationTurn: conversationTurn() }),
    );

    render(<AgentRunDetailDrawer runId="RUN-conversation" onClose={() => {}} onSelectRun={() => {}} />);

    fireEvent.click(await screen.findByTestId('drawer-tab-conversation'));

    const section = await screen.findByTestId('drawer-section-conversation');
    expect(section).toHaveTextContent('TURN-test-1');
    expect(section).toHaveTextContent('TASK-test-1');
    expect(section).toHaveTextContent('9001');
    expect(section).toHaveTextContent('统计客户信息');
    expect(section).toHaveTextContent('客户信息统计完成');
    expect(screen.getByTestId('conversation-message-900101')).toHaveTextContent('human');
    expect(screen.getByTestId('conversation-message-900102')).toHaveTextContent('thinking trace');
  });

  it('deep-links from an action row to its selected result contract', async () => {
    getAgentRunDetailMock.mockResolvedValue(
      buildDetail({
        actions: [
          {
            ...llmAction(1, 'platform.execute_sql'),
            actionType: 'tool_call',
            actionStatus: 'success',
            intentSummary: '统计客户信息',
            resultContractId: 'rc-act-1',
          },
        ],
        resultContracts: [resultContractItem()],
      }),
    );

    render(<AgentRunDetailDrawer runId="RUN-result" onClose={() => {}} onSelectRun={() => {}} />);

    await screen.findByTestId('drawer-section-metadata');
    fireEvent.click(screen.getByTestId('action-toggle-act-1'));
    fireEvent.click(await screen.findByTestId('open-result-contract-act-1'));

    const results = await screen.findByTestId('drawer-section-result-contracts');
    expect(results).toHaveTextContent('rc-act-1');
    expect(results).toHaveTextContent('Customer stats ready');
    expect(results).toHaveTextContent('platform.execute_sql');
    expect(screen.getByTestId('result-contract-item-rc-act-1')).toHaveClass('border-indigo-300');
  });
});
