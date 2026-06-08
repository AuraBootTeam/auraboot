import { useMemo, useState } from 'react';

/**
 * DecisionOps connector / integration registry view (mockup 集成 / F8, docs/2.md §7 CALL_CONNECTOR
 * / WEBHOOK actions): the external endpoints EventPolicy actions can target — type, endpoint, auth,
 * health, enabled. Filter by type + health. Read-only registry (CRUD is a governance slice); the
 * health column surfaces degraded targets before a policy run fails on them.
 */

export type ConnectorType = 'WEBHOOK' | 'REST' | 'KAFKA' | 'MQ' | 'SCRIPT';
export type ConnectorHealth = 'HEALTHY' | 'DEGRADED' | 'DOWN' | 'UNKNOWN';

export interface Connector {
  code: string;
  name: string;
  type: ConnectorType;
  endpoint?: string;
  authMode?: string;
  health: ConnectorHealth;
  enabled: boolean;
}

export interface ConnectorListViewProps {
  connectors: Connector[];
  initialHealth?: ConnectorHealth | 'ALL';
}

const HEALTH_OPTIONS: (ConnectorHealth | 'ALL')[] = ['ALL', 'HEALTHY', 'DEGRADED', 'DOWN', 'UNKNOWN'];

export function ConnectorListView({ connectors, initialHealth = 'ALL' }: ConnectorListViewProps) {
  const [health, setHealth] = useState<ConnectorHealth | 'ALL'>(initialHealth);
  const [query, setQuery] = useState('');

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return connectors
      .filter((c) => health === 'ALL' || c.health === health)
      .filter((c) => !q || c.code.toLowerCase().includes(q) || c.name.toLowerCase().includes(q));
  }, [connectors, health, query]);

  const unhealthy = connectors.filter((c) => c.enabled && (c.health === 'DOWN' || c.health === 'DEGRADED')).length;

  return (
    <div data-testid="connector-list">
      <div className="cl-toolbar">
        <select aria-label="health-filter" value={health} onChange={(e) => setHealth(e.target.value as ConnectorHealth | 'ALL')}>
          {HEALTH_OPTIONS.map((h) => <option key={h} value={h}>{h}</option>)}
        </select>
        <input aria-label="connector-search" placeholder="搜索连接器 code / 名称" value={query}
          onChange={(e) => setQuery(e.target.value)} />
        <span data-testid="cl-count">{filtered.length}</span>
        {unhealthy > 0 && <span data-testid="cl-unhealthy" className="cl-warn">{unhealthy} 个异常</span>}
      </div>

      {filtered.length === 0 ? (
        <div data-testid="cl-empty">无匹配连接器</div>
      ) : (
        <table className="cl-table">
          <thead>
            <tr><th>编码</th><th>名称</th><th>类型</th><th>端点</th><th>认证</th><th>健康</th><th>启用</th></tr>
          </thead>
          <tbody>
            {filtered.map((c) => (
              <tr key={c.code} data-testid={`cl-row-${c.code}`} data-health={c.health}>
                <td className="mono">{c.code}</td>
                <td>{c.name}</td>
                <td>{c.type}</td>
                <td className="mono">{c.endpoint ?? '—'}</td>
                <td>{c.authMode ?? '—'}</td>
                <td><span className={`cl-health cl-${c.health}`}>{c.health}</span></td>
                <td>{c.enabled ? '启用' : '停用'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default ConnectorListView;
