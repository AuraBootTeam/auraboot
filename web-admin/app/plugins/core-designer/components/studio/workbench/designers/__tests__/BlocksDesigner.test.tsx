import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { BlocksDesigner } from '../BlocksDesigner';
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';

vi.mock('@dnd-kit/core', () => ({
  DndContext: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  DragOverlay: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  pointerWithin: vi.fn(),
  useSensor: vi.fn(),
  useSensors: vi.fn(() => []),
  PointerSensor: vi.fn(),
  KeyboardSensor: vi.fn(),
}));

vi.mock('@dnd-kit/sortable', () => ({
  SortableContext: ({ children }: { children: React.ReactNode }) => <>{children}</>,
  arrayMove: vi.fn((items: unknown[]) => items),
  sortableKeyboardCoordinates: vi.fn(),
  verticalListSortingStrategy: {},
}));

vi.mock('../areas/BlockLibrary', () => ({
  BlockLibrary: () => <div data-testid="block-library" />,
}));

vi.mock('../areas/BlockPropertyPanel', () => ({
  BlockPropertyPanel: () => <div data-testid="block-property-panel" />,
}));

vi.mock('../areas/BlockDragPreview', () => ({
  BlockDragPreview: () => <div data-testid="block-drag-preview" />,
}));

vi.mock('../areas/SortableBlock', () => ({
  SortableBlock: ({ block, onSelect }: { block: { id: string }; onSelect: () => void }) => (
    <button data-testid={`sortable-block-${block.id}`} onClick={onSelect}>
      {block.id}
    </button>
  ),
}));

vi.mock('~/plugins/core-designer/components/studio/hooks/fields/useApiSchemaDetection', () => ({
  useApiSchemaDetection: () => ({
    detect: vi.fn(),
    connected: false,
    recordCount: 0,
    error: null,
  }),
}));

function createSchema(): PageSchema {
  return {
    schemaVersion: 2,
    kind: 'list',
    id: 'page_designer_test',
    modelCode: 'demo_model',
    pageKey: 'demo_model_list',
    title: { 'en-US': 'Demo' },
    layout: { type: 'stack' },
    blocks: [
      {
        id: 'toolbar_block',
        blockType: 'toolbar',
        title: { 'en-US': 'Toolbar Block' },
        buttons: [],
      },
    ],
  } as PageSchema;
}

describe('BlocksDesigner', () => {
  it('renders outline items with localized block titles', async () => {
    const user = userEvent.setup();

    render(<BlocksDesigner schema={createSchema()} onSchemaChange={vi.fn()} />);

    await user.click(screen.getByTestId('designer-tab-outline'));

    expect(screen.getByRole('button', { name: 'Toolbar Block' })).toBeInTheDocument();
  });
});
