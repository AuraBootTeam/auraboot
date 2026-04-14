// OSS implementation of the Automation editor page body.
// The route shell (plugins/core-automation/pages/automation.$id.tsx) lazy-loads
// this component and renders it inside a <Suspense>. Loader data is consumed via
// useLoaderData().
//
// Enterprise builds may overlay this file to provide advanced behaviors.

import { useParams, useNavigate, useSearchParams, useLoaderData } from 'react-router';
import { AutomationEditor } from './AutomationEditor';
import type { Automation } from '../services/automationService';
import type { FlowData } from '~/plugins/core-designer/components/flow-designer-sdk';

interface LoaderData {
  automation: Automation | null;
  token: string | null;
  isNew: boolean;
  error?: string;
}

/**
 * Synthesize a FlowData layout from flat triggerType + actions when flowConfig is null.
 * Ensures the visual editor always has nodes to render for legacy automations.
 */
function synthesizeFlowData(automation: Automation | null | undefined): FlowData | undefined {
  if (!automation?.triggerType) return undefined;

  const nodes: FlowData['nodes'] = [];
  const edges: FlowData['edges'] = [];
  const yCenter = 200;
  let x = 100;

  const triggerId = 'trigger_0';
  nodes.push({
    id: triggerId,
    type: 'trigger',
    position: { x, y: yCenter },
    data: {
      type: 'trigger',
      label: automation.triggerType,
      config: { triggerType: automation.triggerType, modelCode: automation.modelCode },
    },
  });

  let prevId = triggerId;
  x += 250;

  (automation.actions || []).forEach((action, idx) => {
    const actionId = `action_${idx}`;
    nodes.push({
      id: actionId,
      type: 'action',
      position: { x, y: yCenter },
      data: {
        type: 'action',
        label: action.label || action.type,
        config: { actionType: action.type, ...action.config },
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

  const flowData = automation?.flowConfig ?? synthesizeFlowData(automation);

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
    />
  );
}

export default AutomationEditPageImpl;
