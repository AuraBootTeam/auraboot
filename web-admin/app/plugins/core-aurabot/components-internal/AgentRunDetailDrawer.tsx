/**
 * AgentRunDetailDrawer
 *
 * Right-side slide-in drawer that surfaces the full Replay UI MVP detail
 * payload for a single agent run. Five sections:
 *   1. Run metadata
 *   2. Action timeline (sorted by executedAt; expandable JSONB diff)
 *   3. Interrupt log table
 *   4. Child Runs list (clickable -> swap drawer to child)
 *   5. BIF grounding summary
 *
 * The drawer is read-only; it never mutates the run. Closing or selecting
 * a child run is handled via the onClose / onSelectRun props so the parent
 * page owns navigation history (URL stays in sync with the open runId).
 */

import { useEffect, useMemo, useState } from 'react';
import {
  getAgentRunDetail,
  type AgentRunDetail,
  type AgentActionItem,
  type AgentInterruptItem,
  type AgentRunListItem,
  type AgentBifSummary,
  type AgentConversationTurnReplay,
  type AgentConversationMessageItem,
  type AgentResultContractItem,
} from '../services/agentRunsApi';
import LiveStreamSection from './LiveStreamSection';
import { ResultContractView } from './ResultContractView';

/**
 * Heuristic mirror of {@code LiveStreamSection.isLlmAction}. Determines
 * whether to render the "Live Stream" tab — hidden for runs that contain no
 * llm_call actions so the surface stays clean for non-AI workflows.
 */
function automationContainsLlmNode(actions: AgentActionItem[]): boolean {
  return actions.some((a) => {
    const probe = `${a.actionType ?? ''} ${a.actionCode ?? ''}`.toLowerCase();
    return probe.includes('llm');
  });
}
import ChildRunTree from './ChildRunTree';

interface Props {
  runId: string | null;
  onClose: () => void;
  onSelectRun: (runId: string) => void;
}

function shortPid(pid: string | null | undefined): string {
  if (!pid) return '-';
  return pid.length > 10 ? `${pid.slice(0, 8)}…` : pid;
}

function fmtCost(n: number | null | undefined): string {
  if (n === null || n === undefined) return '-';
  return `$${Number(n).toFixed(4)}`;
}

function fmtDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function statusColor(status: string | null | undefined): string {
  switch ((status ?? '').toLowerCase()) {
    case 'succeeded':
    case 'success':
      return 'bg-emerald-100 text-emerald-800';
    case 'failed':
    case 'error':
      return 'bg-red-100 text-red-800';
    case 'running':
    case 'pending':
      return 'bg-blue-100 text-blue-800';
    case 'cancelled':
      return 'bg-gray-200 text-gray-700';
    case 'timeout':
      return 'bg-amber-100 text-amber-800';
    default:
      return 'bg-gray-100 text-gray-600';
  }
}

function prettyJson(raw: string | null | undefined): string {
  if (!raw) return '';
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

// ---------------------------------------------------------------------------
// Section components
// ---------------------------------------------------------------------------

function MetadataSection({ run }: { run: AgentRunListItem }) {
  const fields: Array<[string, string]> = [
    ['Run ID', run.runId],
    ['Agent Code', run.agentCode ?? '-'],
    ['Status', run.runStatus],
    ['Parent Run', run.parentRunId ?? '-'],
    ['Subtask Origin', run.subtaskOrigin ?? '-'],
    ['Cost', fmtCost(run.costUsd)],
    ['Duration', fmtDuration(run.durationMs)],
    ['Created', new Date(run.createdAt).toLocaleString()],
    ['Completed', run.completedAt ? new Date(run.completedAt).toLocaleString() : '-'],
    ['Intent', run.intentSummary ?? '-'],
  ];
  return (
    <section data-testid="drawer-section-metadata" className="border-b border-gray-200 p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Run Metadata</h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
        {fields.map(([k, v]) => (
          <div key={k} className="flex flex-col">
            <dt className="text-gray-500">{k}</dt>
            <dd className="text-gray-900 break-all font-mono">{v}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function ActionRow({
  action,
  onOpenResult,
}: {
  action: AgentActionItem;
  onOpenResult?: (contractId: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const hasDiff = action.beforeSnapshot || action.afterSnapshot || action.fieldChanges;
  return (
    <li className="border-b border-gray-100 py-2" data-testid={`action-row-${action.pid}`}>
      <button
        type="button"
        className="w-full text-left flex items-center gap-2"
        onClick={() => setOpen((v) => !v)}
        data-testid={`action-toggle-${action.pid}`}
      >
        <span className="text-xs text-gray-400 tabular-nums w-8">
          {action.stepIndex ?? '-'}
        </span>
        <span className="text-xs font-mono text-gray-700">
          {action.actionCode ?? action.actionType ?? '-'}
        </span>
        <span className={`px-1.5 py-0.5 text-[10px] rounded ${statusColor(action.actionStatus)}`}>
          {action.actionStatus ?? 'unknown'}
        </span>
        <span className="ml-auto text-xs text-gray-500 tabular-nums">
          {fmtCost(action.costUsd)}
        </span>
      </button>
      {open && (
        <div className="mt-2 ml-10 text-xs space-y-2" data-testid={`action-detail-${action.pid}`}>
          {action.resultContractId && onOpenResult && (
            <button
              type="button"
              onClick={() => onOpenResult(action.resultContractId!)}
              className="rounded border border-indigo-200 bg-indigo-50 px-2 py-1 text-xs font-medium text-indigo-700 hover:bg-indigo-100"
              data-testid={`open-result-contract-${action.pid}`}
            >
              Open Result
            </button>
          )}
          {action.intentSummary && (
            <div>
              <span className="text-gray-500">Intent: </span>
              {action.intentSummary}
            </div>
          )}
          {(action.targetModel || action.targetRecordPid || action.targetRecordId) && (
            <div data-testid={`action-target-${action.pid}`}>
              <span className="text-gray-500">Target PID: </span>
              <span className="font-mono">
                {[action.targetModel, action.targetRecordPid ?? action.targetRecordId]
                  .filter(Boolean)
                  .join(' / ')}
              </span>
            </div>
          )}
          {action.errorMessage && (
            <div className="text-red-700">
              <span className="text-gray-500">Error: </span>
              {action.errorMessage}
            </div>
          )}
          {hasDiff && (
            <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
              {action.beforeSnapshot && (
                <div>
                  <div className="text-gray-500 mb-1">Before</div>
                  <pre className="bg-gray-50 p-2 rounded overflow-auto max-h-48 text-[11px]">
                    {prettyJson(action.beforeSnapshot)}
                  </pre>
                </div>
              )}
              {action.afterSnapshot && (
                <div>
                  <div className="text-gray-500 mb-1">After</div>
                  <pre className="bg-gray-50 p-2 rounded overflow-auto max-h-48 text-[11px]">
                    {prettyJson(action.afterSnapshot)}
                  </pre>
                </div>
              )}
              {action.fieldChanges && (
                <div>
                  <div className="text-gray-500 mb-1">Field Changes</div>
                  <pre className="bg-gray-50 p-2 rounded overflow-auto max-h-48 text-[11px]">
                    {prettyJson(action.fieldChanges)}
                  </pre>
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </li>
  );
}

function ActionsSection({
  actions,
  onOpenResult,
}: {
  actions: AgentActionItem[];
  onOpenResult?: (contractId: string) => void;
}) {
  return (
    <section data-testid="drawer-section-actions" className="border-b border-gray-200 p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">
        Action Timeline ({actions.length})
      </h3>
      {actions.length === 0 ? (
        <div className="text-xs text-gray-500">No actions recorded.</div>
      ) : (
        <ul className="space-y-0">
          {actions.map((a) => (
            <ActionRow key={a.pid} action={a} onOpenResult={onOpenResult} />
          ))}
        </ul>
      )}
    </section>
  );
}

function InterruptsSection({ rows }: { rows: AgentInterruptItem[] }) {
  return (
    <section data-testid="drawer-section-interrupts" className="border-b border-gray-200 p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">
        Interrupt Log ({rows.length})
      </h3>
      {rows.length === 0 ? (
        <div className="text-xs text-gray-500">No interrupts.</div>
      ) : (
        <table className="w-full text-xs">
          <thead>
            <tr className="text-left text-gray-500 border-b border-gray-200">
              <th className="py-1 pr-2">Policy</th>
              <th className="py-1 pr-2">Action</th>
              <th className="py-1 pr-2">Tier</th>
              <th className="py-1 pr-2">Subtask Run</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r) => (
              <tr key={r.pid} className="border-b border-gray-100">
                <td className="py-1 pr-2">{r.subPolicy ?? '-'}</td>
                <td className="py-1 pr-2">{r.actionTaken ?? '-'}</td>
                <td className="py-1 pr-2">{r.classifierTier ?? '-'}</td>
                <td className="py-1 pr-2 font-mono">{shortPid(r.subtaskRunId)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function ChildRunsSection({
  rows,
  parentRunId,
  onSelectRun,
}: {
  rows: AgentRunListItem[];
  parentRunId: string;
  onSelectRun: (runId: string) => void;
}) {
  return (
    <section data-testid="drawer-section-child-runs" className="border-b border-gray-200 p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Child Runs ({rows.length})</h3>
      <ChildRunTree rows={rows} parentRunId={parentRunId} onSelectRun={onSelectRun} />
    </section>
  );
}

function BifSection({ bif }: { bif: AgentBifSummary | null }) {
  if (!bif) {
    return (
      <section data-testid="drawer-section-bif" className="p-4">
        <h3 className="text-sm font-semibold text-gray-700 mb-3">Grounding (BIF)</h3>
        <div className="text-xs text-gray-500">No BIF for this run.</div>
      </section>
    );
  }
  const fields: Array<[string, string]> = [
    ['Intent', bif.intent ?? '-'],
    ['Primary Object', bif.primaryObject ?? '-'],
    ['Dispatched Skill', bif.dispatchedSkill ?? '-'],
    ['Channel', bif.channel ?? '-'],
    ['Confidence', bif.confidence ?? '-'],
  ];
  return (
    <section data-testid="drawer-section-bif" className="p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Grounding (BIF)</h3>
      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
        {fields.map(([k, v]) => (
          <div key={k} className="flex flex-col">
            <dt className="text-gray-500">{k}</dt>
            <dd className="text-gray-900 break-all">{v}</dd>
          </div>
        ))}
      </dl>
    </section>
  );
}

function MessageRow({ message }: { message: AgentConversationMessageItem }) {
  return (
    <li
      className="rounded border border-gray-200 bg-white p-3 text-xs"
      data-testid={`conversation-message-${message.messageId}`}
    >
      <div className="mb-1 flex items-center gap-2">
        <span className="rounded bg-gray-100 px-1.5 py-0.5 font-medium text-gray-700">
          {message.senderType ?? 'unknown'}
        </span>
        <span className="rounded bg-gray-50 px-1.5 py-0.5 text-gray-600">
          {message.messageType ?? 'message'}
        </span>
        <span className="font-mono text-gray-500">seq {message.seq ?? '-'}</span>
        <span className="font-mono text-gray-500">{message.clientMsgId ?? '-'}</span>
      </div>
      <div className="whitespace-pre-wrap text-sm text-gray-900">
        {message.content ?? message.cardPayload ?? '-'}
      </div>
      {(message.triageBucket || message.thinkingContent) && (
        <div className="mt-2 grid gap-1 text-[11px] text-gray-500">
          {message.triageBucket && (
            <div>
              Triage: {message.triageBucket}
              {message.triageConfidence ? ` · ${message.triageConfidence}` : ''}
            </div>
          )}
          {message.thinkingContent && <div>Thinking: {message.thinkingContent}</div>}
        </div>
      )}
    </li>
  );
}

function ConversationSection({ turn }: { turn: AgentConversationTurnReplay | null }) {
  if (!turn) {
    return (
      <section data-testid="drawer-section-conversation" className="p-4">
        <h3 className="mb-3 text-sm font-semibold text-gray-700">Conversation Turn</h3>
        <div className="text-xs text-gray-500">No conversation turn data.</div>
      </section>
    );
  }
  const fields: Array<[string, string]> = [
    ['Turn ID', turn.turnId ?? '-'],
    ['Task PID', turn.taskPid ?? '-'],
    ['Conversation', turn.conversationId != null ? String(turn.conversationId) : '-'],
    ['Inbound Message', turn.inboundMessageId != null ? String(turn.inboundMessageId) : '-'],
    ['Outbound Message', turn.outboundMessageId != null ? String(turn.outboundMessageId) : '-'],
    ['Outcome', turn.outcomeStatus ?? '-'],
    ['Triage', turn.triageBucket ?? '-'],
  ];
  return (
    <section data-testid="drawer-section-conversation" className="p-4">
      <h3 className="mb-3 text-sm font-semibold text-gray-700">Conversation Turn</h3>
      <dl className="mb-4 grid grid-cols-2 gap-x-4 gap-y-2 text-xs">
        {fields.map(([k, v]) => (
          <div key={k} className="flex flex-col">
            <dt className="text-gray-500">{k}</dt>
            <dd className="break-all font-mono text-gray-900">{v}</dd>
          </div>
        ))}
      </dl>
      {turn.userMessage && (
        <div className="mb-3 rounded border border-gray-200 bg-gray-50 p-3 text-sm">
          <div className="mb-1 text-xs font-medium text-gray-500">User Message</div>
          <div className="whitespace-pre-wrap text-gray-900">{turn.userMessage}</div>
        </div>
      )}
      {turn.finalResponse && (
        <div className="mb-3 rounded border border-emerald-200 bg-emerald-50 p-3 text-sm">
          <div className="mb-1 text-xs font-medium text-emerald-700">Final Response</div>
          <div className="whitespace-pre-wrap text-gray-900">{turn.finalResponse}</div>
        </div>
      )}
      <h4 className="mb-2 text-xs font-semibold text-gray-600">
        Message Tape ({turn.messages?.length ?? 0})
      </h4>
      {turn.messages && turn.messages.length > 0 ? (
        <ul className="space-y-2">
          {turn.messages.map((message) => (
            <MessageRow key={message.messageId} message={message} />
          ))}
        </ul>
      ) : (
        <div className="text-xs text-gray-500">No persisted messages for this turn.</div>
      )}
    </section>
  );
}

function ResultContractsSection({
  contracts,
  selectedContractId,
  onSelectContract,
}: {
  contracts: AgentResultContractItem[];
  selectedContractId: string | null;
  onSelectContract: (contractId: string) => void;
}) {
  const selectedContract =
    contracts.find((item) => item.contractId === selectedContractId) ?? contracts[0] ?? null;
  const statusCounts = contracts.reduce<Record<string, number>>((acc, item) => {
    const status = item.contract?.status ?? 'unknown';
    acc[status] = (acc[status] ?? 0) + 1;
    return acc;
  }, {});

  return (
    <section data-testid="drawer-section-result-contracts" className="p-4">
      <h3 className="mb-3 text-sm font-semibold text-gray-700">
        Result Contracts ({contracts.length})
      </h3>
      {contracts.length === 0 ? (
        <div className="text-xs text-gray-500">No result contracts.</div>
      ) : (
        <div className="space-y-3">
          <div
            className="grid grid-cols-2 gap-2 rounded border border-gray-200 bg-gray-50 p-3 text-xs"
            data-testid="result-contract-summary"
          >
            <div>
              <div className="text-gray-500">Total</div>
              <div className="text-lg font-semibold text-gray-900">{contracts.length}</div>
            </div>
            <div className="flex flex-wrap items-center gap-1">
              {Object.entries(statusCounts).map(([status, count]) => (
                <span
                  key={status}
                  className="rounded border border-gray-200 bg-white px-2 py-1 font-medium text-gray-700"
                  data-testid={`result-contract-status-${status}`}
                >
                  {status}: {count}
                </span>
              ))}
            </div>
          </div>
          <div className="grid gap-3 md:grid-cols-[minmax(0,14rem)_1fr]">
            <div
              className="space-y-2 rounded border border-gray-200 bg-white p-2"
              data-testid="result-contract-list"
            >
              {contracts.map((item) => {
                const selected = selectedContract?.contractId === item.contractId;
                return (
                  <button
                    key={item.contractId}
                    type="button"
                    onClick={() => onSelectContract(item.contractId)}
                    className={`w-full rounded border px-2 py-2 text-left text-xs ${
                      selected
                        ? 'border-indigo-300 bg-indigo-50 text-indigo-900'
                        : 'border-gray-200 bg-white text-gray-700 hover:bg-gray-50'
                    }`}
                    data-testid={`result-contract-select-${item.contractId}`}
                  >
                    <div className="break-all font-mono">{item.contractId}</div>
                    <div className="mt-1 flex flex-wrap gap-1 text-[11px] text-gray-500">
                      <span>{item.contract?.status ?? 'unknown'}</span>
                      {item.actionPid && <span>Action {shortPid(item.actionPid)}</span>}
                    </div>
                  </button>
                );
              })}
            </div>
            {selectedContract && (
              <article
                className="rounded border border-indigo-300 bg-indigo-50/50 p-3"
                data-testid={`result-contract-item-${selectedContract.contractId}`}
              >
                <dl
                  className="mb-3 grid grid-cols-2 gap-x-4 gap-y-1 text-xs"
                  data-testid="result-contract-provenance"
                >
                  <div className="flex flex-col">
                    <dt className="text-gray-500">Contract</dt>
                    <dd className="break-all font-mono text-gray-900">
                      {selectedContract.contractId}
                    </dd>
                  </div>
                  <div className="flex flex-col">
                    <dt className="text-gray-500">Action</dt>
                    <dd className="font-mono text-gray-900">
                      {selectedContract.actionPid ?? '-'}
                    </dd>
                  </div>
                  <div className="flex flex-col">
                    <dt className="text-gray-500">Source</dt>
                    <dd className="text-gray-900">{selectedContract.source ?? '-'}</dd>
                  </div>
                  <div className="flex flex-col">
                    <dt className="text-gray-500">Emitted</dt>
                    <dd className="text-gray-900">
                      {selectedContract.emittedAt
                        ? new Date(selectedContract.emittedAt).toLocaleString()
                        : '-'}
                    </dd>
                  </div>
                </dl>
                <ResultContractView contract={selectedContract.contract} />
              </article>
            )}
          </div>
        </div>
      )}
    </section>
  );
}

// ---------------------------------------------------------------------------
// Drawer shell
// ---------------------------------------------------------------------------

type DrawerTab = 'overview' | 'conversation' | 'results' | 'live-stream';

export default function AgentRunDetailDrawer({ runId, onClose, onSelectRun }: Props) {
  const [detail, setDetail] = useState<AgentRunDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [activeTab, setActiveTab] = useState<DrawerTab>('overview');
  const [selectedResultContractId, setSelectedResultContractId] = useState<string | null>(null);

  const showLiveStreamTab = useMemo(
    () => (detail ? automationContainsLlmNode(detail.actions) : false),
    [detail],
  );

  useEffect(() => {
    if (!runId) {
      setDetail(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);
    getAgentRunDetail(runId)
      .then((d) => {
        if (!cancelled) {
          setDetail(d);
          setSelectedResultContractId(d.resultContracts?.[0]?.contractId ?? null);
        }
      })
      .catch((e: Error) => {
        if (!cancelled) setError(e.message);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [runId]);

  if (!runId) return null;

  return (
    <div
      className="fixed inset-0 z-[60] flex justify-end"
      data-testid="agent-run-detail-drawer"
      role="dialog"
      aria-modal="true"
    >
      <div
        className="absolute inset-0 bg-black/30"
        onClick={onClose}
        data-testid="drawer-backdrop"
      />
      <aside className="relative h-full w-full max-w-2xl overflow-y-auto bg-white shadow-xl">
        <header className="sticky top-0 z-10 flex items-center justify-between border-b border-gray-200 bg-white px-4 py-3">
          <div>
            <div className="text-xs text-gray-500">Agent Run</div>
            <div className="text-sm font-mono">{shortPid(runId)}</div>
          </div>
          <button
            type="button"
            onClick={onClose}
            data-testid="drawer-close"
            className="text-sm text-gray-500 hover:text-gray-800"
          >
            Close ✕
          </button>
        </header>
        {loading && (
          <div className="p-4 text-sm text-gray-500" data-testid="drawer-loading">
            Loading…
          </div>
        )}
        {error && (
          <div className="p-4 text-sm text-red-600" data-testid="drawer-error">
            {error}
          </div>
        )}
        {detail && !loading && (
          <>
            <nav
              className="flex items-center gap-2 border-b border-gray-200 px-4 py-2 bg-gray-50"
              data-testid="drawer-tab-bar"
            >
              <button
                type="button"
                onClick={() => setActiveTab('overview')}
                data-testid="drawer-tab-overview"
                className={`px-2 py-1 text-xs rounded ${
                  activeTab === 'overview'
                    ? 'bg-white text-gray-900 shadow-sm border border-gray-200'
                    : 'text-gray-500 hover:text-gray-800'
                }`}
              >
                Overview
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('conversation')}
                data-testid="drawer-tab-conversation"
                className={`px-2 py-1 text-xs rounded ${
                  activeTab === 'conversation'
                    ? 'bg-white text-gray-900 shadow-sm border border-gray-200'
                    : 'text-gray-500 hover:text-gray-800'
                }`}
              >
                Conversation
              </button>
              <button
                type="button"
                onClick={() => setActiveTab('results')}
                data-testid="drawer-tab-results"
                className={`px-2 py-1 text-xs rounded ${
                  activeTab === 'results'
                    ? 'bg-white text-gray-900 shadow-sm border border-gray-200'
                    : 'text-gray-500 hover:text-gray-800'
                }`}
              >
                Results
              </button>
              {showLiveStreamTab && (
                <button
                  type="button"
                  onClick={() => setActiveTab('live-stream')}
                  data-testid="drawer-tab-live-stream"
                  className={`px-2 py-1 text-xs rounded ${
                    activeTab === 'live-stream'
                      ? 'bg-white text-gray-900 shadow-sm border border-gray-200'
                      : 'text-gray-500 hover:text-gray-800'
                  }`}
                >
                  Live Stream
                </button>
              )}
              {detail.traceId && (
                <a
                  href={`/aurabot/traces/${encodeURIComponent(detail.traceId)}`}
                  className="ml-auto rounded border border-blue-200 bg-blue-50 px-2 py-1 text-xs font-medium text-blue-700 hover:bg-blue-100"
                  data-testid="open-trace-link"
                >
                  Open Trace
                </a>
              )}
            </nav>
            {activeTab === 'overview' && (
              <>
                <MetadataSection run={detail.run} />
                <ActionsSection
                  actions={detail.actions}
                  onOpenResult={(contractId) => {
                    setSelectedResultContractId(contractId);
                    setActiveTab('results');
                  }}
                />
                <InterruptsSection rows={detail.interruptLog} />
                <ChildRunsSection rows={detail.childRuns} parentRunId={detail.run.runId} onSelectRun={onSelectRun} />
                <BifSection bif={detail.bif} />
              </>
            )}
            {activeTab === 'conversation' && (
              <ConversationSection turn={detail.conversationTurn} />
            )}
            {activeTab === 'results' && (
              <ResultContractsSection
                contracts={detail.resultContracts ?? []}
                selectedContractId={selectedResultContractId}
                onSelectContract={setSelectedResultContractId}
              />
            )}
            {activeTab === 'live-stream' && showLiveStreamTab && (
              <LiveStreamSection runId={runId} actions={detail.actions} />
            )}
          </>
        )}
      </aside>
    </div>
  );
}

export { shortPid, fmtCost, fmtDuration, statusColor };
