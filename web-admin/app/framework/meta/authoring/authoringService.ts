import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type {
  AuthoringSession,
  AuthoringReviewWorkspace,
  CapabilityRegistry,
  HandoffContext,
  HandoffCreated,
  PatchOperation,
  PatchResult,
  AuthoringGovernanceAction,
  AuthoringChangeItem,
  AuthoringRelease,
  AuthoringReleaseHistory,
  AuthoringSplitResult,
  AuthoringRolePreviewTarget,
  AuthoringRoleStructurePreview,
  AuthoringSyntheticPreview,
  AuthoringIdentitySimulation,
  AuthoringAiPatchProposal,
  AuthoringAiPatchProposalItemRequest,
  ApplyAuthoringAiPatchProposalResult,
} from './types';

export interface InteractionContext {
  route: string;
  recordPid?: string;
  tabId?: string;
  filters?: unknown;
  sort?: unknown;
  scroll?: { x: number; y: number };
  viewport?: { width: number; height: number; scale: number };
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

export async function loadAuthoringRolePreviewTargets(
  sessionPid: string,
): Promise<AuthoringRolePreviewTarget[]> {
  const result = await fetchResult<AuthoringRolePreviewTarget[]>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/role-preview-targets`,
  );
  return requireData(result, '无法加载可预览角色');
}

export async function loadAuthoringRoleStructurePreview(
  sessionPid: string,
  rolePid: string,
): Promise<AuthoringRoleStructurePreview> {
  const result = await fetchResult<AuthoringRoleStructurePreview>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/role-structure-preview`,
    { params: { rolePid } },
  );
  return requireData(result, '无法生成角色权限结构预览');
}

export async function loadAuthoringSyntheticPreview(
  sessionPid: string,
): Promise<AuthoringSyntheticPreview> {
  const result = await fetchResult<AuthoringSyntheticPreview>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/synthetic-preview`,
  );
  return requireData(result, '无法生成隔离合成数据');
}

export async function startAuthoringIdentitySimulation(
  sessionPid: string,
  rolePid: string,
  durationMinutes: 5 | 10 | 15,
  reason: string,
): Promise<AuthoringIdentitySimulation> {
  const result = await fetchResult<AuthoringIdentitySimulation>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/identity-simulations`,
    {
      method: 'post',
      params: { rolePid, durationMinutes, reason },
    },
  );
  return requireData(result, '无法启动审计身份模拟');
}

export async function loadAuthoringIdentitySimulation(
  simulationPid: string,
): Promise<AuthoringIdentitySimulation> {
  const result = await fetchResult<AuthoringIdentitySimulation>(
    `/api/authoring/identity-simulations/${encodeURIComponent(simulationPid)}`,
  );
  return requireData(result, '无法刷新审计身份模拟');
}

export async function endAuthoringIdentitySimulation(
  simulationPid: string,
): Promise<AuthoringIdentitySimulation> {
  const result = await fetchResult<AuthoringIdentitySimulation>(
    `/api/authoring/identity-simulations/${encodeURIComponent(simulationPid)}/end`,
    { method: 'post' },
  );
  return requireData(result, '无法结束审计身份模拟');
}

export async function observeAuthoringChangeSet(
  changeSetPid: string,
  interactionContext?: Partial<InteractionContext>,
): Promise<AuthoringSession> {
  const result = await fetchResult<AuthoringSession>(
    `/api/authoring/change-sets/${encodeURIComponent(changeSetPid)}/sessions`,
    {
      method: 'post',
      params: interactionContext ? { interactionContext } : {},
    },
  );
  return requireData(result, '无法打开 ChangeSet 只读会话');
}

export async function openAuthoringReviewWorkspace(
  changeSetPid: string,
  interactionContext?: Partial<InteractionContext>,
): Promise<AuthoringReviewWorkspace> {
  const result = await fetchResult<AuthoringReviewWorkspace>(
    `/api/authoring/change-sets/${encodeURIComponent(changeSetPid)}/review-workspaces`,
    {
      method: 'post',
      params: interactionContext ? { interactionContext } : {},
    },
  );
  return requireData(result, '无法打开 ChangeSet 评审工作区');
}

export async function loadAuthoringReviewWorkspace(
  sessionPid: string,
): Promise<AuthoringReviewWorkspace> {
  const result = await fetchResult<AuthoringReviewWorkspace>(
    `/api/authoring/review-workspaces/${encodeURIComponent(sessionPid)}`,
  );
  return requireData(result, '无法刷新 ChangeSet 评审工作区');
}

export async function takeoverAuthoringWriterLease(
  sessionPid: string,
  revision: number,
  reason: string,
): Promise<AuthoringSession> {
  const result = await fetchResult<AuthoringSession>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/writer-lease/takeover`,
    {
      method: 'post',
      params: { expectedRevision: revision, reason },
    },
  );
  return requireData(result, '无法接管 ChangeSet 编辑权');
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

export async function moveAuthoringStudioBlock(
  sessionPid: string,
  revision: number,
  blockId: string,
  beforeBlockId: string | null,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/studio-moves`,
    {
      method: 'patch',
      params: {
        expectedRevision: revision,
        blockId,
        beforeBlockId,
        manifestChecksum,
      },
    },
  );
  return requireData(result, '无法保存区块顺序变更');
}

export async function createAuthoringStudioBlock(
  sessionPid: string,
  revision: number,
  blockId: string,
  blockType: string,
  parentBlockId: string | null,
  beforeBlockId: string | null,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/studio-blocks`,
    {
      method: 'post',
      params: {
        expectedRevision: revision,
        blockId,
        blockType,
        parentBlockId,
        beforeBlockId,
        manifestChecksum,
      },
    },
  );
  return requireData(result, '无法创建受治理区块');
}

export async function removeAuthoringStudioBlock(
  sessionPid: string,
  revision: number,
  blockId: string,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/studio-block-removals`,
    {
      method: 'post',
      params: { expectedRevision: revision, blockId, manifestChecksum },
    },
  );
  return requireData(result, '无法删除受治理区块');
}

export async function relocateAuthoringStudioBlock(
  sessionPid: string,
  revision: number,
  blockId: string,
  targetParentBlockId: string,
  beforeBlockId: string | null,
  manifestChecksum: string,
): Promise<PatchResult> {
  const result = await fetchResult<PatchResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/studio-relocations`,
    {
      method: 'patch',
      params: {
        expectedRevision: revision,
        blockId,
        targetParentBlockId,
        beforeBlockId,
        manifestChecksum,
      },
    },
  );
  return requireData(result, '无法跨父级移动受治理区块');
}

export async function createAuthoringAiPatchProposal(
  sessionPid: string,
  revision: number,
  items: AuthoringAiPatchProposalItemRequest[],
): Promise<AuthoringAiPatchProposal> {
  const result = await fetchResult<AuthoringAiPatchProposal>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/ai-patch-proposals`,
    {
      method: 'post',
      params: { expectedRevision: revision, items },
    },
  );
  return requireData(result, '无法创建受治理 AI 变更提案');
}

export async function loadAuthoringAiPatchProposal(
  sessionPid: string,
  proposalPid: string,
): Promise<AuthoringAiPatchProposal> {
  const result = await fetchResult<AuthoringAiPatchProposal>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}` +
      `/ai-patch-proposals/${encodeURIComponent(proposalPid)}`,
  );
  return requireData(result, '无法加载受治理 AI 变更提案');
}

export async function applyAuthoringAiPatchProposal(
  sessionPid: string,
  proposalPid: string,
  revision: number,
): Promise<ApplyAuthoringAiPatchProposalResult> {
  const result = await fetchResult<ApplyAuthoringAiPatchProposalResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}` +
      `/ai-patch-proposals/${encodeURIComponent(proposalPid)}/apply`,
    {
      method: 'post',
      params: { expectedRevision: revision },
    },
  );
  return requireData(result, '无法应用受治理 AI 变更提案');
}

export async function rejectAuthoringAiPatchProposal(
  sessionPid: string,
  proposalPid: string,
  reason: string,
): Promise<AuthoringAiPatchProposal> {
  const result = await fetchResult<AuthoringAiPatchProposal>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}` +
      `/ai-patch-proposals/${encodeURIComponent(proposalPid)}/reject`,
    {
      method: 'post',
      params: { reason },
    },
  );
  return requireData(result, '无法拒绝受治理 AI 变更提案');
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

export async function prepareAuthoringSession(
  sessionPid: string,
  revision: number,
): Promise<AuthoringSession> {
  const result = await fetchResult<AuthoringSession>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/prepare`,
    { method: 'post', params: { expectedRevision: revision } },
  );
  return requireData(result, '无法完成校验与影响分析');
}

export async function transitionAuthoringGovernance(
  action: AuthoringGovernanceAction,
  session: Pick<AuthoringSession, 'sessionPid' | 'changeSetPid' | 'revision'>,
  reason: string,
): Promise<void> {
  const target =
    action === 'withdraw'
      ? `/api/authoring/sessions/${encodeURIComponent(session.sessionPid)}/review/withdraw`
      : action === 'reopen'
        ? `/api/authoring/sessions/${encodeURIComponent(session.sessionPid)}/approved/reopen`
        : `/api/authoring/change-sets/${encodeURIComponent(session.changeSetPid)}/${action}`;
  const result = await fetchResult<unknown>(target, {
    method: 'post',
    params: { expectedRevision: session.revision, reason },
  });
  if (!ResultHelper.isSuccess(result)) {
    throw new Error(result.message || result.desc || '无法完成 ChangeSet 治理操作');
  }
}

export async function publishAuthoringChangeSet(
  changeSetPid: string,
  revision: number,
): Promise<AuthoringRelease> {
  const result = await fetchResult<AuthoringRelease>(
    `/api/authoring/change-sets/${encodeURIComponent(changeSetPid)}/publish`,
    { method: 'post', params: { expectedRevision: revision } },
  );
  return requireData(result, '发布失败；活动版本未改变，可检查状态后重试');
}

export async function loadAuthoringReleaseHistory(
  changeSetPid: string,
  page = 1,
  size = 20,
): Promise<AuthoringReleaseHistory> {
  const result = await fetchResult<AuthoringReleaseHistory>(
    `/api/authoring/change-sets/${encodeURIComponent(changeSetPid)}/releases`,
    { params: { page, size } },
  );
  return requireData(result, '无法加载发布历史');
}

export async function rollbackAuthoringRelease(
  activeReleasePid: string,
  channelVersion: number,
  reason: string,
): Promise<AuthoringRelease> {
  const result = await fetchResult<AuthoringRelease>(
    `/api/authoring/releases/${encodeURIComponent(activeReleasePid)}/rollback`,
    {
      method: 'post',
      params: { expectedChannelVersion: channelVersion, reason },
    },
  );
  return requireData(result, '回滚失败；活动版本可能已变化，请刷新后重试');
}

export async function loadAuthoringChangeItems(sessionPid: string): Promise<AuthoringChangeItem[]> {
  const result = await fetchResult<AuthoringChangeItem[]>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/change-items`,
  );
  return requireData(result, '无法加载 ChangeSet 变更项');
}

export async function splitAuthoringChangeSet(
  sessionPid: string,
  revision: number,
  itemPids: string[],
  title: string,
  reason: string,
): Promise<AuthoringSplitResult> {
  const result = await fetchResult<AuthoringSplitResult>(
    `/api/authoring/sessions/${encodeURIComponent(sessionPid)}/split`,
    {
      method: 'post',
      params: { expectedRevision: revision, itemPids, title, reason },
    },
  );
  return requireData(result, '无法拆分 ChangeSet；请检查变更依赖后重试');
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
