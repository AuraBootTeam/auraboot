import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

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
  });
});
