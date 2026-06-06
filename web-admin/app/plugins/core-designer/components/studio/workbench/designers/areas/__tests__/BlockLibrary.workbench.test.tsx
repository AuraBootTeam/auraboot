import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

vi.mock('@dnd-kit/core', () => ({
  useDraggable: () => ({
    attributes: {},
    listeners: {},
    setNodeRef: vi.fn(),
    isDragging: false,
  }),
}));

vi.mock('~/contexts/I18nContext', () => ({
  useI18n: () => ({ locale: 'en-US' }),
}));

vi.mock('../SmartComponentLibrary', () => ({
  SmartComponentLibrary: () => <div data-testid="smart-component-library" />,
}));

import { BlockLibrary } from '../BlockLibrary';

describe('BlockLibrary workbench blocks', () => {
  it('shows workbench block types for list pages', () => {
    render(<BlockLibrary pageKind="list" />);

    expect(screen.getByTestId('block-palette-item-metric-strip')).toBeInTheDocument();
    expect(screen.getByTestId('block-palette-item-record-inspector')).toBeInTheDocument();
    expect(screen.getByTestId('block-palette-item-candidate-list')).toBeInTheDocument();
    expect(screen.getByTestId('block-palette-item-workbench-action-bar')).toBeInTheDocument();
  });
});
