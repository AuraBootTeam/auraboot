import { useMemo, useState } from 'react';
import type { DataType } from '../ast/conditionAst';

/**
 * DecisionOps data-model field whitelist viewer (mockup 数据模型 / F6, docs/1.md §11, §23): the
 * registered fields a Condition AST may reference (scope.path), with data type, reference count
 * (impact analysis), masking and field permission. Filter by entity + search. The catalogue that
 * backs the ConditionBuilder's field options; read-only here (editing is a governance slice).
 */

export interface ModelField {
  entityCode: string;
  path: string;
  label: string;
  dataType: DataType;
  refs?: number;
  masked?: boolean;
  permission?: string;
}

export interface DataModelFieldViewerProps {
  fields: ModelField[];
  initialEntity?: string;
}

export function DataModelFieldViewer({ fields, initialEntity = 'ALL' }: DataModelFieldViewerProps) {
  const [entity, setEntity] = useState(initialEntity);
  const [query, setQuery] = useState('');

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
            <tr><th>实体</th><th>字段</th><th>名称</th><th>类型</th><th>引用</th><th>脱敏</th><th>权限</th></tr>
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
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </div>
  );
}

export default DataModelFieldViewer;
