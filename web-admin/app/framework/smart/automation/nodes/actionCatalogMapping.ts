import type { FlowNodeDefinition } from '~/plugins/core-designer/components/flow-designer-sdk';
import type { DecisionAction } from '~/shared/decision/api/decisionApi';
import { resolveDecisionActionAvailability } from '~/shared/decision/ui/actionAvailability';

export interface AutomationActionCatalogFieldMapping {
  automationField: string;
  catalogPath: string;
  note?: string;
}

export interface AutomationActionCatalogMapping {
  automationActionType: string;
  decisionActionType: string;
  fieldMappings: AutomationActionCatalogFieldMapping[];
}

export const AUTOMATION_ACTION_CATALOG_MAPPINGS: AutomationActionCatalogMapping[] = [
  {
    automationActionType: 'update_record',
    decisionActionType: 'UPDATE_RECORD',
    fieldMappings: [
      { automationField: 'fields', catalogPath: 'payload.fields' },
      { automationField: 'modelCode', catalogPath: 'payload.modelCode' },
      { automationField: 'recordPid', catalogPath: 'target' },
    ],
  },
  {
    automationActionType: 'send_notification',
    decisionActionType: 'NOTIFY',
    fieldMappings: [
      { automationField: 'recipients', catalogPath: 'target' },
      { automationField: 'title', catalogPath: 'payload.title' },
      { automationField: 'content', catalogPath: 'payload.content' },
      { automationField: 'notificationType', catalogPath: 'payload.channel' },
    ],
  },
  {
    automationActionType: 'send_webhook',
    decisionActionType: 'WEBHOOK',
    fieldMappings: [
      { automationField: 'eventType', catalogPath: 'payload.eventType' },
      { automationField: 'payload', catalogPath: 'payload' },
      { automationField: 'url', catalogPath: 'target', note: 'Automation direct-post target URL' },
    ],
  },
  {
    automationActionType: 'start_process',
    decisionActionType: 'START_PROCESS',
    fieldMappings: [
      {
        automationField: 'processKey',
        catalogPath: 'payload.processDefinitionId',
        note: 'Automation stores process key; the runtime resolves the deployed process definition',
      },
      { automationField: 'businessKey', catalogPath: 'payload.businessKey' },
      { automationField: 'variables', catalogPath: 'payload.variables' },
    ],
  },
  {
    automationActionType: 'send_sms',
    decisionActionType: 'SEND_SMS',
    fieldMappings: [
      { automationField: 'target', catalogPath: 'target' },
      { automationField: 'template', catalogPath: 'payload.template' },
      { automationField: 'content', catalogPath: 'payload.content' },
    ],
  },
  {
    automationActionType: 'send_im',
    decisionActionType: 'SEND_IM',
    fieldMappings: [
      { automationField: 'target', catalogPath: 'target' },
      { automationField: 'channel', catalogPath: 'payload.channel' },
      { automationField: 'content', catalogPath: 'payload.content' },
    ],
  },
  {
    automationActionType: 'create_task',
    decisionActionType: 'CREATE_TASK',
    fieldMappings: [
      { automationField: 'target', catalogPath: 'target' },
      { automationField: 'title', catalogPath: 'payload.title' },
      { automationField: 'assignee', catalogPath: 'payload.assignee' },
      { automationField: 'dueDate', catalogPath: 'payload.dueDate' },
    ],
  },
  {
    automationActionType: 'cc_task',
    decisionActionType: 'CC_TASK',
    fieldMappings: [
      { automationField: 'target', catalogPath: 'target' },
      { automationField: 'taskId', catalogPath: 'payload.taskId' },
      { automationField: 'message', catalogPath: 'payload.message' },
    ],
  },
  {
    automationActionType: 'add_comment',
    decisionActionType: 'ADD_COMMENT',
    fieldMappings: [
      { automationField: 'content', catalogPath: 'payload.content' },
      { automationField: 'mentions', catalogPath: 'payload.mentions' },
    ],
  },
  {
    automationActionType: 'patch_record',
    decisionActionType: 'PATCH_RECORD',
    fieldMappings: [
      { automationField: 'fields', catalogPath: 'payload.fields' },
      { automationField: 'modelCode', catalogPath: 'payload.modelCode' },
      { automationField: 'recordPid', catalogPath: 'target' },
    ],
  },
  {
    automationActionType: 'write_audit',
    decisionActionType: 'WRITE_AUDIT',
    fieldMappings: [
      { automationField: 'message', catalogPath: 'payload.message' },
      { automationField: 'payload', catalogPath: 'payload' },
    ],
  },
];

export function catalogActionTypeForAutomationAction(actionType: string): string | undefined {
  return AUTOMATION_ACTION_CATALOG_MAPPINGS.find(
    (mapping) => mapping.automationActionType === actionType,
  )?.decisionActionType;
}

export interface AutomationActionCatalogGap {
  automationActionType: string;
  decisionActionType: string;
  missingRequiredPaths: string[];
  missingLocalFields: string[];
}

export interface AutomationActionCatalogAvailability {
  automationActionType: string;
  decisionActionType: string;
  unavailable: boolean;
  status?: string;
  reason?: string;
  providerSummary?: string;
}

const AUTOMATION_CONSUMER_TYPE = 'AUTOMATION';

function requiredPaths(action: DecisionAction | undefined): string[] {
  const required = action?.inputSchema?.required;
  return Array.isArray(required)
    ? required.filter((path): path is string => typeof path === 'string' && path.length > 0)
    : [];
}

function automationActionType(node: FlowNodeDefinition): string | undefined {
  const value = node.defaultConfig?.actionType;
  return typeof value === 'string' ? value : undefined;
}

function catalogSupportsAutomation(action: DecisionAction): boolean {
  const consumers = action.consumerTypes;
  if (!Array.isArray(consumers) || consumers.length === 0) return true;
  return consumers.includes(AUTOMATION_CONSUMER_TYPE);
}

export function actionCatalogAvailabilityForAutomationAction(
  automationActionType: string,
  catalog: DecisionAction[],
): AutomationActionCatalogAvailability | undefined {
  const decisionActionType = catalogActionTypeForAutomationAction(automationActionType);
  if (!decisionActionType) return undefined;
  const action = catalog.find(
    (candidate) => candidate.actionType === decisionActionType && catalogSupportsAutomation(candidate),
  );
  if (!action) return undefined;
  const consumerAvailability = action.consumerAvailability?.find(
    (item) => item.consumerType?.toUpperCase() === AUTOMATION_CONSUMER_TYPE,
  );
  const availability = resolveDecisionActionAvailability(action, AUTOMATION_CONSUMER_TYPE);
  return {
    automationActionType,
    decisionActionType,
    unavailable: availability.unavailable,
    status: consumerAvailability?.availabilityStatus ?? action.availabilityStatus,
    reason: availability.unavailable ? availability.reason || undefined : undefined,
    providerSummary: availability.providerSummary || undefined,
  };
}

export function applyActionCatalogAvailabilityToAutomationNodes(
  nodes: FlowNodeDefinition[],
  catalog: DecisionAction[],
): FlowNodeDefinition[] {
  if (catalog.length === 0) return nodes;

  return nodes.map((node) => {
    const actionType = automationActionType(node);
    if (!actionType) return node;
    const availability = actionCatalogAvailabilityForAutomationAction(actionType, catalog);
    if (!availability) return node;
    return {
      ...node,
      metadata: {
        ...(node.metadata ?? {}),
        availability: {
          unavailable: availability.unavailable,
          status: availability.status,
          reason: availability.reason,
          providerSummary: availability.providerSummary,
          source: 'decision-action-catalog',
          actionType: availability.decisionActionType,
        },
      },
    };
  });
}

export function missingRequiredCatalogPathsForAutomation(
  nodes: FlowNodeDefinition[],
  catalog: DecisionAction[],
): AutomationActionCatalogGap[] {
  const nodesByActionType = new Map(
    nodes
      .map((node) => [automationActionType(node), node] as const)
      .filter((entry): entry is readonly [string, FlowNodeDefinition] => Boolean(entry[0])),
  );
  const catalogByActionType = new Map(catalog.map((action) => [action.actionType, action]));

  return AUTOMATION_ACTION_CATALOG_MAPPINGS.map((mapping) => {
    const node = nodesByActionType.get(mapping.automationActionType);
    const localFields = new Set((node?.configSchema ?? []).map((field) => field.key));
    const mappedCatalogPaths = new Set(mapping.fieldMappings.map((field) => field.catalogPath));
    const mappedLocalFields = new Set(mapping.fieldMappings.map((field) => field.automationField));
    return {
      automationActionType: mapping.automationActionType,
      decisionActionType: mapping.decisionActionType,
      missingRequiredPaths: requiredPaths(catalogByActionType.get(mapping.decisionActionType)).filter(
        (path) => !mappedCatalogPaths.has(path),
      ),
      missingLocalFields: [...mappedLocalFields].filter((field) => !localFields.has(field)),
    };
  }).filter((gap) => gap.missingRequiredPaths.length > 0 || gap.missingLocalFields.length > 0);
}
