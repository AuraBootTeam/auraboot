/**
 * Xiaoya consumer-surface API helpers (child space / class display).
 *
 * Thin wrappers over the platform http client. All reads go through the same
 * dynamic-data / chart-data APIs the DSL pages use; writes go through plugin
 * commands only (server-side invariants stay authoritative).
 */
import { get, post } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

export interface XyRow {
  pid?: string;
  [key: string]: unknown;
}

export async function xyList(modelCode: string, filters: Array<Record<string, unknown>> = [], pageSize = 200): Promise<XyRow[]> {
  const qs = filters.length
    ? `&filters=${encodeURIComponent(JSON.stringify(filters.map((f) => ({ fieldName: f.field, operator: 'EQ', value: f.value }))))}`
    : '';
  const result = await get<{ records?: XyRow[] }>(
    `/api/dynamic/${modelCode}/list?pageNum=1&pageSize=${pageSize}${qs}`,
  );
  if (!ResultHelper.isSuccess(result)) return [];
  return result.data?.records ?? [];
}

export async function xyGet(modelCode: string, pid: string): Promise<XyRow | null> {
  const result = await get<XyRow>(`/api/dynamic/${modelCode}/${pid}`);
  return ResultHelper.isSuccess(result) ? (result.data ?? null) : null;
}

export interface XyCommandResult {
  ok: boolean;
  message: string;
  data: Record<string, unknown>;
}

export async function xyExec(
  commandCode: string,
  payload: Record<string, unknown> = {},
  targetRecordPid?: string,
): Promise<XyCommandResult> {
  const body: Record<string, unknown> = { payload };
  if (targetRecordPid) body.targetRecordPid = targetRecordPid;
  const result = await post<{ data?: Record<string, unknown> }>(
    `/api/meta/commands/execute/${commandCode}`,
    body,
  );
  const ok = ResultHelper.isSuccess(result);
  const message = ok ? '' : String((result as { message?: string }).message || '操作失败');
  return { ok, message, data: (result.data?.data as Record<string, unknown>) ?? result.data ?? {} };
}


/** Load the active growth plan's thresholds (JSON), falling back to PRD defaults. */
export async function loadThresholds(): Promise<number[]> {
  const plans = await xyList('xy_growth_plan', [{ field: 'xy_gp_active', value: true }]);
  const raw = plans[0] ? String(plans[0].xy_gp_thresholds || '') : '';
  try {
    const arr = JSON.parse(raw);
    if (Array.isArray(arr) && arr.length > 0) return arr.map((t: { xp: number }) => Number(t.xp) || 0);
  } catch {
    /* fall through to PRD defaults */
  }
  return DEFAULT_XP_THRESHOLDS;
}

/** PRD 9.1 default level thresholds (fallback when no active growth plan). */
export const DEFAULT_XP_THRESHOLDS = [0, 30, 80, 150, 250, 400, 600, 850, 1150, 1500];

export function levelFor(xp: number, thresholds: number[]): number {
  let level = 1;
  thresholds.forEach((min, i) => {
    if (xp >= min) level = i + 1;
  });
  return level;
}

export function stageFor(level: number): 'stage_1' | 'stage_2' | 'stage_3' {
  if (level >= 7) return 'stage_3';
  if (level >= 4) return 'stage_2';
  return 'stage_1';
}

export const STAGE_LABEL: Record<string, string> = {
  stage_1: '幼年',
  stage_2: '少年',
  stage_3: '成长',
};

export const SPECIES_EMOJI: Record<string, string> = {
  cat: '🐱',
  dog: '🐶',
  rabbit: '🐰',
  hamster: '🐹',
  bird: '🐦',
  turtle: '🐢',
};
