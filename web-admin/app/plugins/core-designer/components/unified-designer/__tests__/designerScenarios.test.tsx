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
});
