import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import { findBlockById } from '../utils/recursiveBlockWalker';
import type { ModelFieldDefinition, PageSchemaV3 } from '../types';

const formDocument: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'form',
  id: 'demo_form',
  title: { en: 'Demo Form', 'zh-CN': '演示表单' },
  modelCode: 'customer',
  blocks: [
    {
      id: 'form_root',
      blockType: 'form',
      title: { en: 'Demo', 'zh-CN': '演示' },
      layout: { span: 12 },
      blocks: [
        {
          id: 'section_basic',
          blockType: 'form-section',
          title: { en: 'Basic', 'zh-CN': '基本信息' },
          layout: { span: 12 },
          blocks: [
            { id: 'field_name', blockType: 'field', field: 'name', layout: { span: 6 }, props: { label: 'Name', component: 'input' } },
          ],
        },
      ],
    },
  ],
};

const modelFields: Record<string, ModelFieldDefinition[]> = {
  customer: [
    { modelCode: 'customer', code: 'email', label: 'Email', type: 'email', component: 'input' },
    { modelCode: 'customer', code: 'ltv', label: 'Lifetime value', type: 'decimal', virtual: true },
    { modelCode: 'customer', code: 'status', label: 'Status', type: 'enum', dictCode: 'cs' },
  ],
};

function openFieldsForSection() {
  render(<UnifiedDesignerWorkbench initialDocument={formDocument} modelFieldsByModel={modelFields} />);
  fireEvent.click(screen.getByTestId('outline-item-section_basic'));
  fireEvent.click(screen.getByTestId('resource-tab-fields'));
}

describe('Designer scenarios (form kind)', () => {
  it('renders the form kind label on the canvas band, not Composite', () => {
    render(<UnifiedDesignerWorkbench initialDocument={formDocument} modelFieldsByModel={modelFields} />);
    const band = screen.getByTestId('canvas-root-drop-zone');
    expect(band).toHaveTextContent('表单');
    expect(band).not.toHaveTextContent('组合页面');
    expect(band).not.toHaveTextContent('Composite');
  });

  it('flags a virtual model field with the virtual badge and count in the field library', () => {
    openFieldsForSection();
    expect(screen.getByTestId('model-field-virtual-ltv')).toBeInTheDocument();
    expect(screen.getByTestId('model-field-virtual-ltv')).toHaveTextContent('虚拟');
    // non-virtual fields carry no badge
    expect(screen.queryByTestId('model-field-virtual-email')).not.toBeInTheDocument();
    // footer count: 1 virtual of 3 fields
    expect(screen.getByTestId('field-palette')).toHaveTextContent('1 虚拟');
  });

  it('binds a model field into the selected section with auto component + data type', () => {
    openFieldsForSection();
    fireEvent.click(screen.getByTestId('model-field-email'));

    const created = screen.getByTestId('canvas-block-field_email');
    expect(created).toBeInTheDocument();
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_email');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('input');
    expect(screen.getByTestId('inspector-field-props.dataType')).toHaveValue('email');
  });

  it('binds a virtual field and maps its decimal type to a number component', () => {
    openFieldsForSection();
    fireEvent.click(screen.getByTestId('model-field-ltv'));

    expect(screen.getByTestId('canvas-block-field_ltv')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('number');
    expect(screen.getByTestId('inspector-field-props.dataType')).toHaveValue('decimal');
  });

  it('saves the document with the newly bound model field (save roundtrip)', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <UnifiedDesignerWorkbench
        initialDocument={formDocument}
        modelFieldsByModel={modelFields}
        onSave={onSave}
      />,
    );
    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('model-field-email'));

    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('已保存');
    expect(onSave).toHaveBeenCalledTimes(1);
    const saved = onSave.mock.calls[0][0] as PageSchemaV3;
    const boundField = findBlockById(saved.blocks, 'field_email');
    expect(boundField?.block.field).toBe('email');
    expect(boundField?.block.props?.component).toBe('input');
  });

  it('deletes a nested block via the canvas delete control and persists the removal', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(
      <UnifiedDesignerWorkbench
        initialDocument={formDocument}
        modelFieldsByModel={modelFields}
        onSave={onSave}
      />,
    );
    expect(screen.getByTestId('canvas-block-field_name')).toBeInTheDocument();

    fireEvent.click(screen.getByTestId('block-delete-field_name'));

    expect(screen.queryByTestId('canvas-block-field_name')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-save'));
    await screen.findByText('已保存');
    const saved = onSave.mock.calls[0][0] as PageSchemaV3;
    expect(findBlockById(saved.blocks, 'field_name')).toBeNull();
  });

  it('does not expose a delete control for the single root container', () => {
    render(<UnifiedDesignerWorkbench initialDocument={formDocument} modelFieldsByModel={modelFields} />);
    expect(screen.getByTestId('canvas-block-form_root')).toBeInTheDocument();
    expect(screen.queryByTestId('block-delete-form_root')).not.toBeInTheDocument();
  });
});

const listDocument: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'list',
  id: 'demo_list',
  title: { 'zh-CN': '演示列表', en: 'Demo List' },
  modelCode: 'customer',
  blocks: [
    {
      id: 'list_root',
      blockType: 'list',
      layout: { span: 12 },
      blocks: [
        { id: 'filters', blockType: 'filter-bar', region: 'filters', layout: { span: 12 }, blocks: [] },
        {
          id: 'tbl',
          blockType: 'table',
          layout: { span: 12 },
          blocks: [{ id: 'col_name', blockType: 'column', field: 'name', props: { label: 'Name' } }],
        },
      ],
    },
  ],
};

describe('Designer scenarios (list kind)', () => {
  it('collapses the palette to list blocks and hides other page kinds', () => {
    render(<UnifiedDesignerWorkbench initialDocument={listDocument} modelFieldsByModel={modelFields} />);
    expect(screen.getByTestId('canvas-root-drop-zone')).toHaveTextContent('列表');
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    expect(screen.getByTestId('palette-add-table')).toBeInTheDocument();
    expect(screen.getByTestId('palette-add-filter-bar')).toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-form-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-detail')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-dashboard')).not.toBeInTheDocument();
    // field-like leaf blocks are never in the palette
    expect(screen.queryByTestId('palette-add-column')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-filter-field')).not.toBeInTheDocument();
  });

  it('binds a model field as a column under a table', () => {
    render(<UnifiedDesignerWorkbench initialDocument={listDocument} modelFieldsByModel={modelFields} />);
    fireEvent.click(screen.getByTestId('outline-item-tbl'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('model-field-email'));
    expect(screen.getByTestId('canvas-block-column_email')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('column_email');
  });

  it('binds a model field as a filter-field under a filter bar', () => {
    render(<UnifiedDesignerWorkbench initialDocument={listDocument} modelFieldsByModel={modelFields} />);
    fireEvent.click(screen.getByTestId('outline-item-filters'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('model-field-status'));
    expect(screen.getByTestId('canvas-block-filter_status')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('filter_status');
  });
});

const detailDocument: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'detail',
  id: 'demo_detail',
  title: { 'zh-CN': '演示详情', en: 'Demo Detail' },
  modelCode: 'customer',
  blocks: [
    {
      id: 'detail_root',
      blockType: 'detail',
      layout: { span: 12 },
      blocks: [{ id: 'dsec', blockType: 'detail-section', layout: { span: 12 }, blocks: [] }],
    },
  ],
};

describe('Designer scenarios (detail kind)', () => {
  it('collapses the palette to detail blocks and binds a field into a detail section', () => {
    render(<UnifiedDesignerWorkbench initialDocument={detailDocument} modelFieldsByModel={modelFields} />);
    expect(screen.getByTestId('canvas-root-drop-zone')).toHaveTextContent('详情');
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    expect(screen.getByTestId('palette-add-detail-section')).toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-form-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-list')).not.toBeInTheDocument();

    fireEvent.click(screen.getByTestId('resource-tab-outline'));
    fireEvent.click(screen.getByTestId('outline-item-dsec'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('model-field-email'));
    expect(screen.getByTestId('canvas-block-field_email')).toBeInTheDocument();
  });
});

const dashboardDocument: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'dashboard',
  id: 'demo_dash',
  title: { 'zh-CN': '演示仪表盘', en: 'Demo Dashboard' },
  blocks: [
    {
      id: 'dash_root',
      blockType: 'dashboard',
      layout: { span: 12, type: 'dashboard-grid', cols: 12, rowHeight: 80, gap: 16 },
      blocks: [],
    },
  ],
};

describe('Designer scenarios (dashboard kind)', () => {
  it('exposes only widget blocks in the palette', () => {
    render(<UnifiedDesignerWorkbench initialDocument={dashboardDocument} />);
    expect(screen.getByTestId('canvas-root-drop-zone')).toHaveTextContent('仪表盘');
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    expect(screen.getByTestId('palette-add-widget')).toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-form-section')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-detail-section')).not.toBeInTheDocument();
  });
});
