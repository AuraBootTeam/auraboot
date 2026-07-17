import { useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type {
  DecisionApi,
  EventPolicyDefinitionRequest,
  EventPolicySummary,
} from '../api/decisionApi';

/**
 * DecisionOps Event Policy list (mockup F2): searchable/filterable entry point into policy design.
 * The backend list shape is intentionally tolerated while the API evolves: array, {records:[]}, or
 * {data:[]} all normalize to the same table rows.
 */

export interface EventPolicyListPageProps {
  api: DecisionApi;
  onOpenDesigner?: (policy: EventPolicySummary) => void;
  onOpenLogs?: (policy: EventPolicySummary) => void;
}

type PolicyStatusFilter =
  | 'ALL'
  | 'PUBLISHED'
  | 'DRAFT'
  | 'VALIDATED'
  | 'PENDING_APPROVAL'
  | 'REJECTED';
type EditingMode = 'create' | 'copy' | null;

const STATUS_OPTIONS: PolicyStatusFilter[] = [
  'ALL',
  'PUBLISHED',
  'DRAFT',
  'VALIDATED',
  'PENDING_APPROVAL',
  'REJECTED',
];
const EMPTY_DRAFT: EventPolicyDefinitionRequest = {
  policyCode: '',
  policyName: '',
  eventType: '',
  targetType: '',
  targetKey: '',
};

function asPolicyList(raw: unknown): EventPolicySummary[] {
  if (Array.isArray(raw)) return raw as EventPolicySummary[];
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    if (Array.isArray(o.records)) return o.records as EventPolicySummary[];
    if (Array.isArray(o.data)) return o.data as EventPolicySummary[];
  }
  return [];
}

function textOf(policy: EventPolicySummary): string {
  return [
    policy.policyCode,
    policy.policyName,
    policy.eventType,
    policy.targetType,
    policy.targetKey,
    policy.owner,
  ]
    .filter(Boolean)
    .join(' ')
    .toLowerCase();
}

function display(value: unknown): string {
  if (value == null || value === '') return '-';
  return String(value);
}

function eventLabel(value: unknown): string {
  switch (String(value ?? '').toUpperCase()) {
    case 'LEAVE_REQUEST_CREATED':
      return '请假申请创建';
    default:
      return display(value).replaceAll('_', ' ');
  }
}

function targetLabel(policy: EventPolicySummary): string {
  if (policy.targetType === 'MODEL' && policy.targetKey === 'wd_leave_request') {
    return '请假申请';
  }
  return policy.targetType || policy.targetKey ? '业务对象' : '-';
}

function phaseLabel(value: unknown): string {
  switch (String(value ?? '').toUpperCase()) {
    case 'BEFORE_COMMIT':
      return '提交前检查';
    case 'AFTER_COMMIT':
      return '保存后执行';
    case 'ASYNC':
      return '异步执行';
    default:
      return display(value).replaceAll('_', ' ');
  }
}

function matchModeLabel(value: unknown): string {
  switch (String(value ?? '').toUpperCase()) {
    case 'COLLECT_ALL':
      return '收集全部命中';
    case 'FIRST_MATCH':
      return '首个命中';
    default:
      return display(value).replaceAll('_', ' ');
  }
}

function statusTone(statusValue: unknown): string {
  const normalized = String(statusValue ?? '').toUpperCase();
  if (['PUBLISHED', 'VALIDATED', 'ENABLED', 'SUCCESS'].includes(normalized)) return 'is-success';
  if (['DRAFT', 'PENDING_APPROVAL'].includes(normalized)) return 'is-warning';
  if (['REJECTED', 'FAILED', 'DISABLED'].includes(normalized)) return 'is-danger';
  return 'is-neutral';
}

export function EventPolicyListPage({ api, onOpenDesigner, onOpenLogs }: EventPolicyListPageProps) {
  const queryClient = useQueryClient();
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<PolicyStatusFilter>('ALL');
  const [eventType, setEventType] = useState('ALL');
  const [editingMode, setEditingMode] = useState<EditingMode>(null);
  const [copySourceCode, setCopySourceCode] = useState<string | null>(null);
  const [draft, setDraft] = useState<EventPolicyDefinitionRequest>(EMPTY_DRAFT);
  const [formError, setFormError] = useState<string | null>(null);

  const { data, isLoading, isError } = useQuery({
    queryKey: ['event-policies'],
    queryFn: () => api.listPolicies({}),
  });

  const refreshPolicies = () => queryClient.invalidateQueries({ queryKey: ['event-policies'] });
  const createMutation = useMutation({
    mutationFn: (request: EventPolicyDefinitionRequest) => api.createPolicyDefinition(request),
    onSuccess: () => {
      setEditingMode(null);
      setCopySourceCode(null);
      setDraft(EMPTY_DRAFT);
      void refreshPolicies();
    },
    onError: (error) => setFormError(error instanceof Error ? error.message : '保存失败'),
  });
  const copyMutation = useMutation({
    mutationFn: ({
      sourceCode,
      request,
    }: {
      sourceCode: string;
      request: { policyCode: string; policyName?: string };
    }) => api.copyPolicyDefinition(sourceCode, request),
    onSuccess: () => {
      setEditingMode(null);
      setCopySourceCode(null);
      setDraft(EMPTY_DRAFT);
      void refreshPolicies();
    },
    onError: (error) => setFormError(error instanceof Error ? error.message : '保存失败'),
  });
  const enabledMutation = useMutation({
    mutationFn: ({ policyCode, enabled }: { policyCode: string; enabled: boolean }) =>
      api.setPolicyEnabled(policyCode, enabled),
    onSuccess: () => {
      void refreshPolicies();
    },
  });

  const rows = asPolicyList(data);
  const eventTypes = useMemo(
    () => ['ALL', ...Array.from(new Set(rows.map((p) => p.eventType).filter(Boolean) as string[]))],
    [rows],
  );
  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return rows
      .filter((p) => status === 'ALL' || p.status === status)
      .filter((p) => eventType === 'ALL' || p.eventType === eventType)
      .filter((p) => !q || textOf(p).includes(q));
  }, [eventType, query, rows, status]);

  const startCreate = () => {
    setEditingMode('create');
    setCopySourceCode(null);
    setDraft(EMPTY_DRAFT);
    setFormError(null);
  };

  const startCopy = (policy: EventPolicySummary) => {
    setEditingMode('copy');
    setCopySourceCode(policy.policyCode);
    setDraft({
      policyCode: `${policy.policyCode}_copy`,
      policyName: `${policy.policyName ?? policy.policyCode} Copy`,
      eventType: policy.eventType ?? '',
      targetType: policy.targetType ?? '',
      targetKey: policy.targetKey ?? '',
    });
    setFormError(null);
  };

  const updateDraft = (field: keyof EventPolicyDefinitionRequest, value: string) => {
    setDraft((current) => ({ ...current, [field]: value }));
  };

  const saveDraft = () => {
    setFormError(null);
    if (editingMode === 'copy') {
      if (!copySourceCode) return;
      copyMutation.mutate({
        sourceCode: copySourceCode,
        request: {
          policyCode: draft.policyCode,
          policyName: draft.policyName,
        },
      });
      return;
    }
    createMutation.mutate(draft);
  };

  const cancelDraft = () => {
    setEditingMode(null);
    setCopySourceCode(null);
    setDraft(EMPTY_DRAFT);
    setFormError(null);
  };

  if (isLoading) return <div data-testid="epl-loading">加载中...</div>;
  if (isError) return <div data-testid="epl-error">加载失败</div>;

  return (
    <section className="decisionops-list-page" data-testid="event-policy-list">
      <div className="decisionops-toolbar epl-toolbar">
        <input
          className="decisionops-search-input"
          aria-label="policy-search"
          placeholder="搜索策略 / 事件 / 目标"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select
          aria-label="policy-status-filter"
          value={status}
          onChange={(e) => setStatus(e.target.value as PolicyStatusFilter)}
        >
          {STATUS_OPTIONS.map((s) => (
            <option key={s} value={s}>
              {s}
            </option>
          ))}
        </select>
        <select
          aria-label="policy-event-filter"
          value={eventType}
          onChange={(e) => setEventType(e.target.value)}
        >
          {eventTypes.map((e) => (
            <option key={e} value={e}>
              {e}
            </option>
          ))}
        </select>
        <span className="decisionops-count" data-testid="epl-count">
          {filtered.length}
        </span>
        <button
          className="decisionops-primary-button"
          type="button"
          data-testid="epl-new-policy"
          onClick={startCreate}
        >
          新建策略
        </button>
      </div>

      {editingMode ? (
        <div className="decisionops-editor-panel epl-editor" data-testid="epl-editor">
          <input
            aria-label="policy-code"
            placeholder="policyCode"
            value={draft.policyCode}
            onChange={(e) => updateDraft('policyCode', e.target.value)}
          />
          <input
            aria-label="policy-name"
            placeholder="policyName"
            value={draft.policyName}
            onChange={(e) => updateDraft('policyName', e.target.value)}
          />
          <input
            aria-label="policy-event-type"
            placeholder="eventType"
            value={draft.eventType}
            disabled={editingMode === 'copy'}
            onChange={(e) => updateDraft('eventType', e.target.value)}
          />
          <input
            aria-label="policy-target-type"
            placeholder="targetType"
            value={draft.targetType}
            disabled={editingMode === 'copy'}
            onChange={(e) => updateDraft('targetType', e.target.value)}
          />
          <input
            aria-label="policy-target-key"
            placeholder="targetKey"
            value={draft.targetKey}
            disabled={editingMode === 'copy'}
            onChange={(e) => updateDraft('targetKey', e.target.value)}
          />
          <button
            type="button"
            data-testid="epl-save-policy"
            disabled={createMutation.isPending || copyMutation.isPending}
            onClick={saveDraft}
          >
            保存
          </button>
          <button type="button" onClick={cancelDraft}>
            取消
          </button>
          {formError ? <div data-testid="epl-form-error">{formError}</div> : null}
        </div>
      ) : null}

      {filtered.length === 0 ? (
        <div className="decisionops-empty" data-testid="epl-empty">
          暂无策略
        </div>
      ) : (
        <div className="decisionops-table-frame">
          <table className="decisionops-table epl-table">
            <thead>
              <tr>
                <th>策略</th>
                <th>触发 / 对象</th>
                <th>阶段</th>
                <th>匹配模式</th>
                <th>版本</th>
                <th>状态</th>
                <th>启用</th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((policy) => (
                <tr key={policy.policyCode} data-testid={`epl-row-${policy.policyCode}`}>
                  <td>
                    <strong>{display(policy.policyName ?? policy.policyCode)}</strong>
                    <div className="decisionops-muted-cell">规则中心策略</div>
                  </td>
                  <td>
                    <span>{eventLabel(policy.eventType)}</span>
                    <div className="decisionops-muted-cell">{targetLabel(policy)}</div>
                  </td>
                  <td>{phaseLabel(policy.phase)}</td>
                  <td>{matchModeLabel(policy.matchMode)}</td>
                  <td>{policy.version != null ? `v${policy.version}` : '-'}</td>
                  <td>
                    <span className={`decisionops-badge ${statusTone(policy.status)}`}>
                      {display(policy.status)}
                    </span>
                  </td>
                  <td>
                    <button
                      className={`decisionops-switch-button ${policy.enabled !== false ? 'is-on' : ''}`}
                      type="button"
                      aria-pressed={policy.enabled !== false}
                      data-testid={`epl-toggle-enabled-${policy.policyCode}`}
                      disabled={enabledMutation.isPending}
                      onClick={() =>
                        enabledMutation.mutate({
                          policyCode: policy.policyCode,
                          enabled: policy.enabled === false,
                        })
                      }
                    >
                      {policy.enabled === false ? '停用' : '启用'}
                    </button>
                  </td>
                  <td>
                    <div className="decisionops-row-actions">
                      <button
                        type="button"
                        data-testid={`epl-open-designer-${policy.policyCode}`}
                        onClick={() => onOpenDesigner?.(policy)}
                      >
                        设计
                      </button>
                      <button
                        type="button"
                        data-testid={`epl-copy-${policy.policyCode}`}
                        onClick={() => startCopy(policy)}
                      >
                        复制
                      </button>
                      <button
                        type="button"
                        data-testid={`epl-open-logs-${policy.policyCode}`}
                        onClick={() => onOpenLogs?.(policy)}
                      >
                        日志
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

export default EventPolicyListPage;
