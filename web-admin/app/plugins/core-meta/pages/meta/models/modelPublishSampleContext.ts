import { getLocalizedText } from '~/utils/i18n';
import TEXT from './modelPublishFeedback.i18n.json';

export const getPublishText = (key: keyof typeof TEXT, locale: string): string =>
  getLocalizedText(TEXT[key], locale);

export interface PermissionSampleInput {
  memberId: string;
  permissionCode: string;
  recordPid: string;
  recordJson: string;
}

export interface SlaNodeSampleInput {
  processInstanceId: string;
  tenantId: string;
  taskId: string;
  processKey: string;
  recordJson: string;
}

export interface WorkflowSampleInput {
  processInstanceId: string;
  processKey: string;
  recordPid: string;
  recordJson: string;
}

type SampleContext = Record<string, Record<string, unknown>>;

function parseRecord(
  raw: string,
  invalidKey: 'recordJsonInvalid' | 'slaJsonInvalid' | 'workflowJsonInvalid',
  objectKey: 'recordObjectRequired' | 'slaObjectRequired' | 'workflowObjectRequired',
  locale: string,
): Record<string, unknown> {
  if (!raw.trim()) return {};
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw.trim()) as unknown;
  } catch {
    throw new Error(getPublishText(invalidKey, locale));
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error(getPublishText(objectKey, locale));
  }
  return parsed as Record<string, unknown>;
}

export function buildPermissionReplayContext(form: PermissionSampleInput, locale: string): SampleContext {
  const memberId = form.memberId.trim();
  if (!/^\d+$/.test(memberId) || /^0+$/.test(memberId)) {
    throw new Error(getPublishText('memberInvalid', locale));
  }
  const recordData = parseRecord(form.recordJson, 'recordJsonInvalid', 'recordObjectRequired', locale);
  const permission: Record<string, unknown> = { memberId };
  if (form.permissionCode.trim()) permission.permissionCode = form.permissionCode.trim();
  const record: Record<string, unknown> = { data: recordData };
  if (form.recordPid.trim()) record.pid = form.recordPid.trim();
  return { permission, record };
}

export function buildSlaNodeReplayContext(form: SlaNodeSampleInput, locale: string): SampleContext {
  const processInstanceId = form.processInstanceId.trim();
  if (!processInstanceId) throw new Error(getPublishText('instanceRequired', locale));
  const tenantId = form.tenantId.trim();
  if (!/^\d+$/.test(tenantId) || /^0+$/.test(tenantId)) {
    throw new Error(getPublishText('tenantInvalid', locale));
  }
  const data = parseRecord(form.recordJson, 'slaJsonInvalid', 'slaObjectRequired', locale);
  const workflow: Record<string, unknown> = { processInstanceId, tenantId };
  if (form.taskId.trim()) workflow.taskId = form.taskId.trim();
  if (form.processKey.trim()) workflow.processKey = form.processKey.trim();
  return { workflow, record: { data } };
}

export function buildWorkflowReplayContext(form: WorkflowSampleInput, locale: string): SampleContext {
  const data = parseRecord(form.recordJson, 'workflowJsonInvalid', 'workflowObjectRequired', locale);
  const context: SampleContext = { record: { data } };
  if (form.recordPid.trim()) context.record.pid = form.recordPid.trim();
  const workflow: Record<string, unknown> = {};
  if (form.processInstanceId.trim()) workflow.processInstanceId = form.processInstanceId.trim();
  if (form.processKey.trim()) workflow.processKey = form.processKey.trim();
  if (Object.keys(workflow).length) context.workflow = workflow;
  return context;
}
