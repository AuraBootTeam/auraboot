import { useI18n } from '~/contexts/I18nContext';
import { diagnosticText } from './diagnosticsCopy';
import type { DecisionImpact, DecisionImpactRef } from '../api/decisionApi';

export interface ImpactGraphPanelProps {
  impact: DecisionImpact;
  fieldLabels?: Record<string, string>;
  decisionName?: string;
}

export function ImpactGraphPanel({ impact, fieldLabels = {}, decisionName }: ImpactGraphPanelProps) {
  const { locale } = useI18n();
  const text = (key: string) => diagnosticText(key, locale);
  return (
    <section className="impact-graph-panel" data-testid="impact-graph-panel">
      <div
        className={impact.risk.blocking ? 'impact-banner impact-banner-danger' : 'impact-banner'}
        data-testid="impact-blast-radius"
      >
        {text(impact.risk.blocking ? 'impactBlocked' : 'impactClear')}
      </div>

      <p className="text-sm text-muted-foreground my-3">{text('impactHelp')}</p>
      <div className="impact-graph-columns">
        <div className="impact-graph-column" data-testid="impact-incoming">
          <h4>{text('incoming')}</h4>
          {impact.incoming.length === 0 ? (
            <p>{text('emptyIncoming')}</p>
          ) : (
            <ul>
              {impact.incoming.map((ref, index) => (
                <li key={`${ref.sourceType}-${ref.sourceCode}-${ref.sourceVersion}-${index}`}>
                  <strong>{ref.sourceName || text(ref.sourceType ?? 'other')}</strong>
                  <span>{text(ref.sourceType ?? 'other')}</span>
                  <details className="text-xs"><summary>{text('technical')}</summary><p>{ref.sourceType} · {ref.sourceCode} · {ref.binding}</p></details>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="impact-graph-center" data-testid="impact-center">
          <h4>{text('rule')}</h4><strong>{decisionName || text('DECISION')}</strong>
          <details className="text-xs"><summary>{text('technical')}</summary>{impact.decisionCode}</details>
        </div>

        <div className="impact-graph-column" data-testid="impact-outgoing">
          <h4>{text('outgoing')}</h4>
          {impact.outgoing.length === 0 ? (
            <p>{text('emptyOutgoing')}</p>
          ) : (
            <ul>
              {impact.outgoing.map((ref, index) => (
                <li key={`${ref.targetType}-${fieldLabels[outgoingLabel(ref)] || text(ref.targetType ?? 'other')}-${index}`}>
                  <strong>{fieldLabels[outgoingLabel(ref)] || text(ref.targetType ?? 'other')}</strong>
                  <span>{text(ref.targetType ?? 'other')}</span>
                  {ref.sourceVersion && <small>v{ref.sourceVersion}</small>}
                  <details className="text-xs"><summary>{text('technical')}</summary>{outgoingLabel(ref)}</details>
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
