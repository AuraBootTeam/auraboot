import { useQuery } from '@tanstack/react-query';
import type { DecisionApi } from '../api/decisionApi';

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

export function DecisionDefinitionListPage({ api }: DecisionDefinitionListPageProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['drt-definitions'],
    queryFn: () => api.listDefinitions(),
  });

  if (isLoading) return <div data-testid="ddl-loading">加载中…</div>;
  if (isError) return <div data-testid="ddl-error">加载失败</div>;

  const rows = asList(data);
  if (rows.length === 0) return <div data-testid="ddl-empty">暂无决策定义</div>;

  return (
    <div data-testid="decision-definition-list">
      <table className="ddl-table">
        <thead>
          <tr><th>决策编码</th><th>名称</th><th>范围</th><th>所属模块</th><th>启用</th></tr>
        </thead>
        <tbody>
          {rows.map((d) => (
            <tr key={d.decisionCode} data-testid={`ddl-row-${d.decisionCode}`}>
              <td className="mono">{d.decisionCode}</td>
              <td>{d.decisionName ?? '—'}</td>
              <td>{d.scopeType ?? '—'}</td>
              <td>{d.ownerModule ?? '—'}</td>
              <td>{d.enabled === false ? '停用' : '启用'}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default DecisionDefinitionListPage;
