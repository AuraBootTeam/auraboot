/**
 * Semantic layer API client.
 *
 * Covers two endpoints exposed by {@code SemanticController}:
 * - GET /api/semantic/lineage/{pid}  — incoming + outgoing edges of a node
 * - GET /api/semantic/meta           — catalog of active models/metrics/dimensions
 */

import { get } from '~/shared/services/http-client';
import type { Result } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';

// ---------------------------------------------------------------------------
// Lineage types
// ---------------------------------------------------------------------------

export interface LineageEdge {
  srcPid: string;
  srcType: string;
  dstPid: string;
  dstType: string;
  /** e.g. "METRIC_USES_DIMENSION", "MODEL_JOINS_MODEL", "EXPOSURE_REFS_METRIC" */
  refType: string;
}

export interface LineageResponse {
  nodePid: string;
  /** "MODEL" | "METRIC" | "DIMENSION" | "EXPOSURE" */
  nodeType: string;
  incoming: LineageEdge[];
  outgoing: LineageEdge[];
}

// ---------------------------------------------------------------------------
// Meta / catalog types
// ---------------------------------------------------------------------------

export interface MetricMeta {
  pid: string;
  code: string;
  label?: Record<string, string>;
  type?: string;
  description?: string;
}

export interface DimensionMeta {
  pid: string;
  code: string;
  label?: Record<string, string>;
  type?: string;
}

export interface ModelMeta {
  pid: string;
  code: string;
  label?: Record<string, string>;
  pluginCode?: string;
  metrics: MetricMeta[];
  dimensions: DimensionMeta[];
}

export interface SemanticMetaResponse {
  models: ModelMeta[];
}

// ---------------------------------------------------------------------------
// API functions
// ---------------------------------------------------------------------------

export async function fetchLineage(pid: string): Promise<LineageResponse> {
  const result: Result<LineageResponse> = await get<LineageResponse>(
    `/api/semantic/lineage/${encodeURIComponent(pid)}`,
  );
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to fetch lineage');
  }
  return result.data;
}

export async function fetchSemanticMeta(): Promise<SemanticMetaResponse> {
  const result: Result<SemanticMetaResponse> = await get<SemanticMetaResponse>(
    '/api/semantic/meta',
  );
  if (!ResultHelper.isSuccess(result) || !result.data) {
    throw new Error(result.desc || 'Failed to fetch semantic meta');
  }
  return result.data;
}
