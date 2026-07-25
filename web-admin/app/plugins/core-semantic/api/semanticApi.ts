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

// ---------------------------------------------------------------------------
// Authoring / query — validate, publish, run a governed query.
//
// These endpoints consume raw YAML (validate/publish) or return dynamic rows
// (query), so they go through a small envelope-aware fetch helper rather than
// the JSON-only http-client. The backend wraps every response in the platform
// ApiResponse envelope ({ code, message, data }); code "0" is success.
// ---------------------------------------------------------------------------

const SUCCESS_CODE = '0';

interface Envelope<T> {
  code?: string | number;
  message?: string;
  data?: T;
  context?: unknown;
}

async function unwrap<T>(res: Response): Promise<T> {
  let body: Envelope<T>;
  try {
    body = (await res.json()) as Envelope<T>;
  } catch {
    throw new Error(`HTTP ${res.status} ${res.statusText}`);
  }
  if (String(body.code) !== SUCCESS_CODE) {
    // Prefer the backend's human message; fall back to a context detail string.
    const ctx =
      body.context && typeof body.context === 'object'
        ? (body.context as { detail?: string }).detail
        : typeof body.context === 'string'
          ? body.context
          : undefined;
    throw new Error(body.message || ctx || `Request failed (code ${body.code})`);
  }
  return body.data as T;
}

export interface ValidateResult {
  ok: boolean;
  modelCode: string;
  version: string;
  metricCount: number;
  dimensionCount: number;
  entityCount: number;
  accessPolicyCount: number;
}

// The web client posts JSON ({ yaml } / { yaml, pluginCode }) rather than a raw
// text/plain body: the BFF proxy only forwards JSON request bodies (a raw
// text/plain body is dropped before it reaches the backend). The backend has
// JSON-consuming variants of these endpoints for exactly this reason.

/** POST /api/semantic/validate — parse + validate YAML without persisting. */
export async function validateSemanticYaml(yaml: string): Promise<ValidateResult> {
  const res = await fetch('/api/semantic/validate', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ yaml }),
  });
  return unwrap<ValidateResult>(res);
}

/** POST /api/semantic/publish — persist (create/update) a model. Returns its pid. */
export async function publishSemanticYaml(
  yaml: string,
  pluginCode: string,
): Promise<{ ok: boolean; pid: string }> {
  const res = await fetch('/api/semantic/publish', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ yaml, pluginCode }),
  });
  return unwrap<{ ok: boolean; pid: string }>(res);
}

export interface SemanticQueryRow {
  [column: string]: unknown;
}

export interface SemanticQueryResult {
  queryId: string;
  rows: SemanticQueryRow[];
  rowcount: number;
  durationMs: number;
  referencedColumns: string[];
  sql?: string | null;
  warnings: string[];
}

export interface SemanticQueryBody {
  metrics: string[];
  dimensions?: string[];
  filters?: Array<{ field: string; op?: string; value: unknown }>;
  order?: Array<{ field: string; dir?: string }>;
  limit?: number;
}

/** POST /api/semantic/query — execute a governed query, returning rows. */
export async function runSemanticQuery(
  body: SemanticQueryBody,
): Promise<SemanticQueryResult> {
  const res = await fetch('/api/semantic/query', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  return unwrap<SemanticQueryResult>(res);
}

/** A ready-to-edit starter model over the always-present ab_role table. */
export const EXAMPLE_SEMANTIC_YAML = `version: "0.1"

semantic_model:
  code: demo_roles
  label:
    zh-CN: 演示·角色
    en-US: Demo · Roles
  description: Starter semantic model over ab_role. Point model_ref at your own mt_<model> table.
  model_ref: ab_role
  primary_entity: role_id

entities:
  - name: role_id
    type: primary
    field_ref: id

dimensions:
  - code: created
    label: { zh-CN: 创建时间, en-US: Created }
    field_ref: created_at
    type: time
    time_grains: [day, month, year]
    primary_time: true
  - code: status
    label: { zh-CN: 状态, en-US: Status }
    field_ref: status
    type: categorical

measures:
  - code: role_count
    label: { zh-CN: 角色数, en-US: Role Count }
    agg: COUNT
    expr: "*"

metrics:
  - code: total_roles
    label: { zh-CN: 角色总数, en-US: Total Roles }
    description: Number of roles.
    type: simple
    type_params:
      measure: role_count
`;
