import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import {
  ColumnSettingsPanel,
  buildColumnSettingsRows,
  serializeColumnSettings,
} from '../ColumnSettingsPanel';

const columns = [
  {
    field: 'name',
    label: '商机名称',
    dataType: 'string',
    defaultVisible: true,
    defaultWidth: 200,
  },
  {
    field: 'amount',
    label: '预计金额',
    dataType: 'decimal',
    defaultVisible: false,
  },
  {
    field: 'updated_at',
    label: '更新时间',
    dataType: 'datetime',
    group: 'system' as const,
    defaultVisible: false,
  },
];

describe('ColumnSettingsPanel', () => {
  it('merges defaults with a SavedView and serializes a stable view payload', () => {
    const rows = buildColumnSettingsRows(columns, [
      { fieldCode: 'amount', visible: true, order: 0, frozen: true, frozenPosition: 'left' },
      { fieldCode: 'name', visible: true, width: 240, order: 1 },
    ]);

    expect(rows.map((row) => row.fieldCode)).toEqual(['amount', 'name', 'updated_at']);
    expect(serializeColumnSettings(rows)).toEqual([
      {
        fieldCode: 'amount',
        visible: true,
        order: 0,
        frozen: true,
        frozenPosition: 'left',
      },
      { fieldCode: 'name', visible: true, width: 240, order: 1, frozen: false },
      { fieldCode: 'updated_at', visible: false, order: 2, frozen: false },
    ]);
  });

  it('keeps one visible field and saves visibility, pinning and density together', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ColumnSettingsPanel
        open
        allColumns={columns}
        onClose={vi.fn()}
        onSave={onSave}
        rowHeight="medium"
      />,
    );

    expect(screen.getByTestId('column-settings-visible-name')).toBeDisabled();
    fireEvent.click(screen.getByTestId('column-settings-visible-amount'));
    expect(screen.getByTestId('column-settings-visible-name')).not.toBeDisabled();
    fireEvent.click(screen.getByTestId('column-settings-visible-name'));
    fireEvent.click(screen.getByTestId('column-settings-pin-left-amount'));
    fireEvent.click(screen.getByTestId('column-settings-density-short'));
    fireEvent.click(screen.getByTestId('column-settings-save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const payload = onSave.mock.calls[0][0];
    expect(payload.rowHeight).toBe('short');
    expect(payload.columns.find((column: any) => column.fieldCode === 'name').visible).toBe(false);
    expect(payload.columns.find((column: any) => column.fieldCode === 'amount')).toMatchObject({
      visible: true,
      frozen: true,
      frozenPosition: 'left',
    });
  });

  it('restores the DSL default layout and default row height', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <ColumnSettingsPanel
        open
        allColumns={columns}
        viewColumns={[
          { fieldCode: 'amount', visible: true, order: 0 },
          { fieldCode: 'name', visible: false, order: 1 },
        ]}
        rowHeight="tall"
        onClose={vi.fn()}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByTestId('column-settings-restore'));
    fireEvent.click(screen.getByTestId('column-settings-save'));
    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));

    expect(onSave.mock.calls[0][0]).toMatchObject({
      rowHeight: 'medium',
      columns: [
        { fieldCode: 'name', visible: true },
        { fieldCode: 'amount', visible: false },
        { fieldCode: 'updated_at', visible: false },
      ],
    });
  });
});
