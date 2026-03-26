/**
 * BPMN Process Definition Service
 *
 * Communicates with the backend ProcessDefinitionController at
 * /api/bpm/process-definitions.
 *
 * Internally maps between the frontend BPMNProcessDefinition type
 * (id, name, key) and the backend ProcessDefinitionDTO
 * (pid, processName, processKey).
 */

import { fetchResult } from '~/services/http-client';
import type { BPMNProcessDefinition } from '~/bpmn-designer/types';

const BASE_PATH = '/api/bpm/process-definitions';

// ==================== Backend DTO Types ====================

/**
 * Matches backend ProcessDefinitionController.ProcessDefinitionDTO
 */
interface ProcessDefinitionDTO {
  pid: string;
  processKey: string;
  processName: string;
  description: string | null;
  category: string | null;
  status: string;
  version: number | null;
  isCurrent: boolean | null;
  deploymentId: string | null;
  deployedAt: string | null;
  formBindings: Record<string, unknown> | null;
  designerJson: string | null;
  createdAt: string | null;
  updatedAt: string | null;
}

/**
 * Matches backend ProcessDefinitionController.CreateProcessRequest
 */
interface CreateProcessRequest {
  processKey: string;
  processName: string;
  description?: string;
  category?: string;
  bpmnContent?: string;
  formBindings?: Record<string, unknown>;
  businessDataBindings?: Array<Record<string, unknown>>;
}

/**
 * Matches backend ProcessDefinitionController.UpdateProcessRequest
 */
interface UpdateProcessRequest {
  processName?: string;
  description?: string;
  category?: string;
  bpmnContent?: string;
  formBindings?: Record<string, unknown>;
  businessDataBindings?: Array<Record<string, unknown>>;
}

// ==================== DTO Mapping ====================

/**
 * Map backend DTO to frontend BPMNProcessDefinition type.
 * Parses designerJson from the backend extension field to restore nodes/edges.
 */
function toFrontend(dto: ProcessDefinitionDTO): BPMNProcessDefinition {
  let nodes: BPMNProcessDefinition['nodes'] = [];
  let edges: BPMNProcessDefinition['edges'] = [];

  if (dto.designerJson) {
    try {
      const parsed = JSON.parse(dto.designerJson);
      nodes = parsed.nodes || [];
      edges = parsed.edges || [];
    } catch {
      // Ignore malformed designerJson
    }
  }

  return {
    id: dto.pid,
    name: dto.processName,
    key: dto.processKey,
    description: dto.description ?? undefined,
    category: dto.category ?? undefined,
    status: mapStatus(dto.status),
    version: dto.version ?? undefined,
    nodes,
    edges,
    createdAt: dto.createdAt ?? undefined,
    updatedAt: dto.updatedAt ?? undefined,
  };
}

/**
 * Map backend status string to the frontend union type.
 */
function mapStatus(status: string | null | undefined): 'draft' | 'published' | 'suspended' {
  switch (status?.toLowerCase()) {
    case 'deployed':
      return 'published';
    case 'suspended':
      return 'suspended';
    case 'draft':
    default:
      return 'draft';
  }
}

// ==================== API Functions ====================

/**
 * List all process definitions.
 */
export async function getProcessDefinitions(status?: string, category?: string) {
  const params: Record<string, string> = {};
  if (status) params.status = status;
  if (category) params.category = category;

  const queryString = new URLSearchParams(params).toString();
  const url = queryString ? `${BASE_PATH}?${queryString}` : BASE_PATH;

  const result = await fetchResult<ProcessDefinitionDTO[]>(url);
  if (result.data) {
    return {
      ...result,
      data: result.data.map(toFrontend),
    };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition[] | null };
}

/**
 * Get a process definition by PID.
 */
export async function getProcessDefinitionById(pid: string) {
  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/${pid}`);
  if (result.data) {
    return { ...result, data: toFrontend(result.data) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Get a process definition by process key.
 */
export async function getProcessDefinitionByKey(key: string) {
  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/key/${key}`);
  if (result.data) {
    return { ...result, data: toFrontend(result.data) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Get all versions of a process by key.
 */
export async function getProcessVersions(processKey: string) {
  const result = await fetchResult<ProcessDefinitionDTO[]>(
    `${BASE_PATH}/key/${processKey}/versions`,
  );
  if (result.data) {
    return { ...result, data: result.data.map(toFrontend) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition[] | null };
}

/**
 * Get deployed process definitions.
 */
export async function getDeployedProcesses() {
  const result = await fetchResult<ProcessDefinitionDTO[]>(`${BASE_PATH}/deployed`);
  if (result.data) {
    return { ...result, data: result.data.map(toFrontend) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition[] | null };
}

/**
 * Create a new process definition.
 *
 * Accepts a frontend BPMNProcessDefinition (without id) and maps
 * the field names to the backend CreateProcessRequest format.
 */
export async function createProcessDefinition(definition: Omit<BPMNProcessDefinition, 'id'>) {
  const body: CreateProcessRequest & { designerJson?: string } = {
    processKey: definition.key,
    processName: definition.name,
    description: definition.description,
    category: definition.category,
    // bpmnContent is not generated by the designer yet;
    // pass undefined so the backend can accept it as-is
    bpmnContent: undefined,
    // Persist designer canvas state (nodes/edges) so it can be restored on reload
    designerJson: JSON.stringify({ nodes: definition.nodes || [], edges: definition.edges || [] }),
  };

  const result = await fetchResult<ProcessDefinitionDTO>(BASE_PATH, {
    method: 'post',
    params: body,
  });
  if (result.data) {
    // Merge back the designer nodes/edges that we sent
    const frontendDef = toFrontend(result.data);
    frontendDef.nodes = definition.nodes || [];
    frontendDef.edges = definition.edges || [];
    return { ...result, data: frontendDef };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Update an existing process definition.
 *
 * @param pid - The process definition PID (backend identifier)
 * @param definition - Partial frontend definition with fields to update
 */
export async function updateProcessDefinition(
  pid: string,
  definition: Partial<BPMNProcessDefinition>,
) {
  const body: UpdateProcessRequest & { designerJson?: string } = {};
  if (definition.name !== undefined) body.processName = definition.name;
  if (definition.description !== undefined) body.description = definition.description;
  if (definition.category !== undefined) body.category = definition.category;
  // Always persist designer canvas state on update
  if (definition.nodes || definition.edges) {
    body.designerJson = JSON.stringify({
      nodes: definition.nodes || [],
      edges: definition.edges || [],
    });
  }

  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/${pid}`, {
    method: 'put',
    params: body,
  });
  if (result.data) {
    const frontendDef = toFrontend(result.data);
    // Preserve designer nodes/edges from the caller
    if (definition.nodes) frontendDef.nodes = definition.nodes;
    if (definition.edges) frontendDef.edges = definition.edges;
    return { ...result, data: frontendDef };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Delete a process definition (soft delete).
 */
export async function deleteProcessDefinition(pid: string) {
  return fetchResult(`${BASE_PATH}/${pid}`, {
    method: 'delete',
  });
}

/**
 * Deploy a process definition to the engine.
 * (Previously named "publish" on the frontend)
 */
export async function deployProcessDefinition(pid: string) {
  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/${pid}/deploy`, {
    method: 'post',
  });
  if (result.data) {
    return { ...result, data: toFrontend(result.data) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Suspend a deployed process definition.
 */
export async function suspendProcessDefinition(pid: string) {
  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/${pid}/suspend`, {
    method: 'post',
  });
  if (result.data) {
    return { ...result, data: toFrontend(result.data) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Resume a suspended process definition.
 */
export async function resumeProcessDefinition(pid: string) {
  const result = await fetchResult<ProcessDefinitionDTO>(`${BASE_PATH}/${pid}/resume`, {
    method: 'post',
  });
  if (result.data) {
    return { ...result, data: toFrontend(result.data) };
  }
  return { ...result, data: null } as typeof result & { data: BPMNProcessDefinition | null };
}

/**
 * Get the BPMN XML content of a process definition.
 * (Previously named "export" on the frontend)
 */
export async function getProcessBpmnXml(pid: string) {
  return fetchResult<string>(`${BASE_PATH}/${pid}/bpmn`);
}

// ==================== Process Instance Status ====================

/**
 * Status of nodes within a running process instance.
 */
export interface ProcessInstanceNodeStatus {
  instanceId?: string;
  processDefinitionId?: string;
  status?: string;
  currentNodes: Array<{
    nodeId: string;
    status: string;
    assignee?: string;
  }>;
  completedNodes: Array<{
    nodeId: string;
    completedAt?: string;
    completedBy?: string;
  }>;
  variables?: Record<string, unknown>;
}

/**
 * Fetch the runtime status of a specific process instance,
 * including which nodes are currently active and which have completed.
 */
export async function getProcessInstanceStatus(instanceId: string) {
  return fetchResult<ProcessInstanceNodeStatus>(`/api/bpm/process-instances/${instanceId}/status`);
}

/**
 * Fetch process instance status by business key.
 */
export async function getProcessInstanceStatusByBusinessKey(
  businessKey: string,
  processKey?: string,
) {
  const params: Record<string, string> = { businessKey };
  if (processKey) params.processKey = processKey;
  const qs = new URLSearchParams(params).toString();
  return fetchResult<ProcessInstanceNodeStatus>(
    `/api/bpm/process-instances/by-business-key/status?${qs}`,
  );
}

// ==================== Backward-compatible aliases ====================

/**
 * @deprecated Use deployProcessDefinition instead
 */
export const publishProcessDefinition = deployProcessDefinition;

/**
 * @deprecated Use getProcessBpmnXml instead
 */
export async function exportProcessDefinitionXML(pid: string) {
  const result = await getProcessBpmnXml(pid);
  // Re-shape for callers that expected { xml: string }
  if (result.data !== null && result.data !== undefined) {
    return { ...result, data: { xml: result.data } };
  }
  return { ...result, data: null } as typeof result & { data: { xml: string } | null };
}
