import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { findBlockById } from '../utils/recursiveBlockWalker';
import type { PageSchemaV3 } from '../types';

const testModelFields = {
  customer: [
    {
      modelCode: 'customer',
      code: 'name',
      label: 'Name',
      type: 'text',
      component: 'input',
      required: true,
    },
    {
      modelCode: 'customer',
      code: 'email',
      label: 'Email',
      type: 'email',
      component: 'input',
      required: true,
    },
    {
      modelCode: 'customer',
      code: 'status',
      label: 'Status',
      type: 'enum',
      component: 'select',
      dictCode: 'customer_status',
    },
  ],
};

const typedModelFields = {
  customer: [
    {
      modelCode: 'customer',
      code: 'is_active',
      label: 'Active',
      type: 'boolean',
    },
    {
      modelCode: 'customer',
      code: 'annual_revenue',
      label: 'Annual Revenue',
      type: 'decimal',
    },
    {
      modelCode: 'customer',
      code: 'contract_file',
      label: 'Contract File',
      type: 'file',
    },
    {
      modelCode: 'customer',
      code: 'industry',
      label: 'Industry',
      type: 'enum',
    },
    {
      modelCode: 'customer',
      code: 'owner_id',
      label: 'Owner',
      type: 'relation',
      component: 'select',
      refTarget: {
        modelCode: 'user',
        valueField: 'pid',
        displayField: 'displayName',
      },
    },
  ],
};

describe('UnifiedDesignerWorkbench', () => {
  it('renders the unified three-panel workbench with the composite fixture', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    expect(screen.getByTestId('unified-designer-workbench')).toBeInTheDocument();
    expect(screen.getByTestId('unified-workbench-body')).toHaveClass(
      'overflow-auto',
      'xl:overflow-hidden',
    );
    expect(screen.getByTestId('unified-resource-panel')).toBeInTheDocument();
    expect(screen.getByTestId('unified-canvas-host')).toBeInTheDocument();
    expect(screen.getByTestId('unified-canvas-host')).toHaveClass(
      'overflow-auto',
      'xl:overflow-auto',
    );
    expect(screen.getByTestId('unified-inspector-host')).toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-form_customer')).toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-list_customer')).toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-dashboard_sales')).toBeInTheDocument();
  });

  it('keeps selection in sync between outline, canvas, and inspector', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name');
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveAttribute(
      'data-selected',
      'true',
    );
  });

  it('switches edit and layout modes without losing the selected object', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    expect(screen.getByTestId('unified-designer-workbench')).toHaveAttribute(
      'data-mode',
      'layout',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name');

    fireEvent.click(screen.getByTestId('designer-mode-edit'));

    expect(screen.getByTestId('unified-designer-workbench')).toHaveAttribute('data-mode', 'edit');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name');
  });

  it('switches to runtime preview using the current V3 document and returns to edit mode', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer Name' },
    });
    fireEvent.click(screen.getByTestId('designer-mode-preview'));

    expect(screen.getByTestId('unified-designer-workbench')).toHaveAttribute(
      'data-mode',
      'preview',
    );
    expect(screen.getByTestId('unified-runtime-preview')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-page-customer_workspace')).toBeInTheDocument();
    expect(screen.getByTestId('runtime-field-field_customer_name')).toHaveTextContent(
      'Customer Name',
    );
    expect(screen.queryByTestId('unified-resource-panel')).not.toBeInTheDocument();
    expect(screen.queryByTestId('unified-canvas-host')).not.toBeInTheDocument();
    expect(screen.queryByTestId('unified-inspector-host')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-mode-edit'));

    expect(screen.getByTestId('unified-designer-workbench')).toHaveAttribute('data-mode', 'edit');
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveTextContent(
      'Customer Name',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name');
  });

  it('writes schema-driven inspector changes back to the selected block', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer Name' },
    });

    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveTextContent(
      'Customer Name',
    );
  });

  it('edits container data source and dashboard layout from the basic inspector', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-list_customer'));

    expect(screen.getByTestId('inspector-field-dataSource.model')).toHaveValue('customer');
    expect(screen.getByTestId('inspector-field-props.selectionMode')).toHaveValue('');

    // The model control is a dropdown of published models plus a manual-entry
    // fallback; with no model list loaded (no API in jsdom) an arbitrary code is
    // bound through the `-manual` text input rather than the empty <select>.
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'account' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.selectionMode'), {
      target: { value: 'multiple' },
    });

    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('inspector-tab-advanced'));

    expect((screen.getByTestId('inspector-json-dataSource') as HTMLTextAreaElement).value).toContain(
      '"model": "account"',
    );
    expect((screen.getByTestId('inspector-json-props') as HTMLTextAreaElement).value).toContain(
      '"selectionMode": "multiple"',
    );

    fireEvent.click(screen.getByTestId('outline-item-dashboard_sales'));

    expect(screen.getByTestId('inspector-field-layout.rowHeight')).toHaveValue(80);
    expect(screen.getByTestId('inspector-field-layout.gap')).toHaveValue(16);

    fireEvent.change(screen.getByTestId('inspector-field-layout.rowHeight'), {
      target: { value: '96' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-layout.gap'), {
      target: { value: '20' },
    });
    fireEvent.click(screen.getByTestId('inspector-tab-advanced'));

    expect((screen.getByTestId('inspector-json-layout') as HTMLTextAreaElement).value).toContain(
      '"rowHeight": 96',
    );
    expect((screen.getByTestId('inspector-json-layout') as HTMLTextAreaElement).value).toContain(
      '"gap": 20',
    );
  });

  it('renders localized title values in the inspector as editable text', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));

    expect(screen.getByTestId('inspector-field-title')).toHaveValue('Basic Information');
  });

  it('writes advanced JSON inspector changes back to structured block fields', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-action_import'));
    fireEvent.click(screen.getByTestId('inspector-tab-advanced'));
    fireEvent.change(screen.getByTestId('inspector-json-props'), {
      target: {
        value: JSON.stringify({ label: 'Bulk import', command: 'customer.bulk_import' }, null, 2),
      },
    });
    fireEvent.click(screen.getByTestId('inspector-json-apply-props'));

    expect(screen.getByTestId('canvas-block-action_import')).toHaveTextContent('Bulk import');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('renders action-type specific inspector fields for button configuration', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-action_import'));

    expect(screen.getByTestId('inspector-field-actionType')).toHaveValue('command');
    expect(screen.getByTestId('inspector-field-props.command')).toHaveValue('customer.import');
    expect(screen.queryByTestId('inspector-field-props.to')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('inspector-field-actionType'), {
      target: { value: 'navigate' },
    });

    expect(screen.getByTestId('inspector-field-props.to')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.target')).toBeInTheDocument();
    expect(screen.queryByTestId('inspector-field-props.command')).not.toBeInTheDocument();

    fireEvent.change(screen.getByTestId('inspector-field-props.to'), {
      target: { value: '/customers/:id' },
    });

    expect(screen.getByTestId('inspector-field-props.to')).toHaveValue('/customers/:id');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('edits action JSON parameters in the basic inspector without falling back to advanced JSON', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-action_import'));

    expect(screen.getByTestId('inspector-field-props.payload').tagName).toBe('TEXTAREA');

    fireEvent.change(screen.getByTestId('inspector-field-props.payload'), {
      target: { value: '{ invalid json' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.payload'));

    expect(screen.getByTestId('inspector-json-field-error-props.payload')).toHaveTextContent(
      'JSON 格式错误',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');

    fireEvent.change(screen.getByTestId('inspector-field-props.payload'), {
      target: { value: '{ "scope": "selected" }' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.payload'));

    expect(screen.queryByTestId('inspector-json-field-error-props.payload')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('inspector-tab-advanced'));

    expect((screen.getByTestId('inspector-json-props') as HTMLTextAreaElement).value).toContain(
      '"scope": "selected"',
    );
  });

  it('edits action form validation behavior from the schema-driven inspector', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} onSave={onSave} />);

    fireEvent.click(screen.getByTestId('outline-item-action_import'));

    const validateFormInput = screen.getByTestId('inspector-field-props.validateForm');
    expect(validateFormInput).toBeChecked();

    fireEvent.click(validateFormInput);

    expect(validateFormInput).not.toBeChecked();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('已保存');
    const savedDocument = onSave.mock.calls[0][0];
    const savedBlock = findBlockById(savedDocument.blocks, 'action_import')?.block;
    expect(savedBlock?.props).toMatchObject({ validateForm: false });
  });

  it('keeps invalid advanced JSON local and shows an inline error', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-action_import'));
    fireEvent.click(screen.getByTestId('inspector-tab-advanced'));
    fireEvent.change(screen.getByTestId('inspector-json-props'), {
      target: { value: '{ invalid json' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-apply-props'));

    expect(screen.getByTestId('inspector-json-error-props')).toHaveTextContent('JSON 格式错误');
    expect(screen.getByTestId('canvas-block-action_import')).toHaveTextContent('Import');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
  });

  it('tracks dirty state and saves the edited V3 document', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} onSave={onSave} />);

    expect(screen.getByTestId('designer-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer Name' },
    });

    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    expect(screen.getByTestId('designer-save')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('已保存');
    expect(onSave).toHaveBeenCalledWith(
      expect.objectContaining({
        schemaVersion: 3,
        id: 'customer_workspace',
      }),
    );
    expect(screen.getByTestId('designer-save')).toBeDisabled();
  });

  it('shows rejected save errors without clearing the dirty document', async () => {
    const onSave = vi.fn().mockRejectedValue(new Error('Backend rejected PageSchema V3.'));
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} onSave={onSave} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer Name' },
    });
    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => {
      expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('保存失败');
    });
    expect(screen.getByTestId('designer-save-error')).toHaveTextContent(
      'Backend rejected PageSchema V3.',
    );
    expect(screen.getByTestId('designer-save')).not.toBeDisabled();

    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer legal name' },
    });

    expect(screen.queryByTestId('designer-save-error')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('guards the return link when the document has unsaved changes', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        returnHref="/p/page_schema"
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer legal name' },
    });
    fireEvent.click(screen.getByTestId('designer-return-link'));

    expect(screen.getByTestId('designer-leave-warning')).toHaveTextContent('有未保存的更改');
    expect(screen.getByTestId('designer-leave-confirm')).toHaveAttribute('href', '/p/page_schema');

    fireEvent.click(screen.getByTestId('designer-leave-cancel'));

    expect(screen.queryByTestId('designer-leave-warning')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('reorders form fields, list columns, and toolbar actions in layout mode', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    // Drag reorder runs through @dnd-kit (verified in the browser + the pure
    // resolveDragEndAction unit test); the accessible quick-order buttons cover
    // the same move-before outcome deterministically in jsdom.
    fireEvent.click(screen.getByTestId('block-move-up-field_customer_phone'));
    fireEvent.click(screen.getByTestId('block-move-up-column_status'));
    fireEvent.click(screen.getByTestId('block-move-up-action_import'));

    expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
      true,
    );
    expect(isBefore('canvas-block-column_status', 'canvas-block-column_title')).toBe(true);
    expect(isBefore('canvas-block-action_import', 'canvas-block-action_create')).toBe(true);
  });

  it('resizes dashboard widgets from the layout canvas handle', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-widget_trend'));
    expect(screen.getByTestId('inspector-field-layout.w')).toHaveValue(6);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    fireEvent.pointerDown(screen.getByTestId('widget-resize-widget_trend'), {
      clientX: 0,
      clientY: 0,
    });
    fireEvent.mouseMove(window, { clientX: 160, clientY: 64 });
    fireEvent.mouseUp(window);

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('widget_trend');
    expect(screen.getByTestId('inspector-field-layout.w')).toHaveValue(8);
    expect(screen.getByTestId('inspector-field-layout.h')).toHaveValue(4);
  });

  it('keeps list-kind widgets draggable as ordinary canvas blocks', () => {
    const listWidgetDocument: PageSchemaV3 = {
      schemaVersion: 3,
      kind: 'list',
      id: 'list_widget_document',
      blocks: [
        {
          id: 'list_root',
          blockType: 'list',
          dataSource: { model: 'page_schema' },
          blocks: [
            {
              id: 'widget_move_candidate',
              blockType: 'widget',
              widgetType: 'number-card',
              layout: { span: 12, x: 0, y: 0, w: 3, h: 2 },
              props: { title: 'Candidate metric' },
            },
          ],
        },
      ],
    };

    render(<UnifiedDesignerWorkbench initialDocument={listWidgetDocument} />);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    expect(screen.getByTestId('block-drag-handle-widget_move_candidate')).toBeInTheDocument();
    expect(screen.queryByTestId('widget-resize-widget_move_candidate')).not.toBeInTheDocument();
    expect(screen.getByTestId('field-span-controls-widget_move_candidate')).toBeInTheDocument();
  });

  it('changes form field span from layout mode quick controls', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    expect(screen.getByTestId('inspector-field-layout.span')).toHaveValue(6);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    expect(screen.getByTestId('field-span-controls-field_customer_name')).toHaveClass(
      'grid',
      'grid-cols-5',
      'w-full',
    );
    fireEvent.click(screen.getByTestId('field-span-field_customer_name-12'));

    expect(screen.getByTestId('inspector-field-layout.span')).toHaveValue(12);
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveAttribute(
      'data-layout-span',
      '12',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('moves form fields with layout mode quick order controls', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    expect(screen.getByTestId('block-move-up-field_customer_name')).toBeDisabled();
    expect(screen.getByTestId('block-move-down-field_customer_name')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('block-move-down-field_customer_name'));

    expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
      true,
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_name');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('block-move-up-field_customer_name'));

    expect(isBefore('canvas-block-field_customer_name', 'canvas-block-field_customer_phone')).toBe(
      true,
    );
  });

  it('undoes and redoes layout reorder operations without losing dirty-state accuracy', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    expect(screen.getByTestId('designer-undo')).toBeDisabled();
    expect(screen.getByTestId('designer-redo')).toBeDisabled();

    fireEvent.click(screen.getByTestId('block-move-down-field_customer_name'));

    expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
      true,
    );
    expect(screen.getByTestId('designer-undo')).not.toBeDisabled();
    expect(screen.getByTestId('designer-redo')).toBeDisabled();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-undo'));

    expect(isBefore('canvas-block-field_customer_name', 'canvas-block-field_customer_phone')).toBe(
      true,
    );
    expect(screen.getByTestId('designer-undo')).toBeDisabled();
    expect(screen.getByTestId('designer-redo')).not.toBeDisabled();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
    expect(screen.getByTestId('designer-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('designer-redo'));

    expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
      true,
    );
    expect(screen.getByTestId('designer-undo')).not.toBeDisabled();
    expect(screen.getByTestId('designer-redo')).toBeDisabled();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
    expect(screen.getByTestId('designer-save')).not.toBeDisabled();
  });

  it('moves dashboard widgets in layout mode and writes dashboard grid coordinates', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-widget_revenue'));
    expect(screen.getByTestId('inspector-field-layout.x')).toHaveValue(0);
    expect(screen.getByTestId('inspector-field-layout.y')).toHaveValue(0);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    fireEvent.pointerDown(screen.getByTestId('canvas-block-widget_revenue'), {
      clientX: 0,
      clientY: 0,
    });
    fireEvent.mouseMove(window, { clientX: 0, clientY: 240 });
    fireEvent.mouseUp(window);
    fireEvent.click(screen.getByTestId('canvas-block-dashboard_sales'));

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('widget_revenue');
    expect(screen.getByTestId('inspector-field-layout.x')).toHaveValue(0);
    expect(screen.getByTestId('inspector-field-layout.y')).toHaveValue(3);
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('rejects dashboard widget moves that would overlap another widget', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-widget_revenue'));
    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    fireEvent.pointerDown(screen.getByTestId('canvas-block-widget_revenue'), {
      clientX: 0,
      clientY: 0,
    });
    fireEvent.mouseMove(window, { clientX: 20, clientY: 0 });
    fireEvent.mouseMove(window, { clientX: 240, clientY: 0 });
    fireEvent.mouseUp(window);
    fireEvent.click(screen.getByTestId('canvas-block-dashboard_sales'));

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('widget_revenue');
    expect(screen.getByTestId('inspector-field-layout.x')).toHaveValue(0);
    expect(screen.getByTestId('inspector-field-layout.y')).toHaveValue(0);
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
  });

  it('adds a custom (unbound) field to the selected parent from the field library escape hatch', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('field-palette-add-field'));

    expect(screen.getByTestId('canvas-block-field_new_field')).toHaveTextContent('New field');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_new_field');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('adds and configures a sub-table inside a form section', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-sub-table'));

    expect(screen.getByTestId('canvas-block-sub_table_new_sub_table')).toHaveTextContent(
      '新子表',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent(
      'sub_table_new_sub_table',
    );

    fireEvent.change(screen.getByTestId('inspector-field-title'), {
      target: { value: 'Line items' },
    });
    // Bind the sub-table model via the manual-entry fallback (the <select> has no
    // options without a loaded model list in jsdom).
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model-manual'), {
      target: { value: 'customer' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.parentField'), {
      target: { value: 'id' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.childField'), {
      target: { value: 'customer_id' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.rows'), {
      target: { value: '[{"name":"Ada"}]' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.rows'));

    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.change(screen.getByTestId('field-palette-search'), {
      target: { value: 'name' },
    });
    fireEvent.click(screen.getByTestId('model-field-name'));

    expect(screen.getByTestId('canvas-block-column_name')).toHaveTextContent('Name');

    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const savedDocument = onSave.mock.calls[0][0];
    const subTableBlock = findBlockById(savedDocument.blocks, 'sub_table_new_sub_table')?.block;
    expect(subTableBlock).toMatchObject({
      blockType: 'sub-table',
      title: 'Line items',
      dataSource: {
        model: 'customer',
        parentField: 'id',
        childField: 'customer_id',
      },
      props: {
        rows: [{ name: 'Ada' }],
      },
    });
    expect(subTableBlock?.blocks?.[0]).toMatchObject({
      blockType: 'column',
      field: 'name',
    });
  });

  it('adds and configures an editable repeater inside a form section', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-repeater'));

    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveTextContent(
      '新重复项',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('repeater_new_repeater');

    fireEvent.change(screen.getByTestId('inspector-field-title'), {
      target: { value: 'Line items' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.rows'), {
      target: { value: '[{"name":"Ada"}]' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.rows'));

    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.change(screen.getByTestId('field-palette-search'), {
      target: { value: 'name' },
    });
    fireEvent.click(screen.getByTestId('model-field-name'));

    expect(screen.getByTestId('canvas-block-field_name')).toHaveTextContent('Name');

    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const savedDocument = onSave.mock.calls[0][0];
    const repeaterBlock = findBlockById(savedDocument.blocks, 'repeater_new_repeater')?.block;
    expect(repeaterBlock).toMatchObject({
      blockType: 'repeater',
      title: 'Line items',
      props: {
        rows: [{ name: 'Ada' }],
      },
    });
    expect(repeaterBlock?.blocks?.[0]).toMatchObject({
      blockType: 'field',
      field: 'name',
    });
  });

  it('adds and configures a nested subform row editor inside a form section', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-subform'));

    expect(screen.getByTestId('canvas-block-subform_new_subform')).toHaveTextContent(
      '新子表单',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('subform_new_subform');

    fireEvent.change(screen.getByTestId('inspector-field-title'), {
      target: { value: 'Team members' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.rows'), {
      target: { value: '[{"name":"Ada"}]' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.rows'));

    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-form-section'));
    expect(screen.getByTestId('canvas-block-form_section_new_section')).toHaveTextContent(
      '新分组',
    );

    fireEvent.change(screen.getByTestId('inspector-field-title'), {
      target: { value: 'Member details' },
    });

    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.change(screen.getByTestId('field-palette-search'), {
      target: { value: 'name' },
    });
    fireEvent.click(screen.getByTestId('model-field-name'));

    expect(screen.getByTestId('canvas-block-field_name')).toHaveTextContent('Name');

    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const savedDocument = onSave.mock.calls[0][0];
    const subformBlock = findBlockById(savedDocument.blocks, 'subform_new_subform')?.block;
    expect(subformBlock).toMatchObject({
      blockType: 'subform',
      title: 'Team members',
      props: {
        rows: [{ name: 'Ada' }],
      },
    });
    expect(subformBlock?.blocks?.[0]).toMatchObject({
      id: 'form_section_new_section',
      blockType: 'form-section',
      title: 'Member details',
      blocks: [
        expect.objectContaining({
          blockType: 'field',
          field: 'name',
        }),
      ],
    });
  });

  it('marks palette actions added to a table as row actions in the V3 document', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} onSave={onSave} />);

    fireEvent.click(screen.getByTestId('outline-item-table_customers'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-action'));

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('action_new_action');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-save'));

    await waitFor(() => expect(onSave).toHaveBeenCalledTimes(1));
    const savedDocument = onSave.mock.calls[0][0];
    const tableBlock = findBlockById(savedDocument.blocks, 'table_customers')?.block;
    const rowAction = tableBlock?.blocks?.find((block) => block.id === 'action_new_action');
    expect(rowAction).toMatchObject({
      blockType: 'action',
      region: 'row-actions',
    });
  });

  it('drags a model field into a form section and creates a field block from metadata', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-email')).toHaveTextContent('Email');

    fireEvent.click(screen.getByTestId('model-field-email'));

    expect(screen.getByTestId('canvas-block-field_email')).toHaveTextContent('Email');
    expect(screen.getByTestId('canvas-block-field_email')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_email');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Email');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('selects model fields from the inspector and writes the selected field to V3', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);

    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
        onSave={onSave}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));

    expect(screen.getByTestId('inspector-field-field').tagName).toBe('SELECT');
    expect(screen.getByTestId('inspector-field-field')).toHaveValue('name');
    expect(screen.getByTestId('inspector-field-field')).toHaveTextContent('Email');

    fireEvent.change(screen.getByTestId('inspector-field-field'), {
      target: { value: 'status' },
    });

    expect(screen.getByTestId('inspector-field-field')).toHaveValue('status');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Status');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('select');
    expect(screen.getByTestId('inspector-field-props.dataType')).toHaveValue('enum');
    expect(screen.getByTestId('inspector-field-props.dictCode')).toHaveValue('customer_status');
    expect(screen.getByTestId('inspector-field-props.required')).not.toBeChecked();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('已保存');
    const savedDocument = onSave.mock.calls[0][0];
    const savedBlock = findBlockById(savedDocument.blocks, 'field_customer_name')?.block;
    expect(savedBlock?.field).toBe('status');
    expect(savedBlock?.props).toMatchObject({
      label: 'Status',
      component: 'select',
      dataType: 'enum',
      dictCode: 'customer_status',
      required: false,
    });
  });

  it('shows advanced form component settings for picker and rich text fields', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));

    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('选择器');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('富文本');

    fireEvent.change(screen.getByTestId('inspector-field-props.component'), {
      target: { value: 'picker' },
    });

    expect(screen.getByTestId('inspector-field-props.pickerSource')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.pickerDataSource')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.pickerQueryCode')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.valueField')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.displayField')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.searchable')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.searchPlaceholder')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.searchField')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.searchParameter')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.pageSize')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.pickerParameters')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.pickerDataSource')).toHaveTextContent(
      '模型',
    );
    expect(screen.getByTestId('inspector-field-props.pickerDataSource')).toHaveTextContent(
      '命名查询',
    );

    fireEvent.change(screen.getByTestId('inspector-field-props.pickerDataSource'), {
      target: { value: 'named-query' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.pickerSource'), {
      target: { value: 'user' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.pickerQueryCode'), {
      target: { value: 'udw_user_options' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.valueField'), {
      target: { value: 'id' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.displayField'), {
      target: { value: 'name' },
    });
    fireEvent.click(screen.getByTestId('inspector-field-props.searchable'));
    fireEvent.change(screen.getByTestId('inspector-field-props.searchPlaceholder'), {
      target: { value: 'Search users' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.searchField'), {
      target: { value: 'name' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.searchParameter'), {
      target: { value: 'keyword' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.pageSize'), {
      target: { value: '50' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.pickerParameters'), {
      target: { value: '{"status":"active"}' },
    });

    fireEvent.change(screen.getByTestId('inspector-field-props.component'), {
      target: { value: 'rich-text' },
    });

    expect(screen.getByTestId('inspector-field-props.richTextToolbar')).toBeInTheDocument();
    expect(screen.queryByTestId('inspector-field-props.pickerSource')).not.toBeInTheDocument();
    expect(screen.queryByTestId('inspector-field-props.pickerDataSource')).not.toBeInTheDocument();
    expect(screen.queryByTestId('inspector-field-props.searchable')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('offers radio as a structured form component with options editing', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));

    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('单选框');

    fireEvent.change(screen.getByTestId('inspector-field-props.component'), {
      target: { value: 'radio' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.options'), {
      target: { value: '[{"label":"Low","value":"low"}]' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.options'));

    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('radio');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('shows upload-specific form component settings in the field inspector', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.component'), {
      target: { value: 'upload' },
    });

    expect(screen.getByTestId('inspector-field-props.accept')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.multiple')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-field-props.maxFiles')).toBeInTheDocument();

    fireEvent.change(screen.getByTestId('inspector-field-props.accept'), {
      target: { value: '.pdf,.docx' },
    });
    fireEvent.click(screen.getByTestId('inspector-field-props.multiple'));
    fireEvent.change(screen.getByTestId('inspector-field-props.maxFiles'), {
      target: { value: '2' },
    });

    expect(screen.getByTestId('inspector-field-props.accept')).toHaveValue('.pdf,.docx');
    expect(screen.getByTestId('inspector-field-props.multiple')).toBeChecked();
    expect(screen.getByTestId('inspector-field-props.maxFiles')).toHaveValue(2);
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('syncs filter metadata when the inspector field selection changes', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-list_filters'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('model-field-name'));

    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('filter_name');
    expect(screen.getByTestId('inspector-field-field')).toHaveValue('name');
    expect(screen.getByTestId('inspector-field-props.operator')).toHaveValue('contains');

    fireEvent.change(screen.getByTestId('inspector-field-field'), {
      target: { value: 'status' },
    });

    expect(screen.getByTestId('inspector-field-field')).toHaveValue('status');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Status');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('select');
    expect(screen.getByTestId('inspector-field-props.dataType')).toHaveValue('enum');
    expect(screen.getByTestId('inspector-field-props.dictCode')).toHaveValue('customer_status');
    expect(screen.getByTestId('inspector-field-props.operator')).toHaveValue('equals');
  });

  it('marks already used model fields and filters field palette by search text', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-name')).toBeDisabled();
    expect(screen.getByTestId('model-field-name')).toHaveAttribute('data-used', 'true');
    expect(screen.getByTestId('model-field-name')).toHaveTextContent('已添加');
    expect(screen.getByTestId('model-field-email')).not.toBeDisabled();

    fireEvent.change(screen.getByTestId('field-palette-search'), {
      target: { value: 'sta' },
    });

    expect(screen.queryByTestId('model-field-email')).not.toBeInTheDocument();
    expect(screen.queryByTestId('model-field-name')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-field-status')).toHaveTextContent('Status');
  });

  it('shows model field type badges and filters field palette by type', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={typedModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-type-is_active')).toHaveTextContent('boolean');
    expect(screen.getByTestId('model-field-type-annual_revenue')).toHaveTextContent('decimal');
    expect(screen.getByTestId('model-field-type-contract_file')).toHaveTextContent('file');

    fireEvent.change(screen.getByTestId('field-palette-search'), {
      target: { value: 'file' },
    });

    expect(screen.queryByTestId('model-field-is_active')).not.toBeInTheDocument();
    expect(screen.queryByTestId('model-field-annual_revenue')).not.toBeInTheDocument();
    expect(screen.getByTestId('model-field-contract_file')).toHaveTextContent('Contract File');
  });

  it('maps common model field types to default form components', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={typedModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    fireEvent.click(screen.getByTestId('model-field-is_active'));
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('checkbox');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    fireEvent.click(screen.getByTestId('model-field-annual_revenue'));
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('number');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    fireEvent.click(screen.getByTestId('model-field-contract_file'));
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('upload');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    fireEvent.click(screen.getByTestId('model-field-industry'));
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('select');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    fireEvent.click(screen.getByTestId('model-field-owner_id'));
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('picker');
    expect(screen.getByTestId('inspector-field-props.pickerDataSource')).toHaveValue('model');
    expect(screen.getByTestId('inspector-field-props.pickerSource')).toHaveValue('user');
    expect(screen.getByTestId('inspector-field-props.valueField')).toHaveValue('pid');
    expect(screen.getByTestId('inspector-field-props.displayField')).toHaveValue('displayName');
  });

  it('drags model fields into list table and filter targets using the target block type', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-table_customers'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-email')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('model-field-email'));

    expect(screen.getByTestId('canvas-block-column_email')).toHaveTextContent('Email');
    expect(screen.getByTestId('canvas-block-column_email')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('column_email');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Email');

    fireEvent.click(screen.getByTestId('resource-tab-outline'));
    fireEvent.click(screen.getByTestId('outline-item-list_filters'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-email')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('model-field-email'));

    expect(screen.getByTestId('canvas-block-filter_email')).toHaveTextContent('Email');
    expect(screen.getByTestId('canvas-block-filter_email')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('filter_email');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Email');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });

  it('adds a model field before the selected compatible sibling from the field palette', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_phone'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    expect(screen.getByTestId('model-field-email')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('model-field-email'));

    expect(screen.getByTestId('canvas-block-field_email')).toHaveTextContent('Email');
    expect(isBefore('canvas-block-field_email', 'canvas-block-field_customer_phone')).toBe(true);
    expect(screen.getByTestId('canvas-block-field_email')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_email');
  });

  it('disables incompatible palette blocks for the current selection', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    expect(screen.getByTestId('palette-add-repeater')).not.toBeDisabled();
    expect(screen.getByTestId('palette-add-widget')).toBeDisabled();
  });

  it('drops a palette block onto a compatible canvas parent', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    fireEvent.click(screen.getByTestId('palette-add-repeater'));

    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveTextContent('新重复项');
    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('repeater_new_repeater');
  });

  it('toggles canvas multi-selection with modifier-click and shows the batch bar', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    // Plain click selects a single block; no multi-select bar yet.
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'));
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.queryByTestId('multi-select-bar')).not.toBeInTheDocument();

    // shift+click adds a second block to the multi-selection -> bar appears.
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_phone'), { shiftKey: true });
    expect(screen.getByTestId('multi-select-bar')).toBeInTheDocument();
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 2 项');
    expect(screen.getByTestId('canvas-block-field_customer_phone')).toHaveAttribute(
      'data-multi-selected',
      'true',
    );
    // The modifier-clicked block also becomes the primary selection (inspector target).
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_customer_phone');

    // cmd+click adds a third (column from the table) -> count updates to 3.
    fireEvent.click(screen.getByTestId('canvas-block-column_title'), { metaKey: true });
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 3 项');
    expect(screen.getByTestId('canvas-block-column_title')).toHaveAttribute(
      'data-multi-selected',
      'true',
    );
  });

  it('removes a block from the multi-selection when modifier-clicked again', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_phone'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-column_title'), { shiftKey: true });
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 3 项');

    // Modifier-clicking an already-selected block toggles it back out.
    fireEvent.click(screen.getByTestId('canvas-block-column_title'), { ctrlKey: true });
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 2 项');
    expect(screen.getByTestId('canvas-block-column_title')).toHaveAttribute(
      'data-multi-selected',
      'false',
    );
  });

  it('clears the multi-selection on a plain click (returning to single select)', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_phone'), { shiftKey: true });
    expect(screen.getByTestId('multi-select-bar')).toBeInTheDocument();

    // A plain (no-modifier) click on a single block collapses the selection.
    fireEvent.click(screen.getByTestId('canvas-block-column_status'));
    expect(screen.queryByTestId('multi-select-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-field_customer_name')).toHaveAttribute(
      'data-multi-selected',
      'false',
    );
    expect(screen.getByTestId('canvas-block-column_status')).toHaveAttribute('data-selected', 'true');
  });

  it('clears the multi-selection with the bar clear button without deleting blocks', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_phone'), { shiftKey: true });

    fireEvent.click(screen.getByTestId('multi-select-clear'));

    expect(screen.queryByTestId('multi-select-bar')).not.toBeInTheDocument();
    // Both blocks remain on the canvas.
    expect(screen.getByTestId('canvas-block-field_customer_name')).toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-field_customer_phone')).toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
  });

  it('batch-deletes the multi-selected blocks in one undoable step', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_phone'), { shiftKey: true });
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 2 项');

    fireEvent.click(screen.getByTestId('multi-select-delete'));

    expect(screen.queryByTestId('canvas-block-field_customer_name')).not.toBeInTheDocument();
    expect(screen.queryByTestId('canvas-block-field_customer_phone')).not.toBeInTheDocument();
    expect(screen.queryByTestId('multi-select-bar')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');

    // A single undo restores both deleted blocks (one history step).
    fireEvent.click(screen.getByTestId('designer-undo'));
    expect(screen.getByTestId('canvas-block-field_customer_name')).toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-field_customer_phone')).toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('已保存');
  });

  it('skips undeletable root blocks during a batch delete and removes only deletable ones', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    // form_customer is a top-level (root) block and is not deletable; field_customer_name is.
    fireEvent.click(screen.getByTestId('canvas-block-form_customer'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    expect(screen.getByTestId('multi-select-count')).toHaveTextContent('已选 2 项');

    fireEvent.click(screen.getByTestId('multi-select-delete'));

    // Deletable child removed; undeletable root container kept.
    expect(screen.queryByTestId('canvas-block-field_customer_name')).not.toBeInTheDocument();
    expect(screen.getByTestId('canvas-block-form_customer')).toBeInTheDocument();
    expect(screen.queryByTestId('multi-select-bar')).not.toBeInTheDocument();
  });

  it('drops a palette block into the primary selection while a multi-selection is active', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    // Establish a multi-selection whose last (primary) block is a form section.
    fireEvent.click(screen.getByTestId('canvas-block-field_customer_name'), { shiftKey: true });
    fireEvent.click(screen.getByTestId('canvas-block-section_basic'), { shiftKey: true });
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('section_basic');

    // The drop context still follows the primary selection (selectedBlockId is
    // intact), so a palette add lands inside section_basic.
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));
    fireEvent.click(screen.getByTestId('palette-add-repeater'));

    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toBeInTheDocument();
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('repeater_new_repeater');
  });

  it('adds a page-level palette block to the canvas root', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    fireEvent.click(screen.getByTestId('palette-add-dashboard'));

    expect(screen.getByTestId('canvas-block-dashboard_new_dashboard')).toHaveTextContent(
      '新仪表盘',
    );
    expect(screen.getByTestId('canvas-block-dashboard_new_dashboard')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent(
      'dashboard_new_dashboard',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('未保存');
  });
});

function isBefore(firstTestId: string, secondTestId: string) {
  return Boolean(
    screen.getByTestId(firstTestId).compareDocumentPosition(screen.getByTestId(secondTestId)) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  );
}
