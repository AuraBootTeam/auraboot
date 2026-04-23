/**
 * BPM lifecycle E2E helpers — reusable API utilities for designer + instance
 * lifecycle tests (Epic B1+B2: designer-gateway-lifecycle.spec.ts and friends).
 *
 * IMPORTANT: these helpers intentionally parse backend responses along a
 * single canonical path (`json.data?.field`). There is no multi-path fallback
 * — if the backend contract changes, tests MUST fail loudly so we notice
 * (project red line: "no silent fallback").
 */

import { expect, type APIRequestContext } from '@playwright/test';

export interface StartInstanceArgs {
  processDefinitionId: string;
  businessKey: string;
  variables?: Record<string, unknown>;
}

export interface StartInstanceResult {
  instanceId: string;
  startUserId: string | null;
}

export interface NodeStatus {
  nodeId: string;
  assignee: string | null;
}

export interface InstanceStatus {
  instanceId: string;
  status: string;
  currentNodes: NodeStatus[];
  completedNodes: Array<{ nodeId: string }>;
  startUserId: string | null;
  variables: Record<string, unknown>;
}

export interface AuditEvent {
  operation: string;
  userId: string | null;
  details: Record<string, unknown> | null;
  result: string | null;
  createdAt: string;
  activityId?: string | null;
  taskId?: string | null;
}

export interface TodoTaskRecord {
  instanceId: string;
  taskId: string | null;
  processInstanceId: string;
  processDefinitionActivityId: string;
  businessKey: string | null;
  raw: Record<string, unknown>;
}

/**
 * Login as the default E2E admin and return the JWT string.
 * Response shape: { data: { jwt: "..." } }
 */
export async function loginAsAdmin(request: APIRequestContext): Promise<string> {
  const resp = await request.post('/api/auth/login', {
    data: { email: 'admin@example.com', password: 'Test2026x' },
    headers: { 'Content-Type': 'application/json' },
  });
  if (!resp.ok()) {
    throw new Error(`Admin login failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const jwt = body?.data?.jwt;
  if (typeof jwt !== 'string' || jwt.length === 0) {
    throw new Error(`Admin login returned no jwt: ${JSON.stringify(body)}`);
  }
  return jwt;
}

function authHeaders(token: string): Record<string, string> {
  return { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' };
}

/**
 * Start a process instance via POST /api/bpm/process-instances.
 * Returns { instanceId, startUserId }.
 */
export async function startProcessInstance(
  request: APIRequestContext,
  token: string,
  args: StartInstanceArgs,
): Promise<StartInstanceResult> {
  const resp = await request.post('/api/bpm/process-instances', {
    headers: authHeaders(token),
    data: {
      processDefinitionId: args.processDefinitionId,
      businessKey: args.businessKey,
      variables: args.variables ?? {},
    },
  });
  if (!resp.ok()) {
    throw new Error(`startProcessInstance failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const data = body?.data;
  const instanceId = data?.processInstanceId ?? data?.instanceId ?? data?.id;
  if (!instanceId || typeof instanceId !== 'string') {
    throw new Error(
      `startProcessInstance: no instanceId in response (keys=${Object.keys(data ?? {}).join(',')})`,
    );
  }
  return {
    instanceId,
    startUserId: data?.startUserId ?? data?.startUser ?? null,
  };
}

/**
 * Query node-level instance status by businessKey + processKey.
 * Returns the DTO shape exposed by ProcessInstanceStatusDTO:
 * { instanceId, status, startUserId, currentNodes[], completedNodes[], variables }
 */
export async function queryInstanceStatus(
  request: APIRequestContext,
  token: string,
  args: { processKey: string; businessKey: string },
): Promise<InstanceStatus> {
  const url =
    `/api/bpm/process-instances/by-business-key/status` +
    `?businessKey=${encodeURIComponent(args.businessKey)}` +
    `&processKey=${encodeURIComponent(args.processKey)}`;
  const resp = await request.get(url, { headers: authHeaders(token) });
  if (!resp.ok()) {
    throw new Error(`queryInstanceStatus failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const data = body?.data;
  if (!data) {
    throw new Error(`queryInstanceStatus: empty data envelope: ${JSON.stringify(body)}`);
  }
  return {
    instanceId: data.instanceId ?? data.processInstanceId,
    status: data.status,
    currentNodes: Array.isArray(data.currentNodes) ? data.currentNodes : [],
    completedNodes: Array.isArray(data.completedNodes) ? data.completedNodes : [],
    startUserId: data.startUserId ?? null,
    variables: (data.variables ?? {}) as Record<string, unknown>,
  };
}

export async function listTodoTasks(
  request: APIRequestContext,
  token: string,
): Promise<TodoTaskRecord[]> {
  const resp = await request.get('/api/bpm/tasks/todo?pageNum=1&pageSize=50', {
    headers: authHeaders(token),
  });
  if (!resp.ok()) {
    throw new Error(`listTodoTasks failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const raw = body?.data;
  const tasks = (Array.isArray(raw) ? raw : raw?.records ?? []) as Array<Record<string, unknown>>;
  return tasks.map((task) => ({
    instanceId: String(task.instanceId ?? task.activityInstanceId ?? task.taskId ?? ''),
    taskId: task.taskId == null ? null : String(task.taskId),
    processInstanceId: String(task.processInstanceId ?? ''),
    processDefinitionActivityId: String(task.processDefinitionActivityId ?? ''),
    businessKey: task.businessKey == null ? null : String(task.businessKey),
    raw: task,
  }));
}

export async function waitForTodoTask(
  request: APIRequestContext,
  token: string,
  predicate: (task: TodoTaskRecord) => boolean,
  opts?: { timeout?: number; message?: string },
): Promise<TodoTaskRecord> {
  let matched: TodoTaskRecord | null = null;
  await expect
    .poll(
      async () => {
        const tasks = await listTodoTasks(request, token);
        matched = tasks.find(predicate) ?? null;
        return Boolean(matched);
      },
      {
        timeout: opts?.timeout ?? 15_000,
        message: opts?.message ?? 'expected matching todo task to appear',
      },
    )
    .toBe(true);
  if (!matched) {
    throw new Error(opts?.message ?? 'expected matching todo task to appear');
  }
  return matched;
}

/**
 * List audit events for a process instance via monitor controller.
 * Backend shape: BpmAuditRecordEntity[] — pass-through from
 *   GET /api/bpm/monitor/instances/{id}/audit
 */
export async function listAuditEvents(
  request: APIRequestContext,
  token: string,
  instanceId: string,
): Promise<AuditEvent[]> {
  const resp = await request.get(`/api/bpm/monitor/instances/${instanceId}/audit`, {
    headers: authHeaders(token),
  });
  if (!resp.ok()) {
    throw new Error(`listAuditEvents failed: ${resp.status()} ${await resp.text()}`);
  }
  const body = await resp.json();
  const records = body?.data;
  if (!Array.isArray(records)) {
    throw new Error(`listAuditEvents: data is not an array: ${JSON.stringify(body)}`);
  }
  return records.map((r: Record<string, unknown>) => {
    const details = (r.details as Record<string, unknown> | null) ?? null;
    // activityId is stored inside the JSONB `details` map (see
    // BpmAuditService.recordActivityEvent). The entity has no top-level
    // column for it.
    const activityId =
      typeof details?.activityId === 'string' && details.activityId.length > 0
        ? (details.activityId as string)
        : null;
    return {
      operation: String(r.operation ?? ''),
      userId: (r.userId as string) ?? null,
      details,
      result: (r.result as string) ?? null,
      createdAt: String(r.createdAt ?? ''),
      activityId,
      taskId: (r.taskId as string) ?? null,
    };
  });
}

/**
 * Undeploy a process definition to keep subsequent test runs clean.
 * Uses POST /api/bpm/process-definitions/{pid}/undeploy.
 *
 * Best-effort: returns the final status WITHOUT throwing. Backend rejects
 * undeploy with HTTP 500 when running instances still exist (expected when
 * E2E left tasks pending) — we accept that and let the admin-hygiene tests
 * handle long-term cleanup.
 */
export async function undeployProcess(
  request: APIRequestContext,
  token: string,
  pid: string,
): Promise<{ ok: boolean; status: number }> {
  const resp = await request.post(`/api/bpm/process-definitions/${pid}/undeploy`, {
    headers: authHeaders(token),
  });
  return { ok: resp.ok(), status: resp.status() };
}

/**
 * Audit record classification (see ab_bpm_audit_record schema):
 *
 * - `operation` is a broad category, e.g. "process_start" (dedicated row at
 *   process boot), "process_event" (generic process-level event, eventType
 *   inside details), "activity_event" (node-level event with activityId +
 *   eventType inside details), "deploy", "task_approve", etc.
 * - `details.eventType` carries the fine-grained event name for both
 *   activity_event and process_event rows, e.g. "activity_start",
 *   "activity_end", "process_start".
 * - `details.activityId` carries the node id for activity_event rows.
 *
 * Use `collectActivityEvents` + `collectProcessStart` to work with these
 * instead of relying on a flat list of magic strings.
 */
export const AuditOp = {
  PROCESS_START: 'process_start',
  PROCESS_EVENT: 'process_event',
  ACTIVITY_EVENT: 'activity_event',
} as const;

/**
 * Extract activity_event rows into a flat list of { activityId, eventType }.
 */
export function collectActivityEvents(
  events: AuditEvent[],
): Array<{ activityId: string; eventType: string }> {
  const out: Array<{ activityId: string; eventType: string }> = [];
  for (const ev of events) {
    if (ev.operation !== AuditOp.ACTIVITY_EVENT) continue;
    const activityId =
      typeof ev.details?.activityId === 'string' ? (ev.details.activityId as string) : '';
    const eventType =
      typeof ev.details?.eventType === 'string' ? (ev.details.eventType as string) : '';
    if (activityId && eventType) out.push({ activityId, eventType });
  }
  return out;
}

/**
 * Returns true if there is at least one audit row indicating the process
 * started (either a `process_start` operation row or a `process_event` row
 * with details.eventType === 'process_start').
 */
export function hasProcessStart(events: AuditEvent[]): boolean {
  return events.some(
    (ev) =>
      ev.operation === AuditOp.PROCESS_START ||
      (ev.operation === AuditOp.PROCESS_EVENT && ev.details?.eventType === 'process_start'),
  );
}
