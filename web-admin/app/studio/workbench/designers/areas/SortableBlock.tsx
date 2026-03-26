/**
 * SortableBlock
 *
 * Wrapper component that makes a block sortable via drag-and-drop.
 * Uses @dnd-kit/sortable for smooth reordering animations.
 *
 * Click vs Drag is handled by the DndContext sensors configuration
 * which requires 8px movement before drag starts.
 */

import React from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { DslBlock, DslFieldRef } from '~/studio/domain/dsl/types';
import { BlockPreview } from './previews/BlockPreview';

/**
 * Selected field info structure
 */
interface SelectedFieldInfo {
  blockId: string;
  fieldIndex: number;
  fieldRef: DslFieldRef;
}

export interface SortableBlockProps {
  block: DslBlock;
  isSelected: boolean;
  selectedFieldInfo?: SelectedFieldInfo | null;
  onSelect: () => void;
  onDelete: () => void;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}

export const SortableBlock: React.FC<SortableBlockProps> = ({
  block,
  isSelected,
  selectedFieldInfo,
  onSelect,
  onDelete,
  onFieldReorder,
  onFieldSelect,
  readonly,
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: block.id,
    disabled: readonly,
    data: { type: 'block', blockId: block.id },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition:
      transition ||
      'transform 200ms cubic-bezier(0.25, 1, 0.5, 1), box-shadow 200ms ease, opacity 150ms ease',
    position: 'relative',
  };

  // Handle click - only fires if not dragging (due to DndContext sensor config)
  const handleClick = (e: React.MouseEvent) => {
    e.stopPropagation();
    if (!isDragging) {
      onSelect();
    }
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group/sortable transition-all duration-200 ${!readonly ? 'cursor-pointer' : ''} ${
        isDragging
          ? 'z-50 scale-[1.02] cursor-grabbing rounded-lg opacity-60 shadow-xl ring-2 ring-blue-400 ring-offset-2'
          : ''
      }`}
      data-testid="sortable-block"
      data-block-type={block.blockType}
      data-block-id={block.id}
      onClick={handleClick}
      {...attributes}
      {...listeners}
    >
      {/* Drag Handle - visual indicator shown on hover */}
      {!readonly && (
        <div
          className={`pointer-events-none absolute top-1/2 -left-7 z-10 -translate-y-1/2 rounded-md p-1.5 transition-all duration-200 ${
            isDragging
              ? 'bg-blue-100 text-blue-500 opacity-100'
              : 'bg-gray-100 text-gray-400 opacity-0 group-hover/sortable:opacity-100'
          }`}
          title="Drag to reorder"
        >
          <svg className="h-4 w-4" viewBox="0 0 24 24" fill="currentColor">
            <circle cx="9" cy="5" r="1.5" />
            <circle cx="15" cy="5" r="1.5" />
            <circle cx="9" cy="12" r="1.5" />
            <circle cx="15" cy="12" r="1.5" />
            <circle cx="9" cy="19" r="1.5" />
            <circle cx="15" cy="19" r="1.5" />
          </svg>
        </div>
      )}
      {/* Hover highlight border */}
      {!readonly && !isDragging && (
        <div className="pointer-events-none absolute inset-0 rounded-lg border-2 border-transparent transition-colors group-hover/sortable:border-blue-200" />
      )}
      <BlockPreview
        block={block}
        isSelected={isSelected}
        selectedFieldInfo={selectedFieldInfo}
        onClick={onSelect}
        onDelete={onDelete}
        onFieldReorder={onFieldReorder}
        onFieldSelect={onFieldSelect}
        readonly={readonly}
      />
    </div>
  );
};

export default SortableBlock;
