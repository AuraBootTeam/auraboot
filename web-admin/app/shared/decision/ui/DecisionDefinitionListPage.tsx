import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import type { DecisionApi, DecisionVersionSummary } from '../api/decisionApi';
import { ImpactGraphPanel } from './ImpactGraphPanel';

/**
 * DecisionOps definition-center list page (mockup Decision Runtime / F5, docs/1.md §13): fetches the
 * tenant's decision definitions via the typed API client and renders code / name / kind / status.
 * Loading / error / empty handled. The API client is injected (default app wiring elsewhere) so the
 * page is unit-testable with a fake client + QueryClientProvider (browser golden defers to full-stack).
 */

export interface DefinitionSummary {
  decisionCode: string;
  decisionName?: string;
  scopeType?: string;
  ownerModule?: string;
  enabled?: boolean;
}

export interface DecisionDefinitionListPageProps {
  api: DecisionApi;
}

/** Normalize the list response: tolerate array | {records:[]} | {data:[]} shapes. */
function asList(raw: unknown): DefinitionSummary[] {
  if (Array.isArray(raw)) return raw as DefinitionSummary[];
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    if (Array.isArray(o.records)) return o.records as DefinitionSummary[];
    if (Array.isArray(o.data)) return o.data as DefinitionSummary[];
  }
  return [];
}

function definitionState(enabled?: boolean): string {
  return enabled === false ? '停用' : '启用';
}

const ROLLOUT_BINDABLE_STATUSES = new Set(['PUBLISHED', 'DEPRECATED']);

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

function rolloutStartHref(
  decisionCode: string,
  candidate: DecisionVersionSummary,
  versions: DecisionVersionSummary[],
): string | null {
  if (!isRolloutBindableVersion(candidate)) return null;
  const baseline = rolloutBaselineFor(candidate, versions);
  if (!baseline || typeof baseline.version !== 'number' || typeof candidate.version !== 'number') {
    return null;
  }

  const params = new URLSearchParams({
    decisionCode,
    baselineVersion: String(baseline.version),
    candidateVersion: String(candidate.version),
  });

  return `/p/decisionops_rollouts?${params.toString()}`;
}

export function DecisionDefinitionListPage({ api }: DecisionDefinitionListPageProps) {
  const [impactCode, setImpactCode] = useState<string | null>(null);
  const [impactAcknowledged, setImpactAcknowledged] = useState(false);
  const [transitioningPid, setTransitioningPid] = useState<string | null>(null);
  const [publishMessage, setPublishMessage] = useState<string | null>(null);
  const { data, isLoading, isError } = useQuery({
    queryKey: ['drt-definitions'],
    queryFn: () => api.listDefinitions(),
  });
  const canListVersions = typeof api.listVersions === 'function';
  const impactQuery = useQuery({
    queryKey: ['drt-definition-impact', impactCode],
    queryFn: () => api.getDecisionImpact(impactCode ?? ''),
    enabled: Boolean(impactCode),
  });
  const versionsQuery = useQuery({
    queryKey: ['drt-definition-versions', impactCode],
    queryFn: () => api.listVersions(impactCode ?? ''),
    enabled: Boolean(impactCode) && canListVersions,
  });

  useEffect(() => {
    setImpactAcknowledged(false);
    setTransitioningPid(null);
    setPublishMessage(null);
  }, [impactCode]);

  if (isLoading) return <div data-testid="ddl-loading">加载中…</div>;
  if (isError) return <div data-testid="ddl-error">加载失败</div>;

  const rows = asList(data);
  if (rows.length === 0)
    return (
      <div className="decisionops-empty" data-testid="ddl-empty">
        暂无决策定义
      </div>
    );

  const impactBlocking = Boolean(impactQuery.data?.risk?.blocking);
  const versions = versionsQuery.data ?? [];
  const publishableVersions = versions.filter((version) => version.status === 'VALIDATED');
  const deprecatableVersions = versions.filter((version) => version.status === 'PUBLISHED');
  const retirableVersions = versions.filter((version) => version.status === 'DEPRECATED');
  const deletableVersions = versions.filter((version) =>
    ['DRAFT', 'VALIDATED', 'PENDING_APPROVAL', 'REJECTED'].includes(String(version.status)),
  );
  const hasVersionActions =
    publishableVersions.length > 0 ||
    deprecatableVersions.length > 0 ||
    retirableVersions.length > 0 ||
    deletableVersions.length > 0;
  const transitionDisabled = (pid: string) =>
    transitioningPid === pid || (impactBlocking && !impactAcknowledged);

  const publishVersion = async (pid: string) => {
    setTransitioningPid(pid);
    setPublishMessage('发布中…');
    try {
      await api.publishVersion(pid, { impactAcknowledged: impactAcknowledged || !impactBlocking });
      setPublishMessage('发布成功');
      await versionsQuery.refetch();
      await impactQuery.refetch();
    } catch (error) {
      setPublishMessage(errorMessage(error));
    } finally {
      setTransitioningPid(null);
    }
  };

  const deprecateVersion = async (pid: string) => {
    setTransitioningPid(pid);
    setPublishMessage('废弃中…');
    try {
      await api.deprecateVersion(pid, {
        impactAcknowledged: impactAcknowledged || !impactBlocking,
        note: 'DecisionOps impact acknowledged in F5 drawer',
      });
      setPublishMessage('废弃成功');
      await versionsQuery.refetch();
      await impactQuery.refetch();
    } catch (error) {
      setPublishMessage(errorMessage(error));
    } finally {
      setTransitioningPid(null);
    }
  };

  const retireVersion = async (pid: string) => {
    setTransitioningPid(pid);
    setPublishMessage('退役中…');
    try {
      await api.retireVersion(pid, {
        impactAcknowledged: impactAcknowledged || !impactBlocking,
        note: 'DecisionOps impact acknowledged in F5 drawer',
      });
      setPublishMessage('退役成功');
      await versionsQuery.refetch();
      await impactQuery.refetch();
    } catch (error) {
      setPublishMessage(errorMessage(error));
    } finally {
      setTransitioningPid(null);
    }
  };

  const deleteVersion = async (pid: string) => {
    setTransitioningPid(pid);
    setPublishMessage('删除草稿中…');
    try {
      await api.deleteVersion(pid);
      setPublishMessage('删除草稿成功');
      await versionsQuery.refetch();
      await impactQuery.refetch();
    } catch (error) {
      setPublishMessage(errorMessage(error));
    } finally {
      setTransitioningPid(null);
    }
  };

  const renderStartRolloutLink = (version: DecisionVersionSummary) => {
    if (!impactCode) return null;
    const href = rolloutStartHref(impactCode, version, versions);
    if (!href) return null;

    return (
      <a
        className="decisionops-inline-link"
        data-testid={`ddl-start-rollout-${version.pid}`}
        href={href}
      >
        开始灰度
      </a>
    );
  };

  return (
    <section className="decisionops-list-page" data-testid="decision-definition-list">
      <div className="decisionops-table-frame">
        <table className="decisionops-table ddl-table">
          <thead>
            <tr>
              <th>决策编码</th>
              <th>名称</th>
              <th>范围</th>
              <th>所属模块</th>
              <th>启用</th>
              <th>影响</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((d) => (
              <tr key={d.decisionCode} data-testid={`ddl-row-${d.decisionCode}`}>
                <td className="mono decisionops-code" title={d.decisionCode}>
                  {d.decisionCode}
                </td>
                <td className="decisionops-main-cell">{d.decisionName ?? '—'}</td>
                <td>
                  <span className="decisionops-badge is-neutral">{d.scopeType ?? '—'}</span>
                </td>
                <td className="decisionops-muted-cell">{d.ownerModule ?? '—'}</td>
                <td>
                  <span
                    className={`decisionops-badge ${d.enabled === false ? 'is-danger' : 'is-success'}`}
                  >
                    {definitionState(d.enabled)}
                  </span>
                </td>
                <td>
                  <button
                    type="button"
                    data-testid={`ddl-impact-${d.decisionCode}`}
                    onClick={() => setImpactCode(d.decisionCode)}
                  >
                    影响
                  </button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      {impactCode && (
        <aside
          className="ddl-impact-drawer"
          role="dialog"
          aria-label="决策影响"
          data-testid="ddl-impact-drawer"
        >
          <div className="drawer-head">
            <h4>决策影响</h4>
            <button
              type="button"
              data-testid="ddl-impact-close"
              onClick={() => setImpactCode(null)}
            >
              关闭
            </button>
          </div>
          {impactQuery.isLoading && <div data-testid="ddl-impact-loading">加载影响分析…</div>}
          {impactQuery.isError && <div data-testid="ddl-impact-error">影响分析加载失败</div>}
          {impactQuery.data && <ImpactGraphPanel impact={impactQuery.data} />}
          {canListVersions && (
            <section className="ddl-publish-guard" data-testid="ddl-publish-guard">
              <h4>版本生命周期</h4>
              {versionsQuery.isLoading && <div data-testid="ddl-versions-loading">加载版本…</div>}
              {versionsQuery.isError && <div data-testid="ddl-versions-error">版本加载失败</div>}
              {impactBlocking && (
                <label className="ddl-impact-ack">
                  <input
                    type="checkbox"
                    data-testid="ddl-impact-ack"
                    checked={impactAcknowledged}
                    onChange={(event) => setImpactAcknowledged(event.currentTarget.checked)}
                  />
                  已确认影响面
                </label>
              )}
              {!versionsQuery.isLoading && !hasVersionActions && (
                <div data-testid="ddl-no-publishable-versions">暂无可执行版本动作</div>
              )}
              {hasVersionActions && (
                <ul className="ddl-version-actions" data-testid="ddl-version-actions">
                  {publishableVersions.map((version) => (
                    <li key={version.pid}>
                      <span>v{version.version ?? '-'}</span>
                      <button
                        type="button"
                        data-testid={`ddl-publish-${version.pid}`}
                        disabled={transitionDisabled(version.pid)}
                        onClick={() => publishVersion(version.pid)}
                      >
                        发布
                      </button>
                    </li>
                  ))}
                  {deprecatableVersions.map((version) => (
                    <li key={version.pid}>
                      <span>v{version.version ?? '-'}</span>
                      <button
                        type="button"
                        data-testid={`ddl-deprecate-${version.pid}`}
                        disabled={transitionDisabled(version.pid)}
                        onClick={() => deprecateVersion(version.pid)}
                      >
                        废弃
                      </button>
                      {renderStartRolloutLink(version)}
                    </li>
                  ))}
                  {retirableVersions.map((version) => (
                    <li key={version.pid}>
                      <span>v{version.version ?? '-'}</span>
                      <button
                        type="button"
                        data-testid={`ddl-retire-${version.pid}`}
                        disabled={transitionDisabled(version.pid)}
                        onClick={() => retireVersion(version.pid)}
                      >
                        退役
                      </button>
                      {renderStartRolloutLink(version)}
                    </li>
                  ))}
                  {deletableVersions.map((version) => (
                    <li key={version.pid}>
                      <span>v{version.version ?? '-'}</span>
                      <button
                        type="button"
                        data-testid={`ddl-delete-${version.pid}`}
                        disabled={transitionDisabled(version.pid)}
                        onClick={() => deleteVersion(version.pid)}
                      >
                        删除草稿
                      </button>
                    </li>
                  ))}
                </ul>
              )}
              {publishMessage && <div data-testid="ddl-publish-message">{publishMessage}</div>}
            </section>
          )}
        </aside>
      )}
    </section>
  );
}

function errorMessage(error: unknown): string {
  if (error instanceof Error && error.message) {
    return error.message;
  }
  return '操作失败';
}

export default DecisionDefinitionListPage;
