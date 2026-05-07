/**
 * Shadow Runs admin API client (D.5 Phase 1).
 *
 * Wraps the AdminShadowRunController REST endpoints:
 *   GET /api/admin/shadow-runs/aggregations
 *   GET /api/admin/shadow-runs?draftId=X&pageNum=0&pageSize=20
 *   GET /api/admin/shadow-runs/{shadowRunPid}
 *
 * Type shapes mirror Java DTOs at
 * platform/src/main/java/com/auraboot/framework/agent/dto/replay/Shadow*.java.
 */

import { get } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface ShadowRunAggregation {
  draftId: string;
  draftSkillCode: string | null;
  draftStatus: string | null;
  runCount: number;
  fidelitySamples: number;
  outputSamples: number;
  fidelityMatchRate: number | null;
  outputMatchRate: number | null;
  costDelta: number | null;
  latestAt: string | null;
}

export interface ShadowRunListItem {
  pid: string;
  draftId: string;
  originalRunId: string;
  shadowStatus: string | null;
  shadowDurationMs: number | null;
  shadowCostUsd: number | null;
  shadowTokens: number | null;
  shadowOutputHash: string | null;
  originalStatus: string | null;
  originalDurationMs: number | null;
  originalCostUsd: number | null;
  originalOutputHash: string | null;
  outputMatch: boolean | null;
  fidelityMatch: boolean | null;
  outputDiff: string | null;
  createdAt: string | null;
}

export async function listShadowRunAggregations(): Promise<ShadowRunAggregation[]> {
  const r = await get('/api/admin/shadow-runs/aggregations');
  if (!ResultHelper.isSuccess(r)) {
    throw new Error(
      (r as { message?: string }).message ?? 'Failed to load shadow run aggregations',
    );
  }
  return (r.data as ShadowRunAggregation[]) ?? [];
}

export async function listShadowRunsForDraft(
  draftId: string,
  pageNum = 0,
  pageSize = 20,
): Promise<ShadowRunListItem[]> {
  const sp = new URLSearchParams();
  sp.set('draftId', draftId);
  sp.set('pageNum', String(pageNum));
  sp.set('pageSize', String(pageSize));
  const r = await get(`/api/admin/shadow-runs?${sp.toString()}`);
  if (!ResultHelper.isSuccess(r)) {
    throw new Error(
      (r as { message?: string }).message ?? 'Failed to load shadow runs',
    );
  }
  return (r.data as ShadowRunListItem[]) ?? [];
}

export async function getShadowRunDetail(shadowRunPid: string): Promise<ShadowRunListItem> {
  const r = await get(`/api/admin/shadow-runs/${encodeURIComponent(shadowRunPid)}`);
  if (!ResultHelper.isSuccess(r)) {
    throw new Error(
      (r as { message?: string }).message ?? 'Failed to load shadow run detail',
    );
  }
  return r.data as ShadowRunListItem;
}
