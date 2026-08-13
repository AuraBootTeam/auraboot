import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';

const { batchUpdate } = vi.hoisted(() => ({ batchUpdate: vi.fn() }));
vi.mock('~/shared/services/dynamicService', () => ({
  dynamicService: { batchUpdate },
}));

import { BulkEditModal } from '../BulkEditModal';

describe('BulkEditModal', () => {
  it('keeps the modal open and surfaces partial batch failures', async () => {
    batchUpdate.mockResolvedValue({
      total: 2,
      success: 1,
      failed: 1,
      failedItems: [{ index: 1, error: 'record is outside the authorized scope' }],
    });
    const onClose = vi.fn();
    const onUpdateComplete = vi.fn();
    render(
      <BulkEditModal
        open
        onClose={onClose}
        modelCode="crm_opportunity_common"
        selectedIds={['opp-1', 'opp-2']}
        fields={[{ code: 'crm_opp_probability', name: '成功概率', dataType: 'integer' }]}
        onUpdateComplete={onUpdateComplete}
      />,
    );

    fireEvent.change(screen.getByTestId('bulk-edit-field'), {
      target: { value: 'crm_opp_probability' },
    });
    fireEvent.change(screen.getByTestId('bulk-edit-value'), { target: { value: '55' } });
    fireEvent.click(screen.getByRole('button', { name: '更新 2 条记录' }));

    await waitFor(() => expect(screen.getByRole('alert')).toHaveTextContent('authorized scope'));
    expect(onUpdateComplete).not.toHaveBeenCalled();
    expect(onClose).not.toHaveBeenCalled();
  });
});
