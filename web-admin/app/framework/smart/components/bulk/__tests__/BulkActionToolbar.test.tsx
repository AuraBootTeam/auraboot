import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { BulkActionToolbar } from '../BulkActionToolbar';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

describe('BulkActionToolbar custom actions', () => {
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
        modelCode="pe_dfm_risk"
        bulkActions={bulkActions}
        resolveActionLabel={(button) => String(button.label)}
        onBulkAction={onBulkAction}
      />,
    );

    fireEvent.click(screen.getByRole('button', { name: 'Mitigate selected' }));

    expect(onBulkAction).toHaveBeenCalledWith(bulkActions[0], ['risk-1', 'risk-2']);
  });
});
