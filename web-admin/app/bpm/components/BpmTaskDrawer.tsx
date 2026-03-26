/**
 * BpmTaskDrawer — Main approval drawer with DSL form rendering.
 *
 * When an approver clicks a pending task, this drawer opens and:
 * 1. Fetches task data (including formBinding) from the backend
 * 2. If formBinding exists: renders the DSL form via useDslForm + DslFormRenderer
 * 3. If no formBinding: shows only the ApprovalOpinionSection (comment-only fallback)
 * 4. Footer: Cancel / Reject / Approve buttons
 * 5. Header: fullscreen toggle
 */

import React, { useState, useEffect, useCallback } from 'react';
import { get, post } from '~/services/http-client';
import { useDslForm } from '~/meta/hooks/useDslForm';
import { DslFormRenderer } from '~/meta/rendering/DslFormRenderer';
import { ApprovalOpinionSection } from './ApprovalOpinionSection';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface BpmTaskDrawerProps {
  taskId: string;
  open: boolean;
  onClose: () => void;
  onComplete: () => void;
}

/** Shape returned by GET /api/bpm/forms/task/{taskId} */
interface TaskFormData {
  taskId: string;
  taskName?: string;
  processName?: string;
  businessKey?: string;
  processVariables?: Record<string, any>;
  formBinding?: {
    formRef?: string;
    saveStrategy?: string;
    fieldPermissions?: Record<string, 'editable' | 'readonly' | 'hidden'>;
    permissionMode?: string;
    variableBindings?: Record<string, string>;
  };
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Build process variable entries from form values based on variableBindings config.
 * variableBindings maps: processVariableName -> formFieldCode
 */
function buildMappedVariables(
  formValues: Record<string, any>,
  variableBindings?: Record<string, string>,
): Record<string, any> {
  if (!variableBindings) return {};
  const result: Record<string, any> = {};
  for (const [varName, fieldCode] of Object.entries(variableBindings)) {
    if (fieldCode in formValues) {
      result[varName] = formValues[fieldCode];
    }
  }
  return result;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export function BpmTaskDrawer({ taskId, open, onClose, onComplete }: BpmTaskDrawerProps) {
  // --- Local state ---
  const [taskData, setTaskData] = useState<TaskFormData | null>(null);
  const [fetchLoading, setFetchLoading] = useState(true);
  const [fetchError, setFetchError] = useState<string | null>(null);
  const [decision, setDecision] = useState('approve');
  const [comment, setComment] = useState('');
  const [actionSubmitting, setActionSubmitting] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [fullscreen, setFullscreen] = useState(false);

  // --- Fetch task data ---
  useEffect(() => {
    if (!open || !taskId) return;

    let cancelled = false;
    (async () => {
      setFetchLoading(true);
      setFetchError(null);
      setTaskData(null);
      try {
        const result = await get<TaskFormData>(`/api/bpm/forms/task/${taskId}`);
        if (!cancelled && result.success && result.data) {
          setTaskData(result.data);
        } else if (!cancelled) {
          setFetchError(result.message || 'Failed to load task data'); // TODO: i18n
        }
      } catch (e) {
        if (!cancelled) {
          setFetchError('Network error'); // TODO: i18n
        }
      } finally {
        if (!cancelled) {
          setFetchLoading(false);
        }
      }
    })();

    return () => {
      cancelled = true;
    };
  }, [open, taskId]);

  // --- DSL form integration ---
  const hasForm = !!taskData?.formBinding?.formRef;

  const handleFormSubmit = useCallback(
    async (data: { values: Record<string, any> }) => {
      await post(`/api/bpm/forms/task/${taskId}/submit`, {
        saveStrategy: taskData?.formBinding?.saveStrategy || 'business_only',
        businessData: data.values,
        variables: {
          decision,
          comment,
          ...buildMappedVariables(data.values, taskData?.formBinding?.variableBindings),
        },
      });
      onComplete();
      onClose();
    },
    [taskId, taskData, decision, comment, onComplete, onClose],
  );

  const form = useDslForm({
    pageKey: taskData?.formBinding?.formRef || '',
    enabled: hasForm,
    recordId: taskData?.businessKey || undefined,
    initialValues: taskData?.processVariables || undefined,
    fieldPermissions: taskData?.formBinding?.fieldPermissions || undefined,
    permissionMode: (taskData?.formBinding?.permissionMode as any) || 'merge',
    onSubmit: hasForm ? handleFormSubmit : undefined,
  });

  // --- Action handlers ---
  const handleApprove = useCallback(async () => {
    setActionSubmitting(true);
    setActionError(null);
    try {
      if (hasForm) {
        // Triggers form.onSubmit which includes decision + comment
        await form.submit();
      } else {
        // No form — submit decision + comment only
        await post(`/api/bpm/forms/task/${taskId}/submit`, {
          saveStrategy: 'variable_only',
          variables: { decision: 'approve', comment },
        });
        onComplete();
        onClose();
      }
    } catch (e: any) {
      setActionError(e?.message || 'Failed to approve task'); // TODO: i18n
    } finally {
      setActionSubmitting(false);
    }
  }, [hasForm, form, taskId, comment, onComplete, onClose]);

  const handleReject = useCallback(async () => {
    setActionSubmitting(true);
    setActionError(null);
    try {
      await post(`/api/bpm/forms/task/${taskId}/submit`, {
        saveStrategy: 'variable_only',
        variables: { decision: 'reject', comment },
      });
      onComplete();
      onClose();
    } catch (e: any) {
      setActionError(e?.message || 'Failed to reject task'); // TODO: i18n
    } finally {
      setActionSubmitting(false);
    }
  }, [taskId, comment, onComplete, onClose]);

  // --- Reset state on close ---
  useEffect(() => {
    if (!open) {
      setDecision('approve');
      setComment('');
      setActionError(null);
      setFullscreen(false);
    }
  }, [open]);

  // --- Don't render when closed ---
  if (!open) return null;

  const isSubmitting = actionSubmitting || form.submitting;

  // --- Drawer width ---
  const drawerWidthClass = fullscreen
    ? 'w-full'
    : 'w-full max-w-2xl';

  return (
    <div className="fixed inset-0 z-50 flex justify-end" data-testid="bpm-task-drawer">
      {/* Backdrop */}
      <div
        className="absolute inset-0 bg-black/30"
        onClick={onClose}
        onKeyDown={(e) => e.key === 'Escape' && onClose()}
        role="button"
        tabIndex={0}
        aria-label="Close drawer" // TODO: i18n
      />

      {/* Drawer panel */}
      <div
        className={`relative flex flex-col bg-white shadow-xl transition-all duration-200 ${drawerWidthClass}`}
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-lg font-semibold text-gray-900">
              {fetchLoading
                ? 'Loading...' // TODO: i18n
                : taskData?.taskName || 'Task'} {/* TODO: i18n */}
            </h2>
            {taskData?.processName && (
              <p className="truncate text-sm text-gray-500">{taskData.processName}</p>
            )}
          </div>
          <div className="ml-4 flex items-center gap-2">
            {/* Fullscreen toggle */}
            <button
              type="button"
              onClick={() => setFullscreen((prev) => !prev)}
              className="rounded p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
              title={fullscreen ? 'Exit fullscreen' : 'Fullscreen'} // TODO: i18n
              data-testid="bpm-drawer-fullscreen-toggle"
            >
              {fullscreen ? (
                // Collapse icon
                <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M9 9V4.5M9 9H4.5M9 9L3.75 3.75M9 15v4.5M9 15H4.5M9 15l-5.25 5.25M15 9h4.5M15 9V4.5M15 9l5.25-5.25M15 15h4.5M15 15v4.5m0-4.5l5.25 5.25"
                  />
                </svg>
              ) : (
                // Expand icon
                <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path
                    strokeLinecap="round"
                    strokeLinejoin="round"
                    strokeWidth={2}
                    d="M3.75 3.75v4.5m0-4.5h4.5m-4.5 0L9 9M3.75 20.25v-4.5m0 4.5h4.5m-4.5 0L9 15M20.25 3.75h-4.5m4.5 0v4.5m0-4.5L15 9m5.25 11.25h-4.5m4.5 0v-4.5m0 4.5L15 15"
                  />
                </svg>
              )}
            </button>
            {/* Close button */}
            <button
              type="button"
              onClick={onClose}
              className="rounded p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
              data-testid="bpm-drawer-close"
            >
              <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* Body (scrollable) */}
        <div className="flex-1 space-y-6 overflow-y-auto px-6 py-4">
          {fetchLoading ? (
            <div className="py-12 text-center text-gray-400" data-testid="bpm-drawer-loading">
              {/* TODO: i18n */}
              <div className="mx-auto mb-3 h-8 w-8 animate-spin rounded-full border-2 border-gray-300 border-t-blue-500" />
              Loading task data...
            </div>
          ) : fetchError ? (
            <div
              className="rounded-lg bg-red-50 p-4 text-sm text-red-600"
              data-testid="bpm-drawer-error"
            >
              {fetchError}
            </div>
          ) : (
            <>
              {/* DSL Form (if formBinding exists) */}
              {hasForm && (
                <DslFormRenderer
                  form={form}
                  showButtons={false}
                  compact
                  className="bpm-task-form"
                />
              )}

              {/* Approval Opinion Section (always shown) */}
              <ApprovalOpinionSection
                decision={decision}
                comment={comment}
                onDecisionChange={setDecision}
                onCommentChange={setComment}
                disabled={isSubmitting}
              />

              {/* Action error */}
              {actionError && (
                <div
                  className="rounded-lg bg-red-50 p-3 text-sm text-red-600"
                  data-testid="bpm-drawer-action-error"
                >
                  {actionError}
                </div>
              )}
            </>
          )}
        </div>

        {/* Footer */}
        {!fetchLoading && !fetchError && (
          <div
            className="flex justify-end gap-3 border-t border-gray-200 px-6 py-4"
            data-testid="bpm-drawer-footer"
          >
            <button
              type="button"
              onClick={onClose}
              disabled={isSubmitting}
              className="rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium
                text-gray-700 transition-colors hover:bg-gray-50
                disabled:cursor-not-allowed disabled:opacity-50"
            >
              {/* TODO: i18n */}
              Cancel
            </button>
            <button
              type="button"
              onClick={handleReject}
              disabled={isSubmitting}
              className="rounded-lg bg-red-500 px-4 py-2 text-sm font-medium text-white
                transition-colors hover:bg-red-600
                disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="bpm-drawer-reject-btn"
            >
              {/* TODO: i18n */}
              {isSubmitting ? 'Processing...' : 'Reject'}
            </button>
            <button
              type="button"
              onClick={handleApprove}
              disabled={isSubmitting}
              className="rounded-lg bg-green-600 px-4 py-2 text-sm font-medium text-white
                transition-colors hover:bg-green-700
                disabled:cursor-not-allowed disabled:opacity-50"
              data-testid="bpm-drawer-approve-btn"
            >
              {/* TODO: i18n */}
              {isSubmitting ? 'Processing...' : 'Approve'}
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

export default BpmTaskDrawer;
