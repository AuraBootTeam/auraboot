import { useI18n } from '~/contexts/I18nContext';
import NAV from './decisionOpsNav.i18n.json';
import { DecisionLoadError } from './DecisionLoadError';
import { useAuth } from '~/contexts/AuthContext';
import './decision-workbench.css';
import { useEffect, useMemo, useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { DecisionDefinitionListPage, type DefinitionSummary } from './DecisionDefinitionListPage';
import { DecisionTableEditor } from './DecisionTableEditor';
import { EventPolicyListPage } from './EventPolicyListPage';
import { EventPolicyDesignerWorkflow } from './EventPolicyDesignerWorkflow';
import { type ExecLogEntry } from './ExecutionLogViewer';
import { DataModelFieldViewer, type ModelField } from './DataModelFieldViewer';
import { PermissionMatrix, type RoleGrants } from './PermissionMatrix';
import { ConnectorListView, type Connector } from './ConnectorListView';
import { DecisionDashboard, type DashboardSummary, type ExceptionItem } from './DecisionDashboard';
import { DecisionRolloutMonitor } from './DecisionRolloutMonitor';
import { StrategyStudioWorkbench } from './StrategyStudioWorkbench';
import { ExecutionLogTraceBlock } from '~/ui/smart/decision/ExecutionLogTraceBlock';
import { type FieldOption } from './ConditionBuilder';
import { type TestSample } from './ConditionTestRunPanel';
import { useDefaultTestSamples } from './defaultTestSamples';
import { factCatalogToFieldOptions, mergeFieldOptions, modelFieldsToFieldOptions } from './factCatalogAdapter';
import { useSmartText } from '~/utils/i18n';
import type { DecisionTable } from '../table/decisionTable';
import type {
  ConditionFragment,
  DecisionApi,
  DecisionTableAnalysis,
  DecisionTableDmnXmlResult,
  EventPolicySummary,
} from '../api/decisionApi';
import { downloadDmnXml } from './dmnDownload';

/**
 * DecisionOps console assembly (mockup F1–F8, docs/1.md §22): a tabbed shell composing the console
 * surfaces — Dashboard / Definitions / Designer / Logs / Data Model / Permissions / Connectors — over
 * the typed API client. The data-driven surfaces receive their data via props (the host app supplies
 * it from API calls); Definitions self-fetches via react-query. This is the assembly that turns the
 * F-components into one navigable console; plugin-route registration + visual browser-golden are
 * documented follow-ons (need the full app shell + a dedicated stack).
 */

export type ConsoleTab =
  | 'studio'
  | 'dashboard'
  | 'policies'
  | 'definitions'
  | 'designer'
  | 'tables'
  | 'rollouts'
  | 'logs'
  | 'model'
  | 'permissions'
  | 'connectors';

export interface DecisionOpsConsoleProps {
  api: DecisionApi;
  fields?: FieldOption[];
  modelFields?: ModelField[];
  samples?: TestSample[];
  logs?: ExecLogEntry[];
  connectors?: Connector[];
  permissionGrants?: RoleGrants[];
  dashboard?: { summary: DashboardSummary; exceptions: ExceptionItem[] };
  initialTab?: ConsoleTab;
  initialDecisionCode?: string;
  initialPolicyCode?: string;
  onTabChange?: (tab: ConsoleTab, selection?: {decisionCode?: string; policyCode?: string}) => void;
}

const TABS: { key: ConsoleTab; label: string; description: string }[] = [
  { key: 'studio', label: '规则工作区', description: '判断逻辑、试算与发布' },
  { key: 'dashboard', label: '概览', description: '运行态总览' },
  { key: 'policies', label: '事件策略', description: '事件触发策略' },
  { key: 'definitions', label: '业务规则', description: '业务用途、判断逻辑与处理结果' },
  { key: 'designer', label: '事件策略工作区', description: '触发条件与后续动作' },
  { key: 'tables', label: 'DMN 互操作', description: '专业导入、导出与互操作验证' },
  { key: 'rollouts', label: '发布治理', description: '灰度与回滚' },
  { key: 'logs', label: '执行日志', description: 'Trace 查询' },
  { key: 'model', label: '数据模型', description: '字段引用' },
  { key: 'permissions', label: '权限治理', description: '角色能力' },
  { key: 'connectors', label: '集成', description: '外部连接' },
];

const EMPTY_SUMMARY: DashboardSummary = {
  definitions: 0,
  policies: 0,
  evaluationsToday: 0,
  matched: 0,
  failed: 0,
  retrying: 0,
};

const DEFAULT_TABLE: DecisionTable = {
  hitPolicy: 'FIRST',
  inputs: [
    { id: 'amount', label: '金额', scope: 'record', path: 'data.amount', dataType: 'decimal' },
    { id: 'priority', label: '优先级', scope: 'record', path: 'data.priority', dataType: 'enum' },
  ],
  outputs: [
    {
      id: 'route',
      label: '路由',
      dataType: 'string',
      allowedValues: ['director', 'manager', 'fallback'],
    },
  ],
  rules: [],
};

function asDefinitionList(raw: unknown): DefinitionSummary[] {
  if (Array.isArray(raw)) return raw as DefinitionSummary[];
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    if (Array.isArray(o.records)) return o.records as DefinitionSummary[];
    if (Array.isArray(o.data)) return o.data as DefinitionSummary[];
  }
  return [];
}

function asConditionFragmentList(raw: unknown): ConditionFragment[] {
  if (Array.isArray(raw)) return raw as ConditionFragment[];
  if (raw && typeof raw === 'object') {
    const o = raw as Record<string, unknown>;
    if (Array.isArray(o.records)) return o.records as ConditionFragment[];
    if (Array.isArray(o.data)) return o.data as ConditionFragment[];
  }
  return [];
}

export function DecisionOpsConsole(props: DecisionOpsConsoleProps) {
  const { hasPermission } = useAuth();
  const {
    api,
    fields,
    modelFields,
    samples: hostSamples = [],
    connectors,
    permissionGrants,
    dashboard,
    initialTab = 'studio',
  } = props;
  const st = useSmartText();
  const {locale}=useI18n();
  const tabs = TABS.map(item => ({...item, ...NAV[item.key][locale.startsWith('zh')?'zh-CN':'en']}));
  const [selectedDecisionCode, setSelectedDecisionCode] = useState(props.initialDecisionCode ?? '');
  useEffect(() => { if (props.initialDecisionCode != null) setSelectedDecisionCode(props.initialDecisionCode); }, [props.initialDecisionCode]);
  const [tab, setTab] = useState<ConsoleTab>(initialTab);
  useEffect(() => setTab(initialTab), [initialTab]);
  const selectTab = (next: ConsoleTab, selection?: {decisionCode?: string;policyCode?: string}) => { setTab(next); props.onTabChange?.(next,selection); };
  const [selectedPolicy, setSelectedPolicy] = useState<EventPolicySummary | null>(null);
  const selectedPolicyQuery = useQuery({
    queryKey:['event-policies'],queryFn:()=>api.listPolicies({}),
    enabled:Boolean(props.initialPolicyCode) && tab==='designer',
  });
  useEffect(() => {
    if (!props.initialPolicyCode || !selectedPolicyQuery.data) return;
    const data = selectedPolicyQuery.data as unknown;
    const rows = Array.isArray(data) ? data : (data as {records?: EventPolicySummary[]}).records ?? [];
    setSelectedPolicy(rows.find((item: EventPolicySummary)=>item.policyCode===props.initialPolicyCode) ?? null);
  },[props.initialPolicyCode,selectedPolicyQuery.data]);
  const [tableDraft, setTableDraft] = useState<DecisionTable>(DEFAULT_TABLE);
  const [tableAnalysis, setTableAnalysis] = useState<DecisionTableAnalysis | null>(null);
  const [tableAnalyzing, setTableAnalyzing] = useState(false);
  const [tableAnalysisError, setTableAnalysisError] = useState<string | null>(null);
  const [tableDmnXml, setTableDmnXml] = useState('');
  const [tableDmnBusy, setTableDmnBusy] = useState(false);
  const [tableDmnError, setTableDmnError] = useState<string | null>(null);
  const [tableDmnStatus, setTableDmnStatus] = useState<string | null>(null);
  const canLoadFactCatalog = typeof api.getFactCatalog === 'function';
  const dashboardQuery = useQuery({
    queryKey: ['decision-dashboard'],
    queryFn: () => api.getDashboard(),
    enabled: dashboard == null,
  });
  const dashboardData = dashboard ??
    dashboardQuery.data ?? { summary: EMPTY_SUMMARY, exceptions: [] };
  const definitionQuery = useQuery({
    queryKey: ['strategy-studio-definitions'],
    queryFn: () => api.listDefinitions(),
    enabled: tab === 'studio',
  });
  const conditionFragmentQuery = useQuery({
    queryKey: ['strategy-studio-condition-fragments'],
    queryFn: () => api.listConditionFragments({ page: 1, size: 50 }),
    enabled: tab === 'studio',
  });
  const factCatalogQuery = useQuery({
    queryKey: ['decision-fact-catalog'],
    queryFn: () => api.getFactCatalog(),
    enabled:
      (tab === 'studio' || tab === 'tables' || tab === 'designer') && canLoadFactCatalog,
  });
  const modelFieldQuery = useQuery({
    queryKey: ['decision-model-fields'],
    queryFn: () => api.getModelFields(),
    enabled: modelFields == null && (tab === 'model' || (tab === 'studio' && !canLoadFactCatalog)),
  });
  const modelFieldData = modelFields ?? modelFieldQuery.data ?? [];
  const strategyDecisions = asDefinitionList(definitionQuery.data)
    .filter((definition) => definition.decisionCode)
    .map((definition) => ({
      code: definition.decisionCode,
      name: definition.decisionName,
      scopeType: definition.scopeType,
    }));
  const factCatalogFields = factCatalogToFieldOptions(factCatalogQuery.data);
  const legacyStrategyFields = modelFieldsToFieldOptions(modelFieldData);
  const strategyFields =
    factCatalogFields.length > 0
      ? factCatalogFields
      : legacyStrategyFields.length > 0
        ? legacyStrategyFields
        : (fields ?? []);
  // The designer tab gets the unified fact catalog merged with the host-provided default
  // fields, so the field picker is not capped at the host's narrow initial set.
  const designerFields = useMemo(
    () =>
      mergeFieldOptions(
        factCatalogFields.length > 0 ? factCatalogFields : legacyStrategyFields,
        fields ?? [],
      ),
    [factCatalogFields, legacyStrategyFields, fields],
  );
  const generatedSamples = useDefaultTestSamples(selectedPolicy);
  const designerSamples = hostSamples.length > 0 ? hostSamples : generatedSamples;
  const connectorQuery = useQuery({
    queryKey: ['decision-connectors'],
    queryFn: () => api.listConnectors(),
    enabled: connectors == null && tab === 'connectors',
  });
  const connectorData = connectors ?? connectorQuery.data ?? [];
  const permissionQuery = useQuery({
    queryKey: ['decision-permission-matrix'],
    queryFn: () => api.getPermissionMatrix(),
    enabled: permissionGrants == null && tab === 'permissions',
  });
  const permissionGrantData = permissionGrants ?? permissionQuery.data?.roles ?? [];
  const activeTab = tabs.find((t) => t.key === tab) ?? tabs[0];
  const headerEyebrow = st('$i18n:decisionops.header.eyebrow', 'Strategy Studio');
  const definitionsLabel = st('$i18n:decisionops.header.definitions', 'Definitions');
  const policiesLabel = st('$i18n:decisionops.header.policies', 'Policies');
  const todayLabel = st('$i18n:decisionops.header.today', 'Today');
  const openWorkbenchLabel = st('$i18n:decisionops.header.openWorkbench', '进入工作区');

  const handleTableDraftChange = (next: DecisionTable) => {
    setTableDraft(next);
    setTableAnalysis(null);
    setTableAnalysisError(null);
    setTableDmnStatus(null);
    setTableDmnError(null);
  };

  const analyzeTableDraft = async () => {
    setTableAnalyzing(true);
    setTableAnalysisError(null);
    try {
      const result = await api.analyzeTable(tableDraft);
      setTableAnalysis(result);
    } catch (err) {
      const message = err instanceof Error ? err.message : '决策表分析失败';
      setTableAnalysis(null);
      setTableAnalysisError(message);
    } finally {
      setTableAnalyzing(false);
    }
  };

  const formatDmnError = (result: DecisionTableDmnXmlResult) => {
    const first = result.errors?.[0];
    return first ? `${first.code}: ${first.message ?? 'DMN XML 处理失败'}` : 'DMN XML 处理失败';
  };

  const applyDmnResult = (
    result: DecisionTableDmnXmlResult,
    status: string,
    updateModel: boolean,
  ) => {
    if (result.dmnXml !== undefined) {
      setTableDmnXml(result.dmnXml);
    }
    if (updateModel && result.model) {
      setTableDraft(result.model);
      setTableAnalysis(null);
      setTableAnalysisError(null);
    }
    if (!result.valid) {
      throw new Error(formatDmnError(result));
    }
    const warningCount = result.warnings?.length ?? 0;
    setTableDmnStatus(warningCount > 0 ? `${status} · warnings ${warningCount}` : status);
  };

  const exportTableDmn = async () => {
    setTableDmnBusy(true);
    setTableDmnError(null);
    try {
      const result = await api.exportTableDmn(tableDraft, 'decision_table');
      applyDmnResult(result, 'DMN XML 已导出', false);
      if (result.valid && result.dmnXml) {
        downloadDmnXml('decision_table', result.dmnXml);
      }
    } catch (err) {
      setTableDmnError(err instanceof Error ? err.message : 'DMN XML 导出失败');
      setTableDmnStatus(null);
    } finally {
      setTableDmnBusy(false);
    }
  };

  const importTableDmn = async () => {
    setTableDmnBusy(true);
    setTableDmnError(null);
    try {
      const result = await api.importTableDmn(tableDmnXml);
      applyDmnResult(result, 'DMN XML 已导入', true);
    } catch (err) {
      setTableDmnError(err instanceof Error ? err.message : 'DMN XML 导入失败');
      setTableDmnStatus(null);
    } finally {
      setTableDmnBusy(false);
    }
  };

  const roundTripTableDmn = async () => {
    setTableDmnBusy(true);
    setTableDmnError(null);
    try {
      const result = await api.roundTripTableDmn(tableDraft, 'decision_table');
      applyDmnResult(result, 'Round-trip 通过', true);
    } catch (err) {
      setTableDmnError(err instanceof Error ? err.message : 'DMN XML Round-trip 失败');
      setTableDmnStatus(null);
    } finally {
      setTableDmnBusy(false);
    }
  };

  return (
    <div className={`decisionops-shell ${tab === 'studio' ? 'decisionops-shell--studio' : ''}`} data-testid="decisionops-console">
      <header className="decisionops-page-header">
        <div>
          <p className="decisionops-eyebrow">{headerEyebrow}</p>
          <h1>规则中心</h1>
        </div>
        <div className="decisionops-header-meta">
          <span>
            {definitionsLabel} {dashboardData.summary.definitions}
          </span>
          <span>
            {policiesLabel} {dashboardData.summary.policies}
          </span>
          <span>
            {todayLabel} {dashboardData.summary.evaluationsToday}
          </span>
          <a className="decisionops-workbench-jump" href="?tab=studio" onClick={event => {event.preventDefault();selectTab('studio');}}>
            {openWorkbenchLabel}
          </a>
        </div>
      </header>

      <div className="decisionops-layout">
        <nav className="doc-tabs decisionops-nav" role="tablist" aria-label="Rule Center modules">
          {tabs.filter((item) => ['definitions', 'policies', 'logs'].includes(item.key)
            || item.key === tab).sort((a,b) => ['definitions','policies','logs'].indexOf(a.key)-['definitions','policies','logs'].indexOf(b.key)).map((t) => (
            <a
              key={t.key}
              href={`?tab=${t.key}`}
              role="tab"
              data-testid={`doc-tab-${t.key}`}
              aria-selected={tab === t.key}
              onClick={(event) => {
                event.preventDefault();
                selectTab(t.key);
                event.currentTarget.closest('nav')?.querySelector('details')?.removeAttribute('open');
              }}
            >
              <span className="decisionops-nav-label">{t.label}</span>
              <span className="decisionops-nav-desc">{t.description}</span>
            </a>
          ))}
          <details key={tab} className="decisionops-professional-entry">
            <summary>{st('$i18n:decisionops.professional', '专业配置')}</summary>
            <div>{tabs.filter(item => !['definitions', 'policies', 'logs'].includes(item.key) && item.key !== tab).map(item => (
              <button key={item.key} type="button" data-testid={`doc-tab-${item.key}`} onClick={event => {selectTab(item.key);event.currentTarget.closest('details')?.removeAttribute('open');}}>{item.label}</button>
            ))}</div>
          </details>
        </nav>

        <section className="decisionops-main" aria-label={activeTab.label}>
          <div className="decisionops-section-head">
            <div>
              <h2>{activeTab.label}</h2>
              <p>{activeTab.description}</p>
            </div>
          </div>

          <div className="doc-panel decisionops-content-card" data-testid={`doc-panel-${tab}`}>
            {tab === 'studio' && (
              <StrategyStudioWorkbench
                readOnly={!hasPermission('decision.definition.manage')}
                canPublish={hasPermission('decision.definition.publish')}
                canRun={hasPermission('decision.runtime.evaluate')}
                api={api}
                initialDecisionCode={selectedDecisionCode}
                onDecisionChange={code => {setSelectedDecisionCode(code);selectTab('studio',{decisionCode:code});}}
                fields={strategyFields}
                decisions={strategyDecisions}
                conditionFragments={asConditionFragmentList(conditionFragmentQuery.data)}
                conditionFragmentsLoading={conditionFragmentQuery.isLoading}
                conditionFragmentsError={conditionFragmentQuery.isError}
              />
            )}
            {tab === 'dashboard' && (
              <>
                {!dashboard && dashboardQuery.isLoading && (
                  <div className="decisionops-state" data-testid="dd-loading">
                    加载中...
                  </div>
                )}
                {!dashboard && dashboardQuery.isError && (
                  <DecisionLoadError testId="dd-error" error={dashboardQuery.error} onRetry={dashboardQuery.refetch} busy={dashboardQuery.isFetching} />
                )}
                {(!dashboardQuery.isLoading || dashboard) && !dashboardQuery.isError && (
                  <DecisionDashboard
                    summary={dashboardData.summary}
                    exceptions={dashboardData.exceptions}
                  />
                )}
              </>
            )}
            {tab === 'policies' && (
              <EventPolicyListPage
                readOnly={!hasPermission('decision.policy.manage')}
                api={api}
                onOpenDesigner={(policy) => {
                  setSelectedPolicy(policy);
                  selectTab('designer',{policyCode:policy.policyCode});
                }}
                onOpenLogs={() => selectTab('logs')}
              />
            )}
            {tab === 'definitions' && <DecisionDefinitionListPage api={api} onOpen={definition => {
              setSelectedDecisionCode(definition.decisionCode); selectTab('studio',{decisionCode:definition.decisionCode});
            }} />}
            {tab === 'designer' && (
              <EventPolicyDesignerWorkflow
                readOnly={!hasPermission('decision.policy.manage')}
                canPublish={hasPermission('decision.policy.publish')}
                canRun={hasPermission('decision.policy.run')}
                api={api}
                fields={designerFields}
                samples={designerSamples}
                selectedPolicy={selectedPolicy}
              />
            )}
            {tab === 'tables' && (
              <DecisionTableEditor
                value={tableDraft}
                onChange={handleTableDraftChange}
                analysis={tableAnalysis}
                analyzing={tableAnalyzing}
                analysisError={tableAnalysisError}
                onAnalyze={analyzeTableDraft}
                dmnXml={tableDmnXml}
                dmnBusy={tableDmnBusy}
                dmnError={tableDmnError}
                dmnStatus={tableDmnStatus}
                onDmnXmlChange={(xml) => {
                  setTableDmnXml(xml);
                  setTableDmnError(null);
                  setTableDmnStatus(null);
                }}
                onExportDmnXml={exportTableDmn}
                onImportDmnXml={importTableDmn}
                onRoundTripDmnXml={roundTripTableDmn}
                fieldOptions={strategyFields}
              />
            )}
            {tab === 'rollouts' && (
              <DecisionRolloutMonitor api={api} initialDecisionCode="complaint_sla_deadline" />
            )}
            {tab === 'logs' && (
              <ExecutionLogTraceBlock block={{ props: { mode: 'list', pageSize: 50, businessFirst: true } }} />
            )}
            {tab === 'model' && (
              <>
                {modelFields == null && modelFieldQuery.isLoading && (
                  <div className="decisionops-state" data-testid="dmv-loading">
                    加载中...
                  </div>
                )}
                {modelFields == null && modelFieldQuery.isError && (
                  <DecisionLoadError testId="dmv-error" error={modelFieldQuery.error} onRetry={modelFieldQuery.refetch} busy={modelFieldQuery.isFetching} />
                )}
                {(!modelFieldQuery.isLoading || modelFields != null) &&
                  !modelFieldQuery.isError && (
                    <DataModelFieldViewer fields={modelFieldData} api={api} />
                  )}
              </>
            )}
            {tab === 'permissions' && (
              <>
                {permissionGrants == null && permissionQuery.isLoading && (
                  <div className="decisionops-state" data-testid="pm-loading">
                    加载中...
                  </div>
                )}
                {permissionGrants == null && permissionQuery.isError && (
                  <DecisionLoadError testId="pm-error" error={permissionQuery.error} onRetry={permissionQuery.refetch} busy={permissionQuery.isFetching} />
                )}
                {(!permissionQuery.isLoading || permissionGrants != null) &&
                  !permissionQuery.isError && (
                    <PermissionMatrix value={permissionGrantData} readOnly />
                  )}
              </>
            )}
            {tab === 'connectors' && (
              <>
                {connectors == null && connectorQuery.isLoading && (
                  <div className="decisionops-state" data-testid="cl-loading">
                    加载中...
                  </div>
                )}
                {connectors == null && connectorQuery.isError && (
                  <DecisionLoadError testId="cl-error" error={connectorQuery.error} onRetry={connectorQuery.refetch} busy={connectorQuery.isFetching} />
                )}
                {(!connectorQuery.isLoading || connectors != null) && !connectorQuery.isError && (
                  <ConnectorListView connectors={connectorData} />
                )}
              </>
            )}
          </div>
        </section>
      </div>
    </div>
  );
}

export default DecisionOpsConsole;
