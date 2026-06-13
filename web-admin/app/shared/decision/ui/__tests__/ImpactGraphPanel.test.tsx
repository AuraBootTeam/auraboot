import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ImpactGraphPanel } from '../ImpactGraphPanel';
import type { DecisionImpact } from '../../api/decisionApi';

describe('ImpactGraphPanel', () => {
  it('renders incoming consumers, selected decision, outgoing refs, and blast-radius risk', () => {
    const impact: DecisionImpact = {
      decisionCode: 'sla_deadline',
      incoming: [
        { sourceType: 'AUTOMATION', sourceCode: 'auto-high-priority', sourceName: 'High Priority Automation' },
        { sourceType: 'SLA_RULE', sourceCode: 'sla-high-priority', sourceName: 'High Priority SLA' },
      ],
      outgoing: [
        { targetType: 'FIELD', targetPath: 'record.data.priority' },
        { targetType: 'FUNCTION', targetCode: 'daysBetween' },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 automation + 1 SLA rule',
        counts: { AUTOMATION: 1, SLA_RULE: 1 },
      },
    };

    render(<ImpactGraphPanel impact={impact} />);

    expect(screen.getByTestId('impact-blast-radius')).toHaveTextContent('Used by 1 automation + 1 SLA rule');
    expect(screen.getByTestId('impact-center')).toHaveTextContent('sla_deadline');
    expect(screen.getByTestId('impact-incoming')).toHaveTextContent('High Priority Automation');
    expect(screen.getByTestId('impact-incoming')).toHaveTextContent('High Priority SLA');
    expect(screen.getByTestId('impact-outgoing')).toHaveTextContent('record.data.priority');
    expect(screen.getByTestId('impact-outgoing')).toHaveTextContent('daysBetween');
  });
});
