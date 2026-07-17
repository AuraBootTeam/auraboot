import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import PolicyConfigDialog from '../PolicyConfigDialog';
import { permissionService } from '~/shared/services/permissionService';

vi.mock('~/ui/ui/dialog', () => ({
  Dialog: ({ open, children }: any) => (open ? <div>{children}</div> : null),
  DialogContent: ({ children, className }: any) => <div className={className}>{children}</div>,
  DialogHeader: ({ children }: any) => <div>{children}</div>,
  DialogTitle: ({ children, className }: any) => <h2 className={className}>{children}</h2>,
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({
    t: (_key: string, _vars?: unknown, fallback?: string) => fallback,
  }),
}));

vi.mock('~/contexts/ToastContext', () => ({
  useToastContext: () => ({
    showSuccessToast: vi.fn(),
    showErrorToast: vi.fn(),
  }),
}));

vi.mock('~/shared/services/permissionService', () => ({
  permissionService: {
    setPolicy: vi.fn().mockResolvedValue(undefined),
  },
}));

describe('PolicyConfigDialog', () => {
  it('loads permission ABAC field choices from the Rule Center fact catalog', async () => {
    const decisionApi = {
      getFactCatalog: vi.fn().mockResolvedValue({
        entities: [
          {
            entityCode: 'wd_leave_request',
            modelCode: 'wd_leave_request',
            modelName: '请假申请',
            facts: [
              {
                scope: 'record',
                path: 'data.wd_req_days',
                label: '请假天数',
                dataType: 'decimal',
                modelCode: 'wd_leave_request',
              },
            ],
          },
        ],
      }),
      getDecisionImpact: vi.fn().mockResolvedValue({ incoming: [], outgoing: [], risk: { summary: '无影响' } }),
      evaluate: vi.fn().mockResolvedValue({ status: 'MATCHED', matched: true, outputs: {} }),
      getModelFields: vi.fn().mockResolvedValue([]),
    };

    render(
      <PolicyConfigDialog
        open
        onClose={vi.fn()}
        rolePid="role-admin"
        permissionPid="perm-approve"
        permissionLabel="Approve Invoice"
        decisionApi={decisionApi}
        schema={{
          dynamicAbac: {
            type: 'rule-center',
            label: 'Rule center ABAC',
            mode: 'decision',
            fieldCatalogMode: 'merge',
            fieldCatalogModelCode: 'wd_leave_request',
            decisions: [{ code: 'permission_amount_guard', name: 'Amount Guard' }],
            fields: [
              { scope: 'actor', path: 'roles', label: '角色', dataType: 'collection' },
            ],
          },
        }}
        initialValues={{}}
        onSuccess={vi.fn()}
      />,
    );

    await waitFor(() => expect(decisionApi.getFactCatalog).toHaveBeenCalledWith('wd_leave_request'));

    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    await waitFor(() =>
      expect(screen.getByRole('option', { name: '请假天数' })).toHaveValue('record:data.wd_req_days'),
    );
    fireEvent.change(screen.getByLabelText('mapping-input-0'), {
      target: { value: 'days' },
    });
    fireEvent.change(screen.getByLabelText('mapping-field-0'), {
      target: { value: 'record:data.wd_req_days' },
    });

    fireEvent.click(screen.getByTestId('policy-save-button'));

    await waitFor(() => {
      expect(permissionService.setPolicy).toHaveBeenCalledWith(
        'role-admin',
        'perm-approve',
        expect.objectContaining({
          dynamicAbac: expect.objectContaining({
            ruleBinding: expect.objectContaining({
              consumerType: 'PERMISSION',
              decisionBinding: expect.objectContaining({
                inputMappings: [
                  {
                    input: 'days',
                    source: { kind: 'FIELD', scope: 'record', path: 'data.wd_req_days' },
                  },
                ],
              }),
            }),
          }),
        }),
      );
    });
  });

  it('saves rule-center ABAC values as a RuleConsumerBinding under the policy key', async () => {
    const onClose = vi.fn();
    const onSuccess = vi.fn();

    render(
      <PolicyConfigDialog
        open
        onClose={onClose}
        rolePid="role-admin"
        permissionPid="perm-approve"
        permissionLabel="Approve Invoice"
        schema={{
          dynamicAbac: {
            type: 'rule-center',
            label: 'Rule center ABAC',
            mode: 'decision',
            timeoutMs: 50,
            decisions: [
              { code: 'permission_amount_guard', name: 'Amount Guard' },
              {
                code: 'permission_department_guard',
                name: 'Department Guard',
                outputs: [
                  { id: 'allowed', label: '是否允许', dataType: 'boolean' },
                  { id: 'grantReason', label: '授权说明', dataType: 'string' },
                ],
              },
            ],
            fields: [
              { scope: 'record', path: 'amount', label: 'Amount', dataType: 'decimal' },
              { scope: 'actor', path: 'departmentId', label: 'Actor Department', dataType: 'department' },
            ],
          },
        }}
        initialValues={{}}
        onSuccess={onSuccess}
      />,
    );

    expect(screen.getByTestId('policy-field-dynamicAbac')).toBeInTheDocument();
    expect(screen.getByTestId('decision-rule-binding-block')).toBeInTheDocument();

    fireEvent.change(screen.getByLabelText('decision-code'), {
      target: { value: 'permission_department_guard' },
    });
    fireEvent.change(screen.getByLabelText('version-policy'), {
      target: { value: 'ROLLOUT' },
    });
    fireEvent.click(screen.getByRole('button', { name: '添加映射' }));
    fireEvent.change(screen.getByLabelText('mapping-input-0'), {
      target: { value: 'departmentId' },
    });
    fireEvent.change(screen.getByLabelText('mapping-field-0'), {
      target: { value: 'actor:departmentId' },
    });
    fireEvent.click(screen.getByRole('button', { name: '添加输出' }));
    expect(screen.getByLabelText('output-mapping-output-picker-0')).toHaveTextContent('授权说明');
    fireEvent.change(screen.getByLabelText('output-mapping-output-picker-0'), {
      target: { value: 'grantReason' },
    });
    expect(screen.getByLabelText('output-mapping-kind-0')).toHaveValue('PERMISSION_CONTEXT');
    fireEvent.change(screen.getByTestId('policy-rule-timeout-dynamicAbac'), {
      target: { value: '75' },
    });

    fireEvent.click(screen.getByTestId('policy-save-button'));

    await waitFor(() => {
      expect(permissionService.setPolicy).toHaveBeenCalledWith(
        'role-admin',
        'perm-approve',
        expect.objectContaining({
          dynamicAbac: expect.objectContaining({
            expectedMatched: true,
            timeoutMs: 75,
            ruleBinding: expect.objectContaining({
              consumerType: 'PERMISSION',
              consumerNodeId: 'dynamicAbac',
              bindingKind: 'DECISION_REF',
              decisionBinding: expect.objectContaining({
                decisionCode: 'permission_department_guard',
                versionPolicy: 'ROLLOUT',
                timeoutMs: 75,
                fallbackPolicy: { mode: 'FAIL_CLOSED' },
                inputMappings: [
                  {
                    input: 'departmentId',
                    source: { kind: 'FIELD', scope: 'actor', path: 'departmentId' },
                  },
                ],
                outputMappings: [
                  {
                    output: 'grantReason',
                    target: { kind: 'PERMISSION_CONTEXT', path: 'grantReason' },
                  },
                ],
              }),
            }),
          }),
        }),
      );
    });
    expect(onSuccess).toHaveBeenCalled();
    expect(onClose).toHaveBeenCalled();
  });
});
