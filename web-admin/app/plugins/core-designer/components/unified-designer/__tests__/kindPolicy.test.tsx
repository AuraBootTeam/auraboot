import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { UnifiedDesignerWorkbench } from '../workbench/UnifiedDesignerWorkbench';
import { getKindPolicy, isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import type { PageSchemaV3 } from '../types';

const formDocument: PageSchemaV3 = {
  schemaVersion: 3,
  kind: 'form',
  id: 'demo_form',
  title: 'Demo Form',
  modelCode: 'customer',
  blocks: [
    {
      id: 'form_root',
      blockType: 'form',
      title: 'Demo Form',
      layout: { span: 12 },
      blocks: [
        { id: 'section_basic', blockType: 'form-section', title: 'Basic', layout: { span: 12 }, blocks: [] },
      ],
    },
  ],
};

describe('kindPolicy', () => {
  it('allows form-family blocks but not other page kinds for a form page', () => {
    expect(isBlockTypeAllowedForKind('form', 'form-section')).toBe(true);
    expect(isBlockTypeAllowedForKind('form', 'field')).toBe(true);
    expect(isBlockTypeAllowedForKind('form', 'list')).toBe(false);
    expect(isBlockTypeAllowedForKind('form', 'detail')).toBe(false);
    expect(isBlockTypeAllowedForKind('form', 'dashboard')).toBe(false);
    expect(isBlockTypeAllowedForKind('form', 'widget')).toBe(false);
  });

  it('exposes the single root container per concrete kind, none for composite', () => {
    expect(getKindPolicy('form').rootBlockType).toBe('form');
    expect(getKindPolicy('list').rootBlockType).toBe('list');
    expect(getKindPolicy('detail').rootBlockType).toBe('detail');
    expect(getKindPolicy('dashboard').rootBlockType).toBe('dashboard');
    expect(getKindPolicy('composite').rootBlockType).toBeNull();
    expect(getKindPolicy('composite').allowedBlockTypes).toBeNull();
  });
});

describe('UnifiedDesignerWorkbench kind collapse', () => {
  it('hides other page-kind blocks from the palette on a form page', () => {
    render(<UnifiedDesignerWorkbench initialDocument={formDocument} />);
    fireEvent.click(screen.getByTestId('resource-tab-blocks'));

    expect(screen.getByTestId('palette-add-form-section')).toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-list')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-detail')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-dashboard')).not.toBeInTheDocument();
    expect(screen.queryByTestId('palette-add-widget')).not.toBeInTheDocument();
  });

  it('renders the kind label instead of "Composite canvas" on a form page', () => {
    render(<UnifiedDesignerWorkbench initialDocument={formDocument} />);
    expect(screen.queryByText('Composite canvas')).not.toBeInTheDocument();
    // Default locale is zh-CN
    expect(screen.getByText('表单')).toBeInTheDocument();
  });
});
