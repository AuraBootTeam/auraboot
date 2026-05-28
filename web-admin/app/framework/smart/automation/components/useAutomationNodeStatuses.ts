// web-admin/app/framework/smart/automation/components/useAutomationNodeStatuses.ts
//
// G5 — fetch per-node runtime status for one automation log row, ready to feed
// into `<FlowDesigner nodeStatuses={...} />`.
//
// Why a thin hook? The endpoint is tenant-scoped on the backend; the frontend
// shape we want is `Record<nodeId, status>` (matches the SDK prop). Centralising
// the flatten + error handling here keeps the editor / history page free of
// fetch boilerplate.

import { useEffect, useState } from 'react';
import type { NodeStatusMap, NodeRuntimeStatus } from '~/plugins/core-designer/components/flow-designer-sdk';

interface NodeExecutionWire {
  nodeId: string;
  status: NodeRuntimeStatus;
  startedAt?: string;
  completedAt?: string;
  errorMessage?: string;
}

interface UseAutomationNodeStatusesResult {
  statuses: NodeStatusMap | null;
  loading: boolean;
  error: string | null;
  /** Force a refetch — useful after the user clicks "test run". */
  reload: () => void;
}

/**
 * Fetch node statuses for an ab_automation_log row. Returns null when the
 * `logId` is undefined (overlay disabled), which is the same shape FlowDesigner
 * treats as "no overlay".
 */
export function useAutomationNodeStatuses(logId: number | string | undefined): UseAutomationNodeStatusesResult {
  const [statuses, setStatuses] = useState<NodeStatusMap | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [reloadKey, setReloadKey] = useState(0);

  useEffect(() => {
    if (logId === undefined || logId === null || logId === '') {
      setStatuses(null);
      setError(null);
      return;
    }
    let cancelled = false;
    setLoading(true);
    setError(null);

    fetch(`/api/automation/executions/by-log/${encodeURIComponent(String(logId))}/node-statuses`)
      .then(async (res) => {
        if (!res.ok) {
          const body = await res.json().catch(() => null);
          throw new Error(body?.desc || `Failed to load node statuses (${res.status})`);
        }
        return res.json();
      })
      .then((body) => {
        if (cancelled) return;
        const rows: NodeExecutionWire[] = (body?.data as NodeExecutionWire[]) || [];
        const map: NodeStatusMap = {};
        // Last write wins per nodeId — if a node ran more than once in the same
        // log (loop body) the latest status reflects the most recent attempt.
        for (const row of rows) {
          if (row.nodeId) {
            map[row.nodeId] = row.status;
          }
        }
        setStatuses(Object.keys(map).length > 0 ? map : null);
      })
      .catch((e) => {
        if (cancelled) return;
        setError(e instanceof Error ? e.message : String(e));
        setStatuses(null);
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [logId, reloadKey]);

  return {
    statuses,
    loading,
    error,
    reload: () => setReloadKey((k) => k + 1),
  };
}
