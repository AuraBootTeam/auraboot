import React from 'react';
import { AlertCircle, RefreshCw } from 'lucide-react';
import { getApiService } from '~/shared/services/ApiService';

export interface BlockState<T> {
  data: T | null;
  loading: boolean;
  error: string | null;
}

export function useDecisionOpsGet<T>(
  endpoint: string,
  params?: Record<string, unknown>,
  options?: { enabled?: boolean },
): BlockState<T> {
  const enabled = options?.enabled ?? true;
  const paramsKey = stableParamsKey(params);
  const [state, setState] = React.useState<BlockState<T>>({
    data: null,
    loading: enabled,
    error: null,
  });

  React.useEffect(() => {
    if (!enabled || !endpoint) {
      setState({ data: null, loading: false, error: null });
      return undefined;
    }

    const controller = new AbortController();
    setState((prev) => ({ ...prev, loading: true, error: null }));

    getApiService()
      .get<T>(endpoint, params, controller.signal)
      .then((response) => {
        if (controller.signal.aborted) return;
        if (response.success) {
          setState({ data: response.data, loading: false, error: null });
        } else {
          setState({
            data: null,
            loading: false,
            error: response.message || `Request failed: ${response.code || 'unknown_error'}`,
          });
        }
      })
      .catch((error: unknown) => {
        if (controller.signal.aborted) return;
        setState({
          data: null,
          loading: false,
          error: error instanceof Error ? error.message : 'Request failed',
        });
      });

    return () => controller.abort();
  }, [enabled, endpoint, paramsKey]);

  return state;
}

export function recordsFrom<T>(value: unknown): T[] {
  if (Array.isArray(value)) return value as T[];
  if (!value || typeof value !== 'object') return [];
  const obj = value as Record<string, unknown>;
  if (Array.isArray(obj.records)) return obj.records as T[];
  if (Array.isArray(obj.items)) return obj.items as T[];
  if (Array.isArray(obj.content)) return obj.content as T[];
  return [];
}

export function asNumber(value: unknown): number | null {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

export function formatPercent(value: unknown): string {
  const n = asNumber(value);
  if (n == null) return '-';
  return `${Math.round(n * 10) / 10}%`;
}

export function formatDateTime(value: unknown): string {
  if (!value) return '-';
  const date = new Date(String(value));
  if (Number.isNaN(date.getTime())) return String(value);
  return date.toLocaleString();
}

const STATUS_BADGE_LABELS: Record<string, string> = {
  ACTIVE: '生效中',
  CANDIDATE_READY: '候选就绪',
  DISABLED: '已停用',
  DRAFT: '草稿',
  ENABLED: '已启用',
  ERROR: '异常',
  FAILED: '失败',
  MATCHED: '已命中',
  NO_BASELINE: '缺少基线',
  NO_VERSION: '无版本',
  PENDING_APPROVAL: '待审批',
  PUBLISHED: '已发布',
  PUBLISHED_ONLY: '仅已发布',
  REJECTED: '已拒绝',
  SUCCESS: '成功',
  VALIDATED: '已校验',
};

export function formatStatusLabel(value: unknown): string {
  const key = String(value || 'UNKNOWN');
  return STATUS_BADGE_LABELS[key] || key;
}

const SCOPE_LABELS: Record<string, string> = {
  AUTOMATION: '自动化',
  BPM: '流程',
  GOVERNANCE: '治理',
  PERMISSION: '权限',
  SLA: 'SLA',
};

const FIELD_TYPE_LABELS: Record<string, string> = {
  boolean: '布尔',
  date: '日期',
  datetime: '日期时间',
  number: '数字',
  string: '文本',
  unknown: '未知',
};

const EVENT_TYPE_LABELS: Record<string, string> = {
  FORM_SUBMITTED: '表单提交',
  RECORD_CHANGED: '记录变更',
  TASK_COMPLETED: '任务完成',
};

const TARGET_TYPE_LABELS: Record<string, string> = {
  FORM: '表单',
  MODEL: '模型',
  PROCESS: '流程',
};

export function formatScopeLabel(value: unknown): string {
  const key = String(value || '');
  return SCOPE_LABELS[key] || key || '-';
}

export function formatFieldTypeLabel(value: unknown): string {
  const key = String(value || 'unknown').toLowerCase();
  return FIELD_TYPE_LABELS[key] || key;
}

export function formatEventTypeLabel(value: unknown): string {
  const key = String(value || '');
  return EVENT_TYPE_LABELS[key] || key || '-';
}

export function formatTargetTypeLabel(value: unknown): string {
  const key = String(value || '');
  return TARGET_TYPE_LABELS[key] || key || '-';
}

export function StatusBadge({ value }: { value: unknown }) {
  const text = String(value || 'UNKNOWN');
  const tone =
    text === 'PUBLISHED' ||
    text === 'PUBLISHED_ONLY' ||
    text === 'ACTIVE' ||
    text === 'MATCHED' ||
    text === 'SUCCESS' ||
    text === 'ENABLED'
      ? 'border-emerald-200 bg-emerald-50 text-emerald-700'
      : text === 'ERROR' || text === 'FAILED' || text === 'REJECTED' || text === 'NO_VERSION'
        ? 'border-red-200 bg-red-50 text-red-700'
        : text === 'PENDING_APPROVAL' ||
            text === 'DRAFT' ||
            text === 'VALIDATED' ||
            text === 'CANDIDATE_READY' ||
            text === 'NO_BASELINE'
          ? 'border-amber-200 bg-amber-50 text-amber-700'
          : 'border-slate-200 bg-slate-50 text-slate-600';
  return (
    <span className={`inline-flex rounded-full border px-2 py-0.5 text-xs font-medium ${tone}`}>
      {formatStatusLabel(text)}
    </span>
  );
}

export function BlockShell({
  title,
  description,
  action,
  children,
}: {
  title: string;
  description?: string;
  action?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section className="rounded-lg border border-slate-200 bg-white shadow-sm">
      <div className="flex flex-col gap-3 border-b border-slate-100 px-5 py-4 md:flex-row md:items-center md:justify-between">
        <div>
          <h2 className="text-base font-semibold text-slate-950">{title}</h2>
          {description ? <p className="mt-1 text-sm text-slate-500">{description}</p> : null}
        </div>
        {action ? <div className="flex shrink-0 items-center gap-2">{action}</div> : null}
      </div>
      <div className="p-5">{children}</div>
    </section>
  );
}

export function LoadingBlock({ label = '加载中' }: { label?: string }) {
  return (
    <div className="flex min-h-24 items-center justify-center rounded-md border border-dashed border-slate-200 bg-slate-50 text-sm text-slate-500">
      <RefreshCw className="mr-2 h-4 w-4 animate-spin" />
      {label}
    </div>
  );
}

export function ErrorBlock({ message }: { message: string }) {
  return (
    <div className="flex items-start gap-3 rounded-md border border-red-200 bg-red-50 p-4 text-sm text-red-700">
      <AlertCircle className="mt-0.5 h-4 w-4 shrink-0" />
      <div>
        <div className="font-medium">数据加载失败</div>
        <div className="mt-1 text-red-600">{message}</div>
      </div>
    </div>
  );
}

export function EmptyBlock({ message }: { message: string }) {
  return (
    <div className="rounded-md border border-dashed border-slate-200 bg-slate-50 px-4 py-8 text-center text-sm text-slate-500">
      {message}
    </div>
  );
}

export function stableParamsKey(params?: Record<string, unknown>): string {
  if (!params) return '';
  return JSON.stringify(
    Object.keys(params)
      .sort()
      .reduce<Record<string, unknown>>((acc, key) => {
        acc[key] = params[key];
        return acc;
      }, {}),
  );
}
