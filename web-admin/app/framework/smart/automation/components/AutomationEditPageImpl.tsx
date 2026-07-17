// OSS implementation of the Automation editor page body.
// The route shell (plugins/core-automation/pages/automation.$id.tsx) lazy-loads
// this component and renders it inside a <Suspense>. Loader data is consumed via
// useLoaderData().
//
// Enterprise builds may overlay this file to provide advanced behaviors.

import { useEffect, useState } from 'react';
import { useParams, useNavigate, useSearchParams, useLoaderData } from 'react-router';
import { AutomationEditor } from './AutomationEditor';
import { useAutomationNodeStatuses } from './useAutomationNodeStatuses';
import type { Automation, AutomationLog } from '../services/automationService';
import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';

interface LoaderData {
  automation: Automation | null;
  token: string | null;
  isNew: boolean;
  error?: string;
}

const TRIGGER_NODE_TYPE_BY_TRIGGER: Record<string, string> = {
  on_record_create: 'trigger-record-create',
  on_record_update: 'trigger-record-update',
  on_field_change: 'trigger-field-change',
  on_state_change: 'trigger-state-change',
  scheduled: 'trigger-scheduled',
  webhook: 'trigger-webhook',
  on_bpm_event: 'trigger-bpm-event',
  on_inactivity: 'trigger-inactivity',
};

const ACTION_NODE_TYPE_BY_ACTION: Record<string, string> = {
  update_record: 'action-update-record',
  create_record: 'action-create-record',
  send_notification: 'action-send-notification',
  execute_command: 'action-execute-command',
  call_api: 'action-call-api',
  send_webhook: 'action-send-webhook',
  start_process: 'action-start-process',
  llm_call: 'action-llm-call',
};

function normalizeRuleBindingScopes(config: Record<string, unknown> | null | undefined): Record<string, unknown> {
  const ruleBinding = config?.ruleBinding;
  if (!ruleBinding || typeof ruleBinding !== 'object' || Array.isArray(ruleBinding)) {
    return { ...(config ?? {}) };
  }
  const bindingRecord = ruleBinding as Record<string, unknown>;
  const decisionBinding = bindingRecord.decisionBinding;
  if (!decisionBinding || typeof decisionBinding !== 'object' || Array.isArray(decisionBinding)) {
    return { ...(config ?? {}) };
  }
  const decisionRecord = decisionBinding as Record<string, unknown>;
  const inputMappings = Array.isArray(decisionRecord.inputMappings)
    ? decisionRecord.inputMappings.map((raw) => {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return raw;
        const mapping = raw as Record<string, unknown>;
        const source = mapping.source;
        if (!source || typeof source !== 'object' || Array.isArray(source)) return mapping;
        const sourceRecord = source as Record<string, unknown>;
        return {
          ...mapping,
          source: {
            ...sourceRecord,
            scope: typeof sourceRecord.scope === 'string'
              ? sourceRecord.scope.toLowerCase()
              : sourceRecord.scope,
          },
        };
      })
    : decisionRecord.inputMappings;

  return {
    ...(config ?? {}),
    ruleBinding: {
      ...bindingRecord,
      decisionBinding: {
        ...decisionRecord,
        inputMappings,
      },
    },
  };
}

function normalizeLegacyActionConfig(
  actionType: string,
  config: Record<string, unknown> | null | undefined,
): Record<string, unknown> {
  const next = { ...(config ?? {}) };
  if (actionType === 'send_notification') {
    if (typeof next.notificationType !== 'string' && typeof next.type === 'string') {
      next.notificationType = next.type;
    }
    if (Array.isArray(next.recipients)) {
      next.recipients = next.recipients.join(', ');
    }
  }
  return next;
}

function isPlainRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function getAutomationTestRunContext(
  automation: Automation | null | undefined,
): Record<string, unknown> | undefined {
  const config = automation?.triggerConfig;
  const sample = isPlainRecord(config) ? config.testContext : undefined;
  return isPlainRecord(sample) ? sample : undefined;
}

function getAutomationTestRunRecordPid(
  automation: Automation | null | undefined,
): string | undefined {
  const config = automation?.triggerConfig;
  const sampleRecordPid = isPlainRecord(config) ? config.testRecordPid : undefined;
  return typeof sampleRecordPid === 'string' && sampleRecordPid.trim()
    ? sampleRecordPid
    : undefined;
}

/**
 * Synthesize a FlowData layout from flat triggerType + actions when flowConfig is null.
 * Ensures the visual editor always has nodes to render for legacy automations.
 */
export function synthesizeFlowData(automation: Automation | null | undefined): FlowData | undefined {
  if (!automation?.triggerType) return undefined;

  const nodes: FlowData['nodes'] = [];
  const edges: FlowData['edges'] = [];
  const yCenter = 200;
  let x = 100;

  const triggerId = 'trigger_0';
  const triggerNodeType = TRIGGER_NODE_TYPE_BY_TRIGGER[automation.triggerType] ?? 'trigger-record-create';
  nodes.push({
    id: triggerId,
    type: triggerNodeType,
    position: { x, y: yCenter },
    data: {
      type: 'trigger',
      label: automation.triggerType,
      config: {
        ...normalizeRuleBindingScopes(automation.triggerConfig),
        triggerType: automation.triggerType,
        modelCode: automation.modelCode,
      },
    },
  });

  let prevId = triggerId;
  x += 250;

  (automation.actions || []).forEach((action, idx) => {
    const actionId = `action_${idx}`;
    const actionNodeType = ACTION_NODE_TYPE_BY_ACTION[action.type] ?? 'action-execute-command';
    nodes.push({
      id: actionId,
      type: actionNodeType,
      position: { x, y: yCenter },
      data: {
        type: 'action',
        label: action.label || action.type,
        config: {
          actionType: action.type,
          ...normalizeLegacyActionConfig(action.type, action.config),
        },
      },
    });
    edges.push({
      id: `edge_${prevId}_${actionId}`,
      source: prevId,
      target: actionId,
      type: 'smoothstep',
    });
    prevId = actionId;
    x += 250;
  });

  return { nodes, edges };
}

export interface AutomationEditPageImplProps {
  id?: string;
}

export function AutomationEditPageImpl(_props: AutomationEditPageImplProps) {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { automation, token, isNew, error } = useLoaderData<LoaderData>();
  const debugMode = searchParams.get('debug') === 'true';

  // G5 runtime overlay: when the URL carries `?logId=<n>` (e.g. opened from a run
  // history row or after a test run), fetch that log's per-node statuses and feed
  // them to the canvas so each node renders its completed/failed/running badge.
  // Without a logId the hook returns null and the canvas shows the plain design view.
  const logIdParam = searchParams.get('logId') ?? undefined;
  const { statuses: nodeStatuses } = useAutomationNodeStatuses(isNew ? undefined : logIdParam);
  const [runtimeLog, setRuntimeLog] = useState<AutomationLog | null>(null);

  useEffect(() => {
    if (isNew || !id || !logIdParam) {
      setRuntimeLog(null);
      return;
    }

    let cancelled = false;
    const headers: HeadersInit = token ? { Authorization: `Bearer ${token}` } : {};
    fetch(`/api/automations/${encodeURIComponent(id)}/logs?limit=50`, { headers })
      .then(async (response) => {
        if (!response.ok) {
          throw new Error(`Failed to load automation log (${response.status})`);
        }
        return response.json();
      })
      .then((body) => {
        if (cancelled) return;
        const rows = Array.isArray(body?.data) ? body.data as AutomationLog[] : [];
        const selected = rows.find((row) => String(row.id) === String(logIdParam) || row.pid === logIdParam) ?? null;
        setRuntimeLog(selected);
      })
      .catch(() => {
        if (!cancelled) setRuntimeLog(null);
      });

    return () => {
      cancelled = true;
    };
  }, [isNew, id, logIdParam, token]);

  const handleSave = async (saveData: {
    name: string;
    description?: string;
    flowData: FlowData;
  }) => {
    const headers: HeadersInit = {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
    };

    if (isNew) {
      const response = await fetch('/api/automations', {
        method: 'post',
        headers,
        body: JSON.stringify({
          name: saveData.name,
          description: saveData.description,
          flowConfig: saveData.flowData,
        }),
      });
      if (!response.ok) throw new Error('Failed to save automation');
      const result = await response.json();
      navigate(`/automation/${result.data.pid}`, { replace: true });
    } else {
      const response = await fetch(`/api/automations/${id}`, {
        method: 'put',
        headers,
        body: JSON.stringify({
          name: saveData.name,
          description: saveData.description,
          flowConfig: saveData.flowData,
        }),
      });
      if (!response.ok) throw new Error('Failed to save automation');
    }
  };

  if (error && !isNew) {
    return (
      <div className="flex h-full items-center justify-center">
        <div className="text-red-500">{error}</div>
      </div>
    );
  }

  // Backend returns `flowConfig: {}` for legacy automations created via flat
  // {triggerType, actions} payloads. Treat an empty/nodes-less object the same
  // as null and synthesize a layout from triggerType + actions, otherwise the
  // canvas mounts with zero nodes (`{} ?? synthesize` returns `{}`).
  const rawFlow = automation?.flowConfig;
  const hasFlowNodes = Array.isArray(rawFlow?.nodes) && rawFlow!.nodes.length > 0;
  const flowData = hasFlowNodes ? rawFlow : synthesizeFlowData(automation);
  const testRunContext = getAutomationTestRunContext(automation);
  const testRunRecordPid = getAutomationTestRunRecordPid(automation);

  return (
    <AutomationEditor
      automationId={isNew ? undefined : id}
      initialData={
        automation
          ? {
              name: automation.name,
              description: automation.description,
              flowData,
            }
          : undefined
      }
      onSave={handleSave}
      initialDebugMode={debugMode && !isNew}
      nodeStatuses={nodeStatuses}
      testRunContext={testRunContext}
      testRunRecordPid={testRunRecordPid}
      initialRunLog={runtimeLog}
    />
  );
}

export default AutomationEditPageImpl;
