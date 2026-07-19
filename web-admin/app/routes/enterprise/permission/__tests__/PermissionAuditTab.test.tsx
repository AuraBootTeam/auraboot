import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router';
import PermissionAuditTab from '../PermissionAuditTab';
import { fetchResult } from '~/shared/services/http-client';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ t: (_key: string, _vars?: unknown, fallback?: string) => fallback }),
}));

vi.mock('~/contexts/TimezoneContext', () => ({
  useTimezone: () => ({
    timezone: 'Asia/Shanghai',
    formats: { date: 'YYYY-MM-DD', datetime: 'YYYY-MM-DD HH:mm:ss', time: 'HH:mm:ss' },
  }),
}));

vi.mock('~/ui/LoadingSpinner', () => ({
  LoadingSpinner: () => <div data-testid="loading-spinner" />,
}));

vi.mock('~/shared/services/http-client', () => ({
  fetchResult: vi.fn(),
}));

const fetchResultMock = vi.mocked(fetchResult);

function ok(data: unknown) {
  return { code: '0', desc: 'OK', data };
}

function renderAuditTab(initialEntry = '/', initialSearch?: string) {
  return render(
    <MemoryRouter initialEntries={[initialEntry]}>
      <PermissionAuditTab initialSearch={initialSearch} />
    </MemoryRouter>,
  );
}

describe('PermissionAuditTab', () => {
  beforeEach(() => {
    fetchResultMock.mockReset();
  });

  it('renders deny trace without leaking sensitive record values', async () => {
    fetchResultMock.mockResolvedValue(
      ok([
        {
          id: 42,
          memberId: 9,
          resourceCode: 'wd_leave_request',
          actionCode: 'view',
          recordPid: '01PUBLICREC',
          result: false,
          reason: 'record.data.salary denied with value=1234567890',
          createdAt: '2026-07-13T10:00:00Z',
          evaluationTrace: [
            {
              evaluatorName: 'Policy',
              verdict: 'DENY',
              reason: 'record.data.salary is not available in permission ABAC fact catalog',
              payload: { salary: '1234567890', token: 'secret-token-value' },
            },
          ],
        },
      ]),
    );

    renderAuditTab();

    await waitFor(() => expect(screen.getByTestId('permission-audit-row-42')).toBeTruthy());
    expect(screen.getByTestId('permission-audit-row-42')).toHaveTextContent('DENY');
    expect(screen.getByTestId('permission-audit-row-42')).toHaveTextContent('wd_leave_request / view');
    expect(screen.getByTestId('permission-audit-reason-42')).toHaveTextContent(
      'record.data.salary denied with value=***',
    );
    expect(screen.getByTestId('permission-audit-trace-step-42-0')).toHaveTextContent('Policy');
    expect(screen.getByTestId('permission-audit-trace-step-42-0')).toHaveTextContent(
      'record.data.salary is not available in permission ABAC fact catalog',
    );
    expect(screen.queryByText('1234567890')).toBeNull();
    expect(screen.queryByText('secret-token-value')).toBeNull();
  });

  it('renders Rule Center DMN output details as structured permission audit evidence', async () => {
    fetchResultMock.mockResolvedValue(
      ok([
        {
          id: 77,
          memberId: 11,
          resourceCode: 'wd_leave_request',
          actionCode: 'approve',
          recordPid: '01KVRULETRACE',
          result: true,
          reason: 'Rule Center policy allowed the permission check',
          createdAt: '2026-07-15T08:30:00Z',
          evaluationTrace: [
            {
              evaluatorName: 'Rule Center',
              verdict: 'ALLOW',
              reason: 'Decision output mapped into permission context',
              details: {
                ruleTraceId: 'trace-permission-001',
                bindingKind: 'PERMISSION_POLICY',
                decisionCode: 'leave_approval_route',
                decisionVersion: 3,
                decisionStatus: 'PUBLISHED',
                matched: true,
                fallbackApplied: false,
                inputSnapshot: {
                  record: {
                    wd_req_days: 5,
                    salary: '1234567890',
                  },
                },
                decisionOutputs: {
                  severity: 'high',
                  message: 'Manager approval required',
                  actionType: 'notify',
                },
                permissionContext: {
                  severity: 'high',
                  decisionMessage: 'Manager approval required',
                },
                fieldRefs: ['record.data.wd_req_days'],
                decisionRefs: ['leave_approval_route@3'],
              },
            },
          ],
        },
      ]),
    );

    renderAuditTab();

    await waitFor(() => expect(screen.getByTestId('permission-audit-row-77')).toBeTruthy());
    expect(screen.getByTestId('permission-audit-rule-meta-77-0')).toHaveTextContent('trace-permission-001');
    expect(screen.getByTestId('permission-audit-rule-meta-77-0')).toHaveTextContent('leave_approval_route');
    expect(screen.getByTestId('permission-audit-rule-meta-77-0')).toHaveTextContent('PUBLISHED');
    expect(screen.getByTestId('permission-audit-open-decision-trace-77-0')).toHaveTextContent('统一 Trace');
    expect(screen.getByTestId('permission-audit-open-decision-trace-77-0')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?traceId=trace-permission-001',
    );
    expect(screen.getByTestId('permission-audit-permission-context-77-0')).toHaveTextContent('权限上下文');
    expect(screen.getByTestId('permission-audit-permission-context-77-0')).toHaveTextContent('severity');
    expect(screen.getByTestId('permission-audit-permission-context-77-0')).toHaveTextContent('decisionMessage');
    expect(screen.getByTestId('permission-audit-permission-context-77-0')).toHaveTextContent('Manager approval required');
    expect(screen.getByTestId('permission-audit-decision-outputs-77-0')).toHaveTextContent('DMN 输出');
    expect(screen.getByTestId('permission-audit-decision-outputs-77-0')).toHaveTextContent('actionType');
    expect(screen.getByTestId('permission-audit-decision-outputs-77-0')).toHaveTextContent('notify');
    expect(screen.queryByText('1234567890')).toBeNull();
  });

  it('renders field-governance failures as structured permission audit evidence', async () => {
    fetchResultMock.mockResolvedValue(
      ok([
        {
          id: 88,
          memberId: 12,
          resourceCode: 'wd_leave_request',
          actionCode: 'read',
          recordPid: '01KVPERMGOV',
          result: false,
          reason: 'Condition guard not satisfied',
          createdAt: '2026-07-17T09:30:00Z',
          evaluationTrace: [
            {
              evaluatorName: 'Policy',
              verdict: 'DENY',
              reason: 'Condition guard not satisfied',
              details: {
                ruleCenterFailures: [
                  {
                    grantId: 900,
                    matched: false,
                    ruleTraceId: 'decision-01222993-910e-4004-9dbb-f8a3b6960e60',
                    decisionCode: 'permission_applicant_trace',
                    decisionVersion: 1,
                    decisionStatus: 'NOT_MATCHED',
                    bindingKind: 'DECISION_REF',
                    fallbackApplied: false,
                    fieldRefs: ['record.data.wd_req_applicant'],
                    decisionOutputs: {
                      truth: 'FALSE',
                      matched: false,
                    },
                    error: 'record.data.salary is masked and cannot be used with token=secret-token-value',
                    fieldGovernance: {
                      fieldRef: 'record.data.salary',
                      reason: 'masked',
                      validation: 'DENY',
                      source: 'permission-policy-validation',
                    },
                  },
                ],
              },
            },
          ],
        },
      ]),
    );

    renderAuditTab();

    await waitFor(() => expect(screen.getByTestId('permission-audit-row-88')).toBeTruthy());
    expect(screen.getByTestId('permission-audit-rule-meta-88-0')).toHaveTextContent(
      'decision-***-910e-4004-9dbb-f8a3b6960e60',
    );
    expect(screen.getByTestId('permission-audit-rule-meta-88-0')).not.toHaveTextContent(
      'decision-01222993-910e-4004-9dbb-f8a3b6960e60',
    );
    expect(screen.getByTestId('permission-audit-rule-meta-88-0')).toHaveTextContent('permission_applicant_trace');
    expect(screen.getByTestId('permission-audit-rule-meta-88-0')).toHaveTextContent('NOT_MATCHED');
    expect(screen.getByTestId('permission-audit-open-decision-trace-88-0')).toHaveAttribute(
      'href',
      '/p/decisionops_execution_logs?traceId=decision-01222993-910e-4004-9dbb-f8a3b6960e60',
    );
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('字段治理');
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('record.data.wd_req_applicant');
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('record.data.salary');
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('masked');
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('permission-policy-validation');
    expect(screen.getByTestId('permission-audit-field-governance-88-0')).toHaveTextContent('grantId');
    expect(screen.getByTestId('permission-audit-decision-outputs-88-0')).toHaveTextContent('truth');
    expect(screen.getByTestId('permission-audit-decision-outputs-88-0')).toHaveTextContent('FALSE');
    expect(screen.queryByText('secret-token-value')).toBeNull();
  });

  it('passes resource and member filters to the audit API', async () => {
    fetchResultMock.mockResolvedValue(ok([]));

    renderAuditTab();

    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenCalledWith('/api/permissions/audit', {
        method: 'get',
        params: { limit: 50 },
      }),
    );

    fireEvent.change(screen.getByTestId('permission-audit-resource-filter'), {
      target: { value: 'wd_leave_request' },
    });
    fireEvent.change(screen.getByTestId('permission-audit-member-filter'), {
      target: { value: '123abc' },
    });

    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenLastCalledWith('/api/permissions/audit', {
        method: 'get',
        params: { limit: 50, resourceCode: 'wd_leave_request', memberId: '123' },
      }),
    );
  });

  it('initializes trace and resource filters from URL params', async () => {
    fetchResultMock.mockResolvedValue(ok([]));

    renderAuditTab(
      '/enterprise/permissions',
      '?tab=audit&traceId=trace-permission-001&resourceCode=wd_leave_request',
    );

    await waitFor(() =>
      expect(fetchResultMock).toHaveBeenCalledWith('/api/permissions/audit', {
        method: 'get',
        params: {
          limit: 50,
          traceId: 'trace-permission-001',
          resourceCode: 'wd_leave_request',
        },
      }),
    );
    expect(screen.getByTestId('permission-audit-trace-filter')).toHaveValue('trace-permission-001');
    expect(screen.getByTestId('permission-audit-resource-filter')).toHaveValue('wd_leave_request');
  });
});
