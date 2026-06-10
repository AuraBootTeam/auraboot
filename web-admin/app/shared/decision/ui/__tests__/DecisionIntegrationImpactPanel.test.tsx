import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DecisionIntegrationImpactPanel } from '../DecisionIntegrationImpactPanel';

describe('DecisionIntegrationImpactPanel', () => {
  it('loads connector impact and links back to the platform management page', async () => {
    const getIntegrationImpact = vi.fn(async () => ({
      targetType: 'CONNECTOR',
      targetCode: 'api-1',
      manageUrl: '/p/api_connector',
      references: [
        {
          sourceType: 'AUTOMATION',
          sourceCode: 'auto-1',
          sourceName: 'Escalation Flow',
          targetType: 'CONNECTOR',
          targetCode: 'api-1',
          targetPath: 'enrich',
          binding: 'ACTION',
          metadata: { actionType: 'call_api' },
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 automation',
        counts: { AUTOMATION: 1 },
      },
    }));

    render(
      <DecisionIntegrationImpactPanel
        api={{ getIntegrationImpact }}
        targetType="CONNECTOR"
        targetCode="api-1"
      />,
    );

    await waitFor(() => expect(getIntegrationImpact).toHaveBeenCalledWith('CONNECTOR', 'api-1'));
    expect(screen.getByTestId('integration-impact-risk')).toHaveTextContent('Used by 1 automation');
    expect(screen.getByTestId('integration-impact-counts')).toHaveTextContent('AUTOMATION: 1');
    expect(screen.getByTestId('integration-impact-ref-0')).toHaveTextContent('Escalation Flow');
    expect(screen.getByTestId('integration-impact-ref-0')).toHaveTextContent('enrich');
    expect(screen.getByTestId('integration-impact-manage')).toHaveAttribute('href', '/p/api_connector');
  });
});
