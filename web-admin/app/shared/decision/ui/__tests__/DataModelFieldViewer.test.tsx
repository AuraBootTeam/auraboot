import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, it, expect, vi } from 'vitest';
import { DataModelFieldViewer, type ModelField } from '../DataModelFieldViewer';

const fields: ModelField[] = [
  { entityCode: 'complaint', path: 'priority', label: '优先级', dataType: 'enum', refs: 18, masked: false, permission: '业务可见' },
  { entityCode: 'complaint', path: 'amount', label: '影响金额', dataType: 'decimal', refs: 12, masked: true, permission: '经理可见', decisionCodes: ['amount_risk', 'sla_deadline'] },
  { entityCode: 'incident', path: 'severity', label: '故障等级', dataType: 'enum', refs: 15, masked: false, permission: 'ITSM' },
];

describe('DataModelFieldViewer', () => {
  it('renders all fields with refs/mask and a count', () => {
    render(<DataModelFieldViewer fields={fields} />);
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('3');
    const row = screen.getByTestId('dmv-row-complaint.amount');
    expect(row).toHaveTextContent('影响金额');
    expect(row).toHaveTextContent('是'); // masked
    expect(screen.getByTestId('dmv-refs-amount')).toHaveTextContent('12');
  });

  it('filters by entity', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('entity-filter'), { target: { value: 'incident' } });
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('dmv-row-incident.severity')).toBeInTheDocument();
    expect(screen.queryByTestId('dmv-row-complaint.amount')).not.toBeInTheDocument();
  });

  it('searches by path / label', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('field-search'), { target: { value: 'priority' } });
    expect(screen.getByTestId('dmv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('dmv-row-complaint.priority')).toBeInTheDocument();
  });

  it('shows empty state when nothing matches', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.change(screen.getByLabelText('field-search'), { target: { value: 'zzz' } });
    expect(screen.getByTestId('dmv-empty')).toBeInTheDocument();
  });

  it('opens a field impact drawer from a field row', () => {
    render(<DataModelFieldViewer fields={fields} />);
    fireEvent.click(screen.getByTestId('dmv-open-complaint.amount'));
    expect(screen.getByTestId('dmv-impact-drawer')).toHaveTextContent('complaint.amount');
    expect(screen.getByTestId('dmv-impact-drawer')).toHaveTextContent('引用 12 次');
    expect(screen.getByTestId('dmv-impact-drawer')).toHaveTextContent('amount_risk');
    expect(screen.getByTestId('dmv-impact-drawer')).toHaveTextContent('sla_deadline');
  });

  it('loads indexed field impact when the drawer opens', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'complaint.amount',
      references: [{
        sourceType: 'DECISION_VERSION',
        sourceCode: 'amount_risk',
        sourceVersion: '1',
        targetType: 'FIELD',
        targetPath: 'complaint.amount',
      }],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
      },
    }));

    render(<DataModelFieldViewer fields={fields} api={{ getFieldImpact }} />);
    fireEvent.click(screen.getByTestId('dmv-open-complaint.amount'));

    await waitFor(() => expect(getFieldImpact).toHaveBeenCalledWith('complaint.amount'));
    await waitFor(() => expect(screen.getByTestId('dmv-indexed-impact')).toHaveTextContent('Used by 1 decision version'));
    expect(screen.getByTestId('dmv-indexed-impact')).toHaveTextContent('DECISION_VERSION');
    expect(screen.getByTestId('dmv-indexed-impact')).toHaveTextContent('amount_risk');
    expect(screen.getByTestId('dmv-indexed-impact')).toHaveTextContent('v1');
  });

  it('preflights destructive field changes and requires impact acknowledgement before allowing', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'complaint.amount',
      references: [{
        sourceType: 'DECISION_VERSION',
        sourceCode: 'amount_risk',
        sourceVersion: '1',
        targetType: 'FIELD',
        targetPath: 'complaint.amount',
      }],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
      },
    }));
    const preflightFieldChange = vi.fn(async (req: { impactAcknowledged?: boolean }) => ({
      fieldRef: 'complaint.amount',
      action: 'DELETE_FIELD' as const,
      allowed: Boolean(req.impactAcknowledged),
      blocked: !req.impactAcknowledged,
      requiresAcknowledgement: true,
      references: [{
        sourceType: 'DECISION_VERSION',
        sourceCode: 'amount_risk',
        targetType: 'FIELD',
        targetPath: 'complaint.amount',
      }],
      risk: {
        blocking: true,
        summary: 'Used by 1 decision version',
      },
      message: req.impactAcknowledged
        ? 'Field change allowed after impact acknowledgement: Used by 1 decision version'
        : 'Field change requires impact acknowledgement: Used by 1 decision version',
    }));

    render(<DataModelFieldViewer fields={fields} api={{ getFieldImpact, preflightFieldChange }} />);
    fireEvent.click(screen.getByTestId('dmv-open-complaint.amount'));
    fireEvent.click(screen.getByTestId('dmv-preflight-delete'));

    await waitFor(() => expect(preflightFieldChange).toHaveBeenCalledWith({
      fieldRef: 'complaint.amount',
      action: 'DELETE_FIELD',
      currentDataType: 'decimal',
      impactAcknowledged: false,
      note: undefined,
    }));
    await waitFor(() => expect(screen.getByTestId('dmv-preflight-result')).toHaveTextContent('已阻断'));

    fireEvent.click(screen.getByTestId('dmv-preflight-ack'));
    fireEvent.click(screen.getByTestId('dmv-preflight-delete'));

    await waitFor(() => expect(preflightFieldChange).toHaveBeenLastCalledWith({
      fieldRef: 'complaint.amount',
      action: 'DELETE_FIELD',
      currentDataType: 'decimal',
      impactAcknowledged: true,
      note: 'DecisionOps field impact acknowledged in F6 drawer',
    }));
    await waitFor(() => expect(screen.getByTestId('dmv-preflight-result')).toHaveTextContent('可执行'));
  });
});
