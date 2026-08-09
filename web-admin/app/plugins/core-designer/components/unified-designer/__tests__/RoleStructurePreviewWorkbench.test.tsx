import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  loadAuthoringRolePreviewTargets,
  loadAuthoringRoleStructurePreview,
} from '~/framework/meta/authoring/authoringService';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';

vi.mock('~/framework/meta/authoring/authoringService', () => ({
  endAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringRolePreviewTargets: vi.fn(),
  loadAuthoringRoleStructurePreview: vi.fn(),
  loadAuthoringSyntheticPreview: vi.fn(),
  startAuthoringIdentitySimulation: vi.fn(),
}));

describe('UnifiedDesignerWorkbench role structure preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadAuthoringRolePreviewTargets).mockResolvedValue([
      { rolePid: 'role-operator', roleCode: 'operator', roleName: '操作员' },
    ]);
    vi.mocked(loadAuthoringRoleStructurePreview).mockResolvedValue({
      mode: 'STRUCTURE',
      pagePid: 'page-1',
      targetRole: { rolePid: 'role-operator', roleCode: 'operator', roleName: '操作员' },
      actorIntersectionApplied: true,
      businessDataIncluded: false,
      exportAllowed: false,
      businessActionsAllowed: false,
      decisions: [
        {
          nodeType: 'FIELD',
          nodeId: 'field_customer_name',
          label: 'Customer name',
          permissionCode: 'customer.public.read',
          allowed: true,
          visible: true,
          writable: true,
          reason: 'ALLOW',
        },
        {
          nodeType: 'FIELD',
          nodeId: 'field_customer_phone',
          label: 'Phone',
          permissionCode: 'customer.secret.read',
          allowed: false,
          visible: false,
          writable: false,
          reason: 'ACTOR_SCOPE_LIMIT',
        },
        {
          nodeType: 'MENU',
          nodeId: 'menu-customer',
          label: '客户',
          permissionCode: 'customer.public.read',
          allowed: true,
          visible: true,
          writable: false,
          reason: 'ALLOW',
        },
      ],
    });
  });

  it('switches from actor preview to a no-data, no-action target-role structure', async () => {
    const document = structuredClone(samplePageSchemaV3);
    const form = document.blocks[0];
    const fields = form.blocks?.[0].blocks ?? [];
    fields[0].props = { ...fields[0].props, permissionCode: 'customer.public.read' };
    fields[1].props = { ...fields[1].props, permissionCode: 'customer.secret.read' };

    render(
      <UnifiedDesignerWorkbench
        initialDocument={document}
        roleStructurePreviewSessionPid="session-1"
      />,
    );

    expect(loadAuthoringRolePreviewTargets).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() => {
      expect(screen.getByTestId('role-preview-target-select')).toHaveTextContent('操作员');
    });
    expect(loadAuthoringRolePreviewTargets).toHaveBeenCalledWith('session-1');
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: 'role-operator' },
    });

    expect(await screen.findByTestId('role-structure-preview-banner')).toHaveTextContent(
      '不读取目标角色真实数据',
    );
    expect(screen.getByTestId('role-structure-preview-banner')).toHaveTextContent('导出关闭');
    expect(screen.getByTestId('role-structure-preview-banner')).toHaveTextContent('业务动作关闭');
    expect(screen.getByTestId('role-preview-summary-field')).toHaveTextContent('字段 1/2');
    expect(screen.getByTestId('role-preview-summary-menu')).toHaveTextContent('菜单 1/1');
    expect(screen.getByTestId('runtime-page-customer_workspace').closest('fieldset')).toBeDisabled();
    expect(screen.getByTestId('runtime-field-permission-field_customer_phone')).toHaveTextContent(
      'customer.secret.read',
    );
    expect(loadAuthoringRoleStructurePreview).toHaveBeenCalledWith(
      'session-1',
      'role-operator',
    );

    fireEvent.click(screen.getByTestId('role-preview-exit'));
    await waitFor(() => {
      expect(screen.queryByTestId('role-structure-preview-banner')).not.toBeInTheDocument();
    });
  });

  it('fails closed instead of falling back to current-actor data after a preview error', async () => {
    vi.mocked(loadAuthoringRoleStructurePreview).mockRejectedValue(new Error('preview denied'));

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        roleStructurePreviewSessionPid="session-1"
      />,
    );
    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() => expect(screen.getByTestId('role-preview-target-select')).toHaveTextContent('操作员'));
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: 'role-operator' },
    });

    expect(await screen.findByTestId('role-preview-error')).toHaveTextContent('preview denied');
    expect(screen.getByTestId('role-preview-fail-closed')).toBeInTheDocument();
    expect(screen.queryByTestId('runtime-page-customer_workspace')).not.toBeInTheDocument();
  });
});
