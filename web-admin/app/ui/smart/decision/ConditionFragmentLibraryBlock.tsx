import { useEffect, useMemo, useState } from 'react';
import { getApiService } from '~/shared/services/ApiService';
import { group, type ConditionNode, type DataType, type GroupNode, type PathOperand, type Scope } from '~/shared/decision/ast/conditionAst';
import { ConditionBuilder, type FieldOption } from '~/shared/decision/ui/ConditionBuilder';
import {
  factCatalogToFieldOptions,
  mergeFieldOptions,
  modelFieldsToFieldOptions,
} from '~/shared/decision/ui/factCatalogAdapter';
import {
  createDecisionApi,
  type ConditionFragment,
  type ConditionFragmentEvaluation,
  type ConditionFragmentImpact,
  type DecisionApi,
  type DecisionImpactRef,
  type HttpClient,
  type ScopedContext,
} from '~/shared/decision/api/decisionApi';

type EditorMode = 'create' | 'version' | null;

interface LoadFragmentsOptions {
  silentError?: boolean;
}

interface FragmentDraft {
  fragmentCode: string;
  fragmentName: string;
  description: string;
  scopeType: string;
  scopeRef: string;
  ownerModule: string;
  conditionRoot: GroupNode;
  conditionSpecExtra: Record<string, unknown>;
}

interface DecisionBindingDraft {
  decisionCode: string;
  versionPolicy?: string;
  enabled?: boolean;
}

interface DecisionDefinitionOption {
  decisionCode: string;
  decisionName?: string;
  scopeType?: string;
  enabled?: boolean;
}

interface ConditionFragmentLibraryBlockProps {
  block?: {
    props?: {
      defaultScopeType?: string;
      sampleContextJson?: string;
    };
  };
}

const DEFAULT_SAMPLE_CONTEXT = JSON.stringify(
  {
    record: {
      data: {
        targetKey: 'task_manager_approve',
        wd_req_days: 3,
      },
    },
  },
  null,
  2,
);

const SCOPE_OPTIONS = ['SLA', 'BPM', 'AUTOMATION', 'EVENT_POLICY', 'PERMISSION'];
const FIELD_SCOPES = new Set<Scope>([
  'meta',
  'event',
  'record',
  'before',
  'after',
  'process',
  'task',
  'sla',
  'actor',
  'tenant',
  'time',
  'env',
]);
const DATA_TYPES = new Set<DataType>([
  'string',
  'text',
  'integer',
  'decimal',
  'boolean',
  'date',
  'time',
  'datetime',
  'duration',
  'enum',
  'dict',
  'user',
  'role',
  'group',
  'department',
  'collection',
  'object',
]);
const MODEL_CODES_BY_SCOPE: Record<string, string[]> = {
  SLA: ['sla_config', 'wd_leave_request'],
  SLA_RULE: ['sla_config', 'wd_leave_request'],
  BPM: ['wd_leave_request'],
  BPM_PROCESS: ['wd_leave_request'],
  WORKFLOW: ['wd_leave_request'],
  AUTOMATION: ['wd_leave_request'],
  EVENT_POLICY: ['wd_leave_request'],
  PERMISSION: ['wd_leave_request', 'tenant_member', 'department'],
};
const SLA_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: '主管审批节点',
  task_hr_approve: 'HR 审批节点',
};
const PROCESS_NODE_VALUE_LABELS: Record<string, string> = {
  task_manager_approve: '主管审批节点',
  task_hr_approve: 'HR 审批节点',
  gw_manager: '主管审批网关',
};
const SCENARIO_FIELDS: Record<string, FieldOption[]> = {
  SLA: [
    {
      scope: 'record',
      path: 'data.targetKey',
      label: 'SLA 节点',
      dataType: 'string',
      options: Object.keys(SLA_NODE_VALUE_LABELS),
      valueLabels: SLA_NODE_VALUE_LABELS,
    },
    { scope: 'sla', path: 'deadlineMinutes', label: '截止分钟', dataType: 'integer' },
    { scope: 'sla', path: 'warningBeforeMinutes', label: '提前提醒', dataType: 'integer' },
  ],
  BPM: [
    {
      scope: 'process',
      path: 'nodeId',
      label: '流程节点',
      dataType: 'string',
      options: Object.keys(PROCESS_NODE_VALUE_LABELS),
      valueLabels: PROCESS_NODE_VALUE_LABELS,
    },
    { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
    { scope: 'actor', path: 'roles', label: '审批角色', dataType: 'collection' },
  ],
  AUTOMATION: [
    { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
    { scope: 'record', path: 'pid', label: '申请记录', dataType: 'string' },
    { scope: 'time', path: 'now', label: '触发时间', dataType: 'datetime' },
  ],
  EVENT_POLICY: [
    { scope: 'event', path: 'type', label: '事件类型', dataType: 'string' },
    { scope: 'record', path: 'data.wd_req_days', label: '请假天数', dataType: 'decimal' },
    { scope: 'actor', path: 'roles', label: '触发人角色', dataType: 'collection' },
  ],
  PERMISSION: [
    { scope: 'actor', path: 'orgPath', label: '组织路径', dataType: 'department' },
    { scope: 'record', path: 'data.departmentId', label: '记录部门', dataType: 'department' },
    { scope: 'tenant', path: 'id', label: '租户', dataType: 'string' },
  ],
};

function createApi(): DecisionApi {
  const service = getApiService();
  const http: HttpClient = {
    get: <T,>(endpoint: string, params?: Record<string, unknown>) =>
      service.get<T>(endpoint, params),
    post: <T,>(endpoint: string, body?: unknown) => service.post<T>(endpoint, body),
    delete: <T,>(endpoint: string) => service.delete<T>(endpoint),
  };
  return createDecisionApi(http);
}

function emptyDraft(defaultScopeType?: string): FragmentDraft {
  return {
    fragmentCode: '',
    fragmentName: '',
    description: '',
    scopeType: defaultScopeType ?? 'SLA',
    scopeRef: '',
    ownerModule: '',
    conditionRoot: group('AND', []),
    conditionSpecExtra: {},
  };
}

function compactDisplay(value: unknown): string {
  return typeof value === 'string' && value.trim() ? value : '-';
}

const FIELD_REF_SCOPE_LABELS: Record<string, string> = {
  record: '当前记录',
  process: '流程',
  task: '任务',
  sla: 'SLA',
  actor: '操作者',
  event: '事件',
  tenant: '租户',
  time: '时间',
};

const FIELD_REF_LABELS: Record<string, string> = {
  'record.data.targetKey': 'SLA 节点',
  'record.data.wd_req_days': '请假天数',
  'record.pid': '申请记录',
  'process.nodeId': '流程节点',
  'process.taskKey': '流程任务',
  'sla.deadlineMinutes': '截止分钟',
  'sla.warningBeforeMinutes': '提前提醒',
  'event.type': '事件类型',
  'actor.roles': '审批角色',
};

const DECISION_REF_LABELS: Record<string, string> = {
  complaint_sla_deadline: '请假审批 SLA 截止时间',
  approval_routing: '请假审批分派',
  leave_request_automation: '请假申请自动化策略',
  leave_visibility_policy: '请假可见性策略',
};

function fieldRefDisplayLabel(ref: string): string {
  const [scope, ...pathParts] = ref.split('.');
  const path = pathParts.join('.');
  const scopeLabelText = FIELD_REF_SCOPE_LABELS[scope] ?? scope;
  const fieldLabel = FIELD_REF_LABELS[ref] ?? (path || ref);
  return scopeLabelText ? `${scopeLabelText}.${fieldLabel}` : fieldLabel;
}

function decisionRefDisplayLabel(code: string): string {
  return DECISION_REF_LABELS[code] ?? code;
}

function decisionOptionLabel(option: DecisionDefinitionOption): string {
  return option.decisionName ? `${option.decisionName} · ${option.decisionCode}` : option.decisionCode;
}

function scopeLabel(value: unknown): string {
  switch (String(value ?? '').toUpperCase()) {
    case 'SLA':
    case 'SLA_RULE':
      return 'SLA / 超时策略';
    case 'BPM':
    case 'BPM_PROCESS':
    case 'WORKFLOW':
      return 'BPM / 审批路由';
    case 'AUTOMATION':
      return '自动化';
    case 'EVENT_POLICY':
      return '事件策略';
    case 'PERMISSION':
    case 'PERMISSION_POLICY':
      return '权限策略';
    default:
      return compactDisplay(value);
  }
}

function statusLabel(status?: string): string {
  switch (String(status ?? '').toUpperCase()) {
    case 'PUBLISHED':
      return '已发布';
    case 'VALIDATED':
      return '已校验';
    case 'DRAFT':
      return '草稿';
    case 'DEPRECATED':
      return '已废弃';
    case 'RETIRED':
      return '已停用';
    default:
      return compactDisplay(status);
  }
}

function evaluationResultLabel(result?: string): string {
  switch (String(result ?? '').toUpperCase()) {
    case 'MATCHED':
      return '命中';
    case 'NOT_MATCHED':
      return '未命中';
    case 'ERROR':
      return '执行异常';
    default:
      return compactDisplay(result);
  }
}

function refsSummary(fragment: ConditionFragment): string {
  const fieldCount = fragment.fieldRefs?.length ?? 0;
  const decisionCount = fragment.decisionRefs?.length ?? 0;
  return `${fieldCount} 个字段 · ${decisionCount} 个决策`;
}

function statusTone(status?: string): string {
  switch (String(status ?? '').toUpperCase()) {
    case 'PUBLISHED':
      return 'is-success';
    case 'VALIDATED':
    case 'DRAFT':
      return 'is-warning';
    case 'DEPRECATED':
    case 'RETIRED':
      return 'is-neutral';
    default:
      return 'is-neutral';
  }
}

function normalizedStatus(status?: string): string {
  return String(status ?? '').toUpperCase();
}

function isImmutableStatus(status?: string): boolean {
  return ['PUBLISHED', 'DEPRECATED', 'RETIRED'].includes(normalizedStatus(status));
}

function canCreateNewVersion(fragment: ConditionFragment | null): boolean {
  return Boolean(fragment?.pid && isImmutableStatus(fragment.status));
}

function canValidateVersion(fragment: ConditionFragment | null): boolean {
  return Boolean(fragment?.pid && !isImmutableStatus(fragment.status));
}

function canPublishVersion(fragment: ConditionFragment | null): boolean {
  return Boolean(fragment?.pid && normalizedStatus(fragment.status) === 'VALIDATED');
}

function latestFragments(rows: ConditionFragment[]): ConditionFragment[] {
  const byCode = new Map<string, ConditionFragment>();
  rows.forEach((fragment) => {
    const current = byCode.get(fragment.fragmentCode);
    if (!current || (fragment.version ?? 0) > (current.version ?? 0)) {
      byCode.set(fragment.fragmentCode, fragment);
    }
  });
  return Array.from(byCode.values()).sort((a, b) =>
    String(a.fragmentCode).localeCompare(String(b.fragmentCode)),
  );
}

function upsertFragmentVersion(rows: ConditionFragment[], fragment: ConditionFragment): ConditionFragment[] {
  const matches = (row: ConditionFragment) => {
    if (fragment.pid && row.pid === fragment.pid) return true;
    return row.fragmentCode === fragment.fragmentCode && row.version === fragment.version;
  };
  const next = rows.map((row) => (matches(row) ? fragment : row));
  const hasExisting = rows.some(matches);
  return hasExisting ? next : [fragment, ...next];
}

function parseJsonObject(value: string): unknown {
  const parsed = JSON.parse(value);
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('JSON 必须是对象');
  }
  return parsed;
}

function normalizeDataType(value: unknown): DataType {
  return DATA_TYPES.has(value as DataType) ? (value as DataType) : 'string';
}

function fieldKey(field: Pick<FieldOption, 'scope' | 'path'>): string {
  return `${field.scope}:${field.path}`;
}

function isObject(value: unknown): value is Record<string, unknown> {
  return Boolean(value && typeof value === 'object' && !Array.isArray(value));
}

function asConditionNode(value: unknown): ConditionNode | null {
  if (!isObject(value)) return null;
  const type = value.type;
  if (type === 'group' || type === 'compare' || type === 'not') {
    return value as unknown as ConditionNode;
  }
  return null;
}

function conditionSpecParts(spec: unknown): Pick<FragmentDraft, 'conditionRoot' | 'conditionSpecExtra'> {
  const extra: Record<string, unknown> = {};
  if (!isObject(spec)) {
    return { conditionRoot: group('AND', []), conditionSpecExtra: extra };
  }
  Object.entries(spec).forEach(([key, value]) => {
    if (key !== 'root') extra[key] = value;
  });
  const rootNode = asConditionNode(spec.root);
  if (!rootNode) {
    return { conditionRoot: group('AND', []), conditionSpecExtra: extra };
  }
  return {
    conditionRoot: rootNode.type === 'group' ? rootNode : group('AND', [rootNode]),
    conditionSpecExtra: extra,
  };
}

function conditionSpecFromDraft(draft: FragmentDraft): Record<string, unknown> {
  return {
    ...draft.conditionSpecExtra,
    root: draft.conditionRoot,
  };
}

function asDecisionDefinitionList(raw: unknown): DecisionDefinitionOption[] {
  const rows = Array.isArray(raw)
    ? raw
    : isObject(raw) && Array.isArray(raw.records)
      ? raw.records
      : isObject(raw) && Array.isArray(raw.data)
        ? raw.data
        : [];
  return rows
    .filter(isObject)
    .map((row) => ({
      decisionCode: String(row.decisionCode ?? ''),
      decisionName: typeof row.decisionName === 'string' ? row.decisionName : undefined,
      scopeType: typeof row.scopeType === 'string' ? row.scopeType : undefined,
      enabled: typeof row.enabled === 'boolean' ? row.enabled : undefined,
    }))
    .filter((row) => row.decisionCode);
}

function decisionBindingsFromExtra(extra: Record<string, unknown>): DecisionBindingDraft[] {
  const raw = extra.decisionBindings;
  if (!Array.isArray(raw)) return [];
  return raw
    .filter(isObject)
    .map((binding) => ({
      decisionCode: String(binding.decisionCode ?? ''),
      versionPolicy:
        typeof binding.versionPolicy === 'string'
          ? binding.versionPolicy
          : 'LATEST_PUBLISHED',
      enabled: typeof binding.enabled === 'boolean' ? binding.enabled : true,
    }))
    .filter((binding) => binding.decisionCode);
}

function decisionSpecExtraWithBindings(
  extra: Record<string, unknown>,
  bindings: DecisionBindingDraft[],
): Record<string, unknown> {
  return {
    ...extra,
    decisionBindings: bindings.map((binding) => ({
      decisionCode: binding.decisionCode,
      versionPolicy: binding.versionPolicy ?? 'LATEST_PUBLISHED',
      enabled: binding.enabled !== false,
    })),
  };
}

function fieldFromRef(ref: string): FieldOption | null {
  const parts = ref.split('.');
  const scope = parts[0] as Scope;
  if (!FIELD_SCOPES.has(scope) || parts.length < 2) return null;
  return {
    scope,
    path: parts.slice(1).join('.'),
    label: fieldRefDisplayLabel(ref),
    dataType: 'string',
  };
}

function pathOperandToField(operand: PathOperand): FieldOption {
  const ref = `${operand.scope}.${operand.path}`;
  return {
    scope: operand.scope,
    path: operand.path,
    label: fieldRefDisplayLabel(ref),
    dataType: normalizeDataType(operand.dataType),
  };
}

function fieldsFromConditionNode(node: ConditionNode): FieldOption[] {
  if (node.type === 'group') {
    return node.children.flatMap(fieldsFromConditionNode);
  }
  if (node.type === 'not') {
    return fieldsFromConditionNode(node.child);
  }
  const fields: FieldOption[] = [];
  if (node.left?.type === 'path') fields.push(pathOperandToField(node.left));
  if (node.right?.type === 'path') fields.push(pathOperandToField(node.right));
  return fields;
}

function scopeDefaults(scopeType: string): FieldOption[] {
  const scope = String(scopeType || 'SLA').toUpperCase();
  return SCENARIO_FIELDS[scope] ?? SCENARIO_FIELDS.SLA;
}

function filterCatalogFields(scopeType: string, fields: FieldOption[]): FieldOption[] {
  const scope = String(scopeType || '').toUpperCase();
  const modelCodes = new Set(MODEL_CODES_BY_SCOPE[scope] ?? []);
  return fields.filter((field) => {
    if (field.scope !== 'record') return true;
    if (modelCodes.size === 0) return true;
    return Boolean(field.modelCode && modelCodes.has(field.modelCode));
  });
}

function editorFieldsFor(
  draft: FragmentDraft,
  fieldCatalog: FieldOption[],
  selectedFragment: ConditionFragment | null,
): FieldOption[] {
  return mergeFieldOptions(
    scopeDefaults(draft.scopeType),
    filterCatalogFields(draft.scopeType, fieldCatalog),
    fieldsFromConditionNode(draft.conditionRoot),
    (selectedFragment?.fieldRefs ?? []).map(fieldFromRef).filter((field): field is FieldOption => Boolean(field)),
  );
}

function draftFromFragment(fragment: ConditionFragment): FragmentDraft {
  const spec = conditionSpecParts(fragment.conditionSpec);
  return {
    fragmentCode: fragment.fragmentCode,
    fragmentName: fragment.fragmentName ?? '',
    description: fragment.description ?? '',
    scopeType: fragment.scopeType ?? 'SLA',
    scopeRef: fragment.scopeRef ?? '',
    ownerModule: fragment.ownerModule ?? '',
    conditionRoot: spec.conditionRoot,
    conditionSpecExtra: spec.conditionSpecExtra,
  };
}

function fetchFragmentDetails(api: DecisionApi, code: string) {
  return Promise.all([
    api.listConditionFragmentVersions(code),
    api.getConditionFragmentImpact(code),
  ]);
}

function sourceLabel(ref: DecisionImpactRef): string {
  return compactDisplay(ref.sourceName ?? ref.sourceCode ?? ref.sourcePid);
}

function encodePath(value: string): string {
  return encodeURIComponent(value);
}

function testIdPart(value: unknown): string {
  return String(value ?? 'unknown').replace(/[^a-zA-Z0-9_-]+/g, '_');
}

function decisionDefinitionUrl(decisionCode: string): string {
  return `/p/decisionops_definitions/view/${encodePath(decisionCode)}`;
}

function decisionLogsUrl(decisionCode: string): string {
  return `/p/decisionops_execution_logs?decisionCode=${encodePath(decisionCode)}`;
}

function impactSourceUrl(ref: DecisionImpactRef): string | null {
  const sourceType = String(ref.sourceType ?? '').toUpperCase();
  const sourcePid = ref.sourcePid?.trim();
  const sourceCode = ref.sourceCode?.trim();

  switch (sourceType) {
    case 'SLA_RULE':
      return sourcePid ? `/p/sla_config/view/${encodePath(sourcePid)}` : null;
    case 'BPM_PROCESS':
      return sourcePid ? `/p/bpm_process_management/edit/${encodePath(sourcePid)}` : null;
    case 'AUTOMATION':
      return sourcePid ? `/automation/${encodePath(sourcePid)}` : null;
    case 'EVENT_POLICY':
      return sourceCode ? `/p/decisionops_event_policies/view/${encodePath(sourceCode)}` : null;
    case 'PERMISSION_POLICY':
      return '/enterprise/permissions';
    case 'DECISION_VERSION':
      return sourceCode ? decisionDefinitionUrl(sourceCode) : null;
    default:
      return null;
  }
}

export function ConditionFragmentLibraryBlock({ block }: ConditionFragmentLibraryBlockProps) {
  const api = useMemo(() => createApi(), []);
  const defaultScopeType = block?.props?.defaultScopeType;
  const sampleContextJson = block?.props?.sampleContextJson ?? DEFAULT_SAMPLE_CONTEXT;
  const [keyword, setKeyword] = useState('');
  const [scopeType, setScopeType] = useState('');
  const [fragments, setFragments] = useState<ConditionFragment[]>([]);
  const [fieldCatalog, setFieldCatalog] = useState<FieldOption[]>([]);
  const [decisionDefinitions, setDecisionDefinitions] = useState<DecisionDefinitionOption[]>([]);
  const [decisionDefinitionError, setDecisionDefinitionError] = useState('');
  const [decisionBindingCandidate, setDecisionBindingCandidate] = useState('');
  const [selectedCode, setSelectedCode] = useState('');
  const [versions, setVersions] = useState<ConditionFragment[]>([]);
  const [impact, setImpact] = useState<ConditionFragmentImpact | null>(null);
  const [evaluation, setEvaluation] = useState<ConditionFragmentEvaluation | null>(null);
  const [editorMode, setEditorMode] = useState<EditorMode>(null);
  const [draft, setDraft] = useState<FragmentDraft>(() => emptyDraft(defaultScopeType));
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(false);
  const [detailLoading, setDetailLoading] = useState(false);
  const [impactAcknowledged, setImpactAcknowledged] = useState(false);
  const visibleFragments = useMemo(() => latestFragments(fragments), [fragments]);
  const selectedFragment = useMemo(
    () => visibleFragments.find((fragment) => fragment.fragmentCode === selectedCode) ?? null,
    [selectedCode, visibleFragments],
  );
  const editorFields = useMemo(
    () => editorFieldsFor(draft, fieldCatalog, selectedFragment),
    [draft, fieldCatalog, selectedFragment],
  );
  const decisionBindings = useMemo(
    () => decisionBindingsFromExtra(draft.conditionSpecExtra),
    [draft.conditionSpecExtra],
  );
  const availableDecisionDefinitions = useMemo(() => {
    const selected = new Set(decisionBindings.map((binding) => binding.decisionCode));
    return decisionDefinitions.filter((definition) => !selected.has(definition.decisionCode));
  }, [decisionBindings, decisionDefinitions]);
  const decisionDefinitionLabels = useMemo(
    () =>
      new Map(
        decisionDefinitions.map((definition) => [
          definition.decisionCode,
          definition.decisionName?.trim() || definition.decisionCode,
        ]),
      ),
    [decisionDefinitions],
  );
  const decisionDisplayLabel = (decisionCode: string) => {
    const loadedLabel = decisionDefinitionLabels.get(decisionCode);
    return loadedLabel && loadedLabel !== decisionCode
      ? loadedLabel
      : decisionRefDisplayLabel(decisionCode);
  };
  const incomingImpactCount = impact?.incomingCount ?? 0;
  const publishRequiresImpactAck = Boolean(
    selectedFragment?.pid &&
      canPublishVersion(selectedFragment) &&
      (selectedFragment.version ?? 0) > 1 &&
      incomingImpactCount > 0,
  );
  const newVersionDisabledReason = selectedFragment && !canCreateNewVersion(selectedFragment)
    ? '只有已发布、已废弃或已停用版本可以编辑为新版本'
    : '';
  const validateDisabledReason = selectedFragment && !canValidateVersion(selectedFragment)
    ? '不可校验已发布、已废弃或已停用版本'
    : '';
  const publishDisabledReason = selectedFragment && !canPublishVersion(selectedFragment)
    ? '只有已校验版本可以发布'
    : publishRequiresImpactAck && !impactAcknowledged
      ? `请先确认 ${incomingImpactCount} 个复用方影响`
      : '';

  const loadFragments = async (options: LoadFragmentsOptions = {}) => {
    setLoading(true);
    if (!options.silentError) {
      setError('');
    }
    try {
      const result = await api.listConditionFragments({
        keyword: keyword.trim() || undefined,
        scopeType: scopeType || undefined,
        page: 1,
        size: 50,
      });
      setFragments(result.records ?? []);
    } catch (e) {
      if (!options.silentError) {
        setError(e instanceof Error ? e.message : String(e));
      }
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    void loadFragments();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    let cancelled = false;
    const loadFactCatalog = async () => {
      if (typeof api.getFactCatalog === 'function') {
        const catalogFields = factCatalogToFieldOptions(await api.getFactCatalog());
        if (catalogFields.length > 0) return catalogFields;
      }
      return modelFieldsToFieldOptions(await api.getModelFields());
    };
    loadFactCatalog()
      .then((fields) => {
        if (!cancelled) setFieldCatalog(fields);
      })
      .catch(() => {
        if (!cancelled) setFieldCatalog([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    let cancelled = false;
    api.listDefinitions({ page: 1, size: 200 })
      .then((result) => {
        if (cancelled) return;
        setDecisionDefinitions(asDecisionDefinitionList(result));
        setDecisionDefinitionError('');
      })
      .catch((e) => {
        if (cancelled) return;
        setDecisionDefinitionError(e instanceof Error ? e.message : String(e));
        setDecisionDefinitions([]);
      });
    return () => {
      cancelled = true;
    };
  }, [api]);

  useEffect(() => {
    if (!editorMode) {
      setDecisionBindingCandidate('');
      return;
    }
    if (decisionBindingCandidate && availableDecisionDefinitions.some(
      (definition) => definition.decisionCode === decisionBindingCandidate,
    )) {
      return;
    }
    setDecisionBindingCandidate(availableDecisionDefinitions[0]?.decisionCode ?? '');
  }, [availableDecisionDefinitions, decisionBindingCandidate, editorMode]);

  useEffect(() => {
    if (selectedCode && visibleFragments.some((fragment) => fragment.fragmentCode === selectedCode)) {
      return;
    }
    if (selectedCode && loading) {
      return;
    }
    setSelectedCode(visibleFragments[0]?.fragmentCode ?? '');
  }, [loading, selectedCode, visibleFragments]);

  useEffect(() => {
    if (!selectedCode) {
      setVersions([]);
      setImpact(null);
      setImpactAcknowledged(false);
      return;
    }

    let cancelled = false;
    setDetailLoading(true);
    setEvaluation(null);
    fetchFragmentDetails(api, selectedCode)
      .then(([nextVersions, nextImpact]) => {
        if (cancelled) return;
        setVersions(nextVersions);
        setImpact(nextImpact);
      })
      .catch((e) => {
        if (!cancelled) setError(e instanceof Error ? e.message : String(e));
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [api, selectedCode]);

  const reloadSelectedDetails = async (code: string) => {
    if (!code) return;
    setDetailLoading(true);
    try {
      const [nextVersions, nextImpact] = await fetchFragmentDetails(api, code);
      setVersions(nextVersions);
      setImpact(nextImpact);
    } finally {
      setDetailLoading(false);
    }
  };

  useEffect(() => {
    setImpactAcknowledged(false);
  }, [selectedFragment?.pid, incomingImpactCount]);

  const updateDraft = (field: keyof FragmentDraft, value: string) => {
    setDraft((current) => ({ ...current, [field]: value }));
  };

  const updateDraftCondition = (conditionRoot: GroupNode) => {
    setDraft((current) => ({ ...current, conditionRoot }));
  };

  const setDecisionBindings = (bindings: DecisionBindingDraft[]) => {
    setDraft((current) => ({
      ...current,
      conditionSpecExtra: decisionSpecExtraWithBindings(current.conditionSpecExtra, bindings),
    }));
  };

  const addDecisionBinding = () => {
    const decisionCode = decisionBindingCandidate || availableDecisionDefinitions[0]?.decisionCode;
    if (!decisionCode || decisionBindings.some((binding) => binding.decisionCode === decisionCode)) {
      return;
    }
    setDecisionBindings([
      ...decisionBindings,
      { decisionCode, versionPolicy: 'LATEST_PUBLISHED', enabled: true },
    ]);
  };

  const removeDecisionBinding = (decisionCode: string) => {
    setDecisionBindings(decisionBindings.filter((binding) => binding.decisionCode !== decisionCode));
  };

  const openCreate = () => {
    setEditorMode('create');
    setDraft(emptyDraft(defaultScopeType));
    setMessage('');
    setError('');
  };

  const openVersion = () => {
    if (!selectedFragment) return;
    if (!canCreateNewVersion(selectedFragment)) {
      setError('只有已发布、已废弃或已停用版本可以编辑为新版本');
      return;
    }
    setEditorMode('version');
    setDraft(draftFromFragment(selectedFragment));
    setMessage('');
    setError('');
  };

  const closeEditor = () => {
    setEditorMode(null);
    setDraft(emptyDraft(defaultScopeType));
  };

  const saveFragment = async () => {
    setError('');
    setMessage('');
    try {
      const conditionSpec = conditionSpecFromDraft(draft);
      let savedFragment: ConditionFragment;
      if (editorMode === 'version') {
        savedFragment = await api.createConditionFragmentVersion(draft.fragmentCode, {
          fragmentName: draft.fragmentName,
          description: draft.description,
          scopeType: draft.scopeType,
          scopeRef: draft.scopeRef,
          ownerModule: draft.ownerModule,
          enabled: true,
          conditionSpec,
        });
        setSelectedCode(savedFragment.fragmentCode);
        setMessage(`已创建 ${savedFragment.fragmentCode} v${savedFragment.version ?? '-'}`);
      } else {
        savedFragment = await api.createConditionFragment({
          fragmentCode: draft.fragmentCode,
          fragmentName: draft.fragmentName,
          description: draft.description,
          scopeType: draft.scopeType,
          scopeRef: draft.scopeRef,
          ownerModule: draft.ownerModule,
          enabled: true,
          conditionSpec,
        });
        setSelectedCode(savedFragment.fragmentCode);
        setMessage(`已创建条件片段 ${savedFragment.fragmentName ?? savedFragment.fragmentCode}`);
      }
      closeEditor();
      setFragments((current) => upsertFragmentVersion(current, savedFragment));
      setVersions((current) => upsertFragmentVersion(current, savedFragment));
      await loadFragments({ silentError: true });
      await reloadSelectedDetails(savedFragment.fragmentCode);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const validateSelected = async () => {
    if (!selectedFragment?.pid) return;
    if (!canValidateVersion(selectedFragment)) {
      setError('不可校验已发布、已废弃或已停用版本');
      return;
    }
    setError('');
    setMessage('');
    try {
      const updated = await api.validateConditionFragmentVersion(selectedFragment.pid);
      setFragments((current) => upsertFragmentVersion(current, updated));
      setVersions((current) => upsertFragmentVersion(current, updated));
      setMessage(`${updated.fragmentName ?? updated.fragmentCode} 已校验`);
      await loadFragments({ silentError: true });
      await reloadSelectedDetails(selectedFragment.fragmentCode);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const publishSelected = async () => {
    if (!selectedFragment?.pid) return;
    if (!canPublishVersion(selectedFragment)) {
      setError('只有已校验版本可以发布');
      return;
    }
    if (publishRequiresImpactAck && !impactAcknowledged) {
      setError(`请先确认 ${incomingImpactCount} 个复用方影响`);
      return;
    }
    setError('');
    setMessage('');
    try {
      const updated = await api.publishConditionFragmentVersion(selectedFragment.pid, {
        impactAcknowledged: publishRequiresImpactAck ? impactAcknowledged : false,
      });
      setFragments((current) => upsertFragmentVersion(current, updated));
      setVersions((current) => upsertFragmentVersion(current, updated));
      setMessage(`${updated.fragmentName ?? updated.fragmentCode} 已发布`);
      await loadFragments({ silentError: true });
      await reloadSelectedDetails(selectedFragment.fragmentCode);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  const evaluateSelected = async () => {
    if (!selectedFragment?.fragmentCode) return;
    setError('');
    setMessage('');
    try {
      const context = parseJsonObject(sampleContextJson) as ScopedContext;
      const result = await api.evaluateConditionFragment(selectedFragment.fragmentCode, context);
      setEvaluation(result);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  };

  return (
    <section className="decisionops-list-page cfl-shell" data-testid="condition-fragment-library">
      <div className="decisionops-toolbar cfl-toolbar">
        <input
          className="decisionops-search-input"
          aria-label="condition-fragment-keyword"
          placeholder="搜索片段编码、名称"
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
        />
        <select
          aria-label="condition-fragment-scope-filter"
          value={scopeType}
          onChange={(e) => setScopeType(e.target.value)}
        >
          <option value="">全部场景</option>
          {SCOPE_OPTIONS.map((scope) => (
            <option key={scope} value={scope}>
              {scopeLabel(scope)}
            </option>
          ))}
        </select>
        <button type="button" onClick={() => void loadFragments()} data-testid="cfl-refresh">
          刷新
        </button>
        <button
          type="button"
          className="decisionops-primary-button"
          onClick={openCreate}
          data-testid="cfl-open-create"
        >
          新建片段
        </button>
        <span className="decisionops-count" data-testid="cfl-count">
          {visibleFragments.length}
        </span>
      </div>

      {editorMode && (
        <div className="decisionops-editor-panel cfl-editor" data-testid="cfl-editor">
          <input
            aria-label="fragment-code"
            placeholder="片段编码"
            value={draft.fragmentCode}
            disabled={editorMode === 'version'}
            onChange={(e) => updateDraft('fragmentCode', e.target.value)}
          />
          <input
            aria-label="fragment-name"
            placeholder="片段名称"
            value={draft.fragmentName}
            onChange={(e) => updateDraft('fragmentName', e.target.value)}
          />
          <select
            aria-label="fragment-scope-type"
            value={draft.scopeType}
            onChange={(e) => updateDraft('scopeType', e.target.value)}
          >
            {SCOPE_OPTIONS.map((scope) => (
              <option key={scope} value={scope}>
                {scopeLabel(scope)}
              </option>
            ))}
          </select>
          <input
            aria-label="fragment-scope-ref"
            placeholder="消费方/场景引用"
            value={draft.scopeRef}
            onChange={(e) => updateDraft('scopeRef', e.target.value)}
          />
          <input
            aria-label="fragment-owner-module"
            placeholder="所属模块"
            value={draft.ownerModule}
            onChange={(e) => updateDraft('ownerModule', e.target.value)}
          />
          <textarea
            aria-label="fragment-description"
            placeholder="说明"
            value={draft.description}
            onChange={(e) => updateDraft('description', e.target.value)}
          />
          <div className="cfl-condition-editor">
            <div className="cfl-condition-head">
              <strong>条件</strong>
              <span>{editorFields.length} 个字段</span>
            </div>
            <ConditionBuilder
              value={draft.conditionRoot}
              fields={editorFields}
              onChange={updateDraftCondition}
            />
          </div>
          <div className="cfl-condition-editor" data-testid="cfl-decision-binding-editor">
            <div className="cfl-condition-head">
              <strong>复用决策</strong>
              <span>{decisionBindings.length} 个决策</span>
            </div>
            <div className="decisionops-toolbar cfl-binding-toolbar">
              <select
                aria-label="fragment-decision-binding-select"
                value={decisionBindingCandidate}
                onChange={(e) => setDecisionBindingCandidate(e.target.value)}
                disabled={availableDecisionDefinitions.length === 0}
              >
                {availableDecisionDefinitions.length === 0 ? (
                  <option value="">暂无可选决策</option>
                ) : (
                  availableDecisionDefinitions.map((definition) => (
                    <option key={definition.decisionCode} value={definition.decisionCode}>
                      {decisionOptionLabel(definition)}
                    </option>
                  ))
                )}
              </select>
              <button
                type="button"
                onClick={addDecisionBinding}
                disabled={!decisionBindingCandidate}
                data-testid="cfl-add-decision-binding"
              >
                添加
              </button>
            </div>
            {decisionDefinitionError && (
              <div className="decisionops-state is-error" data-testid="cfl-decision-binding-error">
                {decisionDefinitionError}
              </div>
            )}
            <div className="cfl-binding-list" data-testid="cfl-decision-bindings">
              {decisionBindings.length === 0 ? (
                <span className="decisionops-muted">未绑定决策</span>
              ) : (
                decisionBindings.map((binding) => (
                  <span
                    key={binding.decisionCode}
                    className="cfl-binding-chip"
                    data-testid={`cfl-decision-binding-${testIdPart(binding.decisionCode)}`}
                  >
                    <a href={decisionDefinitionUrl(binding.decisionCode)}>
                      {decisionDisplayLabel(binding.decisionCode)}
                    </a>
                    <button
                      type="button"
                      aria-label={`remove-decision-binding-${binding.decisionCode}`}
                      onClick={() => removeDecisionBinding(binding.decisionCode)}
                    >
                      移除
                    </button>
                  </span>
                ))
              )}
            </div>
          </div>
          <div className="decisionops-row-actions">
            <button type="button" data-testid="cfl-save-fragment" onClick={saveFragment}>
              {editorMode === 'version' ? '保存新版本' : '保存片段'}
            </button>
            <button type="button" onClick={closeEditor}>
              取消
            </button>
          </div>
        </div>
      )}

      {message && (
        <div className="decisionops-state" data-testid="cfl-message">
          {message}
        </div>
      )}
      {error && (
        <div className="decisionops-state is-error" data-testid="cfl-error">
          {error}
        </div>
      )}

      <div className="cfl-grid">
        <div className="decisionops-table-frame cfl-table-frame">
          <table className="decisionops-table cfl-table">
            <thead>
              <tr>
                <th>条件片段</th>
                <th>场景</th>
                <th>版本</th>
                <th>字段/决策引用</th>
              </tr>
            </thead>
            <tbody>
              {visibleFragments.map((fragment) => {
                const active = fragment.fragmentCode === selectedCode;
                return (
                  <tr key={fragment.fragmentCode} data-active={active}>
                    <td>
                      <button
                        type="button"
                        className="cfl-row-button"
                        data-testid={`cfl-row-${fragment.fragmentCode}`}
                        onClick={() => setSelectedCode(fragment.fragmentCode)}
                      >
                        <strong>{fragment.fragmentName ?? fragment.fragmentCode}</strong>
                        <span>{fragment.description || '可复用条件片段'}</span>
                      </button>
                    </td>
                    <td>
                      <span className="decisionops-badge is-neutral">
                        {scopeLabel(fragment.scopeType)}
                      </span>
                      <span>{fragment.scopeRef ? '已绑定消费对象' : '未限定消费对象'}</span>
                    </td>
                    <td>
                      <span className={`decisionops-badge ${statusTone(fragment.status)}`}>
                        {statusLabel(fragment.status)}
                      </span>
                      <span className="decisionops-code">v{fragment.version ?? '-'}</span>
                    </td>
                    <td>
                      <span>{refsSummary(fragment)}</span>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {loading && <div className="decisionops-state">加载条件片段...</div>}
          {!loading && visibleFragments.length === 0 && (
            <div className="decisionops-empty">暂无条件片段，先创建一个可复用条件。</div>
          )}
        </div>

        <aside className="cfl-detail">
          {selectedFragment ? (
            <>
              <div className="cfl-detail-header">
                <div>
                  <p className="decisionops-eyebrow">当前片段</p>
                  <h3>{selectedFragment.fragmentName ?? selectedFragment.fragmentCode}</h3>
                  <p>{selectedFragment.description || '可复用条件片段'}</p>
                </div>
                <span className={`decisionops-badge ${statusTone(selectedFragment.status)}`}>
                  {statusLabel(selectedFragment.status)}
                </span>
              </div>
              <div className="cfl-actions">
                <button
                  type="button"
                  onClick={openVersion}
                  disabled={!canCreateNewVersion(selectedFragment)}
                  title={newVersionDisabledReason || '编辑为新版本'}
                  data-testid="cfl-open-version"
                >
                  编辑为新版本
                </button>
                <button
                  type="button"
                  onClick={validateSelected}
                  disabled={!canValidateVersion(selectedFragment)}
                  title={validateDisabledReason || '校验当前版本'}
                  data-testid="cfl-validate-selected"
                >
                  校验
                </button>
                <button
                  type="button"
                  onClick={publishSelected}
                  disabled={Boolean(publishDisabledReason)}
                  title={publishDisabledReason || '发布当前版本'}
                  data-testid="cfl-publish-selected"
                >
                  发布
                </button>
                <button type="button" onClick={evaluateSelected} data-testid="cfl-run-evaluate">
                  测试运行
                </button>
              </div>
              <dl className="cfl-facts">
                <div>
                  <dt>消费场景</dt>
                  <dd>{scopeLabel(selectedFragment.scopeType)}</dd>
                </div>
                <div>
                  <dt>消费方</dt>
                  <dd>{selectedFragment.scopeRef ? '已绑定当前场景对象' : '未限定消费对象'}</dd>
                </div>
                <div>
                  <dt>所属模块</dt>
                  <dd>{compactDisplay(selectedFragment.ownerModule)}</dd>
                </div>
                <div>
                  <dt>字段引用</dt>
                  <dd>
                    {(selectedFragment.fieldRefs ?? []).length
                      ? selectedFragment.fieldRefs?.map((fieldRef, index, refs) => (
                        <span key={fieldRef} title={fieldRef}>
                          {fieldRefDisplayLabel(fieldRef)}
                          {index < refs.length - 1 ? ', ' : ''}
                        </span>
                      ))
                      : '-'}
                  </dd>
                </div>
                <div>
                  <dt>决策引用</dt>
                  <dd className="cfl-link-list">
                    {(selectedFragment.decisionRefs ?? []).length ? (
                      selectedFragment.decisionRefs?.map((decisionCode) => (
                        <span key={decisionCode} className="cfl-link-pair">
                          <a
                            href={decisionDefinitionUrl(decisionCode)}
                            title={decisionCode}
                            data-testid={`cfl-decision-link-${testIdPart(decisionCode)}`}
                          >
                            {decisionDisplayLabel(decisionCode)}
                          </a>
                          <a
                            href={decisionLogsUrl(decisionCode)}
                            data-testid={`cfl-decision-logs-${testIdPart(decisionCode)}`}
                          >
                            日志
                          </a>
                        </span>
                      ))
                    ) : (
                      '-'
                    )}
                  </dd>
                </div>
              </dl>
              <section className="cfl-panel" data-testid="cfl-impact">
                <h4>被哪些链路复用</h4>
                {detailLoading && <p>加载影响面...</p>}
                {!detailLoading && (impact?.incomingCount ?? 0) === 0 && <p>暂无复用方引用</p>}
                {!detailLoading &&
                  (impact?.incoming ?? []).map((ref, idx) => {
                    const href = impactSourceUrl(ref);
                    const linkTestId = `cfl-impact-link-${testIdPart(ref.sourceType)}-${testIdPart(
                      ref.sourcePid ?? ref.sourceCode ?? idx,
                    )}`;
                    return (
                      <div key={`${ref.sourceType}:${ref.sourceCode}:${idx}`} className="cfl-impact-row">
                        <span className="decisionops-badge is-neutral">
                          {scopeLabel(ref.sourceType)}
                        </span>
                        {href ? (
                          <a href={href} data-testid={linkTestId}>
                            <strong>{sourceLabel(ref)}</strong>
                          </a>
                        ) : (
                          <strong>{sourceLabel(ref)}</strong>
                        )}
                      </div>
                    );
                  })}
                {publishRequiresImpactAck && (
                  <label className="cfl-impact-ack">
                    <input
                      type="checkbox"
                      checked={impactAcknowledged}
                      onChange={(e) => setImpactAcknowledged(e.target.checked)}
                      data-testid="cfl-impact-ack"
                    />
                    <span>已核对 {incomingImpactCount} 个复用方影响</span>
                  </label>
                )}
              </section>
              <section className="cfl-panel" data-testid="cfl-versions">
                <h4>版本</h4>
                {versions.map((version) => (
                  <div key={version.pid ?? `${version.fragmentCode}:${version.version}`} className="cfl-version-row">
                    <strong>v{version.version ?? '-'}</strong>
                    <span className={`decisionops-badge ${statusTone(version.status)}`}>
                      {statusLabel(version.status)}
                    </span>
                  </div>
                ))}
              </section>
              {evaluation && (
                <section className="cfl-panel" data-testid="cfl-evaluation">
                  <h4>测试结果</h4>
                  <p>
                    {evaluationResultLabel(evaluation.result)} · {evaluation.matched ? '命中' : '未命中'} · v
                    {evaluation.version ?? '-'}
                  </p>
                </section>
              )}
            </>
          ) : (
            <div className="decisionops-empty">请选择一个条件片段。</div>
          )}
        </aside>
      </div>
    </section>
  );
}

export default ConditionFragmentLibraryBlock;
