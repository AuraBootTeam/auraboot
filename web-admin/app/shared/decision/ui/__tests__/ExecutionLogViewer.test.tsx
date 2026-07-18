import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { ExecutionLogViewer, type ExecLogEntry } from '../ExecutionLogViewer';

const logs: ExecLogEntry[] = [
  { traceId: 'trace-aaa', policyCode: 'complaint_form_submit', status: 'SUCCESS', matchedRules: ['R-101'], actionPlans: ['NOTIFY'], durationMs: 120, time: '09:42' },
  { traceId: 'trace-bbb', policyCode: 'vip_case_update', status: 'FAILED_RETRYING', matchedRules: ['R-501'], actionPlans: ['CALL_CONNECTOR'], durationMs: 921, time: '09:39' },
  { traceId: 'trace-ccc', decisionCode: 'complaint_sla_deadline', status: 'SUCCESS', matchedRules: [], actionPlans: [], durationMs: 12, time: '08:56' },
];

describe('ExecutionLogViewer', () => {
  it('renders all log rows with status + count', () => {
    render(<ExecutionLogViewer logs={logs} />);
    const row = screen.getByTestId('elv-row-trace-aaa');
    expect(row).toHaveAttribute('data-status', 'SUCCESS');
    const status = row.querySelector('.elv-status');
    expect(status).toHaveTextContent('成功');
    expect(status).not.toHaveTextContent('SUCCESS');
    expect(screen.getByTestId('elv-count')).toHaveTextContent('3');
  });

  it('filters by status', () => {
    render(<ExecutionLogViewer logs={logs} />);
    expect(screen.getByLabelText('status-filter')).toHaveTextContent('失败重试中');
    expect(screen.getByLabelText('status-filter')).not.toHaveTextContent('FAILED_RETRYING');
    fireEvent.change(screen.getByLabelText('status-filter'), { target: { value: 'FAILED_RETRYING' } });
    expect(screen.getByTestId('elv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('elv-row-trace-bbb')).toBeInTheDocument();
    expect(screen.queryByTestId('elv-row-trace-aaa')).not.toBeInTheDocument();
  });

  it('searches by traceId / code', () => {
    render(<ExecutionLogViewer logs={logs} />);
    fireEvent.change(screen.getByLabelText('log-search'), { target: { value: 'sla_deadline' } });
    expect(screen.getByTestId('elv-count')).toHaveTextContent('1');
    expect(screen.getByTestId('elv-row-trace-ccc')).toBeInTheDocument();
  });

  it('shows empty state when nothing matches', () => {
    render(<ExecutionLogViewer logs={logs} />);
    fireEvent.change(screen.getByLabelText('log-search'), { target: { value: 'no-such-thing' } });
    expect(screen.getByTestId('elv-empty')).toBeInTheDocument();
  });

  it('renders matched rules + action plans of a row', () => {
    render(<ExecutionLogViewer logs={logs} />);
    const row = screen.getByTestId('elv-row-trace-aaa');
    expect(row).toHaveTextContent('R-101');
    expect(row).toHaveTextContent('NOTIFY');
    expect(row).toHaveTextContent('120ms');
  });

  it('honors initialStatus filter', () => {
    render(<ExecutionLogViewer logs={logs} initialStatus="SUCCESS" />);
    expect(screen.getByTestId('elv-count')).toHaveTextContent('2');
  });

  it('opens an execution detail drawer from a log row', () => {
    render(<ExecutionLogViewer logs={logs} />);
    fireEvent.click(screen.getByTestId('elv-open-trace-bbb'));
    expect(screen.getByTestId('elv-detail-drawer')).toHaveTextContent('trace-bbb');
    expect(screen.getByTestId('elv-detail-drawer')).toHaveTextContent('失败重试中');
    expect(screen.getByTestId('elv-detail-drawer')).not.toHaveTextContent('FAILED_RETRYING');
    expect(screen.getByTestId('elv-detail-drawer')).toHaveTextContent('CALL_CONNECTOR');
    expect(screen.getByTestId('elv-detail-drawer')).toHaveTextContent('921ms');
  });

  it('shows virtual source trace evidence with actual values and unknown reasons', () => {
    render(<ExecutionLogViewer logs={[
      {
        traceId: 'trace-virtual',
        decisionCode: 'virtual_sla_risk',
        status: 'UNKNOWN',
        traceSnapshot: {
          virtualSources: [
            {
              sourceRef: 'virtual.leave_request_summary.v1',
              modelCode: 'leave_request_summary_v',
              recordPid: 'REQ-001',
              status: 'RESOLVED',
              fields: {
                slaRiskScore: 91,
                tenant_id: 1,
              },
            },
          ],
          unknownReasons: ['Missing record.data.managerLevel'],
        },
      },
    ]} />);

    fireEvent.click(screen.getByTestId('elv-open-trace-virtual'));
    const drawer = screen.getByTestId('elv-detail-drawer');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('virtual.leave_request_summary.v1');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('RESOLVED');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('slaRiskScore');
    expect(screen.getByTestId('elv-virtual-sources')).toHaveTextContent('91');
    expect(screen.getByTestId('elv-unknown-reasons')).toHaveTextContent('Missing record.data.managerLevel');
    expect(drawer).not.toHaveTextContent('tenant_id');
  });

  it('shows low-code fact metadata labels and dictionary values in the detail drawer', () => {
    render(<ExecutionLogViewer logs={[
      {
        traceId: 'trace-meta',
        decisionCode: 'leave_type_gate',
        status: 'SUCCESS',
        traceSnapshot: {
          factMetadata: {
            wd_req_type: {
              label: '请假类型',
              valueLabels: {
                annual: '年假',
              },
            },
            'record.data.wd_req_type': {
              scope: 'record',
              path: 'data.wd_req_type',
              factKey: 'record.data.wd_req_type',
              label: '请假类型',
              dataType: 'enum',
              modelCode: 'wd_leave_request',
              dictCode: 'wd_leave_type',
            },
          },
        },
      },
    ]} />);

    fireEvent.click(screen.getByTestId('elv-open-trace-meta'));
    const facts = screen.getByTestId('elv-fact-metadata');
    expect(facts).toHaveTextContent('事实快照');
    expect(facts).toHaveTextContent('请假类型');
    expect(facts).toHaveTextContent('record.data.wd_req_type');
    expect(facts).toHaveTextContent('模型 wd_leave_request');
    expect(facts).toHaveTextContent('字典 wd_leave_type');
    expect(facts).toHaveTextContent('annual');
    expect(facts).toHaveTextContent('年假');
    expect(facts.querySelectorAll('.elv-fact-card')).toHaveLength(1);
  });
});
