import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';

vi.mock('~/framework/meta/rendering/ControlledFieldRenderer', () => ({
  ControlledFieldRenderer: ({ field, onChange, error }: any) => (
    <div>
      <button type="button" data-testid={`field-${field.field}`} onClick={() => onChange('user-2')}>
        Select member
      </button>
      {error ? <span role="alert">{error}</span> : null}
    </div>
  ),
}));

import { BulkFieldCommandModal } from '../BulkFieldCommandModal';

describe('BulkFieldCommandModal', () => {
  it('requires the configured value and submits the exact picker result', async () => {
    const onSubmit = vi.fn(async () => undefined);
    render(
      <BulkFieldCommandModal
        open
        actionLabel="批量转移负责人"
        selectedCount={2}
        field={{
          field: 'crm_opp_owner',
          label: '新负责人',
          type: 'reference',
          component: 'MemberPicker',
          required: true,
        }}
        context={createExpressionContext({ locale: 'zh-CN' })}
        onClose={vi.fn()}
        onSubmit={onSubmit}
      />,
    );

    expect(screen.getByRole('dialog')).toHaveTextContent('2 条记录');
    expect(screen.getByTestId('bulk-field-command-submit')).toBeDisabled();

    fireEvent.click(screen.getByTestId('field-crm_opp_owner'));
    expect(screen.getByTestId('bulk-field-command-submit')).toBeEnabled();
    fireEvent.click(screen.getByTestId('bulk-field-command-submit'));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledWith('user-2'));
  });
});
