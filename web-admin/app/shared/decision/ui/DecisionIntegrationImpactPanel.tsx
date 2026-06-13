import { useEffect, useMemo, useState } from 'react';
import type {
  DecisionApi,
  DecisionImpactRef,
  DecisionIntegrationImpact,
} from '../api/decisionApi';

type IntegrationImpactApi = Pick<DecisionApi, 'getIntegrationImpact'>;

export interface DecisionIntegrationImpactPanelProps {
  api: IntegrationImpactApi;
  targetType: string;
  targetCode: string;
  autoLoad?: boolean;
}

function riskCounts(counts?: Record<string, number>) {
  if (!counts) return [];
  return Object.entries(counts)
    .filter(([, count]) => Number(count) > 0)
    .sort(([left], [right]) => left.localeCompare(right));
}

function formatReference(ref: DecisionImpactRef): string {
  const title = ref.sourceName || ref.sourceCode || ref.sourceType || 'Reference';
  const parts = [ref.sourceType, title];
  if (ref.sourceVersion) {
    parts.push(`v${ref.sourceVersion}`);
  }
  if (ref.targetPath) {
    parts.push(ref.targetPath);
  }
  if (ref.binding) {
    parts.push(ref.binding);
  }
  return parts.filter(Boolean).join(' / ');
}

export function DecisionIntegrationImpactPanel({
  api,
  targetType,
  targetCode,
  autoLoad = true,
}: DecisionIntegrationImpactPanelProps) {
  const [impact, setImpact] = useState<DecisionIntegrationImpact | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const normalizedType = targetType.trim().toUpperCase();
  const normalizedCode = targetCode.trim();
  const counts = useMemo(() => riskCounts(impact?.risk.counts), [impact?.risk.counts]);

  const loadImpact = async () => {
    if (!normalizedType || !normalizedCode) {
      setError('缺少集成目标');
      return;
    }
    setLoading(true);
    setError('');
    try {
      setImpact(await api.getIntegrationImpact(normalizedType, normalizedCode));
    } catch {
      setImpact(null);
      setError('集成影响加载失败');
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    if (autoLoad && normalizedType && normalizedCode) {
      void loadImpact();
    }
  }, [autoLoad, normalizedType, normalizedCode]);

  return (
    <section className="decision-integration-impact" data-testid="decision-integration-impact">
      <div className="decision-integration-impact-head">
        <div>
          <h3>引用影响</h3>
          <p>
            {normalizedType || 'INTEGRATION'} <span className="mono">{normalizedCode || '-'}</span>
          </p>
        </div>
        <div className="decision-integration-impact-actions">
          {impact?.manageUrl && (
            <a
              href={impact.manageUrl}
              data-testid="integration-impact-manage"
              className="decision-integration-impact-link"
            >
              平台管理
            </a>
          )}
          <button
            type="button"
            data-testid="integration-impact-refresh"
            disabled={loading || !normalizedType || !normalizedCode}
            onClick={() => void loadImpact()}
          >
            {loading ? '刷新中' : '刷新影响'}
          </button>
        </div>
      </div>

      {error && (
        <div className="decisionops-state is-error" data-testid="integration-impact-error">
          {error}
        </div>
      )}

      {impact && (
        <div className="decision-integration-impact-body">
          <div
            className={
              impact.risk.blocking
                ? 'decision-integration-impact-risk is-blocking'
                : 'decision-integration-impact-risk'
            }
            data-testid="integration-impact-risk"
          >
            {impact.risk.summary}
          </div>
          {counts.length > 0 && (
            <div
              className="decision-integration-impact-counts"
              data-testid="integration-impact-counts"
            >
              {counts.map(([key, count]) => (
                <span key={key}>
                  {key}: {count}
                </span>
              ))}
            </div>
          )}
          {impact.references.length === 0 ? (
            <div className="decisionops-state" data-testid="integration-impact-empty">
              暂无引用
            </div>
          ) : (
            <div className="decision-integration-impact-refs">
              {impact.references.map((ref, index) => (
                <div
                  key={`${ref.sourceType}-${ref.sourcePid || ref.sourceCode}-${index}`}
                  data-testid={`integration-impact-ref-${index}`}
                  className="decision-integration-impact-ref"
                >
                  <span>{formatReference(ref)}</span>
                  {typeof ref.metadata?.actionType === 'string' && ref.metadata.actionType && (
                    <small>{ref.metadata.actionType}</small>
                  )}
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </section>
  );
}

export default DecisionIntegrationImpactPanel;
