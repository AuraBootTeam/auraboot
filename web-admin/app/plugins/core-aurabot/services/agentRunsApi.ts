/**
 * Agent Runs admin API client.
 *
 * Wraps the Replay UI MVP REST endpoints exposed by AgentRunController:
 *   GET /api/admin/agent-runs
 *   GET /api/admin/agent-runs/{runId}
 *
 * Type shapes mirror the backend DTOs at
 * platform/src/main/java/com/auraboot/framework/agent/dto/replay/*.java.
 */

import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { ResultContract } from '../types/ResultContract';

// ---------------------------------------------------------------------------
// DTO types — mirror Java DTOs field-for-field
// ---------------------------------------------------------------------------

export interface AgentRunListItem {
  runId: string;
  agentCode: string | null;
  runStatus: string;
  parentRunId: string | null;
  subtaskOrigin: string | null;
  costUsd: number | null;
  durationMs: number | null;
  createdAt: string;
  completedAt: string | null;
  intentSummary: string | null;
}

export interface AgentActionItem {
  pid: string;
  resultContractId: string | null;
  stepIndex: number | null;
  toolCallIndex: number | null;
  actionCode: string | null;
  actionType: string | null;
  intentSummary: string | null;
  targetModel: string | null;
  targetRecordId: string | null;
  beforeSnapshot: string | null;
  afterSnapshot: string | null;
  fieldChanges: string | null;
  commandCode: string | null;
  commandResult: string | null;
  riskLevel: string | null;
  estimatedRisk: string | null;
  riskDeviation: boolean | null;
  reversalMode: string | null;
  actionStatus: string | null;
  errorMessage: string | null;
  costUsd: number | null;
  tokenUsage: number | null;
  fidelity: string | null;
  skillCode: string | null;
  parallelGroupId: string | null;
  parallelIndex: number | null;
  executedAt: string | null;
}

export interface AgentInterruptItem {
  pid: string;
  sessionId: string | null;
  activeRunId: string | null;
  newMessageExcerpt: string | null;
  subPolicy: string | null;
  classifierTier: string | null;
  confidence: number | null;
  reason: string | null;
  actionTaken: string | null;
  subtaskRunId: string | null;
  createdAt: string | null;
}

export interface AgentBifSummary {
  pid: string;
  intent: string | null;
  primaryObject: string | null;
  confidence: string | null;
  dispatchedSkill: string | null;
  channel: string | null;
}

export interface AgentConversationMessageItem {
  messageId: number;
  conversationId: number;
  senderType: string | null;
  senderId: number | null;
  seq: number | null;
  messageType: string | null;
  content: string | null;
  cardPayload: string | null;
  clientMsgId: string | null;
  triageBucket: string | null;
  triageConfidence: string | null;
  triageReasonCodes: string | null;
  thinkingContent: string | null;
  thinkingSignature: string | null;
  createdAt: string | null;
}

export interface AgentConversationTurnReplay {
  runId: string;
  taskPid: string | null;
  turnId: string | null;
  conversationId: number | null;
  inboundMessageId: number | null;
  outboundMessageId: number | null;
  triageBucket: string | null;
  triageConfidence: string | null;
  triageReasonCodes: string | null;
  userMessage: string | null;
  finalResponse: string | null;
  outcomeStatus: string | null;
  startedAt: string | null;
  completedAt: string | null;
  messages: AgentConversationMessageItem[];
  resultContractIds: string[];
}

export interface AgentResultContractItem {
  contractId: string;
  actionPid: string | null;
  source: string | null;
  contract: ResultContract;
  emittedAt: string | null;
}

export interface AgentRunDetail {
  run: AgentRunListItem;
  actions: AgentActionItem[];
  interruptLog: AgentInterruptItem[];
  childRuns: AgentRunListItem[];
  bif: AgentBifSummary | null;
  traceId: string | null;
  conversationTurn: AgentConversationTurnReplay | null;
  resultContracts: AgentResultContractItem[];
}

export interface AgentRunPage {
  items: AgentRunListItem[];
  total: number;
  page: number;
  size: number;
}

export interface AgentRunsListParams {
  page?: number;
  size?: number;
  status?: string;
  agentCode?: string;
  parentRunId?: string;
  keyword?: string;
}

// ---------------------------------------------------------------------------
// API calls
// ---------------------------------------------------------------------------

function buildQuery(params: AgentRunsListParams): string {
  const sp = new URLSearchParams();
  if (params.page !== undefined) sp.set('page', String(params.page));
  if (params.size !== undefined) sp.set('size', String(params.size));
  if (params.status) sp.set('status', params.status);
  if (params.agentCode) sp.set('agentCode', params.agentCode);
  if (params.parentRunId) sp.set('parentRunId', params.parentRunId);
  if (params.keyword) sp.set('keyword', params.keyword);
  const qs = sp.toString();
  return qs ? `?${qs}` : '';
}

export async function listAgentRuns(params: AgentRunsListParams = {}): Promise<AgentRunPage> {
  const r = await get(`/api/admin/agent-runs${buildQuery(params)}`);
  if (!ResultHelper.isSuccess(r)) {
    throw new Error((r as { message?: string }).message ?? 'Failed to load agent runs');
  }
  return (r.data as AgentRunPage) ?? { items: [], total: 0, page: 0, size: 20 };
}

export async function getAgentRunDetail(runId: string): Promise<AgentRunDetail> {
  const r = await get(`/api/admin/agent-runs/${encodeURIComponent(runId)}`);
  if (!ResultHelper.isSuccess(r)) {
    throw new Error((r as { message?: string }).message ?? 'Failed to load agent run detail');
  }
  return r.data as AgentRunDetail;
}
