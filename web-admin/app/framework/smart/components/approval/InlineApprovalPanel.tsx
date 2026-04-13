/**
 * InlineApprovalPanel
 *
 * Thin wrapper rendered at the bottom of DSL detail pages.
 * Checks if there is an active BPM process instance for the given record pid,
 * and if so, renders the existing ApprovalChainPanel component.
 *
 * API used:
 *   GET /api/bpm/process-instances/by-business-key/status?businessKey={recordPid}
 *
 * Returns nothing (null) when:
 *   - recordPid is empty / falsy
 *   - no process instance exists for this record
 *   - the API call fails for any reason (silent failure — do not break the detail page)
 */

import { useState, useEffect } from 'react';
import { RefreshCw } from 'lucide-react';
import { get, ErrorCodes } from '~/shared/services/http-client';
import { ApprovalChainPanel } from '~/plugins/core-bpm/components/ApprovalChainPanel';

// ==================== Types ====================

interface ProcessInstanceStatusDTO {
  processInstanceId: string;
  businessKey: string;
  status: string;
  activeNodes?: string[];
  completedNodes?: unknown[];
}

// ==================== Props ====================

export interface InlineApprovalPanelProps {
  /** The pid (business key) of the record to look up */
  recordPid: string;
  className?: string;
}

// ==================== Component ====================

/**
 * InlineApprovalPanel — renders approval timeline if a BPM process exists for the record.
 */
export function InlineApprovalPanel({ recordPid, className }: InlineApprovalPanelProps) {
  const [processInstanceId, setProcessInstanceId] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [checked, setChecked] = useState(false);

  useEffect(() => {
    if (!recordPid) {
      setChecked(true);
      return;
    }

    let cancelled = false;

    const checkProcessInstance = async () => {
      setLoading(true);
      try {
        const result = await get<ProcessInstanceStatusDTO>(
          '/api/bpm/process-instances/by-business-key/status',
          { params: { businessKey: recordPid } },
        );

        if (!cancelled) {
          if (result.code === ErrorCodes.SUCCESS && result.data?.processInstanceId) {
            setProcessInstanceId(result.data.processInstanceId);
          } else {
            // No process instance found — render nothing
            setProcessInstanceId(null);
          }
        }
      } catch {
        // Silent failure: if BPM is unavailable or record has no approval process,
        // simply show nothing rather than breaking the detail page
        if (!cancelled) {
          setProcessInstanceId(null);
        }
      } finally {
        if (!cancelled) {
          setLoading(false);
          setChecked(true);
        }
      }
    };

    checkProcessInstance();

    return () => {
      cancelled = true;
    };
  }, [recordPid]);

  // While still checking, show a subtle loading indicator
  if (!checked || loading) {
    return (
      <div className="flex items-center justify-center py-4 text-sm text-gray-400">
        <RefreshCw className="mr-1.5 h-3.5 w-3.5 animate-spin" />
        Checking approval status...
      </div>
    );
  }

  // No approval process associated with this record
  if (!processInstanceId) {
    return null;
  }

  // Render the approval chain panel
  return (
    <div className={className}>
      <div className="mt-6 border-t border-gray-200 pt-6">
        <h3 className="mb-4 border-b border-gray-100 pb-2 text-base font-semibold text-gray-900">
          Approval History
        </h3>
        <ApprovalChainPanel processInstanceId={processInstanceId} />
      </div>
    </div>
  );
}
