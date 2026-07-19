import { useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type EventPolicyDefinitionRequest,
  type EventPolicySummary,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';

interface EventPolicyActionsBlockProps {
  block?: {
    props?: EventPolicyActionsProps;
    mode?: EventPolicyActionsProps['mode'];
    detailUrl?: string;
    designerUrl?: string;
    logsUrl?: string;
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

interface EventPolicyActionsProps {
  mode?: 'list' | 'detail';
  detailUrl?: string;
  designerUrl?: string;
  logsUrl?: string;
  defaultEventType?: string;
  defaultTargetType?: string;
  defaultTargetKey?: string;
}

type EditingMode = 'create' | 'copy' | null;

const EMPTY_DRAFT: EventPolicyDefinitionRequest = {
  policyCode: '',
  policyName: '',
  eventType: '',
  targetType: '',
  targetKey: '',
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

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function booleanValue(value: unknown): boolean | undefined {
  return typeof value === 'boolean' ? value : undefined;
}

function templateUrl(template: string, policyCode: string): string {
  return template.replaceAll('{policyCode}', encodeURIComponent(policyCode));
}

function recordFromRuntime(runtime: EventPolicyActionsBlockProps['runtime']) {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function policyCodeFromPath(pathname: string): string | undefined {
  const match = pathname.match(/\/p\/decisionops_event_policies\/view\/([^/?#]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

export function EventPolicyActionsBlock({ block, runtime }: EventPolicyActionsBlockProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  const api = useMemo(() => createApi(), []);
  const props = block?.props ?? {};
  const record = recordFromRuntime(runtime);
  const routePolicyCode =
    stringValue(params.recordPid) ?? policyCodeFromPath(location.pathname);
  const policyCode =
    stringValue(record.policyCode) ??
    stringValue(record.policy_code) ??
    routePolicyCode;
  const policyName =
    stringValue(record.policyName) ??
    stringValue(record.policy_name) ??
    policyCode;
  const mode = routePolicyCode
    ? 'detail'
    : (props.mode ?? block?.mode ?? (policyCode ? 'detail' : 'list'));
  const detailUrl = props.detailUrl ?? block?.detailUrl ?? '/p/decisionops_event_policies/view/{policyCode}';
  const designerUrl =
    props.designerUrl ??
    block?.designerUrl ??
    '/p/decisionops_event_policy_designer?policyCode={policyCode}';
  const logsUrl =
    props.logsUrl ??
    block?.logsUrl ??
    '/p/decisionops_execution_logs?policyCode={policyCode}';
  const [editingMode, setEditingMode] = useState<EditingMode>(null);
  const [copySourceCode, setCopySourceCode] = useState<string | null>(null);
  const [draft, setDraft] = useState<EventPolicyDefinitionRequest>(EMPTY_DRAFT);
  const [enabled, setEnabled] = useState<boolean | undefined>(booleanValue(record.enabled));
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');

  useEffect(() => {
    setEnabled(booleanValue(record.enabled));
  }, [record.enabled]);

  const updateDraft = (field: keyof EventPolicyDefinitionRequest, value: string) => {
    setDraft((current) => ({ ...current, [field]: value }));
  };

  const startCreate = () => {
    setEditingMode('create');
    setCopySourceCode(null);
    setDraft({
      policyCode: '',
      policyName: '',
      eventType: props.defaultEventType ?? '',
      targetType: props.defaultTargetType ?? '',
      targetKey: props.defaultTargetKey ?? '',
    });
    setMessage('');
    setError('');
  };

  const startCopy = () => {
    if (!policyCode) return;
    setEditingMode('copy');
    setCopySourceCode(policyCode);
    setDraft({
      policyCode: `${policyCode}_copy`,
      policyName: `${policyName ?? policyCode} Copy`,
      eventType: '',
      targetType: '',
      targetKey: '',
    });
    setMessage('');
    setError('');
  };

  const closeEditor = () => {
    setEditingMode(null);
    setCopySourceCode(null);
    setDraft(EMPTY_DRAFT);
    setError('');
  };

  const save = async () => {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const saved =
        editingMode === 'copy' && copySourceCode
          ? await api.copyPolicyDefinition(copySourceCode, {
              policyCode: draft.policyCode,
              policyName: draft.policyName,
            })
          : await api.createPolicyDefinition(draft);
      const savedCode = saved.policyCode ?? draft.policyCode;
      setMessage(`已保存策略 ${savedCode}`);
      closeEditor();
      if (savedCode) {
        navigate(templateUrl(detailUrl, savedCode));
      }
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const toggleEnabled = async () => {
    if (!policyCode) return;
    const nextEnabled = !(enabled ?? booleanValue(record.enabled) ?? true);
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const updated = await api.setPolicyEnabled(policyCode, nextEnabled);
      setEnabled(updated.enabled ?? nextEnabled);
      setMessage(nextEnabled ? '策略已启用' : '策略已停用');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  };

  const navigatePolicy = (url: string) => {
    if (!policyCode) return;
    navigate(templateUrl(url, policyCode));
  };

  return (
    <section className="decisionops-list-page" data-testid="event-policy-actions-block">
      <div className="decisionops-toolbar">
        {mode === 'list' && (
          <button
            className="decisionops-primary-button"
            type="button"
            data-testid="epa-new-policy"
            onClick={startCreate}
          >
            新建策略
          </button>
        )}
        {mode === 'detail' && policyCode && (
          <>
            <button
              type="button"
              data-testid="epa-open-designer"
              onClick={() => navigatePolicy(designerUrl)}
            >
              设计
            </button>
            <button type="button" data-testid="epa-copy-policy" onClick={startCopy}>
              复制
            </button>
            <button
              type="button"
              data-testid="epa-toggle-enabled"
              disabled={busy}
              onClick={toggleEnabled}
              aria-pressed={enabled ?? booleanValue(record.enabled) ?? true}
            >
              {(enabled ?? booleanValue(record.enabled) ?? true) ? '停用' : '启用'}
            </button>
            <button
              type="button"
              data-testid="epa-open-logs"
              onClick={() => navigatePolicy(logsUrl)}
            >
              日志
            </button>
          </>
        )}
      </div>

      {editingMode && (
        <div className="decisionops-editor-panel epl-editor" data-testid="epa-editor">
          <input
            aria-label="policy-code"
            data-testid="epa-policy-code"
            placeholder="policyCode"
            value={draft.policyCode}
            onChange={(e) => updateDraft('policyCode', e.target.value)}
          />
          <input
            aria-label="policy-name"
            data-testid="epa-policy-name"
            placeholder="policyName"
            value={draft.policyName}
            onChange={(e) => updateDraft('policyName', e.target.value)}
          />
          {editingMode === 'create' && (
            <>
              <input
                aria-label="policy-event-type"
                data-testid="epa-policy-event-type"
                placeholder="eventType"
                value={draft.eventType}
                onChange={(e) => updateDraft('eventType', e.target.value)}
              />
              <input
                aria-label="policy-target-type"
                data-testid="epa-policy-target-type"
                placeholder="targetType"
                value={draft.targetType}
                onChange={(e) => updateDraft('targetType', e.target.value)}
              />
              <input
                aria-label="policy-target-key"
                data-testid="epa-policy-target-key"
                placeholder="targetKey"
                value={draft.targetKey}
                onChange={(e) => updateDraft('targetKey', e.target.value)}
              />
            </>
          )}
          <button type="button" data-testid="epa-save-policy" disabled={busy} onClick={save}>
            保存
          </button>
          <button type="button" data-testid="epa-cancel-policy" disabled={busy} onClick={closeEditor}>
            取消
          </button>
        </div>
      )}

      {message && (
        <div className="decisionops-state" data-testid="epa-message">
          {message}
        </div>
      )}
      {error && (
        <div className="decisionops-state is-error" data-testid="epa-error">
          {error}
        </div>
      )}
    </section>
  );
}

export default EventPolicyActionsBlock;
