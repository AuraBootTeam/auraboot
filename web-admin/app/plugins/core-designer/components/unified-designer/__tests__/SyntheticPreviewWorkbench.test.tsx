import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import {
  loadAuthoringRolePreviewTargets,
  loadAuthoringSyntheticPreview,
} from '~/framework/meta/authoring/authoringService';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';

vi.mock('~/framework/meta/authoring/authoringService', () => ({
  acknowledgeAuthoringIdentitySimulation: vi.fn(),
  endAuthoringIdentitySimulation: vi.fn(),
  loadActiveAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringIdentitySimulation: vi.fn(),
  loadAuthoringRolePreviewTargets: vi.fn(),
  loadAuthoringRoleStructurePreview: vi.fn(),
  loadAuthoringSyntheticPreview: vi.fn(),
  startAuthoringIdentitySimulation: vi.fn(),
}));

describe('UnifiedDesignerWorkbench synthetic preview', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(loadAuthoringRolePreviewTargets).mockResolvedValue([]);
    vi.mocked(loadAuthoringSyntheticPreview).mockResolvedValue({
      mode: 'SYNTHETIC',
      pagePid: 'page-1',
      source: 'GENERATED_IN_MEMORY',
      isolatedFromTenantData: true,
      persisted: false,
      exportAllowed: false,
      businessActionsAllowed: false,
      fixtureRevision: 3,
      formValues: {
        name: 'Sample customer 01',
        phone: '13800000001',
        pid: 'synthetic-001',
      },
      records: [
        {
          title: 'Sample customer 01',
          status: 'Sample status 01',
          pid: 'synthetic-001',
        },
        {
          title: 'Sample customer 02',
          status: 'Sample status 02',
          pid: 'synthetic-002',
        },
        {
          title: 'Sample customer 03',
          status: 'Sample status 03',
          pid: 'synthetic-003',
        },
      ],
      widgets: {
        widget_revenue: {
          source: 'GENERATED_IN_MEMORY',
          value: '128',
          series: [{ label: 'Sample A', value: 24 }],
        },
      },
    });
  });

  it('shows generated records without replaying an embedded tenant row', async () => {
    const document = structuredClone(samplePageSchemaV3);
    const table = document.blocks[1].blocks?.find((block) => block.blockType === 'table');
    if (!table) throw new Error('table fixture missing');
    table.props = {
      ...table.props,
      rows: [{ pid: 'real-record-7', title: 'REAL-TENANT-SECRET', status: 'real' }],
    };

    render(
      <UnifiedDesignerWorkbench
        initialDocument={document}
        roleStructurePreviewSessionPid="session-1"
      />,
    );

    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() => expect(screen.getByTestId('role-preview-target-select')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: '__synthetic_fixture__' },
    });

    expect(await screen.findByTestId('synthetic-preview-banner')).toHaveTextContent(
      '不查询真实租户记录',
    );
    expect(screen.getByTestId('synthetic-preview-banner')).toHaveTextContent('合成值不保存');
    expect(screen.getByTestId('synthetic-preview-record-count')).toHaveTextContent('3 条合成记录');
    expect(screen.getByTestId('runtime-input-field_customer_name')).toHaveValue(
      'Sample customer 01',
    );
    expect(screen.getByTestId('runtime-table-table_customers')).toHaveTextContent(
      'Sample customer 01',
    );
    expect(screen.getByTestId('runtime-page-customer_workspace').closest('fieldset')).toBeDisabled();
    expect(screen.queryByText('REAL-TENANT-SECRET')).not.toBeInTheDocument();
    expect(loadAuthoringSyntheticPreview).toHaveBeenCalledWith('session-1');
    expect(screen.getByTestId('designer-export')).toBeDisabled();

    fireEvent.click(screen.getByTestId('synthetic-preview-exit'));
    await waitFor(() => {
      expect(screen.queryByTestId('synthetic-preview-banner')).not.toBeInTheDocument();
    });
  });

  it('fails closed without falling back to current-actor live data', async () => {
    vi.mocked(loadAuthoringSyntheticPreview).mockRejectedValue(new Error('fixture denied'));

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        roleStructurePreviewSessionPid="session-1"
      />,
    );
    fireEvent.click(screen.getByTestId('designer-mode-preview'));
    await waitFor(() => expect(screen.getByTestId('role-preview-target-select')).toBeInTheDocument());
    fireEvent.change(screen.getByTestId('role-preview-target-select'), {
      target: { value: '__synthetic_fixture__' },
    });

    expect(await screen.findByTestId('synthetic-preview-error')).toHaveTextContent('fixture denied');
    expect(screen.getByTestId('synthetic-preview-fail-closed')).toBeInTheDocument();
    expect(screen.queryByTestId('runtime-page-customer_workspace')).not.toBeInTheDocument();
  });
});
