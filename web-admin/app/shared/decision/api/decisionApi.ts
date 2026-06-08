/**
 * Typed DecisionOps API client — thin wrapper over the platform HTTP layer for the merged backend
 * endpoints (`/api/decision/*` + `/api/event-policy/*`). Unwraps the ApiResponse envelope and
 * returns the `data`. Takes a minimal {@link HttpClient} so it is unit-testable with a fake client.
 */
import type { ConditionNode } from '../ast/conditionAst';

/** Minimal surface of the platform ApiService used here (get/post returning an ApiResponse). */
export interface HttpClient {
  get<T>(endpoint: string, params?: Record<string, unknown>): Promise<{ data: T }>;
  post<T>(endpoint: string, body?: unknown): Promise<{ data: T }>;
}

export type DecisionStatus =
  | 'MATCHED' | 'NOT_MATCHED' | 'UNKNOWN' | 'VIOLATED' | 'ERROR' | 'SKIPPED';

export interface DecisionResult {
  traceId?: string;
  decisionCode?: string;
  status: DecisionStatus;
  matched: boolean;
  outputs?: Record<string, unknown>;
  violations?: { fieldPath?: string; code?: string; message?: string; severity?: string }[];
  matchedRules?: { ruleId?: string; reason?: string }[];
  errors?: string[];
}

export interface ValidateResult {
  valid: boolean;
  errors?: { code: string; message: string }[];
  warnings?: { code: string; message: string }[];
  fieldRefs?: string[];
  functionRefs?: string[];
}

export type ScopedContext = Record<string, Record<string, unknown>>;

export interface EvaluateRequest {
  decisionCode: string;
  binding?: 'LATEST' | 'FIXED_VERSION' | 'VERSION_TAG' | 'DEPLOYMENT_VERSION' | 'EFFECTIVE_TIME' | 'AS_OF_EVENT_TIME';
  fixedVersion?: number;
  versionTag?: string;
  asOf?: string;
  callerType?: string;
  context: ScopedContext;
}

const D = '/api/decision';
const P = '/api/event-policy';

export function createDecisionApi(http: HttpClient) {
  return {
    // ── Decision Runtime ──
    validate: (kind: string, runtimeAdapter: string, contentJson: ConditionNode | unknown) =>
      http.post<ValidateResult>(`${D}/validate`, { kind, runtimeAdapter, contentJson }).then((r) => r.data),

    testRun: (req: { kind: string; runtimeAdapter: string; contentJson: unknown; context: ScopedContext }) =>
      http.post<DecisionResult>(`${D}/test-run`, req).then((r) => r.data),

    evaluate: (req: EvaluateRequest) =>
      http.post<DecisionResult>(`${D}/evaluate`, req).then((r) => r.data),

    batchEvaluate: (requests: EvaluateRequest[]) =>
      http.post<DecisionResult[]>(`${D}/batch-evaluate`, requests).then((r) => r.data),

    createDefinition: (req: { decisionCode: string; decisionName?: string; scopeType?: string; ownerModule?: string }) =>
      http.post<unknown>(`${D}/definitions`, req).then((r) => r.data),

    getDefinition: (code: string) => http.get<unknown>(`${D}/definitions/${code}`).then((r) => r.data),
    listDefinitions: () => http.get<unknown>(`${D}/definitions`).then((r) => r.data),

    createDraftVersion: (code: string, req: { kind: string; runtimeAdapter: string; contentJson: unknown }) =>
      http.post<{ pid: string }>(`${D}/definitions/${code}/versions`, req).then((r) => r.data),

    validateVersion: (pid: string) => http.post<ValidateResult>(`${D}/versions/${pid}/validate`).then((r) => r.data),
    publishVersion: (pid: string) => http.post<unknown>(`${D}/versions/${pid}/publish`).then((r) => r.data),
    getLogs: (traceId: string) => http.get<unknown[]>(`${D}/logs`, { traceId }).then((r) => r.data),

    // ── Event Policy ──
    runPolicy: (req: { eventType: string; targetType: string; targetKey: string; context: ScopedContext }) =>
      http.post<unknown>(`${P}/run`, req).then((r) => r.data),

    runAndExecutePolicy: (req: { eventType: string; targetType: string; targetKey: string; context: ScopedContext }) =>
      http.post<unknown>(`${P}/run-and-execute`, req).then((r) => r.data),
  };
}

export type DecisionApi = ReturnType<typeof createDecisionApi>;
