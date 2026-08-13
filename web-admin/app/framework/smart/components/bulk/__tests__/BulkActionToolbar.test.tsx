import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BulkActionToolbar, partitionBulkActions } from '../BulkActionToolbar';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

describe('BulkActionToolbar custom actions', () => {
  it('keeps only frequent non-danger actions inline and moves the rest to overflow', () => {
    const bulkActions = [
      { code: 'transfer' },
      { code: 'qualify' },
      { code: 'advance' },
      { code: 'lose', danger: true },
    ] as ButtonConfig[];

    expect(partitionBulkActions(bulkActions, true)).toEqual({
      inlineActions: bulkActions.slice(0, 2),
      overflowActions: bulkActions.slice(2),
    });
  });

  it('renders DSL-configured business bulk actions and passes selected ids', () => {
    const onBulkAction = vi.fn();
    const bulkActions: ButtonConfig[] = [
      {
        code: 'bulk_mitigate',
        label: 'Mitigate selected',
        action: {
          type: 'bulk_state_transition',
          command: 'pe:mitigate_dfm_risk',
        } as any,
      },
    ];

    render(
      <BulkActionToolbar
        selectedCount={2}
        selectedIds={['risk-1', 'risk-2']}
        modelCode="crm_risk_common"
        bulkActions={bulkActions}
        resolveActionLabel={(button) => String(button.label)}
        onBulkAction={onBulkAction}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Mitigate selected' }));

    expect(onBulkAction).toHaveBeenCalledWith(bulkActions[0], ['risk-1', 'risk-2']);
  });

  it('opens secondary actions from More and closes the menu with Escape', () => {
    const onBulkAction = vi.fn();
    const bulkActions = [
      { code: 'transfer', label: 'Transfer owner' },
      { code: 'qualify', label: 'Qualify' },
      { code: 'advance', label: 'Advance stage' },
      { code: 'lose', label: 'Mark lost', danger: true },
    ] as ButtonConfig[];

    render(
      <BulkActionToolbar
        selectedCount={2}
        selectedIds={['opp-1', 'opp-2']}
        modelCode="crm_opportunity"
        bulkActions={bulkActions}
        onBulkEdit={() => undefined}
        resolveActionLabel={(button) => String(button.label)}
        onBulkAction={onBulkAction}
      />,
    );

    expect(screen.getByRole('button', { name: 'Transfer owner' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Qualify' })).toBeVisible();
    expect(screen.queryByRole('button', { name: 'Advance stage' })).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('bulk-more-actions-btn'));
    expect(screen.getByRole('menuitem', { name: 'Advance stage' })).toBeVisible();
    expect(screen.getByRole('menuitem', { name: 'Mark lost' })).toBeVisible();

    fireEvent.keyDown(document, { key: 'Escape' });
    expect(screen.queryByTestId('bulk-more-actions-menu')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('bulk-more-actions-btn'));
    fireEvent.click(screen.getByRole('menuitem', { name: 'Advance stage' }));
    expect(onBulkAction).toHaveBeenCalledWith(bulkActions[2], ['opp-1', 'opp-2']);
    expect(screen.queryByTestId('bulk-more-actions-menu')).not.toBeInTheDocument();
  });
});
