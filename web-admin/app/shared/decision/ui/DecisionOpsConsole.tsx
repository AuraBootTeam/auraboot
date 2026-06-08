import { useState } from 'react';
import { DecisionDefinitionListPage } from './DecisionDefinitionListPage';
import { DecisionConditionDesigner } from './DecisionConditionDesigner';
import { ExecutionLogViewer, type ExecLogEntry } from './ExecutionLogViewer';
import { DataModelFieldViewer, type ModelField } from './DataModelFieldViewer';
import { PermissionMatrix, type RoleGrants } from './PermissionMatrix';
import { ConnectorListView, type Connector } from './ConnectorListView';
import { DecisionDashboard, type DashboardSummary, type ExceptionItem } from './DecisionDashboard';
import { type FieldOption } from './ConditionBuilder';
import { type TestSample } from './ConditionTestRunPanel';
import type { DecisionApi } from '../api/decisionApi';

/**
 * DecisionOps console assembly (mockup F1–F8, docs/1.md §22): a tabbed shell composing the console
 * surfaces — Dashboard / Definitions / Designer / Logs / Data Model / Permissions / Connectors — over
 * the typed API client. The data-driven surfaces receive their data via props (the host app supplies
 * it from API calls); Definitions self-fetches via react-query. This is the assembly that turns the
 * F-components into one navigable console; plugin-route registration + visual browser-golden are
 * documented follow-ons (need the full app shell + a dedicated stack).
 */

export type ConsoleTab = 'dashboard' | 'definitions' | 'designer' | 'logs' | 'model' | 'permissions' | 'connectors';

export interface DecisionOpsConsoleProps {
  api: DecisionApi;
  fields: FieldOption[];
  modelFields?: ModelField[];
  samples?: TestSample[];
  logs?: ExecLogEntry[];
  connectors?: Connector[];
  permissionGrants?: RoleGrants[];
  dashboard?: { summary: DashboardSummary; exceptions: ExceptionItem[] };
  initialTab?: ConsoleTab;
}

const TABS: { key: ConsoleTab; label: string }[] = [
  { key: 'dashboard', label: '概览' },
  { key: 'definitions', label: '决策定义' },
  { key: 'designer', label: '策略设计器' },
  { key: 'logs', label: '执行日志' },
  { key: 'model', label: '数据模型' },
  { key: 'permissions', label: '权限治理' },
  { key: 'connectors', label: '集成' },
];

const EMPTY_SUMMARY: DashboardSummary = {
  definitions: 0, policies: 0, evaluationsToday: 0, matched: 0, failed: 0, retrying: 0,
};

export function DecisionOpsConsole(props: DecisionOpsConsoleProps) {
  const { api, fields, modelFields = [], samples = [], logs = [], connectors = [],
    permissionGrants = [], dashboard, initialTab = 'dashboard' } = props;
  const [tab, setTab] = useState<ConsoleTab>(initialTab);

  return (
    <div data-testid="decisionops-console">
      <nav className="doc-tabs" role="tablist">
        {TABS.map((t) => (
          <button
            key={t.key}
            type="button"
            role="tab"
            data-testid={`doc-tab-${t.key}`}
            aria-selected={tab === t.key}
            onClick={() => setTab(t.key)}
          >{t.label}</button>
        ))}
      </nav>

      <div className="doc-panel" data-testid={`doc-panel-${tab}`}>
        {tab === 'dashboard' && (
          <DecisionDashboard summary={dashboard?.summary ?? EMPTY_SUMMARY} exceptions={dashboard?.exceptions ?? []} />
        )}
        {tab === 'definitions' && <DecisionDefinitionListPage api={api} />}
        {tab === 'designer' && <DecisionConditionDesigner api={api} fields={fields} samples={samples} />}
        {tab === 'logs' && <ExecutionLogViewer logs={logs} />}
        {tab === 'model' && <DataModelFieldViewer fields={modelFields} />}
        {tab === 'permissions' && <PermissionMatrix value={permissionGrants} readOnly />}
        {tab === 'connectors' && <ConnectorListView connectors={connectors} />}
      </div>
    </div>
  );
}

export default DecisionOpsConsole;
