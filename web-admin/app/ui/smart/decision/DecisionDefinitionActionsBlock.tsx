import { useCallback, useEffect, useMemo, useState } from 'react';
import { useLocation, useNavigate, useParams } from 'react-router';
import { useAuth } from '~/contexts/AuthContext';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type DecisionImpact,
  type DecisionVersionSummary,
  type HttpClient,
} from '~/shared/decision/api/decisionApi';
import { ImpactGraphPanel } from '~/shared/decision/ui/ImpactGraphPanel';

interface DecisionDefinitionActionsBlockProps {
  block?: {
    props?: DecisionDefinitionActionsProps;
    mode?: DecisionDefinitionActionsProps['mode'];
    rolloutUrl?: string;
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

interface DecisionDefinitionActionsProps {
  mode?: 'detail';
  rolloutUrl?: string;
  initialDecisionCode?: string;
  permissionCodes?: string[];
}

type VersionAction = {
  code: string;
  label: string;
  testId: string;
  permissionCode?: string;
  requiresImpactAck?: boolean;
  run: () => Promise<unknown>;
};

const ROLLOUT_BINDABLE_STATUSES = new Set(['PUBLISHED', 'DEPRECATED']);
const PUBLISH_PERMISSION = 'decision.definition.publish';
const APPROVE_PERMISSION = 'decision.definition.approve';
const DEFAULT_ROLLOUT_URL =
  '/p/decisionops_rollouts?decisionCode={decisionCode}&baselineVersion={baselineVersion}&candidateVersion={candidateVersion}';

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

function recordFromRuntime(runtime: DecisionDefinitionActionsBlockProps['runtime']) {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function decisionCodeFromPath(pathname: string): string | undefined {
  const match = pathname.match(/\/p\/decisionops_definitions\/view\/([^/?#]+)/);
  return match?.[1] ? decodeURIComponent(match[1]) : undefined;
}

function isRolloutBindableVersion(version: DecisionVersionSummary): boolean {
  return (
    typeof version.version === 'number' &&
    ROLLOUT_BINDABLE_STATUSES.has(String(version.status ?? ''))
  );
}

function rolloutBaselineFor(
  candidate: DecisionVersionSummary,
  versions: DecisionVersionSummary[],
): DecisionVersionSummary | null {
  if (typeof candidate.version !== 'number') return null;

  return (
    versions
      .filter(isRolloutBindableVersion)
      .filter((version) => Number(version.version) < Number(candidate.version))
      .sort((left, right) => Number(right.version) - Number(left.version))[0] ?? null
  );
}

function rolloutUrlFor(
  template: string,
  decisionCode: string,
  candidate: DecisionVersionSummary,
  versions: DecisionVersionSummary[],
): string | null {
  if (!isRolloutBindableVersion(candidate)) return null;
  const baseline = rolloutBaselineFor(candidate, versions);
  if (!baseline || typeof baseline.version !== 'number' || typeof candidate.version !== 'number') {
    return null;
  }

  return template
    .replaceAll('{decisionCode}', encodeURIComponent(decisionCode))
    .replaceAll('{baselineVersion}', encodeURIComponent(String(baseline.version)))
    .replaceAll('{candidateVersion}', encodeURIComponent(String(candidate.version)));
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return '操作失败';
}

function emitErrorToast(message: string) {
  if (typeof window === 'undefined') return;
  window.dispatchEvent(
    new CustomEvent('aura:toast', {
      detail: {
        message,
        variant: 'error',
      },
    }),
  );
}

function versionLabel(version: DecisionVersionSummary): string {
  return typeof version.version === 'number' ? `v${version.version}` : version.versionTag || version.pid;
}

export function DecisionDefinitionActionsBlock({
  block,
  runtime,
}: DecisionDefinitionActionsBlockProps) {
  const navigate = useNavigate();
  const location = useLocation();
  const params = useParams();
  const auth = useAuth();
  const api = useMemo(() => createApi(), []);
  const props = block?.props ?? {};
  const record = recordFromRuntime(runtime);
  const decisionCode =
    stringValue(record.decisionCode) ??
    stringValue(record.decision_code) ??
    stringValue(params.recordId) ??
    decisionCodeFromPath(location.pathname) ??
    props.initialDecisionCode;
  const rolloutUrl = props.rolloutUrl ?? block?.rolloutUrl ?? DEFAULT_ROLLOUT_URL;
  const [impact, setImpact] = useState<DecisionImpact | null>(null);
  const [versions, setVersions] = useState<DecisionVersionSummary[]>([]);
  const [loading, setLoading] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [impactAcknowledged, setImpactAcknowledged] = useState(false);
  const [transitioningPid, setTransitioningPid] = useState<string | null>(null);
  const [message, setMessage] = useState('');

  const hasActionPermission = useCallback(
    (permissionCode?: string) => {
      if (!permissionCode) return true;
      if (Array.isArray(props.permissionCodes)) {
        return props.permissionCodes.includes(permissionCode);
      }
      if (!auth.isAuthenticated && auth.permissions == null) {
        return true;
      }
      return auth.hasPermission(permissionCode);
    },
    [auth, props.permissionCodes],
  );

  const refresh = useCallback(async () => {
    if (!decisionCode) return;
    setLoading(true);
    setLoadError('');
    try {
      const [nextImpact, nextVersions] = await Promise.all([
        api.getDecisionImpact(decisionCode),
        api.listVersions(decisionCode),
      ]);
      setImpact(nextImpact);
      setVersions(nextVersions);
    } catch (error) {
      setLoadError(errorMessage(error));
    } finally {
      setLoading(false);
    }
  }, [api, decisionCode]);

  useEffect(() => {
    setImpact(null);
    setVersions([]);
    setImpactAcknowledged(false);
    setMessage('');
    void refresh();
  }, [refresh]);

  const impactBlocking = Boolean(impact?.risk?.blocking);
  const disabledReason = (action: VersionAction, pid: string) => {
    if (transitioningPid === pid) {
      return '操作执行中';
    }
    if (!hasActionPermission(action.permissionCode)) {
      return `缺少权限 ${action.permissionCode}`;
    }
    if (action.requiresImpactAck && impactBlocking && !impactAcknowledged) {
      return '请先确认影响面';
    }
    return '';
  };
  const transitionDisabled = (action: VersionAction, pid: string) =>
    Boolean(disabledReason(action, pid));

  const runAction = async (version: DecisionVersionSummary, action: VersionAction) => {
    if (transitionDisabled(action, version.pid)) return;
    setTransitioningPid(version.pid);
    setMessage(`${action.label}中...`);
    try {
      await action.run();
      setMessage(`${action.label}成功`);
      await refresh();
    } catch (error) {
      const msg = errorMessage(error);
      setMessage(msg);
      emitErrorToast(msg);
    } finally {
      setTransitioningPid(null);
    }
  };

  const actionsFor = (version: DecisionVersionSummary): VersionAction[] => {
    const status = String(version.status ?? '');
    const impactReq = {
      impactAcknowledged: impactAcknowledged || !impactBlocking,
      note: 'DecisionOps definition DSL action acknowledged impact',
    };

    switch (status) {
      case 'DRAFT':
        return [
          {
            code: 'validate',
            label: '校验',
            testId: `dda-validate-${version.pid}`,
            run: () => api.validateVersion(version.pid),
          },
          {
            code: 'delete',
            label: '删除草稿',
            testId: `dda-delete-${version.pid}`,
            run: () => api.deleteVersion(version.pid),
          },
        ];
      case 'VALIDATED':
        return [
          {
            code: 'submit',
            label: '提交审批',
            testId: `dda-submit-${version.pid}`,
            permissionCode: PUBLISH_PERMISSION,
            run: () => api.submitVersionForApproval(version.pid),
          },
          {
            code: 'publish',
            label: '发布',
            testId: `dda-publish-${version.pid}`,
            permissionCode: PUBLISH_PERMISSION,
            requiresImpactAck: true,
            run: () => api.publishVersion(version.pid, impactReq),
          },
          {
            code: 'delete',
            label: '删除草稿',
            testId: `dda-delete-${version.pid}`,
            run: () => api.deleteVersion(version.pid),
          },
        ];
      case 'PENDING_APPROVAL':
        return [
          {
            code: 'approve',
            label: '审批通过',
            testId: `dda-approve-${version.pid}`,
            permissionCode: APPROVE_PERMISSION,
            requiresImpactAck: true,
            run: () => api.approveVersion(version.pid, impactReq),
          },
          {
            code: 'reject',
            label: '驳回',
            testId: `dda-reject-${version.pid}`,
            permissionCode: APPROVE_PERMISSION,
            run: () => api.rejectVersion(version.pid, { note: 'Rejected from DecisionOps DSL action' }),
          },
          {
            code: 'delete',
            label: '删除草稿',
            testId: `dda-delete-${version.pid}`,
            run: () => api.deleteVersion(version.pid),
          },
        ];
      case 'PUBLISHED':
        return [
          {
            code: 'deprecate',
            label: '废弃',
            testId: `dda-deprecate-${version.pid}`,
            permissionCode: PUBLISH_PERMISSION,
            requiresImpactAck: true,
            run: () => api.deprecateVersion(version.pid, impactReq),
          },
        ];
      case 'DEPRECATED':
        return [
          {
            code: 'retire',
            label: '退役',
            testId: `dda-retire-${version.pid}`,
            permissionCode: PUBLISH_PERMISSION,
            requiresImpactAck: true,
            run: () => api.retireVersion(version.pid, impactReq),
          },
        ];
      case 'REJECTED':
        return [
          {
            code: 'delete',
            label: '删除草稿',
            testId: `dda-delete-${version.pid}`,
            run: () => api.deleteVersion(version.pid),
          },
        ];
      default:
        return [];
    }
  };

  const openRollout = (version: DecisionVersionSummary) => {
    if (!decisionCode) return;
    const url = rolloutUrlFor(rolloutUrl, decisionCode, version, versions);
    if (url) {
      navigate(url);
    }
  };

  if (!decisionCode) {
    return (
      <section className="decisionops-list-page" data-testid="decision-definition-actions-block">
        <div className="decisionops-state is-error">缺少 decisionCode</div>
      </section>
    );
  }

  return (
    <section className="decisionops-list-page dda-panel" data-testid="decision-definition-actions-block">
      <div className="decisionops-toolbar dda-toolbar">
        <button type="button" data-testid="dda-refresh" disabled={loading} onClick={() => void refresh()}>
          刷新
        </button>
        <button
          type="button"
          data-testid="dda-open-rollouts"
          onClick={() => navigate(`/p/decisionops_rollouts?decisionCode=${encodeURIComponent(decisionCode)}`)}
        >
          灰度发布
        </button>
      </div>

      {loading && <div className="decisionops-state" data-testid="dda-loading">加载影响与版本...</div>}
      {loadError && (
        <div className="decisionops-state is-error" data-testid="dda-error">
          {loadError}
        </div>
      )}

      <div className="dda-grid">
        <div className="dda-impact" data-testid="dda-impact-panel">
          <div className="ddl-section-head">
            <h4>影响分析</h4>
            {impact?.risk?.summary && <span>{impact.risk.summary}</span>}
          </div>
          {impact ? <ImpactGraphPanel impact={impact} /> : <div className="decisionops-empty">暂无影响数据</div>}
          {impactBlocking && (
            <label className="ddl-impact-ack">
              <input
                type="checkbox"
                data-testid="dda-impact-ack"
                checked={impactAcknowledged}
                onChange={(event) => setImpactAcknowledged(event.currentTarget.checked)}
              />
              已确认影响面
            </label>
          )}
        </div>

        <div className="dda-versions" data-testid="dda-version-panel">
          <div className="ddl-section-head">
            <h4>版本生命周期</h4>
            <span>{versions.length} 个版本</span>
          </div>
          {versions.length === 0 ? (
            <div className="decisionops-empty" data-testid="dda-empty-versions">
              暂无版本
            </div>
          ) : (
            <ul className="ddl-version-actions" data-testid="dda-version-actions">
              {versions.map((version) => {
                const actions = actionsFor(version);
                const rolloutHref = decisionCode
                  ? rolloutUrlFor(rolloutUrl, decisionCode, version, versions)
                  : null;
                return (
                  <li key={version.pid} data-testid={`dda-version-${version.pid}`}>
                    <div>
                      <strong>{versionLabel(version)}</strong>
                      <span className="decisionops-badge is-neutral">{version.status ?? '-'}</span>
                    </div>
                    <div className="decisionops-row-actions">
                      {actions.map((action) => {
                        const reason = disabledReason(action, version.pid);
                        return (
                          <span key={action.code} className="dda-action">
                            <button
                              type="button"
                              data-testid={action.testId}
                              disabled={Boolean(reason)}
                              title={reason || undefined}
                              aria-describedby={reason ? `${action.testId}-disabled-reason` : undefined}
                              onClick={() => void runAction(version, action)}
                            >
                              {action.label}
                            </button>
                            {reason && (
                              <span
                                id={`${action.testId}-disabled-reason`}
                                data-testid={`${action.testId}-disabled-reason`}
                                className="dda-disabled-reason"
                              >
                                {reason}
                              </span>
                            )}
                          </span>
                        );
                      })}
                      {rolloutHref && (
                        <button
                          type="button"
                          data-testid={`dda-start-rollout-${version.pid}`}
                          onClick={() => openRollout(version)}
                        >
                          开始灰度
                        </button>
                      )}
                    </div>
                  </li>
                );
              })}
            </ul>
          )}
          {message && <div className="decisionops-state" data-testid="dda-message">{message}</div>}
        </div>
      </div>
    </section>
  );
}

export default DecisionDefinitionActionsBlock;
