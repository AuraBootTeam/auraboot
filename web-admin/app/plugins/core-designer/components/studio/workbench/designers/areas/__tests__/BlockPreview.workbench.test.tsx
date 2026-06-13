import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DndContext } from '@dnd-kit/core';

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US' }),
}));

vi.mock('../hooks/useBlockDropZone', () => ({
  useBlockDropZone: () => ({
    setNodeRef: vi.fn(),
    showDropIndicator: false,
    dropLabel: '',
    canAcceptFields: false,
  }),
}));

import { BlockPreview } from '../previews/BlockPreview';
import type { DslBlock } from '~/plugins/core-designer/components/studio/domain/dsl/types';

function renderPreview(blockType: DslBlock['blockType']) {
  render(
    <BlockPreview
      block={{ id: `${blockType}_1`, blockType }}
      isSelected={false}
      onClick={vi.fn()}
      onDelete={vi.fn()}
    />,
  );
}

describe('BlockPreview workbench blocks', () => {
  it('renders dedicated previews for workbench blocks', () => {
    renderPreview('metric-strip');
    expect(screen.getByText('Metric Strip')).toBeInTheDocument();

    renderPreview('record-inspector');
    expect(screen.getByText('Record Inspector')).toBeInTheDocument();

    renderPreview('candidate-list');
    expect(screen.getByText('Candidate List')).toBeInTheDocument();

    renderPreview('workbench-action-bar');
    expect(screen.getByText('Workbench Action Bar')).toBeInTheDocument();

    renderPreview('evidence-panel');
    expect(screen.getByText('Evidence Panel')).toBeInTheDocument();

    renderPreview('artifact-timeline');
    expect(screen.getByText('Artifact Timeline')).toBeInTheDocument();

    renderPreview('review-drawer');
    expect(screen.getByText('Review Drawer')).toBeInTheDocument();
  });

  it('renders every form-section field so hidden overflow fields remain editable', () => {
    const fields = Array.from({ length: 9 }, (_, index) => `field_${index + 1}`);

    render(
      <DndContext>
        <BlockPreview
          block={{
            id: 'form_section_overflow',
            blockType: 'form-section',
            title: 'Overflow fields',
            fields,
          }}
          isSelected={false}
          onClick={vi.fn()}
          onDelete={vi.fn()}
        />
      </DndContext>,
    );

    expect(screen.getByTestId('designer-field-field_1')).toBeInTheDocument();
    expect(screen.getByTestId('designer-field-field_9')).toBeInTheDocument();
    expect(screen.queryByText('+1 more fields')).not.toBeInTheDocument();
  });
});
