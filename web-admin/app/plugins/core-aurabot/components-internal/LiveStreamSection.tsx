/**
 * LiveStreamSection — admin live LLM streaming viewer (E.1 Phase 1).
 *
 * <p>Renders one accumulating <pre> per detected LLM node on the parent run,
 * subscribes to the per-node SSE feed at
 * {@code /api/admin/automation-runs/{runId}/llm-stream?nodeId=...},
 * and exposes a red dropped-chunks badge whenever the terminal {@code done}
 * envelope reports {@code droppedCount > 0}. Per spec Q4 there is no replay,
 * so streams that finish before the user opens this tab show the "finished"
 * empty state — the authoritative final output is still in the action
 * timeline / output variable on the parent run record.
 */

import { useEffect, useMemo, useRef, useState } from 'react';
import type { AgentActionItem } from '../services/agentRunsApi';

interface Props {
  runId: string;
  actions: AgentActionItem[];
}

interface NodeStreamState {
  nodeId: string;
  accumulated: string;
  thinkingAccumulated: string;
  chunks: number;
  droppedCount: number;
  done: boolean;
  error: string | null;
}

/**
 * Heuristic: pick actions that look like LLM calls. The action timeline does
 * not yet carry a structured node-id field for automation actions, so we
 * fall back to {@code actionCode} / {@code actionType} substring matching.
 */
function isLlmAction(action: AgentActionItem): boolean {
  const probe = `${action.actionType ?? ''} ${action.actionCode ?? ''}`.toLowerCase();
  return probe.includes('llm');
}

/**
 * Compute the SSE node identifier from an action. Mirrors
 * {@code LlmCallExecutor.resolveNodeId} on the backend so the channel keys
 * line up exactly. Without this matched key the SSE subscriber would see no
 * chunks even though the publisher fan-out is healthy.
 */
function nodeIdFor(action: AgentActionItem): string {
  if (action.actionCode && action.actionCode.length > 0) return action.actionCode;
  if (action.stepIndex !== null && action.stepIndex !== undefined) {
    return `action_${action.stepIndex}`;
  }
  return action.pid;
}

function NodeStreamView({
  runId,
  nodeId,
  label,
}: {
  runId: string;
  nodeId: string;
  label: string;
}) {
  const [state, setState] = useState<NodeStreamState>(() => ({
    nodeId,
    accumulated: '',
    thinkingAccumulated: '',
    chunks: 0,
    droppedCount: 0,
    done: false,
    error: null,
  }));
  const sourceRef = useRef<EventSource | null>(null);

  useEffect(() => {
    const url = `/api/admin/automation-runs/${encodeURIComponent(
      runId,
    )}/llm-stream?nodeId=${encodeURIComponent(nodeId)}`;
    const es = new EventSource(url, { withCredentials: true });
    sourceRef.current = es;

    es.addEventListener('chunk', (event: MessageEvent) => {
      try {
        const payload = JSON.parse(event.data) as {
          seq: number;
          delta?: string;
          thinkingDelta?: string;
          done?: boolean;
        };
        setState((prev) => ({
          ...prev,
          accumulated: prev.accumulated + (payload.delta ?? ''),
          thinkingAccumulated:
            prev.thinkingAccumulated + (payload.thinkingDelta ?? ''),
          chunks: prev.chunks + 1,
        }));
      } catch (err) {
        // Malformed payload should never happen — backend is authoritative.
        // Log to console for forensics; the user-visible state stays intact.
        // eslint-disable-next-line no-console
        console.error('LiveStreamSection chunk parse failed', err);
      }
    });

    es.addEventListener('done', (event: MessageEvent) => {
      try {
        const payload = JSON.parse(event.data) as { droppedCount?: number };
        setState((prev) => ({
          ...prev,
          done: true,
          droppedCount: payload.droppedCount ?? 0,
        }));
      } finally {
        es.close();
      }
    });

    es.onerror = () => {
      // EventSource auto-retries; treat readyState=CLOSED as terminal so
      // the user sees an explicit error rather than a perpetually empty pre.
      if (es.readyState === EventSource.CLOSED) {
        setState((prev) => ({ ...prev, error: 'connection closed' }));
      }
    };

    return () => {
      es.close();
    };
  }, [runId, nodeId]);

  return (
    <div
      className="border border-gray-200 rounded p-3 mb-3"
      data-testid={`live-stream-node-${nodeId}`}
    >
      <div className="flex items-center justify-between mb-2">
        <div className="text-xs font-mono text-gray-700" data-testid="live-stream-label">
          {label}
        </div>
        <div className="flex items-center gap-2">
          <span
            className="text-[10px] text-gray-500 tabular-nums"
            data-testid="live-stream-chunk-count"
          >
            {state.chunks} chunks
          </span>
          {state.droppedCount > 0 && (
            <span
              className="text-[10px] px-1.5 py-0.5 rounded bg-red-100 text-red-700"
              data-testid="live-stream-dropped-badge"
            >
              {state.droppedCount} chunks dropped
            </span>
          )}
          {state.done && (
            <span
              className="text-[10px] px-1.5 py-0.5 rounded bg-emerald-100 text-emerald-700"
              data-testid="live-stream-done-badge"
            >
              done
            </span>
          )}
        </div>
      </div>
      {state.error && (
        <div
          className="text-[11px] text-red-700 mb-2"
          data-testid="live-stream-error"
        >
          Stream error: {state.error}
        </div>
      )}
      {state.thinkingAccumulated && (
        <details className="mb-2">
          <summary className="text-[11px] text-gray-500 cursor-pointer">
            Thinking trace
          </summary>
          <pre
            className="bg-amber-50 text-amber-900 p-2 rounded text-[11px] whitespace-pre-wrap"
            data-testid="live-stream-thinking"
          >
            {state.thinkingAccumulated}
          </pre>
        </details>
      )}
      <pre
        className="bg-gray-50 p-2 rounded text-[11px] whitespace-pre-wrap min-h-[2.5rem]"
        data-testid="live-stream-output"
      >
        {state.accumulated.length > 0
          ? state.accumulated
          : state.done
            ? 'Run finished — view final output in the Action Timeline.'
            : 'Run not started yet — waiting for first chunk…'}
      </pre>
    </div>
  );
}

export default function LiveStreamSection({ runId, actions }: Props) {
  const llmActions = useMemo(() => actions.filter(isLlmAction), [actions]);

  if (llmActions.length === 0) {
    return (
      <section
        data-testid="drawer-section-live-stream-empty"
        className="p-4 text-xs text-gray-500"
      >
        This run has no LLM call actions to stream.
      </section>
    );
  }

  return (
    <section
      data-testid="drawer-section-live-stream"
      className="p-4 border-b border-gray-200"
    >
      <h3 className="text-sm font-semibold text-gray-700 mb-3">
        Live LLM Stream ({llmActions.length})
      </h3>
      {llmActions.map((action) => {
        const nodeId = nodeIdFor(action);
        const label = `${action.actionCode ?? action.actionType ?? '-'} · step ${action.stepIndex ?? '-'}`;
        return (
          <NodeStreamView
            key={action.pid}
            runId={runId}
            nodeId={nodeId}
            label={label}
          />
        );
      })}
    </section>
  );
}
