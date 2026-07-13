/**
 * table-wysiwyg.test.tsx
 *
 * The list-page designer must render the `table` block as a real data table:
 *  - preview mode → the live RecordListView (real rows / dict chips / typed cells),
 *  - edit canvas → editable column cards laid out as a table header + representative rows.
 * Both are gated on model metadata being present (backward compatible: without it the
 * schematic path is used). RecordListView is mocked to isolate the wiring from fetch.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('~/framework/meta/rendering/blocks/RecordListView', () => ({
  __esModule: true,
  default: ({
    modelCode,
    columns,
  }: {
    modelCode: string;
    columns: Array<{ field: string; label?: unknown }>;
  }) => (
    <div data-testid="record-list-view" data-model={modelCode} data-col-count={columns.length}>
      {columns.map((c) => (
        <span key={c.field} data-testid={`rlv-col-${c.field}`}>
          {typeof c.label === 'string' ? c.label : c.field}
        </span>
      ))}
    </div>
  ),
}));

import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import { buildPreviewColumnConfigs } from '../runtime/platformTablePreview';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import type { ModelFieldDefinition, PageSchemaV3 } from '../types';

const modelFields: ModelFieldDefinition[] = [
  { modelCode: 'demo', code: 'demo_code', label: '编号', type: 'string' },
  { modelCode: 'demo', code: 'demo_status', label: '状态', type: 'enum', dictCode: 'demo_status_dict' },
  { modelCode: 'demo', code: 'demo_amount', label: '金额', type: 'decimal', component: 'moneyinput' },
];

const listSchema: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'list',
  id: 'list_page',
  modelCode: 'demo',
  blocks: [
    {
      id: 'list_root',
      blockType: 'list',
      blocks: [
        {
          id: 'table_root',
          blockType: 'table',
          blocks: [
            { id: 'col_code', blockType: 'column', field: 'demo_code' },
            { id: 'col_status', blockType: 'column', field: 'demo_status' },
            { id: 'col_amount', blockType: 'column', field: 'demo_amount' },
          ],
        },
      ],
    },
  ],
};

describe('table WYSIWYG', () => {
  it('buildPreviewColumnConfigs resolves label, dictCode and valueType from model fields', () => {
    const tableBlock = listSchema.blocks[0].blocks![0];
    const cols = buildPreviewColumnConfigs(tableBlock, modelFields);
    expect(cols).toEqual([
      { field: 'demo_code', label: '编号', valueType: undefined },
      { field: 'demo_status', label: '状态', dictCode: 'demo_status_dict', valueType: undefined },
      { field: 'demo_amount', label: '金额', valueType: 'currency' },
    ]);
  });

  it('renders the real data table (RecordListView) in preview when model metadata is available', () => {
    render(<RecursiveBlockRenderer schema={listSchema} modelFields={modelFields} />);
    const rlv = screen.getByTestId('record-list-view');
    expect(rlv).toHaveAttribute('data-model', 'demo');
    expect(rlv).toHaveAttribute('data-col-count', '3');
    expect(screen.getByTestId('rlv-col-demo_status')).toHaveTextContent('状态');
  });

  it('falls back to the schematic runtime table when model metadata is absent', () => {
    render(<RecursiveBlockRenderer schema={listSchema} />);
    expect(screen.queryByTestId('record-list-view')).toBeNull();
    // schematic path still renders the column blocks
    expect(screen.getByTestId('runtime-column-col_code')).toBeInTheDocument();
  });

  it('renders columns as editable cards with resolved headers on the edit canvas', () => {
    render(
      <UnifiedDesignerWorkbench initialDocument={listSchema} modelFieldsByModel={{ demo: modelFields }} />,
    );
    // Edit mode default: each column stays an individually selectable BlockFrame,
    // and its header resolves the model-field display label.
    const statusColumn = screen.getByTestId('canvas-block-col_status');
    expect(statusColumn).toBeInTheDocument();
    expect(statusColumn).toHaveTextContent('状态');
  });
});
