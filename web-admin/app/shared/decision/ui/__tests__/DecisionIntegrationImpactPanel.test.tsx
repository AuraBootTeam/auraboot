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

  it('loads webhook event impact and links back to the platform webhook page', async () => {
    const getIntegrationImpact = vi.fn(async () => ({
      targetType: 'WEBHOOK',
      targetCode: 'case.closed',
      manageUrl: '/p/webhook',
      references: [
        {
          sourceType: 'EVENT_POLICY',
          sourceCode: 'case_closed_policy',
          sourceName: 'Case Closed Policy',
          sourceVersion: '2',
          targetType: 'WEBHOOK',
          targetCode: 'case.closed',
          targetPath: 'case.closed',
          binding: 'VERSION_RULES',
          metadata: { actionType: 'WEBHOOK' },
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 EventPolicy',
        counts: { EVENT_POLICY: 1 },
      },
    }));

    render(
      <DecisionIntegrationImpactPanel
        api={{ getIntegrationImpact }}
        targetType="WEBHOOK"
        targetCode="case.closed"
      />,
    );

    await waitFor(() => expect(getIntegrationImpact).toHaveBeenCalledWith('WEBHOOK', 'case.closed'));
    expect(screen.getByTestId('integration-impact-risk')).toHaveTextContent('Used by 1 EventPolicy');
    expect(screen.getByTestId('integration-impact-ref-0')).toHaveTextContent('Case Closed Policy');
    expect(screen.getByTestId('integration-impact-ref-0')).toHaveTextContent('case.closed');
    expect(screen.getByTestId('integration-impact-manage')).toHaveAttribute('href', '/p/webhook');
  });
});
