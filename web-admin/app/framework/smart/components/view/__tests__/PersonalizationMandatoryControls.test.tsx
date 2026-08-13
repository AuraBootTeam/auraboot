import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { ColumnSettingsPanel } from '../ColumnSettingsPanel';
import { ActionConfigPanel } from '~/framework/meta/rendering/pages/list/ActionConfigPanel';

describe('runtime personalization mandatory controls', () => {
  it('keeps a mandatory field visible through legacy config and view changes', () => {
    const onSave = vi.fn();
    render(
      <ColumnSettingsPanel
        open
        onClose={vi.fn()}
        allColumns={[
          { field: 'orderNo', label: '单号', mandatory: true },
          { field: 'remark', label: '备注' },
        ]}
        viewColumns={[
          { fieldCode: 'orderNo', visible: false },
          { fieldCode: 'remark', visible: true },
        ]}
        onSave={onSave}
      />,
    );

    const mandatory = screen.getByTestId('column-settings-visible-orderNo');
    expect(mandatory).toBeChecked();
    expect(mandatory).toBeDisabled();
    fireEvent.click(screen.getByTestId('column-settings-visible-remark'));
    expect(mandatory).toBeChecked();
    expect(screen.getByTestId('column-settings-visible-remark')).not.toBeChecked();
    fireEvent.click(screen.getByTestId('column-settings-save'));
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        columns: expect.arrayContaining([
          expect.objectContaining({ fieldCode: 'orderNo', visible: true }),
        ]),
      }),
    );
  });

  it('keeps a mandatory action visible but still allows toolbar/menu placement', () => {
    const onChange = vi.fn();
    render(
      <ActionConfigPanel
        buttons={[{ code: 'approve', label: '审批', mandatory: true }]}
        currentConfig={[{ code: 'approve', visible: false, pinned: true, order: 0 }]}
        resolveLabel={(button) => String(button.label)}
        onChange={onChange}
        onClose={vi.fn()}
      />,
    );

    const visibility = screen.getByTestId('action-config-visible-approve');
    expect(visibility).toBeDisabled();
    fireEvent.click(visibility);
    expect(onChange).not.toHaveBeenCalled();
    fireEvent.click(screen.getByTestId('action-config-pin-approve'));
    expect(onChange).toHaveBeenCalledWith(
      expect.arrayContaining([
        expect.objectContaining({ code: 'approve', visible: true, pinned: false }),
      ]),
    );
  });
});
