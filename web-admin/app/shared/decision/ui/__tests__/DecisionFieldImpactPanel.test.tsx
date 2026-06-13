import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DecisionFieldImpactPanel } from '../DecisionFieldImpactPanel';

describe('DecisionFieldImpactPanel', () => {
  it('loads indexed field impact from the initial field ref', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'record.data.amount',
      references: [
        {
          sourceType: 'DECISION_VERSION',
          sourceCode: 'amount_risk',
          sourceName: 'Amount Risk',
          sourceVersion: '2',
          targetType: 'FIELD',
          targetPath: 'record.data.amount',
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
        counts: { DECISION_VERSION: 1 },
      },
    }));

    render(
      <DecisionFieldImpactPanel
        api={{ getFieldImpact }}
        initialFieldRef="record.data.amount"
        initialCurrentDataType="decimal"
      />,
    );

    await waitFor(() => expect(getFieldImpact).toHaveBeenCalledWith('record.data.amount'));
    expect(screen.getByTestId('field-impact-risk')).toHaveTextContent('Used by 1 decision version');
    expect(screen.getByTestId('field-impact-counts')).toHaveTextContent('DECISION_VERSION: 1');
    expect(screen.getByTestId('field-impact-ref-0')).toHaveTextContent('Amount Risk');
    expect(screen.getByTestId('field-impact-ref-0')).toHaveTextContent('v2');
  });

  it('runs destructive field preflight and requires acknowledgement to allow it', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'record.data.amount',
      references: [
        {
          sourceType: 'DECISION_VERSION',
          sourceCode: 'amount_risk',
          targetType: 'FIELD',
          targetPath: 'record.data.amount',
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
      },
    }));
    const preflightFieldChange = vi.fn(async (req: { impactAcknowledged?: boolean }) => ({
      fieldRef: 'record.data.amount',
      action: 'DELETE_FIELD' as const,
      currentDataType: 'decimal',
      allowed: Boolean(req.impactAcknowledged),
      blocked: !req.impactAcknowledged,
      requiresAcknowledgement: true,
      references: [],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
      },
      message: req.impactAcknowledged
        ? 'Field change allowed after impact acknowledgement'
        : 'Field change requires impact acknowledgement',
    }));

    render(
      <DecisionFieldImpactPanel
        api={{ getFieldImpact, preflightFieldChange }}
        initialFieldRef="record.data.amount"
        initialCurrentDataType="decimal"
      />,
    );

    await screen.findByTestId('field-impact-risk');
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenCalledWith({
        fieldRef: 'record.data.amount',
        action: 'DELETE_FIELD',
        currentDataType: 'decimal',
        nextDataType: undefined,
        impactAcknowledged: false,
        note: undefined,
      }),
    );
    expect(screen.getByTestId('field-preflight-result')).toHaveTextContent('已阻断');

    fireEvent.click(screen.getByTestId('field-preflight-ack'));
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenLastCalledWith({
        fieldRef: 'record.data.amount',
        action: 'DELETE_FIELD',
        currentDataType: 'decimal',
        nextDataType: undefined,
        impactAcknowledged: true,
        note: 'DecisionOps field impact acknowledged in DSL field-impact block',
      }),
    );
    expect(screen.getByTestId('field-preflight-result')).toHaveTextContent('可执行');
  });
});
