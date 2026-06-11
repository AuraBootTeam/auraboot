/**
 * Typed DecisionOps API client — thin wrapper over the platform HTTP layer for the merged backend
 * endpoints (`/api/decision/*` + `/api/event-policy/*`). Unwraps the ApiResponse envelope and
 * returns the `data`. Takes a minimal {@link HttpClient} so it is unit-testable with a fake client.
 */
import type { ConditionNode, DataType } from '../ast/conditionAst';
import type { DecisionTable } from '../table/decisionTable';

/** Minimal surface of the platform ApiService used here (get/post returning an ApiResponse). */
export interface HttpClient {
  get<T>(endpoint: string, params?: Record<string, unknown>): Promise<{ data: T }>;
  post<T>(endpoint: string, body?: unknown): Promise<{ data: T }>;
  delete<T>(endpoint: string): Promise<{ data: T }>;
}

export type DecisionStatus =
  | 'MATCHED'
  | 'NOT_MATCHED'
  | 'UNKNOWN'
  | 'VIOLATED'
  | 'ERROR'
  | 'SKIPPED';

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

export interface DecisionLogRecord {
  pid?: string;
  traceId?: string;
  correlationId?: string;
  decisionCode?: string;
  decisionVersion?: number;
  selectedVersion?: number;
  rolloutPolicyPid?: string;
  rolloutBucket?: number;
  rolloutArm?: string;
  routingKey?: string;
  rolloutResultKey?: string;
  kind?: string;
  runtimeAdapter?: string;
  callerType?: string;
  callerRef?: string;
  inputDigest?: string;
  resultDigest?: string;
  matched?: boolean;
  status?: string;
  matchedRulesJson?: unknown;
  durationMs?: number;
  errorCode?: string;
  errorMessage?: string;
  createdAt?: string;
}

export interface DecisionLogFilters {
  keyword?: string;
  decisionCode?: string;
  status?: string;
  callerType?: string;
  matched?: boolean | '';
  rolloutArm?: string;
  minDurationMs?: number | '';
  maxDurationMs?: number | '';
  page?: number;
  size?: number;
}

export interface DecisionPageResult<T> {
  records: T[];
  total?: number;
  size?: number;
  current?: number;
  pages?: number;
  hasPrevious?: boolean;
  hasNext?: boolean;
}

export interface DecisionDashboardSummary {
  definitions: number;
  policies: number;
  evaluationsToday: number;
  matched: number;
  failed: number;
  retrying: number;
  p95LatencyMs?: number;
}

export interface DecisionDashboardException {
  traceId: string;
  code: string;
  status: 'FAILED' | 'FAILED_RETRYING' | 'ERROR';
  error?: string;
  time?: string;
}

export interface DecisionDashboardResponse {
  summary: DecisionDashboardSummary;
  exceptions: DecisionDashboardException[];
}

export interface DecisionModelField {
  entityCode: string;
  path: string;
  label: string;
  dataType: DataType;
  refs?: number;
  masked?: boolean;
  permission?: string;
  decisionCodes?: string[];
}

export type DecisionPermissionCapabilityKey =
  | 'view'
  | 'test'
  | 'publish'
  | 'approve'
  | 'rolloutManage'
  | 'rolloutPromote'
  | 'rolloutRollback'
  | 'field';

export interface DecisionPermissionCapabilityGrant {
  permissionCode: string;
  granted: boolean;
}

export interface DecisionPermissionRoleGrant {
  role: string;
  roleCode?: string;
  rolePid?: string;
  caps: Partial<Record<DecisionPermissionCapabilityKey, boolean>>;
  capabilities?: Partial<
    Record<DecisionPermissionCapabilityKey, DecisionPermissionCapabilityGrant>
  >;
}

export interface DecisionPermissionMatrix {
  roles: DecisionPermissionRoleGrant[];
}

export type DecisionImpactSourceType =
  | 'AUTOMATION'
  | 'SLA_RULE'
  | 'EVENT_POLICY'
  | 'DECISION_VERSION'
  | string;
export type DecisionImpactTargetType =
  | 'DECISION'
  | 'FIELD'
  | 'FUNCTION'
  | 'CONNECTOR'
  | 'POLICY'
  | string;

export interface DecisionImpactRef {
  sourceType?: DecisionImpactSourceType;
  sourceCode?: string;
  sourceName?: string;
  sourceVersion?: string;
  sourcePid?: string;
  targetType?: DecisionImpactTargetType;
  targetCode?: string;
  targetPath?: string;
  binding?: string;
  metadata?: Record<string, unknown>;
}

export interface DecisionImpactRisk {
  blocking: boolean;
  summary: string;
  counts?: Record<string, number>;
}

export interface DecisionImpact {
  decisionCode: string;
  incoming: DecisionImpactRef[];
  outgoing: DecisionImpactRef[];
  risk: DecisionImpactRisk;
}

export interface DecisionFieldImpact {
  fieldRef: string;
  references: DecisionImpactRef[];
  risk: DecisionImpactRisk;
}

export interface DecisionIntegrationImpact {
  targetType: 'CONNECTOR' | 'WEBHOOK' | string;
  targetCode: string;
  manageUrl?: string;
  references: DecisionImpactRef[];
  risk: DecisionImpactRisk;
}

export type DecisionFieldPreflightAction = 'DELETE_FIELD' | 'CHANGE_TYPE';

export interface DecisionFieldPreflightRequest {
  fieldRef: string;
  action: DecisionFieldPreflightAction;
  currentDataType?: string;
  nextDataType?: string;
  impactAcknowledged?: boolean;
  note?: string;
}

export interface DecisionFieldPreflight {
  fieldRef: string;
  action: DecisionFieldPreflightAction;
  currentDataType?: string;
  nextDataType?: string;
  allowed: boolean;
  blocked: boolean;
  requiresAcknowledgement: boolean;
  risk: DecisionImpactRisk;
  references: DecisionImpactRef[];
  message?: string;
}

export interface DecisionUsageIndexRebuild {
  tenantId?: number;
  totalRefs: number;
  consumerRefs: number;
  decisionRefs: number;
  fieldRefs: number;
  functionRefs: number;
}

export interface DecisionTableAnalysisIssue {
  code: string;
  severity?: 'ERROR' | 'WARNING' | string;
  ruleIds?: string[];
  inputCombination?: Record<string, unknown>;
  metadata?: Record<string, unknown>;
  message?: string;
}

export interface DecisionTableAnalysisMetrics {
  ruleCount: number;
  gapCount: number;
  overlapCount: number;
  conflictCount: number;
  unreachableRuleCount: number;
  finiteCombinationCount: number;
  finiteDomainComplete: boolean;
  finiteInputCount?: number;
  continuousInputCount?: number;
  analysisDurationMs?: number;
}

export interface DecisionTableAnalysis {
  valid: boolean;
  errors: DecisionTableAnalysisIssue[];
  warnings: DecisionTableAnalysisIssue[];
  metrics: DecisionTableAnalysisMetrics;
}

export interface DecisionTableDmnXmlIssue {
  code: string;
  message?: string;
}

export interface DecisionTableDmnXmlResult {
  valid: boolean;
  dmnXml?: string;
  model?: DecisionTable;
  errors: DecisionTableDmnXmlIssue[];
  warnings: DecisionTableDmnXmlIssue[];
}

export interface DecisionVersionTransitionRequest {
  impactAcknowledged?: boolean;
  note?: string;
}

export interface DecisionVersionSummary {
  pid: string;
  decisionCode?: string;
  version?: number;
  versionTag?: string;
  status?: string;
  kind?: string;
  runtimeAdapter?: string;
  publishedAt?: string;
}

export type DecisionRolloutStatus =
  | 'DRAFT'
  | 'ACTIVE'
  | 'PAUSED'
  | 'PROMOTED'
  | 'ROLLED_BACK'
  | string;

export interface DecisionRolloutCreateRequest {
  baselineVersion: number;
  candidateVersion: number;
  percentage: number;
  cohort?: unknown;
  segment?: unknown;
  routingKeyExpr?: string;
  salt?: string;
}

export interface DecisionRolloutActionRequest {
  note?: string;
}

export interface DecisionRollout {
  pid: string;
  decisionCode?: string;
  baselineVersion?: number;
  candidateVersion?: number;
  status?: DecisionRolloutStatus;
  percentage?: number;
  cohort?: unknown;
  segment?: unknown;
  routingKeyExpr?: string;
  salt?: string;
  startedBy?: string;
  startedAt?: string;
  endedBy?: string;
  endedAt?: string;
  audit?: unknown;
  createdAt?: string;
  updatedAt?: string;
}

export interface DecisionRolloutArmMetrics {
  version?: number;
  evaluations: number;
  matched: number;
  errors: number;
  matchedRate: number;
  errorRate: number;
  p95LatencyMs?: number;
  resultDistribution?: Record<string, number>;
}

export interface DecisionRolloutWindowMetrics {
  windowStart?: string;
  baseline: DecisionRolloutArmMetrics;
  candidate: DecisionRolloutArmMetrics;
}

export interface DecisionRolloutMetricsParams {
  windowHours?: number;
  bucketMinutes?: number;
  refresh?: boolean;
}

export interface DecisionRolloutMetrics {
  policyPid: string;
  windowHours?: number;
  bucketSeconds?: number;
  retentionDays?: number;
  source?: string;
  latencyAggregation?: string;
  refreshedAt?: string;
  baseline: DecisionRolloutArmMetrics;
  candidate: DecisionRolloutArmMetrics;
  windows?: DecisionRolloutWindowMetrics[];
}

export type DecisionConnectorType = 'WEBHOOK' | 'REST' | 'KAFKA' | 'MQ' | 'SCRIPT';
export type DecisionConnectorHealth = 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export interface DecisionConnector {
  code: string;
  name: string;
  type: DecisionConnectorType;
  endpoint?: string;
  authMode?: string;
  health: DecisionConnectorHealth;
  enabled: boolean;
}

interface PlatformApiConnector {
  pid?: string;
  name?: string;
  baseUrl?: string;
  authType?: string;
  enabled?: boolean;
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
  binding?:
    | 'LATEST'
    | 'FIXED_VERSION'
    | 'VERSION_TAG'
    | 'DEPLOYMENT_VERSION'
    | 'EFFECTIVE_TIME'
    | 'AS_OF_EVENT_TIME'
    | 'ROLLOUT';
  fixedVersion?: number;
  versionTag?: string;
  asOf?: string;
  callerType?: string;
  callerRef?: string;
  routingKey?: string;
  tenantSegment?: string;
  segment?: string;
  context: ScopedContext;
}

export interface PolicyListFilters {
  keyword?: string;
  eventType?: string;
  targetType?: string;
  targetKey?: string;
  status?: string;
}

export interface EventPolicySummary {
  pid?: string;
  policyCode: string;
  policyName?: string;
  eventType?: string;
  targetType?: string;
  targetKey?: string;
  phase?: string;
  matchMode?: string;
  status?: string;
  enabled?: boolean;
  latestVersionPid?: string;
  version?: number;
  owner?: string;
  createdAt?: string;
  updatedAt?: string;
  publishedAt?: string;
}

export interface EventPolicyDefinitionRequest {
  policyCode: string;
  policyName?: string;
  eventType: string;
  targetType: string;
  targetKey: string;
}

export interface EventPolicyCopyRequest {
  policyCode: string;
  policyName?: string;
}

export interface EventPolicyDraftVersionRequest {
  phase: 'BEFORE_SUBMIT' | 'AFTER_COMMIT' | 'ASYNC_WORKER';
  matchMode: 'FIRST_MATCH' | 'COLLECT_ALL' | 'UNIQUE' | 'PRIORITY_FIRST';
  executionMode?: 'ORDERED' | 'UNORDERED';
  failureStrategy?:
    | 'FAIL_FAST'
    | 'CONTINUE_ON_ERROR'
    | 'ALL_OR_NOTHING'
    | 'RETRY_ASYNC'
    | 'DEAD_LETTER';
  conflictStrategy?:
    | 'REJECT_ON_CONFLICT'
    | 'PRIORITY_WINS'
    | 'LAST_WRITE_WINS'
    | 'MERGE_IF_COMPATIBLE';
  dedupStrategy?: 'NONE' | 'BY_IDEMPOTENCY_KEY' | 'BY_ACTION_TYPE_AND_TARGET';
  rulesJson: unknown;
}

export interface EventPolicyVersionSummary {
  pid: string;
  policyCode?: string;
  version?: number;
  status?: string;
  phase?: EventPolicyDraftVersionRequest['phase'];
  matchMode?: EventPolicyDraftVersionRequest['matchMode'];
  executionMode?: EventPolicyDraftVersionRequest['executionMode'];
  failureStrategy?: EventPolicyDraftVersionRequest['failureStrategy'];
  conflictStrategy?: EventPolicyDraftVersionRequest['conflictStrategy'];
  dedupStrategy?: EventPolicyDraftVersionRequest['dedupStrategy'];
  rulesJson?: unknown;
  createdAt?: string;
  publishedAt?: string;
}

// Paths are relative to the HttpClient's base. The platform ApiService prepends '/api', so endpoints
// here must NOT include it (otherwise '/api/api/...' -> 404). Verified by the full-app browser golden.
const D = '/decision';
const P = '/event-policy';
const C = '/connectors';

export function createDecisionApi(http: HttpClient) {
  const policyListParams = (filters: PolicyListFilters): Record<string, unknown> =>
    Object.fromEntries(
      Object.entries(filters).filter(
        ([, value]) => value !== undefined && value !== null && value !== '',
      ),
    );
  const compactParams = (filters: object): Record<string, unknown> =>
    Object.fromEntries(
      Object.entries(filters).filter(
        ([, value]) => value !== undefined && value !== null && value !== '',
      ),
    );

  return {
    // ── Decision Runtime ──
    validate: (kind: string, runtimeAdapter: string, contentJson: ConditionNode | unknown) =>
      http
        .post<ValidateResult>(`${D}/validate`, { kind, runtimeAdapter, contentJson })
        .then((r) => r.data),

    testRun: (req: {
      kind: string;
      runtimeAdapter: string;
      contentJson: unknown;
      context: ScopedContext;
    }) => http.post<DecisionResult>(`${D}/test-run`, req).then((r) => r.data),

    evaluate: (req: EvaluateRequest) =>
      http.post<DecisionResult>(`${D}/evaluate`, req).then((r) => r.data),

    batchEvaluate: (requests: EvaluateRequest[]) =>
      http.post<DecisionResult[]>(`${D}/batch-evaluate`, requests).then((r) => r.data),

    createDefinition: (req: {
      decisionCode: string;
      decisionName?: string;
      scopeType?: string;
      ownerModule?: string;
    }) => http.post<unknown>(`${D}/definitions`, req).then((r) => r.data),

    getDefinition: (code: string) =>
      http.get<unknown>(`${D}/definitions/${code}`).then((r) => r.data),
    listDefinitions: () => http.get<unknown>(`${D}/definitions`).then((r) => r.data),

    createDraftVersion: (
      code: string,
      req: {
        kind: string;
        runtimeAdapter: string;
        versionTag?: string;
        contentJson: unknown;
        inputSchemaJson?: unknown;
        outputSchemaJson?: unknown;
        contextSchemaJson?: unknown;
      },
    ) =>
      http
        .post<DecisionVersionSummary>(`${D}/definitions/${code}/versions`, req)
        .then((r) => r.data),

    listVersions: (code: string) =>
      http.get<DecisionVersionSummary[]>(`${D}/definitions/${code}/versions`).then((r) => r.data),

    validateVersion: (pid: string) =>
      http.post<ValidateResult>(`${D}/versions/${pid}/validate`).then((r) => r.data),
    publishVersion: (pid: string, req?: DecisionVersionTransitionRequest) =>
      http.post<unknown>(`${D}/versions/${pid}/publish`, req).then((r) => r.data),
    submitVersionForApproval: (pid: string, req?: Pick<DecisionVersionTransitionRequest, 'note'>) =>
      http
        .post<DecisionVersionSummary>(`${D}/versions/${pid}/submit-for-approval`, req)
        .then((r) => r.data),
    approveVersion: (pid: string, req?: DecisionVersionTransitionRequest) =>
      http.post<DecisionVersionSummary>(`${D}/versions/${pid}/approve`, req).then((r) => r.data),
    rejectVersion: (pid: string, req?: Pick<DecisionVersionTransitionRequest, 'note'>) =>
      http.post<DecisionVersionSummary>(`${D}/versions/${pid}/reject`, req).then((r) => r.data),
    deprecateVersion: (pid: string, req?: DecisionVersionTransitionRequest) =>
      http.post<DecisionVersionSummary>(`${D}/versions/${pid}/deprecate`, req).then((r) => r.data),
    retireVersion: (pid: string, req?: DecisionVersionTransitionRequest) =>
      http.post<DecisionVersionSummary>(`${D}/versions/${pid}/retire`, req).then((r) => r.data),
    deleteVersion: (pid: string) =>
      http.delete<DecisionVersionSummary>(`${D}/versions/${pid}`).then((r) => r.data),
    getLogs: (traceId: string) =>
      http.get<DecisionLogRecord[]>(`${D}/logs`, { traceId }).then((r) => r.data),
    getRecentLogs: (filters: DecisionLogFilters = {}) =>
      http
        .get<DecisionPageResult<DecisionLogRecord>>(`${D}/logs/recent`, compactParams(filters))
        .then((r) => r.data),
    getLogByPid: (pid: string) =>
      http.get<DecisionLogRecord>(`${D}/logs/${encodeURIComponent(pid)}`).then((r) => r.data),
    getDashboard: () =>
      http.get<DecisionDashboardResponse>(`${D}/dashboard/summary`).then((r) => r.data),
    getModelFields: () => http.get<DecisionModelField[]>(`${D}/model/fields`).then((r) => r.data),
    getPermissionMatrix: () =>
      http.get<DecisionPermissionMatrix>(`${D}/permissions/matrix`).then((r) => r.data),
    getDecisionImpact: (code: string) =>
      http.get<DecisionImpact>(`${D}/definitions/${code}/impact`).then((r) => r.data),
    createRollout: (code: string, req: DecisionRolloutCreateRequest) =>
      http.post<DecisionRollout>(`${D}/definitions/${code}/rollouts`, req).then((r) => r.data),
    listRollouts: (code: string) =>
      http.get<DecisionRollout[]>(`${D}/definitions/${code}/rollouts`).then((r) => r.data),
    getActiveRollout: (code: string) =>
      http.get<DecisionRollout>(`${D}/definitions/${code}/rollouts/active`).then((r) => r.data),
    activateRollout: (pid: string, req?: DecisionRolloutActionRequest) =>
      http.post<DecisionRollout>(`${D}/rollouts/${pid}/activate`, req).then((r) => r.data),
    pauseRollout: (pid: string, req?: DecisionRolloutActionRequest) =>
      http.post<DecisionRollout>(`${D}/rollouts/${pid}/pause`, req).then((r) => r.data),
    promoteRollout: (pid: string, req?: DecisionRolloutActionRequest) =>
      http.post<DecisionRollout>(`${D}/rollouts/${pid}/promote`, req).then((r) => r.data),
    rollbackRollout: (pid: string, req?: DecisionRolloutActionRequest) =>
      http.post<DecisionRollout>(`${D}/rollouts/${pid}/rollback`, req).then((r) => r.data),
    getRolloutMetrics: (pid: string, params?: DecisionRolloutMetricsParams) => {
      const query = compactParams(params ?? {});
      return http
        .get<DecisionRolloutMetrics>(
          `${D}/rollouts/${pid}/metrics`,
          Object.keys(query).length > 0 ? query : undefined,
        )
        .then((r) => r.data);
    },
    getFieldImpact: (fieldRef: string) =>
      http.get<DecisionFieldImpact>(`${D}/fields/impact`, { fieldRef }).then((r) => r.data),
    getIntegrationImpact: (targetType: string, targetCode: string) =>
      http
        .get<DecisionIntegrationImpact>(`${D}/integrations/impact`, { targetType, targetCode })
        .then((r) => r.data),
    preflightFieldChange: (req: DecisionFieldPreflightRequest) =>
      http.post<DecisionFieldPreflight>(`${D}/fields/preflight`, req).then((r) => r.data),
    analyzeTable: (model: DecisionTable, decisionCode?: string, versionPid?: string) =>
      http
        .post<DecisionTableAnalysis>(`${D}/tables/analyze`, { decisionCode, versionPid, model })
        .then((r) => r.data),
    exportTableDmn: (model: DecisionTable, decisionName = 'decision_table', decisionId?: string) =>
      http
        .post<DecisionTableDmnXmlResult>(`${D}/tables/export-dmn`, {
          decisionId,
          decisionName,
          model,
        })
        .then((r) => r.data),
    importTableDmn: (dmnXml: string) =>
      http
        .post<DecisionTableDmnXmlResult>(`${D}/tables/import-dmn`, { dmnXml })
        .then((r) => r.data),
    roundTripTableDmn: (
      model: DecisionTable,
      decisionName = 'decision_table',
      decisionId?: string,
    ) =>
      http
        .post<DecisionTableDmnXmlResult>(`${D}/tables/round-trip`, {
          decisionId,
          decisionName,
          model,
        })
        .then((r) => r.data),
    rebuildUsageIndex: () =>
      http.post<DecisionUsageIndexRebuild>(`${D}/usage-index/rebuild`).then((r) => r.data),
    refreshUsageIndexSource: (sourceType: string, sourcePid: string) =>
      http
        .post<DecisionUsageIndexRebuild>(
          `${D}/usage-index/sources/${encodeURIComponent(sourceType)}/${encodeURIComponent(sourcePid)}/refresh`,
        )
        .then((r) => r.data),
    listConnectors: () =>
      http.get<PlatformApiConnector[]>(C).then((r) => r.data.map(toDecisionConnector)),

    // ── Event Policy ──
    listPolicies: (filters: PolicyListFilters = {}) =>
      http
        .get<
          EventPolicySummary[] | { records?: EventPolicySummary[]; data?: EventPolicySummary[] }
        >(`${P}/definitions`, policyListParams(filters))
        .then((r) => r.data),

    createPolicyDefinition: (req: EventPolicyDefinitionRequest) =>
      http.post<EventPolicySummary>(`${P}/definitions`, req).then((r) => r.data),

    setPolicyEnabled: (code: string, enabled: boolean) =>
      http
        .post<EventPolicySummary>(`${P}/definitions/${code}/enabled`, { enabled })
        .then((r) => r.data),

    copyPolicyDefinition: (code: string, req: EventPolicyCopyRequest) =>
      http.post<EventPolicySummary>(`${P}/definitions/${code}/copy`, req).then((r) => r.data),

    runPolicy: (req: {
      eventType: string;
      targetType: string;
      targetKey: string;
      context: ScopedContext;
    }) => http.post<unknown>(`${P}/run`, req).then((r) => r.data),

    runAndExecutePolicy: (req: {
      eventType: string;
      targetType: string;
      targetKey: string;
      context: ScopedContext;
    }) => http.post<unknown>(`${P}/run-and-execute`, req).then((r) => r.data),

    createPolicyDraftVersion: (code: string, req: EventPolicyDraftVersionRequest) =>
      http
        .post<EventPolicyVersionSummary>(`${P}/definitions/${code}/versions`, req)
        .then((r) => r.data),

    listPolicyVersions: (code: string) =>
      http
        .get<EventPolicyVersionSummary[]>(`${P}/definitions/${code}/versions`)
        .then((r) => r.data),

    validatePolicyVersion: (pid: string) =>
      http.post<EventPolicyVersionSummary>(`${P}/versions/${pid}/validate`).then((r) => r.data),

    publishPolicyVersion: (pid: string) =>
      http.post<EventPolicyVersionSummary>(`${P}/versions/${pid}/publish`).then((r) => r.data),
  };
}

function toDecisionConnector(connector: PlatformApiConnector): DecisionConnector {
  return {
    code: connector.pid ?? connector.name ?? connector.baseUrl ?? 'connector',
    name: connector.name ?? connector.pid ?? connector.baseUrl ?? 'Connector',
    type: 'REST',
    endpoint: connector.baseUrl,
    authMode: connector.authType ? connector.authType.replace(/_/g, '').toUpperCase() : undefined,
    health: 'UNKNOWN',
    enabled: connector.enabled !== false,
  };
}

export type DecisionApi = ReturnType<typeof createDecisionApi>;
