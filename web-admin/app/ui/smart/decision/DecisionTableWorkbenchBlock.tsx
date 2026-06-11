import { useCallback, useMemo, useState } from 'react';
import { useLocation } from 'react-router';
import { getApiService } from '~/shared/services/ApiService';
import {
  createDecisionApi,
  type DecisionApi,
  type DecisionResult,
  type DecisionTableAnalysis,
  type DecisionTableDmnXmlResult,
  type DecisionVersionSummary,
  type HttpClient,
  type ValidateResult,
} from '~/shared/decision/api/decisionApi';
import { type DecisionTable, validateTable } from '~/shared/decision/table/decisionTable';
import { DecisionTableEditor } from '~/shared/decision/ui/DecisionTableEditor';

interface DecisionTableWorkbenchBlockProps {
  block?: {
    props?: DecisionTableWorkbenchProps;
    mode?: DecisionTableWorkbenchProps['mode'];
  };
  runtime?: {
    getContext?: () => {
      record?: Record<string, unknown>;
      row?: Record<string, unknown>;
      data?: Record<string, unknown>;
    };
  };
}

interface DecisionTableWorkbenchProps {
  mode?: 'workbench';
  initialDecisionCode?: string;
  initialDecisionName?: string;
  initialVersionTag?: string;
  initialContextJson?: string;
}

type Diagnostic = {
  severity: 'ERROR' | 'WARNING';
  message: string;
};

const DEFAULT_TABLE: DecisionTable = {
  hitPolicy: 'FIRST',
  inputs: [
    {
      id: 'amount',
      label: 'Amount',
      scope: 'record',
      path: 'data.amount',
      dataType: 'decimal',
    },
  ],
  outputs: [{ id: 'route', label: 'Route', dataType: 'string' }],
  rules: [
    {
      ruleId: 'high-value',
      priority: 10,
      when: { amount: { operator: 'EQ', value: '', feel: '> 10000' } },
      then: { route: 'director' },
    },
    {
      ruleId: 'default-route',
      priority: 20,
      when: { amount: { operator: 'EQ', value: '', feel: '-' } },
      then: { route: 'manager' },
    },
  ],
};

const DEFAULT_CONTEXT = JSON.stringify(
  {
    record: {
      data: {
        amount: 20000,
      },
    },
  },
  null,
  2,
);

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

function recordFromRuntime(runtime: DecisionTableWorkbenchBlockProps['runtime']) {
  const context = runtime?.getContext?.();
  return context?.record ?? context?.row ?? context?.data ?? {};
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function initialFromLocation(
  search: string,
  props: DecisionTableWorkbenchProps,
  runtime: DecisionTableWorkbenchBlockProps['runtime'],
) {
  const params = new URLSearchParams(search);
  const record = recordFromRuntime(runtime);
  return {
    decisionCode:
      stringValue(record.decisionCode) ??
      stringValue(record.decision_code) ??
      params.get('decisionCode') ??
      props.initialDecisionCode ??
      'visual_dmn_table',
    decisionName:
      stringValue(record.decisionName) ??
      stringValue(record.decision_name) ??
      params.get('decisionName') ??
      props.initialDecisionName ??
      'Visual DMN Decision Table',
    versionTag:
      params.get('versionTag') ??
      props.initialVersionTag ??
      `visual-draft-${new Date().toISOString().slice(0, 10)}`,
    contextJson: props.initialContextJson ?? DEFAULT_CONTEXT,
  };
}

function errorMessage(error: unknown, fallback = '操作失败'): string {
  if (error instanceof Error && error.message) return error.message;
  return fallback;
}

function parseContext(text: string): Record<string, Record<string, unknown>> {
  const parsed = JSON.parse(text) as unknown;
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    throw new Error('测试上下文必须是 JSON object');
  }
  return parsed as Record<string, Record<string, unknown>>;
}

function supportedFeelSyntax(feel?: string): boolean {
  const text = feel?.trim() ?? '';
  if (!text || text === '-') return true;
  const lower = text.toLowerCase();
  if (lower === 'null' || lower === 'not(null)' || lower === 'not null') return true;
  if (/^\[\s*(.+?)\s*\.\.\s*(.+?)\s*]$/.test(text)) return true;
  const comparison = /^(>=|<=|>|<|!=|=)\s*(.+)$/.exec(text);
  if (comparison) return !looksLikeFeelExpression(comparison[2]);
  if (text.includes(',')) {
    return text.split(',').every((part) => !looksLikeFeelExpression(part));
  }
  return !looksLikeFeelExpression(text);
}

function looksLikeFeelExpression(text: string): boolean {
  const value = text.trim();
  const lower = value.toLowerCase();
  return value.includes('(')
    || value.includes(')')
    || /\b(if|then|else|and|or|between|date|time|duration|not)\b/.test(lower);
}

function localDiagnostics(table: DecisionTable): Diagnostic[] {
  const diagnostics: Diagnostic[] = validateTable(table).map((message) => ({
    severity: 'ERROR',
    message,
  }));
  const inputs = new Map(table.inputs.map((input) => [input.id, input]));
  table.rules.forEach((rule) => {
    Object.entries(rule.when).forEach(([inputId, cell]) => {
      if (!cell.feel?.trim() || supportedFeelSyntax(cell.feel)) return;
      const input = inputs.get(inputId);
      diagnostics.push({
        severity: 'WARNING',
        message: `${rule.ruleId} / ${input?.label ?? inputId}: FEEL 表达式超出平台 unary-test 子集，后端会返回 DMN_UNSUPPORTED_FEEL`,
      });
    });
  });
  return diagnostics;
}

function dmnResultError(result: DecisionTableDmnXmlResult): string {
  const issue = [...(result.errors ?? []), ...(result.warnings ?? [])][0];
  return issue ? `${issue.code}: ${issue.message ?? 'DMN XML 处理失败'}` : 'DMN XML 处理失败';
}

function sanitizeFilenamePart(value: string): string {
  const normalized = value.trim().replace(/[^a-zA-Z0-9._-]+/g, '_').replace(/^_+|_+$/g, '');
  return normalized || 'decision_table';
}

function downloadTextFile(filename: string, content: string, type: string): void {
  if (typeof window === 'undefined' || typeof document === 'undefined') return;
  const blob = new Blob([content], { type });
  const url = window.URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.style.display = 'none';
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.URL.revokeObjectURL(url);
}

function displayJson(value: unknown): string {
  return JSON.stringify(value ?? {}, null, 2);
}

function versionLabel(version?: DecisionVersionSummary | null): string {
  if (!version) return '-';
  const prefix = typeof version.version === 'number' ? `v${version.version}` : version.pid;
  return version.versionTag ? `${prefix} · ${version.versionTag}` : prefix;
}

export function DecisionTableWorkbenchBlock({
  block,
  runtime,
}: DecisionTableWorkbenchBlockProps) {
  const location = useLocation();
  const api = useMemo(() => createApi(), []);
  const props = block?.props ?? {};
  const initial = useMemo(
    () => initialFromLocation(location.search, props, runtime),
    [
      location.search,
      props.initialContextJson,
      props.initialDecisionCode,
      props.initialDecisionName,
      props.initialVersionTag,
      runtime,
    ],
  );
  const [decisionCode, setDecisionCode] = useState(initial.decisionCode);
  const [decisionName, setDecisionName] = useState(initial.decisionName);
  const [versionTag, setVersionTag] = useState(initial.versionTag);
  const [contextJson, setContextJson] = useState(initial.contextJson);
  const [table, setTable] = useState<DecisionTable>(DEFAULT_TABLE);
  const [analysis, setAnalysis] = useState<DecisionTableAnalysis | null>(null);
  const [analysisError, setAnalysisError] = useState<string | null>(null);
  const [analyzing, setAnalyzing] = useState(false);
  const [dmnXml, setDmnXml] = useState('');
  const [dmnBusy, setDmnBusy] = useState(false);
  const [dmnError, setDmnError] = useState<string | null>(null);
  const [dmnStatus, setDmnStatus] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [running, setRunning] = useState(false);
  const [workflowMessage, setWorkflowMessage] = useState('');
  const [workflowError, setWorkflowError] = useState('');
  const [lastVersion, setLastVersion] = useState<DecisionVersionSummary | null>(null);
  const [lastValidation, setLastValidation] = useState<ValidateResult | null>(null);
  const [testResult, setTestResult] = useState<DecisionResult | null>(null);
  const diagnostics = useMemo(() => localDiagnostics(table), [table]);
  const blockingDiagnostics = diagnostics.filter((item) => item.severity === 'ERROR');

  const ensureDefinition = useCallback(async () => {
    const code = decisionCode.trim();
    const existing = await api.getDefinition(code);
    if (existing) return;
    await api.createDefinition({
      decisionCode: code,
      decisionName: decisionName.trim() || code,
      scopeType: 'GOVERNANCE',
      ownerModule: 'decision',
    });
  }, [api, decisionCode, decisionName]);

  const refreshSavedVersion = useCallback(
    async (pid: string) => {
      const versions = await api.listVersions(decisionCode.trim());
      const versionList = Array.isArray(versions) ? versions : [];
      const saved = versionList.find((version) => version.pid === pid) ?? null;
      setLastVersion(saved);
      return saved;
    },
    [api, decisionCode],
  );

  const handleAnalyze = useCallback(async () => {
    setAnalyzing(true);
    setAnalysisError(null);
    try {
      const result = await api.analyzeTable(table, decisionCode.trim());
      setAnalysis(result);
    } catch (error) {
      setAnalysisError(errorMessage(error, '决策表分析失败'));
    } finally {
      setAnalyzing(false);
    }
  }, [api, decisionCode, table]);

  const handleSaveDraft = useCallback(async () => {
    setWorkflowError('');
    setWorkflowMessage('');
    if (!decisionCode.trim()) {
      setWorkflowError('决策编码不能为空');
      return;
    }
    if (blockingDiagnostics.length > 0) {
      setWorkflowError(`本地结构校验失败：${blockingDiagnostics[0].message}`);
      return;
    }
    setSaving(true);
    try {
      await ensureDefinition();
      const draft = await api.createDraftVersion(decisionCode.trim(), {
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        versionTag: versionTag.trim() || undefined,
        contentJson: table,
      });
      if (!draft?.pid) {
        throw new Error('后端没有返回版本 PID');
      }
      const validation = await api.validateVersion(draft.pid);
      setLastValidation(validation);
      const saved = await refreshSavedVersion(draft.pid);
      setWorkflowMessage(
        validation.valid
          ? `草稿已保存并校验通过：${versionLabel(saved ?? draft)}`
          : `草稿已保存但校验失败：${validation.errors?.[0]?.message ?? '检查版本校验结果'}`,
      );
    } catch (error) {
      setWorkflowError(errorMessage(error, '草稿保存失败'));
    } finally {
      setSaving(false);
    }
  }, [
    api,
    blockingDiagnostics,
    decisionCode,
    ensureDefinition,
    refreshSavedVersion,
    table,
    versionTag,
  ]);

  const handleTestRun = useCallback(async () => {
    setWorkflowError('');
    setWorkflowMessage('');
    setRunning(true);
    try {
      const context = parseContext(contextJson);
      const result = await api.testRun({
        kind: 'DECISION_TABLE',
        runtimeAdapter: 'PLATFORM_DECISION_TABLE',
        contentJson: table,
        context,
      });
      setTestResult(result);
      setWorkflowMessage(`Test-run 完成：${result.status}`);
    } catch (error) {
      setWorkflowError(errorMessage(error, 'Test-run 失败'));
    } finally {
      setRunning(false);
    }
  }, [api, contextJson, table]);

  const applyDmnResult = useCallback((result: DecisionTableDmnXmlResult, status: string) => {
    if (!result.valid) {
      setDmnError(dmnResultError(result));
      return;
    }
    if (result.dmnXml) setDmnXml(result.dmnXml);
    if (result.model) setTable(result.model);
    setDmnStatus(status);
    setDmnError(null);
  }, []);

  const handleExportDmn = useCallback(async () => {
    setDmnBusy(true);
    setDmnError(null);
    try {
      const result = await api.exportTableDmn(
        table,
        decisionName.trim() || decisionCode.trim(),
        decisionCode.trim(),
      );
      applyDmnResult(result, 'DMN XML 已导出');
      if (result.valid && result.dmnXml) {
        downloadTextFile(
          `${sanitizeFilenamePart(decisionCode)}.dmn.xml`,
          result.dmnXml,
          'application/xml;charset=utf-8',
        );
      }
    } catch (error) {
      setDmnError(errorMessage(error, 'DMN XML 导出失败'));
    } finally {
      setDmnBusy(false);
    }
  }, [api, applyDmnResult, decisionCode, decisionName, table]);

  const handleImportDmn = useCallback(async () => {
    setDmnBusy(true);
    setDmnError(null);
    try {
      applyDmnResult(await api.importTableDmn(dmnXml), 'DMN XML 已导入');
    } catch (error) {
      setDmnError(errorMessage(error, 'DMN XML 导入失败'));
    } finally {
      setDmnBusy(false);
    }
  }, [api, applyDmnResult, dmnXml]);

  const handleRoundTripDmn = useCallback(async () => {
    setDmnBusy(true);
    setDmnError(null);
    try {
      applyDmnResult(
        await api.roundTripTableDmn(table, decisionName.trim() || decisionCode.trim(), decisionCode.trim()),
        'DMN XML Round-trip 通过',
      );
    } catch (error) {
      setDmnError(errorMessage(error, 'DMN XML Round-trip 失败'));
    } finally {
      setDmnBusy(false);
    }
  }, [api, applyDmnResult, decisionCode, decisionName, table]);

  return (
    <div
      className="decisionops-shell decision-table-workbench-block"
      data-testid="decision-table-workbench-block"
    >
      <section className="dtw-header" aria-label="decision-table-workbench">
        <div className="dtw-identity">
          <label>
            决策编码
            <input
              aria-label="decision-table-code"
              data-testid="dtw-decision-code"
              value={decisionCode}
              onChange={(event) => setDecisionCode(event.target.value)}
            />
          </label>
          <label>
            名称
            <input
              aria-label="decision-table-name"
              value={decisionName}
              onChange={(event) => setDecisionName(event.target.value)}
            />
          </label>
          <label>
            版本标签
            <input
              aria-label="decision-table-version-tag"
              value={versionTag}
              onChange={(event) => setVersionTag(event.target.value)}
            />
          </label>
        </div>
        <div className="dtw-actions">
          <button
            type="button"
            data-testid="dtw-save-draft"
            disabled={saving}
            onClick={() => { void handleSaveDraft(); }}
          >
            {saving ? '保存中...' : '保存草稿并校验'}
          </button>
          <button
            type="button"
            data-testid="dtw-test-run"
            disabled={running}
            onClick={() => { void handleTestRun(); }}
          >
            {running ? '执行中...' : 'Test-run'}
          </button>
        </div>
        <div className="dtw-version-card" data-testid="dtw-version-card">
          <span>最近版本</span>
          <strong>{versionLabel(lastVersion)}</strong>
          <small>{lastVersion?.status ?? (lastValidation?.valid ? 'VALIDATED' : '-')}</small>
        </div>
      </section>

      {(workflowMessage || workflowError) && (
        <div
          className={workflowError ? 'dtw-message is-error' : 'dtw-message'}
          data-testid={workflowError ? 'dtw-workflow-error' : 'dtw-workflow-message'}
        >
          {workflowError || workflowMessage}
        </div>
      )}

      {diagnostics.length > 0 && (
        <section className="dtw-diagnostics" data-testid="dtw-local-diagnostics">
          {diagnostics.map((diagnostic, idx) => (
            <div key={`${diagnostic.severity}-${idx}`} data-severity={diagnostic.severity}>
              <strong>{diagnostic.severity}</strong>
              <span>{diagnostic.message}</span>
            </div>
          ))}
        </section>
      )}

      <section className="dtw-runtime-panel" aria-label="decision-table-test-run">
        <label>
          测试上下文
          <textarea
            aria-label="decision-table-test-context"
            data-testid="dtw-context-json"
            value={contextJson}
            onChange={(event) => setContextJson(event.target.value)}
          />
        </label>
        <div className="dtw-result" data-testid="dtw-test-result">
          <strong>{testResult ? `${testResult.status} · matched=${String(testResult.matched)}` : '尚未执行'}</strong>
          <pre>{testResult ? displayJson(testResult.outputs) : displayJson({})}</pre>
        </div>
      </section>

      <DecisionTableEditor
        value={table}
        onChange={setTable}
        analysis={analysis}
        analyzing={analyzing}
        analysisError={analysisError}
        onAnalyze={handleAnalyze}
        dmnXml={dmnXml}
        dmnBusy={dmnBusy}
        dmnError={dmnError}
        dmnStatus={dmnStatus}
        onDmnXmlChange={setDmnXml}
        onExportDmnXml={handleExportDmn}
        onImportDmnXml={handleImportDmn}
        onRoundTripDmnXml={handleRoundTripDmn}
      />
    </div>
  );
}

export default DecisionTableWorkbenchBlock;
