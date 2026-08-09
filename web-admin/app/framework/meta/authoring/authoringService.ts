import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  AuthoringSession,
  CapabilityRegistry,
  HandoffContext,
  HandoffCreated,
  PatchOperation,
  PatchResult,
} from './types';

export interface InteractionContext {
  route: string;
  recordPid?: string;
  tabId?: string;
  filters?: unknown;
  sort?: unknown;
  scroll?: { x: number; y: number };
  selection?: string;
  outlinePath?: string[];
}

export async function openAuthoringSession(
  pagePid: string,
  interactionContext: InteractionContext,
): Promise<AuthoringSession> {
  const result = await fetchResult<AuthoringSession>('/api/authoring/sessions', {
    method: 'post',
    params: { pagePid, interactionContext },
  });
  return requireData(result, '无法进入配置模式');
}

export async function loadAuthoringCapabilities(): Promise<CapabilityRegistry> {
  const result = await fetchResult<CapabilityRegistry>('/api/authoring/capabilities');
  return requireData(result, '无法加载页面配置能力');
}

export async function loadAuthoringSession(sessionPid: string): Promise<AuthoringSession> {
  const result = await fetchResult<AuthoringSession>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}`,
  );
  return requireData(result, '无法刷新配置草稿');
}

export async function applyAuthoringPatch(
  sessionPid: string,
  revision: number,
  blockId: string,
  propertyPath: string,
  operation: PatchOperation,
  value: unknown,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/patches`,
    {
      method: 'patch',
      params: {
        expectedRevision: revision,
        blockId,
        propertyPath,
        operation,
        ...(operation === 'REMOVE' ? {} : { value }),
        manifestChecksum,
      },
    },
  );
  return requireData(result, '无法保存配置变更');
}

export async function applyAuthoringStudioPatch(
  sessionPid: string,
  revision: number,
  blockId: string,
  propertyPath: string,
  operation: PatchOperation,
  value: unknown,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/studio-patches`,
    {
      method: 'patch',
      params: {
        expectedRevision: revision,
        blockId,
        propertyPath,
        operation,
        ...(operation === 'REMOVE' ? {} : { value }),
        manifestChecksum,
      },
    },
  );
  return requireData(result, '无法保存应用设计中心变更');
}

export async function submitAuthoringSession(sessionPid: string, revision: number): Promise<void> {
  const result = await fetchResult<unknown>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/submit`,
    { method: 'post', params: { expectedRevision: revision } },
  );
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || result.desc || '无法提交评审');
  }
}

export async function createAuthoringHandoff(
  sessionPid: string,
  revision: number,
  intent: string,
  blockId?: string,
  propertyPath?: string,
): Promise<HandoffCreated> {
  const result = await fetchResult<HandoffCreated>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/handoffs`,
    {
      method: 'post',
      params: { expectedRevision: revision, intent, blockId, propertyPath },
    },
  );
  return requireData(result, '无法安全移交到应用设计中心');
}

export async function consumeAuthoringHandoff(contextId: string): Promise<HandoffContext> {
  const existing = handoffConsumptionPromises.get(contextId);
  if (existing) return existing;
  const consumption = consumeAuthoringHandoffRequest(contextId);
  handoffConsumptionPromises.set(contextId, consumption);
  return consumption;
}

const handoffConsumptionPromises = new Map<string, Promise<HandoffContext>>();

async function consumeAuthoringHandoffRequest(contextId: string): Promise<HandoffContext> {
  const result = await fetchResult<HandoffContext>(
    `/api/authoring/handoffs/${encodeURIComponent(contextId)}/consume`,
    { method: 'post' },
  );
  return requireData(result, '配置上下文已过期、已使用或无权访问');
}

export function resetAuthoringHandoffConsumptionForTests(): void {
  handoffConsumptionPromises.clear();
}

function requireData<T>(
  result: { code: string | number; data: T | null; message?: string; desc?: string },
  fallback: string,
): T {
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.message || result.desc || fallback);
  }
  return result.data;
}
