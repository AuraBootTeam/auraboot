/**
 * canvas-field-wysiwyg.test.tsx
 *
 * The edit-mode canvas must render the real platform control for a `field` block once the
 * page's model metadata is available (true WYSIWYG), instead of the field-code
 * placeholder. Without model metadata it stays on the legacy placeholder (backward
 * compatible). The real ControlledFieldRenderer is mocked here to isolate the
 * canvas → EditCanvasFieldPreview wiring from the platform control internals.
 */
import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import type { ModelFieldsByModel, PageSchemaV3 } from '../types';

vi.mock('~/framework/meta/rendering/ControlledFieldRenderer', () => ({
  ControlledFieldRenderer: ({ field }: { field: { field: string; component?: string } }) => (
    <div data-testid={`controlled-field-${field.field}`} data-component={field.component ?? ''}>
      controlled-field
    </div>
  ),
}));

const doc: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'form',
  id: 'wysiwyg_canvas_form',
  modelCode: 'demo_model',
  blocks: [
    {
      id: 'form_root',
      blockType: 'form',
      layout: { span: 12 },
      blocks: [
        {
          id: 'sec',
          blockType: 'form-section',
          layout: { span: 12 },
          blocks: [
            { id: 'field_color', blockType: 'field', field: 'demo_color', layout: { span: 12 } },
          ],
        },
      ],
    },
  ],
};

const modelFieldsByModel: ModelFieldsByModel = {
  demo_model: [
    {
      modelCode: 'demo_model',
      code: 'demo_color',
      label: '颜色标记',
      type: 'string',
      component: 'colorpicker',
    },
  ],
};

describe('Canvas field WYSIWYG', () => {
  it('renders the real platform control on the edit canvas when model metadata is available', () => {
    render(<UnifiedDesignerWorkbench initialDocument={doc} modelFieldsByModel={modelFieldsByModel} />);

    // Edit mode is the default; the field card body now renders the platform control.
    expect(screen.getByTestId('canvas-field-preview-field_color')).toBeInTheDocument();
    const control = screen.getByTestId('controlled-field-demo_color');
    expect(control).toHaveAttribute('data-component', 'colorpicker');
  });

  it('keeps the field-code placeholder when model metadata is absent (backward compatible)', () => {
    render(<UnifiedDesignerWorkbench initialDocument={doc} modelFieldsByModel={{}} />);

    expect(screen.queryByTestId('canvas-field-preview-field_color')).toBeNull();
    expect(screen.queryByTestId('controlled-field-demo_color')).toBeNull();
  });
});
