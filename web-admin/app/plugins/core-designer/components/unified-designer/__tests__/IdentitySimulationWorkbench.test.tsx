import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  endAuthoringIdentitySimulation,
  loadAuthoringIdentitySimulation,
  loadAuthoringRolePreviewTargets,
  loadAuthoringRoleStructurePreview,
  startAuthoringIdentitySimulation,
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringIdentitySimulation,
  AuthoringRoleStructurePreview,
} from '~/framework/meta/authoring/types';
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

const structure: AuthoringRoleStructurePreview = {
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
      writable: false,
      reason: 'ALLOW',
    },
  ],
};

function simulation(status: 'ACTIVE' | 'ENDED' | 'EXPIRED'): AuthoringIdentitySimulation {
  const startedAt = new Date();
  const expiresAt = new Date(startedAt.getTime() + 10 * 60_000);
  return {
    simulationPid: 'simulation-1',
    mode: 'AUDITED_IDENTITY',
    sourceSessionPid: 'session-1',
    pagePid: 'page-1',
    targetRole: structure.targetRole,
    actorIntersectionApplied: true,
    businessDataIncluded: false,
    readOnly: true,
    exportAllowed: false,
    businessActionsAllowed: false,
    status,
    startedAt: startedAt.toISOString(),
    expiresAt: expiresAt.toISOString(),
    endedAt: status === 'ACTIVE' ? null : new Date().toISOString(),
    decisions: status === 'ACTIVE' ? structure.decisions : [],
  };
}

describe('UnifiedDesignerWorkbench audited identity simulation', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadAuthoringRolePreviewTargets).mockResolvedValue([structure.targetRole]);
    vi.mocked(loadAuthoringRoleStructurePreview).mockResolvedValue(structure);
    vi.mocked(startAuthoringIdentitySimulation).mockResolvedValue(simulation('ACTIVE'));
    vi.mocked(loadAuthoringIdentitySimulation).mockResolvedValue(simulation('EXPIRED'));
    vi.mocked(endAuthoringIdentitySimulation).mockResolvedValue(simulation('ENDED'));
  });

  it('requires a reason, starts an actor-intersected readonly session, and ends it explicitly', async () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        roleStructurePreviewSessionPid="session-1"
        identitySimulationAllowed
      />,
    );

    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() =>
      expect(screen.getByTestId('role-preview-target-select')).toHaveTextContent('操作员'),
    );
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: 'role-operator' },
    });
    await screen.findByTestId('role-structure-preview-banner');

    fireEvent.click(screen.getByTestId('identity-simulation-open'));
    expect(screen.getByTestId('identity-simulation-start')).toBeDisabled();
    fireEvent.change(screen.getByTestId('identity-simulation-duration'), {
      target: { value: '10' },
    });
    fireEvent.change(screen.getByTestId('identity-simulation-reason'), {
      target: { value: '复核 INC-742 权限表现' },
    });
    fireEvent.click(screen.getByTestId('identity-simulation-start'));

    expect(await screen.findByTestId('identity-simulation-banner')).toHaveTextContent(
      '当前阶段不读取真实业务记录',
    );
    expect(screen.getByTestId('identity-simulation-banner')).toHaveTextContent('全程只读');
    expect(screen.getByTestId('role-preview-target-select')).toBeDisabled();
    expect(
      screen.getByTestId('runtime-page-customer_workspace').closest('fieldset'),
    ).toBeDisabled();
    expect(startAuthoringIdentitySimulation).toHaveBeenCalledWith(
      'session-1',
      'role-operator',
      10,
      '复核 INC-742 权限表现',
    );

    fireEvent.click(screen.getByTestId('identity-simulation-end'));
    await waitFor(() =>
      expect(screen.getByTestId('identity-simulation-banner')).toHaveAttribute(
        'data-status',
        'ENDED',
      ),
    );
    expect(endAuthoringIdentitySimulation).toHaveBeenCalledWith('simulation-1');
    fireEvent.click(screen.getByTestId('identity-simulation-dismiss'));
    expect(await screen.findByTestId('role-structure-preview-banner')).toBeInTheDocument();
  });

  it('keeps the safe structure preview active when starting the simulation is denied', async () => {
    vi.mocked(startAuthoringIdentitySimulation).mockRejectedValue(new Error('admin denied'));
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        roleStructurePreviewSessionPid="session-1"
        identitySimulationAllowed
      />,
    );

    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() =>
      expect(screen.getByTestId('role-preview-target-select')).toHaveTextContent('操作员'),
    );
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: 'role-operator' },
    });
    await screen.findByTestId('role-structure-preview-banner');
    fireEvent.click(screen.getByTestId('identity-simulation-open'));
    fireEvent.change(screen.getByTestId('identity-simulation-reason'), {
      target: { value: '权限复核' },
    });
    fireEvent.click(screen.getByTestId('identity-simulation-start'));

    expect(await screen.findByTestId('identity-simulation-error')).toHaveTextContent(
      'admin denied',
    );
    expect(screen.getByTestId('role-structure-preview-banner')).toBeInTheDocument();
    expect(
      screen.getByTestId('runtime-page-customer_workspace').closest('fieldset'),
    ).toBeDisabled();
  });

  it('refreshes the server lifecycle at zero and renders the simulation as expired', async () => {
    vi.mocked(startAuthoringIdentitySimulation).mockResolvedValue({
      ...simulation('ACTIVE'),
      expiresAt: new Date(Date.now() + 20).toISOString(),
    });
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        roleStructurePreviewSessionPid="session-1"
        identitySimulationAllowed
      />,
    );

    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() =>
      expect(screen.getByTestId('role-preview-target-select')).toHaveTextContent('操作员'),
    );
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: 'role-operator' },
    });
    await screen.findByTestId('role-structure-preview-banner');
    fireEvent.click(screen.getByTestId('identity-simulation-open'));
    fireEvent.change(screen.getByTestId('identity-simulation-reason'), {
      target: { value: '短时复核' },
    });
    fireEvent.click(screen.getByTestId('identity-simulation-start'));

    await waitFor(
      () => expect(loadAuthoringIdentitySimulation).toHaveBeenCalledWith('simulation-1'),
      { timeout: 2_000 },
    );
    await waitFor(() =>
      expect(screen.getByTestId('identity-simulation-banner')).toHaveAttribute(
        'data-status',
        'EXPIRED',
      ),
    );
    expect(screen.getByTestId('role-preview-target-select')).not.toBeDisabled();
  });
});
