import type { DecisionImpact, DecisionImpactRef } from '../api/decisionApi';

export interface ImpactGraphPanelProps {
  impact: DecisionImpact;
}

export function ImpactGraphPanel({ impact }: ImpactGraphPanelProps) {
  return (
    <section className="impact-graph-panel" data-testid="impact-graph-panel">
      <div
        className={impact.risk.blocking ? 'impact-banner impact-banner-danger' : 'impact-banner'}
        data-testid="impact-blast-radius"
      >
        {impact.risk.summary}
      </div>

      <div className="impact-graph-columns">
        <div className="impact-graph-column" data-testid="impact-incoming">
          <h4>引用方</h4>
          {impact.incoming.length === 0 ? (
            <p>暂无下游引用</p>
          ) : (
            <ul>
              {impact.incoming.map((ref, index) => (
                <li key={`${ref.sourceType}-${ref.sourceCode}-${ref.sourceVersion}-${index}`}>
                  <strong>{ref.sourceName || ref.sourceCode || ref.sourceType}</strong>
                  <span>{ref.sourceType}</span>
                  {ref.binding && <small>{ref.binding}</small>}
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="impact-graph-center" data-testid="impact-center">
          <h4>决策</h4>
          <div className="mono">{impact.decisionCode}</div>
        </div>

        <div className="impact-graph-column" data-testid="impact-outgoing">
          <h4>读取对象</h4>
          {impact.outgoing.length === 0 ? (
            <p>暂无字段或子决策引用</p>
          ) : (
            <ul>
              {impact.outgoing.map((ref, index) => (
                <li key={`${ref.targetType}-${outgoingLabel(ref)}-${index}`}>
                  <strong>{outgoingLabel(ref)}</strong>
                  <span>{ref.targetType}</span>
                  {ref.sourceVersion && <small>v{ref.sourceVersion}</small>}
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </section>
  );
}

function outgoingLabel(ref: DecisionImpactRef): string {
  return ref.targetPath || ref.targetCode || ref.targetType || 'unknown';
}

export default ImpactGraphPanel;
