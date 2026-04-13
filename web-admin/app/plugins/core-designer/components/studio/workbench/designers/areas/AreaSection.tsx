/**
 * Area Section
 *
 * Displays a single area with its blocks.
 * Supports drag-and-drop for reordering and adding blocks.
 */

import React from 'react';
import { useDroppable } from '@dnd-kit/core';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import type { DslBlock, AreaName, DslFieldRef } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { SortableBlock } from './SortableBlock';

/**
 * Selected field info structure
 */
interface SelectedFieldInfo {
  blockId: string;
  fieldIndex: number;
  fieldRef: DslFieldRef;
}

export interface AreaSectionProps {
  areaName: AreaName;
  title: string;
  description: string;
  blocks: DslBlock[];
  isSelected: boolean;
  selectedBlockId: string | null;
  selectedFieldInfo?: SelectedFieldInfo | null;
  onAreaClick: () => void;
  onBlockSelect: (blockId: string) => void;
  onBlockUpdate: (blockId: string, updates: Partial<DslBlock>) => void;
  onBlockDelete: (blockId: string) => void;
  onFieldReorder?: (blockId: string, oldIndex: number, newIndex: number) => void;
  onFieldSelect?: (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => void;
  readonly?: boolean;
}

export const AreaSection: React.FC<AreaSectionProps> = ({
  areaName,
  title,
  description,
  blocks,
  isSelected,
  selectedBlockId,
  selectedFieldInfo,
  onAreaClick,
  onBlockSelect,
  onBlockUpdate,
  onBlockDelete,
  onFieldReorder,
  onFieldSelect,
  readonly,
}) => {
  const { setNodeRef, isOver } = useDroppable({
    id: areaName,
  });

  return (
    <div
      ref={setNodeRef}
      onClick={onAreaClick}
      className={`rounded-lg border-2 bg-white transition-all ${
        isOver
          ? 'border-blue-400 bg-blue-50/30'
          : isSelected
            ? 'border-blue-300 shadow-sm'
            : 'border-gray-200 hover:border-gray-300'
      }`}
    >
      {/* Area header */}
      <div className="border-b border-gray-100 px-4 py-3">
        <div className="flex items-center justify-between">
          <div>
            <h3 className="text-sm font-medium text-gray-900">{title}</h3>
            <p className="mt-0.5 text-xs text-gray-500">{description}</p>
          </div>
          <span className="rounded bg-gray-100 px-2 py-0.5 text-xs text-gray-400">
            {blocks.length} blocks
          </span>
        </div>
      </div>

      {/* Blocks */}
      <div className={`p-4 pl-8 transition-colors duration-200 ${isOver ? 'bg-blue-50/30' : ''}`}>
        {blocks.length === 0 ? (
          <EmptyAreaPlaceholder areaName={areaName} isOver={isOver} />
        ) : (
          <SortableContext items={blocks.map((b) => b.id)} strategy={verticalListSortingStrategy}>
            <div className="space-y-3">
              {blocks.map((block) => (
                <SortableBlock
                  key={block.id}
                  block={block}
                  isSelected={selectedBlockId === block.id}
                  selectedFieldInfo={
                    selectedFieldInfo?.blockId === block.id ? selectedFieldInfo : null
                  }
                  onSelect={() => onBlockSelect(block.id)}
                  onDelete={() => onBlockDelete(block.id)}
                  onFieldReorder={onFieldReorder}
                  onFieldSelect={onFieldSelect}
                  readonly={readonly}
                />
              ))}
            </div>
          </SortableContext>
        )}
      </div>
    </div>
  );
};

/**
 * Empty area placeholder
 */
interface EmptyAreaPlaceholderProps {
  areaName: AreaName;
  isOver: boolean;
}

const EmptyAreaPlaceholder: React.FC<EmptyAreaPlaceholderProps> = ({ areaName, isOver }) => {
  return (
    <div
      className={`rounded-lg border-2 border-dashed p-8 text-center transition-colors ${
        isOver ? 'border-blue-400 bg-blue-50/50' : 'border-gray-200 hover:border-gray-300'
      }`}
    >
      <div className="text-gray-400">
        <svg
          className="mx-auto mb-2 h-10 w-10"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M12 6v6m0 0v6m0-6h6m-6 0H6"
          />
        </svg>
        <p className="text-sm">{isOver ? 'Release to add' : 'Drag components here'}</p>
      </div>
    </div>
  );
};

export default AreaSection;
