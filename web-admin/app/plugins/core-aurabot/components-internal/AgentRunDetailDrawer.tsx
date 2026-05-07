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

import { useEffect, useState } from 'react';
import {
  getAgentRunDetail,
  type AgentRunDetail,
  type AgentActionItem,
  type AgentInterruptItem,
  type AgentRunListItem,
  type AgentBifSummary,
} from '../services/agentRunsApi';

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

function ActionRow({ action }: { action: AgentActionItem }) {
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
          {action.intentSummary && (
            <div>
              <span className="text-gray-500">Intent: </span>
              {action.intentSummary}
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

function ActionsSection({ actions }: { actions: AgentActionItem[] }) {
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
            <ActionRow key={a.pid} action={a} />
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
  onSelectRun,
}: {
  rows: AgentRunListItem[];
  onSelectRun: (runId: string) => void;
}) {
  return (
    <section data-testid="drawer-section-child-runs" className="border-b border-gray-200 p-4">
      <h3 className="text-sm font-semibold text-gray-700 mb-3">Child Runs ({rows.length})</h3>
      {rows.length === 0 ? (
        <div className="text-xs text-gray-500">No child runs.</div>
      ) : (
        <ul className="space-y-1">
          {rows.map((c) => (
            <li key={c.runId}>
              <button
                type="button"
                onClick={() => onSelectRun(c.runId)}
                className="text-xs font-mono text-blue-600 hover:underline"
                data-testid={`child-run-${c.runId}`}
              >
                {shortPid(c.runId)} · {c.agentCode ?? '-'} · {c.runStatus}
              </button>
            </li>
          ))}
        </ul>
      )}
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

// ---------------------------------------------------------------------------
// Drawer shell
// ---------------------------------------------------------------------------

export default function AgentRunDetailDrawer({ runId, onClose, onSelectRun }: Props) {
  const [detail, setDetail] = useState<AgentRunDetail | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
        if (!cancelled) setDetail(d);
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
            <MetadataSection run={detail.run} />
            <ActionsSection actions={detail.actions} />
            <InterruptsSection rows={detail.interruptLog} />
            <ChildRunsSection rows={detail.childRuns} onSelectRun={onSelectRun} />
            <BifSection bif={detail.bif} />
          </>
        )}
      </aside>
    </div>
  );
}

export { shortPid, fmtCost, fmtDuration, statusColor };
