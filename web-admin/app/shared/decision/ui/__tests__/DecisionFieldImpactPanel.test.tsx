import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import type { DecisionFieldPreflightAction } from '../../api/decisionApi';
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
    expect(screen.getByTestId('field-impact-risk')).toHaveTextContent('影响 1 个决策版本');
    expect(screen.getByTestId('field-impact-counts')).toHaveTextContent('决策版本: 1');
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

  it('preflights dict, permission, and virtual source changes with model governance context', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'record.data.wd_req_type',
      references: [
        {
          sourceType: 'SLA_RULE',
          sourceCode: 'wd_manager_approve_sla',
          sourceName: '经理审批 SLA',
          targetType: 'FIELD',
          targetPath: 'record.data.wd_req_type',
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 SLA rule',
        counts: { SLA_RULE: 1 },
      },
    }));
    const preflightFieldChange = vi.fn(async (req: { action: DecisionFieldPreflightAction }) => ({
      fieldRef: 'record.data.wd_req_type',
      action: req.action,
      allowed: false,
      blocked: true,
      requiresAcknowledgement: true,
      references: [],
      risk: {
        blocking: true,
        summary: 'Used by 1 SLA rule',
      },
      message: 'Field change requires impact acknowledgement: Used by 1 SLA rule',
    }));

    render(
      <DecisionFieldImpactPanel
        api={{ getFieldImpact, preflightFieldChange }}
        initialFieldRef="record.data.wd_req_type"
        initialCurrentDataType="enum"
      />,
    );

    await screen.findByTestId('field-impact-risk');
    expect(screen.getByTestId('field-impact-risk')).toHaveTextContent('影响 1 条 SLA 规则');
    expect(screen.getByTestId('field-impact-counts')).toHaveTextContent('SLA 规则: 1');

    fireEvent.change(screen.getByLabelText('field-preflight-action'), {
      target: { value: 'DELETE_DICT_ITEM' },
    });
    fireEvent.change(screen.getByLabelText('field-impact-dict-code'), {
      target: { value: 'leave_type' },
    });
    fireEvent.change(screen.getByLabelText('field-impact-dict-value'), {
      target: { value: 'annual' },
    });
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenLastCalledWith({
        fieldRef: 'record.data.wd_req_type',
        action: 'DELETE_DICT_ITEM',
        currentDataType: 'enum',
        nextDataType: undefined,
        dictCode: 'leave_type',
        dictValue: 'annual',
        impactAcknowledged: false,
        note: undefined,
      }),
    );
    expect(screen.getByTestId('field-preflight-result')).toHaveTextContent(
      '字段变更需要确认影响面：影响 1 条 SLA 规则',
    );

    fireEvent.change(screen.getByLabelText('field-preflight-action'), {
      target: { value: 'CHANGE_PERMISSION' },
    });
    fireEvent.change(screen.getByLabelText('field-impact-next-permission'), {
      target: { value: 'manager.visible' },
    });
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenLastCalledWith({
        fieldRef: 'record.data.wd_req_type',
        action: 'CHANGE_PERMISSION',
        currentDataType: 'enum',
        nextDataType: undefined,
        nextPermission: 'manager.visible',
        impactAcknowledged: false,
        note: undefined,
      }),
    );

    fireEvent.change(screen.getByLabelText('field-preflight-action'), {
      target: { value: 'CHANGE_VIRTUAL_SOURCE' },
    });
    fireEvent.change(screen.getByLabelText('field-impact-next-source-ref'), {
      target: { value: 'virtual.leave_request_summary.v2' },
    });
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenLastCalledWith({
        fieldRef: 'record.data.wd_req_type',
        action: 'CHANGE_VIRTUAL_SOURCE',
        currentDataType: 'enum',
        nextDataType: undefined,
        nextSourceRef: 'virtual.leave_request_summary.v2',
        impactAcknowledged: false,
        note: undefined,
      }),
    );
  });

  it('preflights data type changes with explicit target data type', async () => {
    const getFieldImpact = vi.fn(async () => ({
      fieldRef: 'process.nodeId',
      references: [
        {
          sourceType: 'BPM_PROCESS',
          sourceCode: 'wd_leave_approval',
          sourceName: '请假审批',
          sourceVersion: '13',
          targetType: 'FIELD',
          targetPath: 'process.nodeId',
        },
      ],
      risk: {
        blocking: true,
        summary: 'Used by 1 BPM_PROCESS',
        counts: { BPM_PROCESS: 1 },
      },
    }));
    const preflightFieldChange = vi.fn(async (req: { action: DecisionFieldPreflightAction }) => ({
      fieldRef: 'process.nodeId',
      action: req.action,
      currentDataType: 'string',
      nextDataType: 'decimal',
      allowed: false,
      blocked: true,
      requiresAcknowledgement: true,
      references: [],
      risk: {
        blocking: true,
        summary: 'Used by 1 BPM_PROCESS',
      },
      message: 'Field change requires impact acknowledgement: Used by 1 BPM_PROCESS',
    }));

    render(
      <DecisionFieldImpactPanel
        api={{ getFieldImpact, preflightFieldChange }}
        initialFieldRef="process.nodeId"
        initialCurrentDataType="string"
      />,
    );

    await screen.findByTestId('field-impact-risk');
    fireEvent.change(screen.getByLabelText('field-preflight-action'), {
      target: { value: 'CHANGE_DATA_TYPE' },
    });
    fireEvent.change(screen.getByLabelText('field-impact-next-type'), {
      target: { value: 'decimal' },
    });
    fireEvent.click(screen.getByTestId('field-preflight-run'));

    await waitFor(() =>
      expect(preflightFieldChange).toHaveBeenLastCalledWith({
        fieldRef: 'process.nodeId',
        action: 'CHANGE_DATA_TYPE',
        currentDataType: 'string',
        nextDataType: 'decimal',
        impactAcknowledged: false,
        note: undefined,
      }),
    );
    expect(screen.getByTestId('field-preflight-result')).toHaveTextContent('已阻断');
  });
});
