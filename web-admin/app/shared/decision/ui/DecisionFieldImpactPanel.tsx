import { useEffect, useMemo, useState } from 'react';
import type {
  DecisionApi,
  DecisionFieldImpact,
  DecisionFieldPreflight,
  DecisionFieldPreflightAction,
  DecisionImpactRef,
} from '../api/decisionApi';

type FieldImpactApi = Pick<DecisionApi, 'getFieldImpact'> &
  Partial<Pick<DecisionApi, 'preflightFieldChange'>>;

export interface DecisionFieldImpactPanelProps {
  api: FieldImpactApi;
  initialFieldRef?: string;
  initialCurrentDataType?: string;
  autoLoad?: boolean;
}

function formatReference(ref: DecisionImpactRef): string {
  const title = ref.sourceName || ref.sourceCode || ref.targetPath || ref.sourceType;
  const parts = [ref.sourceType, title];
  if (ref.sourceVersion) {
    parts.push(`v${ref.sourceVersion}`);
  }
  if (ref.targetPath) {
    parts.push(ref.targetPath);
  }
  return parts.filter(Boolean).join(' / ');
}

function riskCounts(counts?: Record<string, number>) {
  if (!counts) return [];
  return Object.entries(counts)
    .filter(([, count]) => Number(count) > 0)
    .sort(([left], [right]) => left.localeCompare(right));
}

export function DecisionFieldImpactPanel({
  api,
  initialFieldRef = '',
  initialCurrentDataType = '',
  autoLoad = true,
}: DecisionFieldImpactPanelProps) {
  const [fieldRef, setFieldRef] = useState(initialFieldRef);
  const [currentDataType, setCurrentDataType] = useState(initialCurrentDataType);
  const [nextDataType, setNextDataType] = useState('');
  const [impact, setImpact] = useState<DecisionFieldImpact | null>(null);
  const [impactLoading, setImpactLoading] = useState(false);
  const [impactError, setImpactError] = useState('');
  const [preflightAction, setPreflightAction] =
    useState<DecisionFieldPreflightAction>('DELETE_FIELD');
  const [impactAcknowledged, setImpactAcknowledged] = useState(false);
  const [preflight, setPreflight] = useState<DecisionFieldPreflight | null>(null);
  const [preflightLoading, setPreflightLoading] = useState(false);
  const [preflightError, setPreflightError] = useState('');

  const normalizedFieldRef = fieldRef.trim();
  const counts = useMemo(() => riskCounts(impact?.risk.counts), [impact?.risk.counts]);
  const showAcknowledgement =
    Boolean(impact?.risk.blocking) || Boolean(preflight?.requiresAcknowledgement);

  useEffect(() => {
    setFieldRef(initialFieldRef);
  }, [initialFieldRef]);

  useEffect(() => {
    setCurrentDataType(initialCurrentDataType);
  }, [initialCurrentDataType]);

  const loadImpact = async (target = normalizedFieldRef) => {
    const ref = target.trim();
    if (!ref) {
      setImpactError('请输入字段引用');
      return;
    }
    setImpactLoading(true);
    setImpactError('');
    setPreflight(null);
    try {
      const result = await api.getFieldImpact(ref);
      setImpact(result);
      setImpactAcknowledged(false);
    } catch {
      setImpact(null);
      setImpactError('字段影响加载失败');
    } finally {
      setImpactLoading(false);
    }
  };

  useEffect(() => {
    if (autoLoad && initialFieldRef.trim()) {
      void loadImpact(initialFieldRef);
    }
  }, [autoLoad, initialFieldRef]);

  const runPreflight = async () => {
    if (!api.preflightFieldChange || !normalizedFieldRef) {
      return;
    }
    setPreflightLoading(true);
    setPreflightError('');
    try {
      const result = await api.preflightFieldChange({
        fieldRef: normalizedFieldRef,
        action: preflightAction,
        currentDataType: currentDataType.trim() || undefined,
        nextDataType:
          preflightAction === 'CHANGE_TYPE' && nextDataType.trim()
            ? nextDataType.trim()
            : undefined,
        impactAcknowledged,
        note: impactAcknowledged
          ? 'DecisionOps field impact acknowledged in DSL field-impact block'
          : undefined,
      });
      setPreflight(result);
    } catch {
      setPreflight(null);
      setPreflightError('字段变更预检失败');
    } finally {
      setPreflightLoading(false);
    }
  };

  return (
    <section className="decision-field-impact" data-testid="decision-field-impact">
      <div className="decision-field-impact-toolbar">
        <label>
          字段引用
          <input
            aria-label="field-impact-ref"
            value={fieldRef}
            placeholder="record.data.priority"
            onChange={(event) => setFieldRef(event.currentTarget.value)}
          />
        </label>
        <label>
          当前类型
          <input
            aria-label="field-impact-current-type"
            value={currentDataType}
            placeholder="string"
            onChange={(event) => setCurrentDataType(event.currentTarget.value)}
          />
        </label>
        <button
          type="button"
          data-testid="field-impact-load"
          disabled={impactLoading || !normalizedFieldRef}
          onClick={() => void loadImpact()}
        >
          {impactLoading ? '加载中' : '加载影响'}
        </button>
      </div>

      {impactError && (
        <div className="decisionops-state is-error" data-testid="field-impact-error">
          {impactError}
        </div>
      )}

      {impact && (
        <div className="decision-field-impact-grid">
          <div className="decision-field-impact-card">
            <h3>字段影响面</h3>
            <div
              className={
                impact.risk.blocking
                  ? 'decision-field-impact-risk is-blocking'
                  : 'decision-field-impact-risk'
              }
              data-testid="field-impact-risk"
            >
              {impact.risk.summary}
            </div>
            <dl>
              <div>
                <dt>字段</dt>
                <dd className="mono">{impact.fieldRef}</dd>
              </div>
              <div>
                <dt>引用数</dt>
                <dd>{impact.references.length}</dd>
              </div>
            </dl>
            {counts.length > 0 && (
              <div className="decision-field-impact-counts" data-testid="field-impact-counts">
                {counts.map(([key, count]) => (
                  <span key={key}>
                    {key}: {count}
                  </span>
                ))}
              </div>
            )}
          </div>

          <div className="decision-field-impact-card">
            <h3>变更预检</h3>
            <label>
              动作
              <select
                aria-label="field-preflight-action"
                value={preflightAction}
                onChange={(event) =>
                  setPreflightAction(event.currentTarget.value as DecisionFieldPreflightAction)
                }
              >
                <option value="DELETE_FIELD">删除字段</option>
                <option value="CHANGE_TYPE">变更类型</option>
              </select>
            </label>
            {preflightAction === 'CHANGE_TYPE' && (
              <label>
                目标类型
                <input
                  aria-label="field-impact-next-type"
                  value={nextDataType}
                  placeholder="decimal"
                  onChange={(event) => setNextDataType(event.currentTarget.value)}
                />
              </label>
            )}
            {showAcknowledgement && (
              <label className="decision-field-impact-ack">
                <input
                  type="checkbox"
                  data-testid="field-preflight-ack"
                  checked={impactAcknowledged}
                  onChange={(event) => setImpactAcknowledged(event.currentTarget.checked)}
                />
                已确认字段影响面
              </label>
            )}
            <button
              type="button"
              data-testid="field-preflight-run"
              disabled={preflightLoading || !api.preflightFieldChange || !normalizedFieldRef}
              onClick={() => void runPreflight()}
            >
              {preflightLoading ? '预检中' : '运行预检'}
            </button>
            {preflightError && (
              <p className="decisionops-state is-error" data-testid="field-preflight-error">
                {preflightError}
              </p>
            )}
            {preflight && (
              <p
                className={
                  preflight.blocked
                    ? 'decision-field-impact-preflight is-blocked'
                    : 'decision-field-impact-preflight'
                }
                data-testid="field-preflight-result"
              >
                {preflight.blocked ? '已阻断' : '可执行'} ·{' '}
                {preflight.message || preflight.risk.summary}
              </p>
            )}
          </div>
        </div>
      )}

      {impact && (
        <div className="decision-field-impact-card" data-testid="field-impact-references">
          <h3>索引引用</h3>
          {impact.references.length === 0 ? (
            <p className="decisionops-empty">暂无字段引用</p>
          ) : (
            <table className="decisionops-table">
              <thead>
                <tr>
                  <th>来源类型</th>
                  <th>来源</th>
                  <th>版本</th>
                  <th>目标路径</th>
                </tr>
              </thead>
              <tbody>
                {impact.references.map((ref, index) => (
                  <tr key={`${formatReference(ref)}-${index}`} data-testid={`field-impact-ref-${index}`}>
                    <td>{ref.sourceType}</td>
                    <td className="mono">{ref.sourceName || ref.sourceCode}</td>
                    <td>{ref.sourceVersion ? `v${ref.sourceVersion}` : '-'}</td>
                    <td className="mono">{ref.targetPath || '-'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </div>
      )}
    </section>
  );
}

export default DecisionFieldImpactPanel;
