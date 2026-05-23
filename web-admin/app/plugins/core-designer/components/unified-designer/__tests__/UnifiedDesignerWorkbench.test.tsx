import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import { samplePageSchemaV3 } from '../fixtures/samplePageSchemaV3';
import { findBlockById } from '../utils/recursiveBlockWalker';

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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

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

    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model'), {
      target: { value: 'account' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.selectionMode'), {
      target: { value: 'multiple' },
    });

    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
      'Invalid JSON',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Saved');

    fireEvent.change(screen.getByTestId('inspector-field-props.payload'), {
      target: { value: '{ "scope": "selected" }' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.payload'));

    expect(screen.queryByTestId('inspector-json-field-error-props.payload')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('Saved');
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

    expect(screen.getByTestId('inspector-json-error-props')).toHaveTextContent('Invalid JSON');
    expect(screen.getByTestId('canvas-block-action_import')).toHaveTextContent('Import');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Saved');
  });

  it('tracks dirty state and saves the edited V3 document', async () => {
    const onSave = vi.fn().mockResolvedValue(undefined);
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} onSave={onSave} />);

    expect(screen.getByTestId('designer-save')).toBeDisabled();

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));
    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer Name' },
    });

    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
    expect(screen.getByTestId('designer-save')).not.toBeDisabled();

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('Saved');
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
      expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Save failed');
    });
    expect(screen.getByTestId('designer-save-error')).toHaveTextContent(
      'Backend rejected PageSchema V3.',
    );
    expect(screen.getByTestId('designer-save')).not.toBeDisabled();

    fireEvent.change(screen.getByTestId('inspector-field-props.label'), {
      target: { value: 'Customer legal name' },
    });

    expect(screen.queryByTestId('designer-save-error')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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

    expect(screen.getByTestId('designer-leave-warning')).toHaveTextContent('Unsaved changes');
    expect(screen.getByTestId('designer-leave-confirm')).toHaveAttribute('href', '/p/page_schema');

    fireEvent.click(screen.getByTestId('designer-leave-cancel'));

    expect(screen.queryByTestId('designer-leave-warning')).not.toBeInTheDocument();
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
  });

  it('reorders form fields, list columns, and toolbar actions in layout mode', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));

    dragBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name');
    dragBefore('canvas-block-column_status', 'canvas-block-column_title');
    dragBefore('canvas-block-action_import', 'canvas-block-action_create');

    expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
      true,
    );
    expect(isBefore('canvas-block-column_status', 'canvas-block-column_title')).toBe(true);
    expect(isBefore('canvas-block-action_import', 'canvas-block-action_create')).toBe(true);
  });

  it('swaps form fields with pointer drag in layout mode', async () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('designer-mode-layout'));
    const targetBlock = screen.getByTestId('canvas-block-field_customer_name');
    const originalElementFromPoint = document.elementFromPoint;
    Object.defineProperty(document, 'elementFromPoint', {
      configurable: true,
      value: vi.fn(() => targetBlock),
    });

    try {
      fireEvent.pointerDown(screen.getByTestId('canvas-block-field_customer_phone'), {
        button: 0,
        clientX: 10,
        clientY: 10,
      });
      fireEvent.mouseMove(window, { clientX: 80, clientY: 10 });
      fireEvent.mouseUp(window, { clientX: 80, clientY: 10 });
    } finally {
      if (originalElementFromPoint) {
        Object.defineProperty(document, 'elementFromPoint', {
          configurable: true,
          value: originalElementFromPoint,
        });
      } else {
        Reflect.deleteProperty(document, 'elementFromPoint');
      }
    }

    await waitFor(() => {
      expect(isBefore('canvas-block-field_customer_phone', 'canvas-block-field_customer_name')).toBe(
        true,
      );
    });
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

    fireEvent.click(screen.getByTestId('block-move-up-field_customer_name'));

    expect(isBefore('canvas-block-field_customer_name', 'canvas-block-field_customer_phone')).toBe(
      true,
    );
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Saved');
  });

  it('adds a custom (unbound) field to the selected parent from the field library escape hatch', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));
    fireEvent.click(screen.getByTestId('field-palette-add-field'));

    expect(screen.getByTestId('canvas-block-field_new_field')).toHaveTextContent('New field');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_new_field');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
      'New sub table',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent(
      'sub_table_new_sub_table',
    );

    fireEvent.change(screen.getByTestId('inspector-field-title'), {
      target: { value: 'Line items' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-dataSource.model'), {
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
      'New repeater',
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
      'New subform',
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
      'New section',
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

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

    dragModelFieldTo('model-field-email', 'canvas-block-section_basic');

    expect(screen.getByTestId('canvas-block-field_email')).toHaveTextContent('Email');
    expect(screen.getByTestId('canvas-block-field_email')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_email');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Email');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');

    fireEvent.click(screen.getByTestId('designer-save'));

    await screen.findByText('Saved');
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

    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('Picker');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('Rich text');

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
      'Model',
    );
    expect(screen.getByTestId('inspector-field-props.pickerDataSource')).toHaveTextContent(
      'Named query',
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
  });

  it('offers radio as a structured form component with options editing', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-field_customer_name'));

    expect(screen.getByTestId('inspector-field-props.component')).toHaveTextContent('Radio');

    fireEvent.change(screen.getByTestId('inspector-field-props.component'), {
      target: { value: 'radio' },
    });
    fireEvent.change(screen.getByTestId('inspector-field-props.options'), {
      target: { value: '[{"label":"Low","value":"low"}]' },
    });
    fireEvent.click(screen.getByTestId('inspector-json-field-apply-props.options'));

    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('radio');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
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
    expect(screen.getByTestId('model-field-name')).toHaveAttribute('draggable', 'false');
    expect(screen.getByTestId('model-field-name')).toHaveTextContent('已添加');
    expect(screen.getByTestId('model-field-email')).not.toBeDisabled();
    expect(screen.getByTestId('model-field-email')).toHaveAttribute('draggable', 'true');

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

    dragModelFieldTo('model-field-is_active', 'canvas-block-section_basic');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('checkbox');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    dragModelFieldTo('model-field-annual_revenue', 'canvas-block-section_basic');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('number');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    dragModelFieldTo('model-field-contract_file', 'canvas-block-section_basic');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('upload');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    dragModelFieldTo('model-field-industry', 'canvas-block-section_basic');
    expect(screen.getByTestId('inspector-field-props.component')).toHaveValue('select');

    fireEvent.click(screen.getByTestId('canvas-block-section_basic'));
    dragModelFieldTo('model-field-owner_id', 'canvas-block-section_basic');
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

    dragModelFieldTo('model-field-email', 'canvas-block-table_customers');

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

    dragModelFieldTo('model-field-email', 'canvas-block-list_filters');

    expect(screen.getByTestId('canvas-block-filter_email')).toHaveTextContent('Email');
    expect(screen.getByTestId('canvas-block-filter_email')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('filter_email');
    expect(screen.getByTestId('inspector-field-props.label')).toHaveValue('Email');
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
  });

  it('drops a model field before an existing compatible field block', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    dragModelFieldTo('model-field-email', 'canvas-block-field_customer_phone');

    expect(screen.getByTestId('canvas-block-field_email')).toHaveTextContent('Email');
    expect(isBefore('canvas-block-field_email', 'canvas-block-field_customer_phone')).toBe(true);
    expect(screen.getByTestId('canvas-block-field_email')).toHaveAttribute('data-selected', 'true');
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('field_email');
  });

  it('shows a before drop indicator while dragging a palette block over a compatible sibling', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    const dataTransfer = createDataTransfer();
    fireEvent.dragStart(screen.getByTestId('palette-add-repeater'), { dataTransfer });
    fireEvent.dragOver(screen.getByTestId('canvas-block-field_customer_phone'), { dataTransfer });

    expect(screen.getByTestId('canvas-block-field_customer_phone')).toHaveAttribute(
      'data-drop-intent',
      'before',
    );
    expect(screen.getByTestId('drop-indicator-before-field_customer_phone')).toBeInTheDocument();

    fireEvent.dragLeave(screen.getByTestId('canvas-block-field_customer_phone'));

    expect(screen.getByTestId('canvas-block-field_customer_phone')).toHaveAttribute(
      'data-drop-intent',
      'none',
    );
  });

  it('shows an inside drop indicator while dragging a model field over a compatible parent', () => {
    render(
      <UnifiedDesignerWorkbench
        initialDocument={samplePageSchemaV3}
        modelFieldsByModel={testModelFields}
      />,
    );

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-fields'));

    const dataTransfer = createDataTransfer();
    fireEvent.dragStart(screen.getByTestId('model-field-email'), { dataTransfer });
    fireEvent.dragOver(screen.getByTestId('canvas-block-section_basic'), { dataTransfer });

    expect(screen.getByTestId('canvas-block-section_basic')).toHaveAttribute(
      'data-drop-intent',
      'inside',
    );
    expect(screen.getByTestId('drop-indicator-inside-section_basic')).toBeInTheDocument();

    fireEvent.drop(screen.getByTestId('canvas-block-section_basic'), { dataTransfer });

    expect(screen.getByTestId('canvas-block-section_basic')).toHaveAttribute(
      'data-drop-intent',
      'none',
    );
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
    expect(screen.getByTestId('palette-add-repeater')).toHaveAttribute('draggable', 'true');
    expect(screen.getByTestId('palette-add-widget')).toBeDisabled();
    expect(screen.getByTestId('palette-add-widget')).toHaveAttribute('draggable', 'false');
  });

  it('drops a palette block onto a compatible canvas parent', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    dragPaletteTo('palette-add-repeater', 'canvas-block-section_basic');

    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveTextContent('New repeater');
    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('repeater_new_repeater');
  });

  it('drops a palette block before an existing compatible child block', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('outline-item-section_basic'));
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    dragPaletteTo('palette-add-repeater', 'canvas-block-field_customer_phone');

    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveTextContent('New repeater');
    expect(isBefore('canvas-block-repeater_new_repeater', 'canvas-block-field_customer_phone')).toBe(
      true,
    );
    expect(screen.getByTestId('canvas-block-repeater_new_repeater')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent('repeater_new_repeater');
  });

  it('drops a page-level palette block onto the canvas root', () => {
    render(<UnifiedDesignerWorkbench initialDocument={samplePageSchemaV3} />);

    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    dragPaletteTo('palette-add-dashboard', 'canvas-root-drop-zone');

    expect(screen.getByTestId('canvas-block-dashboard_new_dashboard')).toHaveTextContent(
      'New dashboard',
    );
    expect(screen.getByTestId('canvas-block-dashboard_new_dashboard')).toHaveAttribute(
      'data-selected',
      'true',
    );
    expect(screen.getByTestId('inspector-selected-id')).toHaveTextContent(
      'dashboard_new_dashboard',
    );
    expect(screen.getByTestId('designer-dirty-state')).toHaveTextContent('Unsaved');
  });
});

function dragBefore(movingTestId: string, targetTestId: string) {
  const movingId = movingTestId.replace('canvas-block-', '');
  const dataTransfer = createDataTransfer();
  fireEvent.dragStart(screen.getByTestId(movingTestId), { dataTransfer });
  dataTransfer.setData('text/plain', movingId);
  fireEvent.drop(screen.getByTestId(targetTestId), { dataTransfer });
}

function createDataTransfer() {
  const data = new Map<string, string>();
  return {
    setData: (type: string, value: string) => data.set(type, value),
    getData: (type: string) => data.get(type) ?? '',
  };
}

function isBefore(firstTestId: string, secondTestId: string) {
  return Boolean(
    screen.getByTestId(firstTestId).compareDocumentPosition(screen.getByTestId(secondTestId)) &
      Node.DOCUMENT_POSITION_FOLLOWING,
  );
}

function dragPaletteTo(paletteTestId: string, targetTestId: string) {
  const dataTransfer = createDataTransfer();
  fireEvent.dragStart(screen.getByTestId(paletteTestId), { dataTransfer });
  fireEvent.dragOver(screen.getByTestId(targetTestId), { dataTransfer });
  fireEvent.drop(screen.getByTestId(targetTestId), { dataTransfer });
}

function dragModelFieldTo(fieldTestId: string, targetTestId: string) {
  const dataTransfer = createDataTransfer();
  fireEvent.dragStart(screen.getByTestId(fieldTestId), { dataTransfer });
  fireEvent.dragOver(screen.getByTestId(targetTestId), { dataTransfer });
  fireEvent.drop(screen.getByTestId(targetTestId), { dataTransfer });
}
