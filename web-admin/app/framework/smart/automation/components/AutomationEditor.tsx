// web-admin/app/smart/automation/components/AutomationEditor.tsx
import React, { useCallback, useState, useEffect, useMemo, useRef } from 'react';
import {
  FlowDesigner,
  useFlowValidation,
  type FlowData,
  type NodeStatusMap,
} from '~/plugins/core-designer/components/flow-designer-sdk';
import { automationNodes, automationCategoryOrder } from '../nodes';
import { applyActionCatalogAvailabilityToAutomationNodes } from '../nodes/actionCatalogMapping';
import { useSmartText } from '~/utils/i18n';
import { useToastContext } from '~/contexts/ToastContext';
import { AutomationDebugger, useDebugSession } from '../debug';
import { DesignerToolbar } from '~/shared/designer/DesignerToolbar';
import type { ActionResult, AutomationLog } from '../services/automationService';
import { ACTION_TYPE_I18N_KEYS } from './automationTypeLabels';
import { humanizeType } from '~/plugins/core-designer/components/flow-designer-sdk/utils';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionAction,
  type DecisionActionCatalog,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';

interface DeclaredAutomationAction {
  label?: string;
  actionType: string;
  config?: Record<string, unknown>;
}

type SmartTextResolver = (key: string, fallback?: string) => string;

interface EvidenceEntry {
  key: string;
  label: string;
  value: string;
}

interface DecisionTraceSummary {
  decisionCode?: string;
  status?: string;
  matched?: boolean;
  traceId?: string;
  outputs?: Record<string, unknown>;
}

interface AutomationDecisionCatalogApi {
  getActionCatalog: () => Promise<DecisionActionCatalog>;
}

function defaultDecisionCatalogApi(): AutomationDecisionCatalogApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

const RESULT_FIELD_ORDER = [
  'type',
  'channel',
  'sentCount',
  'recipientCount',
  'recipients',
  'targetPhones',
  'targetUserIds',
  'assigneeUserIds',
  'conversationIds',
  'messageIds',
  'provider',
  'providers',
  'template',
  'title',
  'content',
  'message',
  'failureReason',
  'errorMessage',
  'delivery',
  'itemType',
  'createdCount',
  'ccCount',
  'inboxItemIds',
  'deliveryMode',
  'statusCode',
  'url',
  'responseBodyPreview',
  'responseBytes',
  'processKey',
  'businessKey',
  'processInstanceId',
  'recordPid',
  'modelCode',
  'commentPid',
  'auditPid',
  'ruleCode',
  'mentions',
  'updatedFields',
  'outputVariable',
  'inputTokens',
  'outputTokens',
  'stopReason',
  'record',
  'success',
];

const RESULT_FIELD_LABELS: Record<string, { key: string; fallback: string }> = {
  type: { key: '$i18n:automation.editor.resultField.channel', fallback: 'Channel' },
  sentCount: { key: '$i18n:automation.editor.resultField.sentCount', fallback: 'Sent' },
  recipientCount: {
    key: '$i18n:automation.editor.resultField.recipientCount',
    fallback: 'Recipients',
  },
  recipients: {
    key: '$i18n:automation.editor.resultField.recipientList',
    fallback: 'Recipient List',
  },
  title: { key: '$i18n:automation.editor.resultField.title', fallback: 'Title' },
  content: { key: '$i18n:automation.editor.resultField.content', fallback: 'Content' },
  message: { key: '$i18n:automation.editor.resultField.message', fallback: 'Message' },
  failureReason: {
    key: '$i18n:automation.editor.resultField.failureReason',
    fallback: 'Failure Reason',
  },
  errorMessage: {
    key: '$i18n:automation.editor.resultField.errorMessage',
    fallback: 'Error Message',
  },
  statusCode: {
    key: '$i18n:automation.editor.resultField.statusCode',
    fallback: 'HTTP Status',
  },
  url: { key: '$i18n:automation.editor.resultField.url', fallback: 'Target URL' },
  deliveryMode: {
    key: '$i18n:automation.editor.resultField.deliveryMode',
    fallback: 'Delivery Mode',
  },
  responseBodyPreview: {
    key: '$i18n:automation.editor.resultField.responseBodyPreview',
    fallback: 'Response Preview',
  },
  responseBytes: {
    key: '$i18n:automation.editor.resultField.responseBytes',
    fallback: 'Response Bytes',
  },
  processKey: { key: '$i18n:automation.editor.resultField.processKey', fallback: 'Process' },
  businessKey: {
    key: '$i18n:automation.editor.resultField.businessKey',
    fallback: 'Business Key',
  },
  processInstanceId: {
    key: '$i18n:automation.editor.resultField.processInstanceId',
    fallback: 'Process Instance',
  },
  recordPid: { key: '$i18n:automation.editor.resultField.recordPid', fallback: 'Record' },
  modelCode: { key: '$i18n:automation.editor.resultField.modelCode', fallback: 'Model' },
  updatedFields: {
    key: '$i18n:automation.editor.resultField.updatedFields',
    fallback: 'Updated Fields',
  },
  outputVariable: {
    key: '$i18n:automation.editor.resultField.outputVariable',
    fallback: 'Output Variable',
  },
  inputTokens: {
    key: '$i18n:automation.editor.resultField.inputTokens',
    fallback: 'Input Tokens',
  },
  outputTokens: {
    key: '$i18n:automation.editor.resultField.outputTokens',
    fallback: 'Output Tokens',
  },
  stopReason: {
    key: '$i18n:automation.editor.resultField.stopReason',
    fallback: 'Stop Reason',
  },
  record: { key: '$i18n:automation.editor.resultField.record', fallback: 'Record' },
  success: { key: '$i18n:automation.editor.resultField.success', fallback: 'Success' },
  channel: { key: '$i18n:automation.editor.resultField.channel', fallback: 'Channel' },
  targetPhones: {
    key: '$i18n:automation.editor.resultField.targetPhones',
    fallback: 'Phone Targets',
  },
  targetUserIds: {
    key: '$i18n:automation.editor.resultField.targetUserIds',
    fallback: 'Target Users',
  },
  assigneeUserIds: {
    key: '$i18n:automation.editor.resultField.assigneeUserIds',
    fallback: 'Assignees',
  },
  conversationIds: {
    key: '$i18n:automation.editor.resultField.conversationIds',
    fallback: 'Conversation IDs',
  },
  messageIds: {
    key: '$i18n:automation.editor.resultField.messageIds',
    fallback: 'Message IDs',
  },
  provider: { key: '$i18n:automation.editor.resultField.smsProvider', fallback: 'SMS Provider' },
  providers: {
    key: '$i18n:automation.editor.resultField.smsProviders',
    fallback: 'SMS Providers',
  },
  template: { key: '$i18n:automation.editor.resultField.smsTemplate', fallback: 'SMS Template' },
  delivery: { key: '$i18n:automation.editor.resultField.delivery', fallback: 'Delivery' },
  itemType: { key: '$i18n:automation.editor.resultField.itemType', fallback: 'Inbox Type' },
  createdCount: {
    key: '$i18n:automation.editor.resultField.createdCount',
    fallback: 'Created',
  },
  ccCount: { key: '$i18n:automation.editor.resultField.ccCount', fallback: 'CC Count' },
  inboxItemIds: {
    key: '$i18n:automation.editor.resultField.inboxItemIds',
    fallback: 'Inbox Item IDs',
  },
  commentPid: { key: '$i18n:automation.editor.resultField.commentPid', fallback: 'Comment' },
  auditPid: { key: '$i18n:automation.editor.resultField.auditPid', fallback: 'Audit Entry' },
  ruleCode: {
    key: '$i18n:automation.editor.resultField.ruleCode',
    fallback: 'Rule / Automation',
  },
  mentions: { key: '$i18n:automation.editor.resultField.mentions', fallback: 'Mentions' },
};

const STATUS_VALUE_LABELS: Record<string, { key: string; fallback: string }> = {
  success: { key: '$i18n:automation.editor.status.success', fallback: 'Success' },
  completed: { key: '$i18n:automation.editor.status.completed', fallback: 'Completed' },
  failed: { key: '$i18n:automation.editor.status.failed', fallback: 'Failed' },
  error: { key: '$i18n:automation.editor.status.failed', fallback: 'Failed' },
  running: { key: '$i18n:automation.editor.status.running', fallback: 'Running' },
  pending: { key: '$i18n:automation.editor.status.pending', fallback: 'Pending' },
  skipped: { key: '$i18n:automation.editor.status.skipped', fallback: 'Skipped' },
};

const CHANNEL_VALUE_LABELS: Record<string, { key: string; fallback: string }> = {
  in_app: {
    key: '$i18n:automation.editor.resultValue.channel.inApp',
    fallback: 'In-app notification',
  },
  sms: { key: '$i18n:automation.editor.resultValue.channel.sms', fallback: 'SMS' },
  im: { key: '$i18n:automation.editor.resultValue.channel.im', fallback: 'IM message' },
  webhook: {
    key: '$i18n:automation.editor.resultValue.channel.webhook',
    fallback: 'Webhook',
  },
  email: { key: '$i18n:automation.editor.resultValue.channel.email', fallback: 'Email' },
};

const RESULT_VALUE_LABELS: Record<string, Record<string, { key: string; fallback: string }>> = {
  failureReason: {
    sms_delivery_failed: {
      key: '$i18n:automation.editor.resultValue.failureReason.smsDeliveryFailed',
      fallback: 'SMS delivery failed',
    },
  },
  delivery: {
    inbox: { key: '$i18n:automation.editor.resultValue.delivery.inbox', fallback: 'Inbox' },
  },
  itemType: {
    task: { key: '$i18n:automation.editor.resultValue.itemType.task', fallback: 'Task' },
    mention: { key: '$i18n:automation.editor.resultValue.itemType.mention', fallback: 'Mention' },
  },
};

export interface AutomationEditorProps {
  automationId?: string;
  initialData?: {
    name: string;
    description?: string;
    flowData?: FlowData;
  };
  onSave?: (data: { name: string; description?: string; flowData: FlowData }) => Promise<void>;
  readOnly?: boolean;
  /** Auto-enter debug mode on mount (triggered by ?debug=true URL param) */
  initialDebugMode?: boolean;
  /**
   * G5 — runtime status overlay. Pass a `Record<nodeId, status>` to highlight
   * each node with its run state (pending/running/completed/failed/skipped).
   * Typically supplied by `useAutomationNodeStatuses(logId)` from a run-history
   * page. Omit for the standard design-time view.
   */
  nodeStatuses?: NodeStatusMap | null;
  /** Business sample context used by the toolbar Test Run action. */
  testRunContext?: Record<string, unknown>;
  testRunRecordPid?: string;
  decisionCatalogApi?: AutomationDecisionCatalogApi;
  /**
   * Runtime trace deep links (`/automation/:pid?logId=...`) pass the historical
   * run log here so the page opens with both node badges and action evidence.
   */
  initialRunLog?: AutomationLog | null;
}

export function AutomationEditor({
  automationId,
  initialData,
  onSave,
  readOnly = false,
  initialDebugMode = false,
  nodeStatuses,
  testRunContext,
  testRunRecordPid,
  decisionCatalogApi,
  initialRunLog,
}: AutomationEditorProps) {
  const st = useSmartText();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const [name, setName] = useState(initialData?.name || '');
  const [description, setDescription] = useState(initialData?.description || '');
  const [flowData, setFlowData] = useState<FlowData | undefined>(initialData?.flowData);
  const [testRunning, setTestRunning] = useState(false);
  const [lastTestRunLog, setLastTestRunLog] = useState<AutomationLog | null>(initialRunLog ?? null);
  const [resultPanelOpen, setResultPanelOpen] = useState(Boolean(initialRunLog));
  const [isDirty, setIsDirty] = useState(false);
  const [saving, setSaving] = useState(false);
  const [actionCatalog, setActionCatalog] = useState<DecisionAction[]>([]);
  const initialised = useRef(false);

  const { isDebugMode, startDebug } = useDebugSession();
  const { validate } = useFlowValidation();
  const catalogApi = useMemo(
    () => decisionCatalogApi ?? defaultDecisionCatalogApi(),
    [decisionCatalogApi],
  );

  useEffect(() => {
    if (initialDebugMode && automationId) {
      startDebug(automationId);
    }
  }, [initialDebugMode, automationId, startDebug]);

  useEffect(() => {
    if (initialRunLog) {
      setLastTestRunLog(initialRunLog);
      setResultPanelOpen(true);
    }
  }, [initialRunLog]);

  useEffect(() => {
    let cancelled = false;
    catalogApi.getActionCatalog()
      .then((catalog) => {
        if (!cancelled) {
          setActionCatalog(Array.isArray(catalog.actions) ? catalog.actions : []);
        }
      })
      .catch(() => {
        if (!cancelled) setActionCatalog([]);
      });
    return () => {
      cancelled = true;
    };
  }, [catalogApi]);

  useEffect(() => {
    if (initialData) {
      setName(initialData.name);
      setDescription(initialData.description || '');
      setFlowData(initialData.flowData);
      // Reset dirty on initial data load (but not on first mount before user edits)
      if (initialised.current) {
        setIsDirty(false);
      }
      initialised.current = true;
    }
  }, [initialData]);

  const handleNameChange = useCallback((value: string) => {
    setName(value);
    setIsDirty(true);
  }, []);

  const handleDescriptionChange = useCallback((value: string) => {
    setDescription(value);
    setIsDirty(true);
  }, []);

  /** Page-level save: uses the latest flowData snapshot and keeps the only save
   *  action in the Automation editor shell. */
  const handleToolbarSave = useCallback(async () => {
    if (!onSave || !flowData) return;
    const result = validate();
    if (!result.valid) {
      showErrorToast(
        st('$i18n:flow.validation.saveBlocked') ||
          'Please fix the highlighted fields before saving',
      );
      return;
    }
    setSaving(true);
    try {
      await onSave({ name, description, flowData });
      setIsDirty(false);
    } finally {
      setSaving(false);
    }
  }, [onSave, name, description, flowData, validate, showErrorToast, st]);

  const handleChange = useCallback((data: FlowData) => {
    setFlowData(data);
    setIsDirty(true);
  }, []);

  /**
   * Stable `initialData` reference for FlowDesigner.
   *
   * Derived strictly from the `initialData` prop (i.e. the value loaded from
   * the route loader / DB), NOT from the local `flowData` state that mirrors
   * the FlowDesigner's onChange output.
   *
   * Previously we passed `flowData` (the local state) back as `initialData`,
   * which meant every onChange → setFlowData → new reference → FlowDesigner
   * mount-effect → importData() → store reset → `selectedNodeId` cleared.
   * Symptom: editing a property field unmounts the property panel until the
   * user re-clicks the node. (See ACP H.1 + the workaround comments removed
   * from `tests/e2e/automation/llm-call-node.spec.ts`.)
   *
   * By memoising on the prop reference, FlowDesigner's mount-effect only
   * re-runs when the parent genuinely supplies a new schema (e.g. after a
   * reload from the server), not on every keystroke.
   */
  const flowDataInitial = useMemo(
    () => initialData?.flowData,
    [initialData],
  );
  const declaredActions = useMemo(() => extractDeclaredActions(flowData), [flowData]);

  const handleDebug = useCallback(() => {
    if (!automationId) return;
    startDebug(automationId);
  }, [automationId, startDebug]);

  const handleTestRun = useCallback(async () => {
    if (!automationId) return;
    setTestRunning(true);
    try {
      const response = await fetch(`/api/automations/${automationId}/trigger`, {
        method: 'post',
        credentials: 'same-origin',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          ...(testRunRecordPid ? { recordPid: testRunRecordPid } : {}),
          context: testRunContext ?? {},
        }),
      });
      if (!response.ok) {
        const errData = await response.json().catch(() => null);
        throw new Error(errData?.desc || `Test run failed (${response.status})`);
      }
      const result = await response.json();
      const log = result.data as AutomationLog | null;
      if (log) {
        setLastTestRunLog(log);
        setResultPanelOpen(true);
      }
      if (log?.status === 'success') {
        showSuccessToast(`Test run completed successfully (${log.durationMs || 0}ms)`);
      } else if (log?.status === 'failed') {
        showErrorToast(`Test run failed: ${log.errorMessage || 'Unknown error'}`);
      } else {
        showSuccessToast('Test run triggered. Check logs for results.');
      }
    } catch (err) {
      showErrorToast(err instanceof Error ? err.message : 'Test run failed');
    } finally {
      setTestRunning(false);
    }
  }, [automationId, showSuccessToast, showErrorToast, testRunContext, testRunRecordPid]);

  const handleExport = useCallback(() => {
    if (!flowData) return;
    const exportData = {
      name,
      description,
      flowConfig: flowData,
      exportedAt: new Date().toISOString(),
    };
    const blob = new Blob([JSON.stringify(exportData, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `automation-${name.replace(/\s+/g, '-').toLowerCase() || 'untitled'}.json`;
    document.body.appendChild(a);
    a.click();
    document.body.removeChild(a);
    URL.revokeObjectURL(url);
    showSuccessToast('Automation exported');
  }, [flowData, name, description, showSuccessToast]);

  const config = useMemo(
    () => ({
      nodeDefinitions: applyActionCatalogAvailabilityToAutomationNodes(automationNodes, actionCatalog),
      categoryOrder: automationCategoryOrder,
      showMinimap: false,
      showControls: true,
    }),
    [actionCatalog],
  );

  // Debug mode: show debugger instead of editor
  if (isDebugMode) {
    return <AutomationDebugger />;
  }

  const title = automationId
    ? `${st('$i18n:automation.editor.edit') || 'Edit Automation'}: ${name}`
    : st('$i18n:automation.editor.create') || 'Create Automation';

  return (
    <div className="relative flex min-h-0 flex-1 flex-col">
      {/* Header toolbar */}
      {!readOnly && (
        <DesignerToolbar
          title={title}
          titleElement={
            <div className="flex items-center gap-3">
              <input
                type="text"
                value={name}
                onChange={(e) => handleNameChange(e.target.value)}
                placeholder={st('$i18n:automation.editor.namePlaceholder') || 'Automation name'}
                data-testid="automation-editor-name-input"
                className="w-64 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
              <input
                type="text"
                value={description}
                onChange={(e) => handleDescriptionChange(e.target.value)}
                placeholder={
                  st('$i18n:automation.editor.descriptionPlaceholder') || 'Description (optional)'
                }
                data-testid="automation-editor-description-input"
                className="w-64 rounded-md border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 focus:outline-none"
              />
            </div>
          }
          isDirty={isDirty}
          isSaving={saving}
          onSave={onSave ? handleToolbarSave : undefined}
          testId="automation-editor-toolbar"
        >
          <button
            onClick={handleExport}
            disabled={!flowData}
            className="shrink-0 rounded-md border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50"
            data-testid="btn-export-automation"
          >
            {st('$i18n:automation.editor.export') || 'Export'}
          </button>
          {automationId && (
            <>
              <button
                onClick={handleTestRun}
                disabled={testRunning}
                className="shrink-0 rounded-md bg-green-600 px-4 py-2 text-sm font-medium text-white hover:bg-green-700 disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="btn-test-run"
              >
                {testRunning
                  ? st('$i18n:automation.editor.testRunning') || 'Running...'
                  : st('$i18n:automation.editor.testRun') || 'Test Run'}
              </button>
              <button
                onClick={handleDebug}
                className="shrink-0 rounded-md bg-gray-800 px-4 py-2 text-sm text-white hover:bg-gray-900"
                data-testid="btn-debug-automation"
              >
                {st('$i18n:automation.editor.debug') || 'Debug'}
              </button>
            </>
          )}
        </DesignerToolbar>
      )}

      {/* Flow Designer */}
      <div className="min-h-0 flex-1">
        <FlowDesigner
          config={config}
          initialData={flowDataInitial}
          title={title}
          onSave={undefined}
          onChange={handleChange}
          readOnly={readOnly}
          nodeStatuses={nodeStatuses}
        />
      </div>

      {lastTestRunLog && resultPanelOpen && (
        <AutomationTestRunResultPanel
          log={lastTestRunLog}
          automationId={automationId}
          declaredActions={declaredActions}
          t={st}
          onClose={() => setResultPanelOpen(false)}
        />
      )}
    </div>
  );
}

function AutomationTestRunResultPanel({
  log,
  automationId,
  declaredActions,
  t,
  onClose,
}: {
  log: AutomationLog;
  automationId?: string;
  declaredActions: DeclaredAutomationAction[];
  t: (key: string) => string;
  onClose: () => void;
}) {
  const actionResults = Array.isArray(log.actionResults) ? log.actionResults : [];
  const decisionTrace = extractDecisionTrace(log.triggerPayload);
  const unifiedTraceHref = automationUnifiedTraceHref(automationId, decisionTrace);
  const runtimeTraceHref = automationId && log.id
    ? `/automation/${encodeURIComponent(automationId)}?logId=${encodeURIComponent(String(log.id))}`
    : undefined;

  return (
    <aside
      className="absolute top-20 right-4 bottom-4 z-30 flex w-[min(28rem,calc(100vw-2rem))] flex-col overflow-hidden rounded-lg border border-gray-200 bg-white shadow-2xl"
      data-testid="automation-test-run-result"
    >
      <div className="flex items-start justify-between border-b border-gray-200 px-5 py-4">
        <div className="min-w-0">
          <h2 className="text-base font-semibold text-gray-900">
            {t('$i18n:automation.editor.testRunResult') || 'Test Run Result'}
          </h2>
          {log.pid && (
            <p className="mt-1 text-xs text-gray-500">
              {t('$i18n:automation.editor.logPid') || 'Log'} {log.pid}
            </p>
          )}
        </div>
        <button
          type="button"
          onClick={onClose}
          className="rounded-md border border-gray-200 px-2 py-1 text-xs text-gray-500 hover:bg-gray-50 hover:text-gray-700"
          data-testid="automation-test-run-result-close"
        >
          {t('$i18n:automation.editor.closeResult') || 'Close'}
        </button>
      </div>

      <div className="flex-1 space-y-4 overflow-y-auto p-5">
        {(unifiedTraceHref || runtimeTraceHref) && (
          <div className="flex flex-wrap gap-2">
            {unifiedTraceHref && (
              <a
                href={unifiedTraceHref}
                className="inline-flex rounded-md border border-indigo-200 bg-indigo-50 px-3 py-2 text-xs font-medium text-indigo-700 hover:bg-indigo-100"
                data-testid="automation-unified-trace-link"
              >
                {resolveSmartText(t, '$i18n:automation.editor.openUnifiedTrace', 'Open Unified Trace')}
              </a>
            )}
            {runtimeTraceHref && (
              <a
                href={runtimeTraceHref}
                className="inline-flex rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-xs font-medium text-blue-700 hover:bg-blue-100"
                data-testid="automation-runtime-trace-link"
              >
                {resolveSmartText(t, '$i18n:automation.editor.openRuntimeTrace', 'Open Runtime Trace')}
              </a>
            )}
          </div>
        )}

        <div className="grid grid-cols-3 gap-3">
          <RunSummaryItem label={t('$i18n:automation.editor.resultStatus') || 'Status'}>
            <span className={statusBadgeClass(log.status)} data-testid="automation-run-status">
              {statusLabel(log.status, t)}
            </span>
          </RunSummaryItem>
          <RunSummaryItem label={t('$i18n:automation.editor.duration') || 'Duration'}>
            {formatDuration(log.durationMs)}
          </RunSummaryItem>
          <RunSummaryItem label={t('$i18n:automation.editor.recordPid') || 'Record'}>
            {log.triggerRecordPid || '-'}
          </RunSummaryItem>
        </div>

        {log.errorMessage && (
          <div className="rounded-md border border-red-200 bg-red-50 p-3 text-sm text-red-700">
            {log.errorMessage}
          </div>
        )}

        {decisionTrace && <DecisionTraceCard trace={decisionTrace} t={t} />}

        <section>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">
            {t('$i18n:automation.editor.actionResults') || 'Action Results'}
          </h3>
          {actionResults.length === 0 ? (
            declaredActions.length > 0 ? (
              <div className="space-y-2">
                <div className="rounded-md border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800">
                  {t('$i18n:automation.editor.actionResultsMissing') ||
                    'Runtime log did not return per-action results'}
                </div>
                {declaredActions.map((action, index) => (
                  <DeclaredActionCard
                    key={`${action.actionType}-${index}`}
                    action={action}
                    index={index}
                    t={t}
                  />
                ))}
              </div>
            ) : (
              <div className="rounded-md border border-gray-200 bg-gray-50 p-3 text-sm text-gray-500">
                {t('$i18n:automation.editor.noActionResults') || 'No action results yet'}
              </div>
            )
          ) : (
            <div className="space-y-2">
              {actionResults.map((action, index) => (
                <ActionResultCard
                  key={`${action.sequence ?? index}-${action.actionType}`}
                  action={action}
                  index={index}
                  t={t}
                />
              ))}
            </div>
          )}
        </section>

        <section>
          <h3 className="mb-2 text-sm font-semibold text-gray-900">
            {t('$i18n:automation.editor.triggerPayload') || 'Trigger Context'}
          </h3>
          <pre className="max-h-56 overflow-auto rounded-md bg-gray-950 p-3 text-xs text-gray-100">
            {formatValue(log.triggerPayload ?? {})}
          </pre>
        </section>
      </div>
    </aside>
  );
}

function DecisionTraceCard({
  trace,
  t,
}: {
  trace: DecisionTraceSummary;
  t: SmartTextResolver;
}) {
  const outputs = trace.outputs && isRecord(trace.outputs)
    ? Object.entries(trace.outputs).filter(([, value]) => value !== undefined && value !== null && value !== '')
    : [];

  return (
    <section
      className="rounded-lg border border-blue-100 bg-blue-50/60 p-4"
      data-testid="automation-decision-trace"
    >
      <div className="flex items-start justify-between gap-3">
        <div>
          <h3 className="text-sm font-semibold text-blue-950">
            {resolveSmartText(t, '$i18n:automation.editor.decisionTrace', 'Rule Decision Trace')}
          </h3>
          <p className="mt-1 text-xs text-blue-700">
            {resolveSmartText(
              t,
              '$i18n:automation.editor.decisionTraceSubtitle',
              'Rule binding outputs used by this run',
            )}
          </p>
        </div>
        <span
          className={trace.matched === false
            ? 'inline-flex rounded-full bg-amber-100 px-2 py-0.5 text-xs font-medium text-amber-700'
            : 'inline-flex rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700'}
          data-testid="automation-decision-trace-status"
        >
          {decisionTraceStatusLabel(trace, t)}
        </span>
      </div>

      <dl className="mt-3 grid grid-cols-2 gap-2">
        {trace.decisionCode && (
          <TraceFact
            label={resolveSmartText(t, '$i18n:automation.editor.decisionCode', 'Decision')}
            value={trace.decisionCode}
          />
        )}
        {trace.traceId && (
          <TraceFact
            label={resolveSmartText(t, '$i18n:automation.editor.decisionTraceId', 'Trace ID')}
            value={trace.traceId}
          />
        )}
        {outputs.map(([key, value]) => (
          <TraceFact key={key} label={key} value={formatEvidenceValue(key, value, t)} />
        ))}
      </dl>
    </section>
  );
}

function TraceFact({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-white px-3 py-2">
      <dt className="text-xs font-medium text-blue-600">{label}</dt>
      <dd className="mt-1 break-words text-sm text-blue-950">{value}</dd>
    </div>
  );
}

function extractDecisionTrace(payload: Record<string, unknown> | undefined): DecisionTraceSummary | null {
  if (!isRecord(payload)) return null;
  const raw = payload.decision;
  if (!isRecord(raw)) return null;
  const outputs = isRecord(raw.outputs) ? raw.outputs : undefined;
  return {
    decisionCode: stringValue(raw.decisionCode),
    status: stringValue(raw.status),
    matched: typeof raw.matched === 'boolean' ? raw.matched : undefined,
    traceId: stringValue(raw.traceId),
    outputs,
  };
}

function automationUnifiedTraceHref(
  automationId: string | undefined,
  trace: DecisionTraceSummary | null,
): string | undefined {
  if (!automationId || !trace?.traceId) return undefined;
  const params = new URLSearchParams();
  params.set('traceId', trace.traceId);
  if (trace.decisionCode) params.set('decisionCode', trace.decisionCode);
  params.set('callerType', 'AUTOMATION');
  params.set('callerRef', automationId);
  return `/p/decisionops_execution_logs?${params.toString()}`;
}

function decisionTraceStatusLabel(trace: DecisionTraceSummary, t: SmartTextResolver) {
  if (trace.matched === true) {
    return resolveSmartText(t, '$i18n:automation.editor.decisionMatched', 'Matched');
  }
  if (trace.matched === false) {
    return resolveSmartText(t, '$i18n:automation.editor.decisionNotMatched', 'Not matched');
  }
  return trace.status ? humanizeType(trace.status) : resolveSmartText(t, '$i18n:automation.editor.status.unknown', 'Unknown');
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function extractDeclaredActions(flowData: FlowData | undefined): DeclaredAutomationAction[] {
  if (!Array.isArray(flowData?.nodes)) return [];
  const actions: DeclaredAutomationAction[] = [];
  for (const node of flowData.nodes) {
    const data = node.data as Record<string, unknown> | undefined;
    const config = data?.config && typeof data.config === 'object' && !Array.isArray(data.config)
      ? data.config as Record<string, unknown>
      : {};
    const actionType = typeof config.actionType === 'string'
      ? config.actionType
      : typeof node.type === 'string' && node.type.startsWith('action-')
        ? node.type.replace(/^action-/, '').replaceAll('-', '_')
        : '';
    if (!actionType) continue;
    actions.push({
      label: typeof data?.label === 'string' ? data.label : undefined,
      actionType,
      config,
    });
  }
  return actions;
}

function RunSummaryItem({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="rounded-md border border-gray-200 bg-gray-50 p-3">
      <div className="text-xs text-gray-500">{label}</div>
      <div className="mt-1 break-words text-sm font-medium text-gray-900">{children}</div>
    </div>
  );
}

function DeclaredActionCard({
  action,
  index,
  t,
}: {
  action: DeclaredAutomationAction;
  index: number;
  t: SmartTextResolver;
}) {
  const displayConfig = sanitizeActionConfig(action.config);
  const evidence = evidenceEntries(displayConfig, t);

  return (
    <div
      className="rounded-md border border-gray-200 p-3"
      data-testid={`automation-declared-action-${index}`}
    >
      <div className="text-sm font-semibold text-gray-900">
        {action.label ? `${action.label} · ` : null}
        {actionDisplayName(action.actionType, t)}
      </div>
      {evidence.length > 0 && <EvidenceGrid entries={evidence} />}
      {displayConfig && Object.keys(displayConfig).length > 0 && (
        <RawDetails
          summary={resolveSmartText(t, '$i18n:automation.editor.rawConfig', 'Raw config')}
          value={displayConfig}
        />
      )}
    </div>
  );
}

function ActionResultCard({
  action,
  index,
  t,
}: {
  action: ActionResult;
  index: number;
  t: SmartTextResolver;
}) {
  const evidence = evidenceEntries(action.result, t);

  return (
    <div
      className="rounded-md border border-gray-200 p-3"
      data-testid={`automation-action-result-${action.sequence ?? index + 1}`}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="text-sm font-semibold text-gray-900">
            {action.sequence ? `${action.sequence}. ` : null}
            {actionDisplayName(action.actionType, t)}
          </div>
          <div className="mt-1 text-xs text-gray-500">{formatDuration(action.durationMs)}</div>
        </div>
        <span
          className={statusBadgeClass(action.status)}
          data-testid={`automation-action-status-${action.sequence ?? index + 1}`}
        >
          {statusLabel(action.status, t)}
        </span>
      </div>
      {action.errorMessage && (
        <div className="mt-2 rounded bg-red-50 px-2 py-1 text-xs text-red-700">
          {action.errorMessage}
        </div>
      )}
      {evidence.length > 0 && <EvidenceGrid entries={evidence} />}
      {action.result !== undefined && (
        <RawDetails
          summary={resolveSmartText(t, '$i18n:automation.editor.rawResult', 'Raw result')}
          value={action.result}
        />
      )}
    </div>
  );
}

function EvidenceGrid({ entries }: { entries: EvidenceEntry[] }) {
  return (
    <dl className="mt-3 grid grid-cols-2 gap-2" data-testid="automation-action-evidence">
      {entries.map((entry) => (
        <div
          key={entry.key}
          className="rounded-md bg-gray-50 px-3 py-2"
          data-testid={`automation-action-evidence-${entry.key}`}
        >
          <dt className="text-xs font-medium text-gray-500">{entry.label}</dt>
          <dd className="mt-1 break-words text-sm text-gray-900">{entry.value}</dd>
        </div>
      ))}
    </dl>
  );
}

function RawDetails({ summary, value }: { summary: string; value: unknown }) {
  return (
    <details className="mt-3 rounded-md border border-gray-200 bg-white">
      <summary className="cursor-pointer px-3 py-2 text-xs font-medium text-gray-600 hover:bg-gray-50">
        {summary}
      </summary>
      <pre className="max-h-40 overflow-auto border-t border-gray-200 bg-gray-50 p-3 text-xs text-gray-700">
        {formatValue(value)}
      </pre>
    </details>
  );
}

function actionDisplayName(actionType: string, t: SmartTextResolver) {
  const fallback = humanizeType(actionType);
  const key = ACTION_TYPE_I18N_KEYS[actionType];
  return key ? resolveSmartText(t, key, fallback) : fallback;
}

function evidenceEntries(value: unknown, t: SmartTextResolver): EvidenceEntry[] {
  if (!isRecord(value)) return [];
  const keys = orderedKeys(Object.keys(value));
  return keys
    .map((key) => {
      const rawValue = value[key];
      if (rawValue === undefined || rawValue === null || rawValue === '') return null;
      const rendered = formatEvidenceValue(key, rawValue, t);
      if (!rendered) return null;
      return {
        key,
        label: fieldLabel(key, t),
        value: rendered,
      };
    })
    .filter((entry): entry is EvidenceEntry => entry !== null);
}

function orderedKeys(keys: string[]) {
  const known = RESULT_FIELD_ORDER.filter((key) => keys.includes(key));
  const knownSet = new Set(known);
  const rest = keys.filter((key) => !knownSet.has(key) && key !== 'actionType').sort();
  return [...known, ...rest];
}

function fieldLabel(field: string, t: SmartTextResolver) {
  const config = RESULT_FIELD_LABELS[field];
  if (config) return resolveSmartText(t, config.key, config.fallback);
  return humanizeType(field);
}

function formatEvidenceValue(field: string, value: unknown, t: SmartTextResolver): string {
  if (field === 'deliveryMode' && value === 'direct_http') {
    return resolveSmartText(t, '$i18n:automation.editor.resultValue.directHttp', 'Direct HTTP');
  }
  if ((field === 'type' || field === 'channel') && typeof value === 'string') {
    const config = CHANNEL_VALUE_LABELS[value.toLowerCase()];
    if (config) return resolveSmartText(t, config.key, config.fallback);
  }
  if (typeof value === 'string') {
    const config = RESULT_VALUE_LABELS[field]?.[value.toLowerCase()];
    if (config) return resolveSmartText(t, config.key, config.fallback);
  }
  if (Array.isArray(value)) {
    return value.map((item) => formatEvidenceValue(field, item, t)).filter(Boolean).join(', ');
  }
  if (typeof value === 'boolean') {
    return value
      ? resolveSmartText(t, '$i18n:automation.editor.resultValue.yes', 'Yes')
      : resolveSmartText(t, '$i18n:automation.editor.resultValue.no', 'No');
  }
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') return value;
  if (isRecord(value)) {
    const compact = Object.entries(value)
      .map(([key, item]) => `${fieldLabel(key, t)}: ${formatEvidenceValue(key, item, t)}`)
      .join(', ');
    return compact.length > 160 ? `${compact.slice(0, 157)}...` : compact;
  }
  return value == null ? '' : String(value);
}

function statusLabel(status: string | undefined, t: SmartTextResolver) {
  const normalized = String(status || '').toLowerCase();
  const config = STATUS_VALUE_LABELS[normalized];
  if (config) return resolveSmartText(t, config.key, config.fallback);
  return status ? humanizeType(status) : resolveSmartText(t, '$i18n:automation.editor.status.unknown', 'Unknown');
}

function sanitizeActionConfig(config: Record<string, unknown> | undefined) {
  if (!config) return undefined;
  const { actionType: _actionType, ...rest } = config;
  return rest;
}

function resolveSmartText(t: SmartTextResolver, key: string, fallback: string) {
  const translated = t(key, fallback);
  if (!translated || translated === key || translated.startsWith('$i18n:')) return fallback;
  return translated;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value);
}

function statusBadgeClass(status: string | undefined) {
  const normalized = String(status || '').toLowerCase();
  if (normalized === 'success' || normalized === 'completed') {
    return 'inline-flex rounded-full bg-green-100 px-2 py-0.5 text-xs font-medium text-green-700';
  }
  if (normalized === 'failed' || normalized === 'error') {
    return 'inline-flex rounded-full bg-red-100 px-2 py-0.5 text-xs font-medium text-red-700';
  }
  if (normalized === 'running' || normalized === 'pending') {
    return 'inline-flex rounded-full bg-blue-100 px-2 py-0.5 text-xs font-medium text-blue-700';
  }
  return 'inline-flex rounded-full bg-gray-100 px-2 py-0.5 text-xs font-medium text-gray-700';
}

function formatDuration(durationMs: number | undefined) {
  return typeof durationMs === 'number' ? `${durationMs}ms` : '-';
}

function formatValue(value: unknown) {
  if (typeof value === 'string') return value;
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export default AutomationEditor;
