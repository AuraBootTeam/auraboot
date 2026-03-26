// web-admin/app/routes/automation.$id.tsx
import { useParams, useNavigate, useSearchParams } from 'react-router';
import type { LoaderFunctionArgs } from 'react-router';
import { useLoaderData } from 'react-router';
import { AutomationEditor } from '~/smart/automation/components/AutomationEditor';
import { useSmartText } from '~/utils/i18n';
import { automationService } from '~/smart/automation/services/automationService';
import type { Automation } from '~/smart/automation/services/automationService';
import { getTokenFromRequest } from '~/services/session';
import type { FlowData } from '~/flow-designer-sdk';

/**
 * Synthesize a FlowData layout from flat triggerType + actions when flowConfig is null.
 * This ensures the visual editor always has nodes to render.
 */
function synthesizeFlowData(automation: Automation | null | undefined): FlowData | undefined {
  if (!automation?.triggerType) return undefined;

  const nodes: FlowData['nodes'] = [];
  const edges: FlowData['edges'] = [];
  const yCenter = 200;
  let x = 100;

  // Trigger node
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

  // Action nodes
  (automation.actions || []).forEach(
    (action: { type: string; label?: string; config?: Record<string, unknown> }, idx: number) => {
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
    },
  );

  return { nodes, edges };
}

interface LoaderData {
  automation: Automation | null;
  token: string | null;
  isNew: boolean;
  error?: string;
}

export const loader = async ({ request, params }: LoaderFunctionArgs): Promise<LoaderData> => {
  const id = params.id!;
  const isNew = id === 'new';

  try {
    const token = await getTokenFromRequest(request);
    if (isNew) {
      return { automation: null, token, isNew };
    }
    const automation = await automationService.get(id, request);
    return { automation, token, isNew };
  } catch (error) {
    const message = error instanceof Error ? error.message : 'Failed to load automation';
    return { automation: null, token: null, isNew, error: message };
  }
};

export default function AutomationEditPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const st = useSmartText();
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

  // Synthesize flowConfig from triggerType + actions if not stored
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
