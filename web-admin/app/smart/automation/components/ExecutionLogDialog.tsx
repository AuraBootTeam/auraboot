import React, { useState, useEffect, useCallback } from 'react';
import dayjs from 'dayjs';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
} from '~/components/ui/dialog';
import { cn } from '~/utils/cn';
import { useSmartText } from '~/utils/i18n';
import type { AutomationLog, ActionResult } from '../services/automationService';

const BASE_URL = '/api/automations';

export interface ExecutionLogDialogProps {
  open: boolean;
  onOpenChange: (open: boolean) => void;
  automationId: string;
  automationName: string;
  token?: string | null;
}

const statusConfig: Record<string, { label: string; className: string }> = {
  success: { label: 'success', className: 'bg-green-100 text-green-700' },
  failed: { label: 'failed', className: 'bg-red-100 text-red-700' },
  running: { label: 'running', className: 'bg-blue-100 text-blue-700' },
  pending: { label: 'pending', className: 'bg-gray-100 text-gray-600' },
  skipped: { label: 'skipped', className: 'bg-yellow-100 text-yellow-700' },
};

function StatusBadge({ status }: { status: string }) {
  const config = statusConfig[status] || statusConfig.PENDING;
  return (
    <span className={cn('rounded px-2 py-0.5 text-xs font-medium', config.className)}>
      {config.label}
    </span>
  );
}

function formatTime(iso?: string): string {
  if (!iso) return '-';
  return dayjs(iso).format('YYYY-MM-DD HH:mm:ss');
}

function formatDuration(ms?: number): string {
  if (ms == null) return '-';
  if (ms < 1000) return `${ms}ms`;
  return `${(ms / 1000).toFixed(1)}s`;
}

function ActionResultItem({ action }: { action: ActionResult }) {
  return (
    <div className="flex items-start gap-2 px-3 py-1.5 text-sm">
      <span className="w-5 shrink-0 text-right font-mono text-gray-400">{action.sequence}.</span>
      <span className="min-w-0 font-medium text-gray-700">{action.actionType}</span>
      <StatusBadge status={action.status} />
      <span className="ml-auto shrink-0 text-xs text-gray-400">
        {formatDuration(action.durationMs)}
      </span>
      {action.status === 'failed' && action.errorMessage && (
        <div className="mt-0.5 basis-full pl-7 text-xs break-all text-red-600">
          {action.errorMessage}
        </div>
      )}
    </div>
  );
}

function LogEntry({ log, token }: { log: AutomationLog; token?: string | null }) {
  const [expanded, setExpanded] = useState(false);
  const [detail, setDetail] = useState<AutomationLog | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const handleToggle = async () => {
    if (expanded) {
      setExpanded(false);
      return;
    }
    setExpanded(true);
    if (!detail && !log.actionResults?.length) {
      try {
        setLoadingDetail(true);
        const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};
        const resp = await fetch(`${BASE_URL}/logs/${log.pid}`, { headers });
        if (resp.ok) {
          const result = await resp.json();
          setDetail(result.data);
        }
      } catch {
        // Silently fail - show whatever we have
      } finally {
        setLoadingDetail(false);
      }
    }
  };

  const actions = detail?.actionResults || log.actionResults || [];

  return (
    <div className="overflow-hidden rounded-lg border border-gray-200">
      <button
        onClick={handleToggle}
        className="flex w-full items-center gap-3 px-4 py-3 text-left transition-colors hover:bg-gray-50"
      >
        <svg
          className={cn(
            'h-4 w-4 shrink-0 text-gray-400 transition-transform',
            expanded && 'rotate-90',
          )}
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M9 5l7 7-7 7" />
        </svg>
        <StatusBadge status={log.status} />
        {log.triggerType && (
          <span className="rounded bg-gray-100 px-1.5 py-0.5 text-xs text-gray-500">
            {log.triggerType}
          </span>
        )}
        <span className="ml-auto shrink-0 text-sm text-gray-600">
          {formatTime(log.startedAt || log.createdAt)}
        </span>
        <span className="w-16 shrink-0 text-right text-xs text-gray-400">
          {formatDuration(log.durationMs)}
        </span>
      </button>

      {expanded && (
        <div className="border-t border-gray-100 bg-gray-50 py-2">
          {loadingDetail ? (
            <div className="px-4 py-2 text-sm text-gray-400">Loading...</div>
          ) : actions.length > 0 ? (
            <div className="divide-y divide-gray-100">
              {actions.map((action, idx) => (
                <ActionResultItem key={idx} action={action} />
              ))}
            </div>
          ) : (
            <div className="px-4 py-2 text-sm text-gray-400">No action details</div>
          )}
          {(detail?.errorMessage || log.errorMessage) && (
            <div className="mt-1 border-t border-gray-100 px-4 pt-2 pb-1 text-xs text-red-600">
              Error: {detail?.errorMessage || log.errorMessage}
            </div>
          )}
        </div>
      )}
    </div>
  );
}

export function ExecutionLogDialog({
  open,
  onOpenChange,
  automationId,
  automationName,
  token,
}: ExecutionLogDialogProps) {
  const st = useSmartText();
  const [logs, setLogs] = useState<AutomationLog[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const loadLogs = useCallback(async () => {
    if (!automationId) return;
    try {
      setLoading(true);
      setError(null);
      const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};
      const resp = await fetch(`${BASE_URL}/${automationId}/logs`, { headers });
      if (!resp.ok) throw new Error('Failed to load logs');
      const result = await resp.json();
      setLogs(result.data || []);
    } catch (err) {
      setError(err instanceof Error ? err.message : 'Failed to load logs');
    } finally {
      setLoading(false);
    }
  }, [automationId, token]);

  useEffect(() => {
    if (open) {
      loadLogs();
    } else {
      setLogs([]);
      setError(null);
    }
  }, [open, loadLogs]);

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent
        className="flex max-h-[80vh] max-w-2xl flex-col"
        data-testid="execution-log-dialog"
      >
        <DialogHeader>
          <DialogTitle>
            {st('$i18n:automation.logs.title') || 'Execution Logs'} - {automationName}
          </DialogTitle>
          <DialogDescription>
            {st('$i18n:automation.logs.description') || 'View execution history and action details'}
          </DialogDescription>
        </DialogHeader>

        <div className="min-h-0 flex-1 space-y-2 overflow-y-auto">
          {loading ? (
            <div className="flex items-center justify-center py-12 text-gray-400">
              {st('$i18n:common.loading') || 'Loading...'}
            </div>
          ) : error ? (
            <div className="flex items-center justify-center py-12 text-red-500">{error}</div>
          ) : logs.length === 0 ? (
            <div className="flex items-center justify-center py-12 text-gray-400">
              {st('$i18n:automation.logs.empty') || 'No execution logs yet'}
            </div>
          ) : (
            logs.map((log) => <LogEntry key={log.pid} log={log} token={token} />)
          )}
        </div>
      </DialogContent>
    </Dialog>
  );
}

export default ExecutionLogDialog;
