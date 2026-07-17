import type { DataType, Operator, Scope } from '../ast/conditionAst';
import { operatorLabel } from '../ast/conditionAst';
import type { HitPolicy, TableAggregation } from '../table/decisionTable';

export type ValueLabels = Record<string, string>;

const DATA_TYPE_LABELS: Record<DataType, string> = {
  string: '文本',
  text: '长文本',
  integer: '整数',
  decimal: '小数',
  boolean: '布尔',
  date: '日期',
  time: '时间',
  datetime: '日期时间',
  duration: '时长',
  enum: '枚举',
  dict: '字典',
  user: '用户',
  role: '角色',
  group: '用户组',
  department: '部门',
  collection: '集合',
  object: '对象',
};

const SCOPE_LABELS: Record<Scope, string> = {
  meta: '元数据',
  event: '事件上下文',
  record: '当前记录',
  before: '变更前',
  after: '变更后',
  process: 'BPM 上下文',
  task: '任务上下文',
  sla: 'SLA 上下文',
  actor: '参与人',
  tenant: '租户上下文',
  time: '时间上下文',
  env: '环境变量',
};

const HIT_POLICY_LABELS: Record<HitPolicy, string> = {
  FIRST: '首个命中',
  UNIQUE: '唯一命中',
  COLLECT: '收集多个',
  PRIORITY: '按优先级',
};

const AGGREGATION_LABELS: Record<TableAggregation, string> = {
  NONE: '不聚合',
  SUM: '求和',
  MIN: '最小值',
  MAX: '最大值',
  COUNT: '计数',
};

export const SCENARIO_SCOPE_LABELS: Record<string, string> = {
  SLA: 'SLA',
  SLA_RULE: 'SLA',
  BPM: 'BPM',
  BPM_PROCESS: 'BPM',
  WORKFLOW: 'BPM',
  AUTOMATION: '自动化',
  PERMISSION: '权限',
  ABAC: '权限',
  EVENT_POLICY: '事件策略',
  EVENTPOLICY: '事件策略',
  POLICY: '事件策略',
};

const ANALYSIS_ISSUE_LABELS: Record<string, string> = {
  DMN_CONFLICT: '输出冲突',
  DMN_CONTINUOUS_GAP: '连续区间缺口',
  DMN_GAP: '规则缺口',
  DMN_OVERLAP: '规则重叠',
  DMN_UNREACHABLE: '不可达规则',
};

const ANALYSIS_METADATA_LABELS: Record<string, string> = {
  gapRanges: '缺口范围',
  coveredRanges: '已覆盖范围',
  input: '输入',
  output: '输出',
};

export function dataTypeLabel(dataType: DataType): string {
  return DATA_TYPE_LABELS[dataType] ?? dataType;
}

export function scopeLabel(scope: Scope): string {
  return SCOPE_LABELS[scope] ?? scope;
}

export function hitPolicyLabel(policy: HitPolicy): string {
  return HIT_POLICY_LABELS[policy] ?? policy;
}

export function aggregationLabel(aggregation: TableAggregation): string {
  return AGGREGATION_LABELS[aggregation] ?? aggregation;
}

export function decisionOperatorLabel(operator: Operator): string {
  return operatorLabel(operator);
}

export function scenarioScopeLabel(scopeType?: string | null): string {
  if (!scopeType) return '未分类';
  const normalized = scopeType.trim().toUpperCase();
  return SCENARIO_SCOPE_LABELS[normalized] ?? scopeType;
}

export function analysisIssueLabel(code: string): string {
  return ANALYSIS_ISSUE_LABELS[code] ?? code;
}

export function analysisMetadataLabel(key: string): string {
  return ANALYSIS_METADATA_LABELS[key] ?? key;
}

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value.trim() : undefined;
}

export function valueKey(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') return value;
  if (typeof value === 'number' || typeof value === 'boolean') return String(value);
  try {
    return JSON.stringify(value);
  } catch {
    return String(value);
  }
}

export function allowedValueValue(value: unknown): unknown {
  const record = recordOf(value);
  if (!record) return value;
  return record.value ?? record.code ?? record['id'] ?? record.key ?? value;
}

export function valueLabel(value: unknown, valueLabels?: ValueLabels | null): string {
  if (Array.isArray(value)) {
    return value.map((item) => valueLabel(item, valueLabels)).join(', ');
  }
  const record = recordOf(value);
  if (record) {
    const label = stringValue(record.label) ?? stringValue(record.name) ?? stringValue(record.title);
    if (label) return label;
  }
  const key = valueKey(value);
  return valueLabels?.[key] ?? key;
}

export function allowedValueOptions(
  values?: unknown[] | null,
  valueLabels?: ValueLabels | null,
): Array<{ value: string; label: string }> {
  return (values ?? []).map((item) => {
    const raw = allowedValueValue(item);
    const value = valueKey(raw);
    const itemLabel = valueLabel(item, valueLabels);
    return {
      value,
      label: itemLabel === valueKey(item) ? valueLabel(raw, valueLabels) : itemLabel,
    };
  });
}
