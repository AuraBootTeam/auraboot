import { useMemo, useState } from 'react';
import type { DataType } from '../ast/conditionAst';
import type { DecisionApi, DecisionFieldImpact, DecisionFieldPreflight } from '../api/decisionApi';

/**
 * DecisionOps data-model field whitelist viewer (mockup 数据模型 / F6, docs/1.md §11, §23): the
 * registered fields a Condition AST may reference (scope.path), with data type, reference count
 * (impact analysis), masking and field permission. Filter by entity + search. The catalogue that
 * backs the ConditionBuilder's field options; read-only here (editing is a governance slice).
 */

export interface ModelField {
  entityCode: string;
  modelCode?: string;
  modelName?: string;
  path: string;
  label: string;
  dataType: DataType;
  refs?: number;
  masked?: boolean;
  permission?: string;
  decisionCodes?: string[];
}

export interface DataModelFieldViewerProps {
  fields: ModelField[];
  initialEntity?: string;
  api?: Pick<DecisionApi, 'getFieldImpact'> & Partial<Pick<DecisionApi, 'preflightFieldChange'>>;
}

export function DataModelFieldViewer({ fields, initialEntity = 'ALL', api }: DataModelFieldViewerProps) {
  const [entity, setEntity] = useState(initialEntity);
  const [query, setQuery] = useState('');
  const [selectedField, setSelectedField] = useState<ModelField | null>(null);
  const [fieldImpact, setFieldImpact] = useState<DecisionFieldImpact | null>(null);
  const [fieldImpactLoading, setFieldImpactLoading] = useState(false);
  const [fieldImpactError, setFieldImpactError] = useState(false);
  const [fieldPreflight, setFieldPreflight] = useState<DecisionFieldPreflight | null>(null);
  const [fieldPreflightLoading, setFieldPreflightLoading] = useState(false);
  const [fieldPreflightError, setFieldPreflightError] = useState(false);
  const [fieldPreflightAcknowledged, setFieldPreflightAcknowledged] = useState(false);

  const entities = useMemo(
    () => ['ALL', ...Array.from(new Set(fields.map((f) => f.entityCode)))],
    [fields],
  );

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase();
    return fields
      .filter((f) => entity === 'ALL' || f.entityCode === entity)
      .filter((f) => !q || f.path.toLowerCase().includes(q) || f.label.toLowerCase().includes(q));
  }, [fields, entity, query]);

  const fieldRef = (field: ModelField) => `${field.entityCode}.${field.path}`;

  const openFieldImpact = (field: ModelField) => {
    setSelectedField(field);
    setFieldImpact(null);
    setFieldImpactError(false);
    setFieldPreflight(null);
    setFieldPreflightError(false);
    setFieldPreflightAcknowledged(false);
    if (!api?.getFieldImpact) {
      return;
    }
    const ref = fieldRef(field);
    setFieldImpactLoading(true);
    void api.getFieldImpact(ref)
      .then((impact) => setFieldImpact(impact))
      .catch(() => setFieldImpactError(true))
      .finally(() => setFieldImpactLoading(false));
  };

  const runDeleteFieldPreflight = () => {
    if (!selectedField || !api?.preflightFieldChange) {
      return;
    }
    setFieldPreflightLoading(true);
    setFieldPreflightError(false);
    void api.preflightFieldChange({
      fieldRef: fieldRef(selectedField),
      action: 'DELETE_FIELD',
      currentDataType: selectedField.dataType,
      impactAcknowledged: fieldPreflightAcknowledged,
      note: fieldPreflightAcknowledged ? 'DecisionOps field impact acknowledged in F6 drawer' : undefined,
    })
      .then((preflight) => setFieldPreflight(preflight))
      .catch(() => setFieldPreflightError(true))
      .finally(() => setFieldPreflightLoading(false));
  };

  return (
    <div data-testid="data-model-viewer">
      <div className="dmv-toolbar">
        <select aria-label="entity-filter" value={entity} onChange={(e) => setEntity(e.target.value)}>
          {entities.map((en) => <option key={en} value={en}>{en}</option>)}
        </select>
        <input aria-label="field-search" placeholder="搜索字段 path / 名称" value={query}
          onChange={(e) => setQuery(e.target.value)} />
        <span data-testid="dmv-count">{filtered.length}</span>
      </div>

      {filtered.length === 0 ? (
        <div data-testid="dmv-empty">无匹配字段</div>
      ) : (
        <table className="dmv-table">
          <thead>
            <tr><th>实体</th><th>字段</th><th>名称</th><th>类型</th><th>引用</th><th>脱敏</th><th>权限</th><th>详情</th></tr>
          </thead>
          <tbody>
            {filtered.map((f) => (
              <tr key={`${f.entityCode}.${f.path}`} data-testid={`dmv-row-${f.entityCode}.${f.path}`}>
                <td>{f.entityCode}</td>
                <td className="mono">{f.path}</td>
                <td>{f.label}</td>
                <td>{f.dataType}</td>
                <td data-testid={`dmv-refs-${f.path}`}>{f.refs ?? 0}</td>
                <td>{f.masked ? '是' : '否'}</td>
                <td>{f.permission ?? '—'}</td>
                <td>
                  <button
                    type="button"
                    data-testid={`dmv-open-${f.entityCode}.${f.path}`}
                    onClick={() => openFieldImpact(f)}
                  >详情</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {selectedField && (
        <aside className="dmv-impact-drawer" role="dialog" aria-label="字段影响" data-testid="dmv-impact-drawer">
          <div className="drawer-head">
            <h4>字段影响</h4>
            <button type="button" data-testid="dmv-impact-close" onClick={() => setSelectedField(null)}>关闭</button>
          </div>
          <dl>
            <dt>字段</dt><dd className="mono">{fieldRef(selectedField)}</dd>
            <dt>名称</dt><dd>{selectedField.label}</dd>
            <dt>类型</dt><dd>{selectedField.dataType}</dd>
            <dt>引用</dt><dd>引用 {selectedField.refs ?? 0} 次</dd>
            <dt>脱敏</dt><dd>{selectedField.masked ? '是' : '否'}</dd>
            <dt>权限</dt><dd>{selectedField.permission ?? '—'}</dd>
          </dl>
          {api?.getFieldImpact && (
            <div className="dmv-indexed-impact" data-testid="dmv-indexed-impact">
              <h5>索引引用</h5>
              {fieldImpactLoading && <p data-testid="dmv-indexed-loading">加载中...</p>}
              {fieldImpactError && <p data-testid="dmv-indexed-error">影响分析加载失败</p>}
              {fieldImpact && (
                <>
                  <p>{fieldImpact.risk.summary}</p>
                  {fieldImpact.references.length === 0 ? (
                    <p>暂无索引引用</p>
                  ) : (
                    <ul>
                      {fieldImpact.references.map((ref, index) => (
                        <li key={`${ref.sourceType}-${ref.sourceCode}-${ref.targetPath}-${index}`}>
                          <span>{ref.sourceType}</span>
                          <span className="mono">{ref.sourceName ?? ref.sourceCode}</span>
                          {ref.sourceVersion && <span className="mono">v{ref.sourceVersion}</span>}
                          {ref.targetPath && <span className="mono">{ref.targetPath}</span>}
                        </li>
                      ))}
                    </ul>
                  )}
                </>
              )}
            </div>
          )}
          {api?.preflightFieldChange && (
            <div className="dmv-field-preflight" data-testid="dmv-field-preflight">
              <h5>变更预检</h5>
              <button
                type="button"
                data-testid="dmv-preflight-delete"
                disabled={fieldPreflightLoading}
                onClick={runDeleteFieldPreflight}
              >
                删除字段预检
              </button>
              {fieldPreflight?.requiresAcknowledgement && (
                <label>
                  <input
                    type="checkbox"
                    data-testid="dmv-preflight-ack"
                    checked={fieldPreflightAcknowledged}
                    onChange={(event) => setFieldPreflightAcknowledged(event.currentTarget.checked)}
                  />
                  已确认字段影响面
                </label>
              )}
              {fieldPreflightLoading && <p data-testid="dmv-preflight-loading">预检中...</p>}
              {fieldPreflightError && <p data-testid="dmv-preflight-error">预检失败</p>}
              {fieldPreflight && (
                <p data-testid="dmv-preflight-result">
                  {fieldPreflight.blocked ? '已阻断' : '可执行'} · {fieldPreflight.message ?? fieldPreflight.risk.summary}
                </p>
              )}
            </div>
          )}
          <div className="dmv-impact-sources">
            <h5>引用决策</h5>
            {(selectedField.decisionCodes ?? []).length === 0 ? (
              <p>暂无决策引用</p>
            ) : (
              <ul>
                {selectedField.decisionCodes?.map((code) => <li key={code} className="mono">{code}</li>)}
              </ul>
            )}
          </div>
        </aside>
      )}
    </div>
  );
}

export default DataModelFieldViewer;
