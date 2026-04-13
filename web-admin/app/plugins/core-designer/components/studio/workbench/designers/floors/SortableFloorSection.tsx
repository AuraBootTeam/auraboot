/**
 * SortableFloorSection
 *
 * Wrapper that makes a FloorSection sortable via @dnd-kit/sortable.
 * Renders a drag handle on the left side.
 */

import React from 'react';
import { useSortable } from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';
import type { FloorSectionProps } from './FloorSection';
import { FloorSection } from './FloorSection';

interface SortableFloorSectionProps extends FloorSectionProps {
  id: string;
}

export const SortableFloorSection: React.FC<SortableFloorSectionProps> = ({
  id,
  readOnly,
  ...rest
}) => {
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id,
    disabled: readOnly,
    data: { type: 'floor', floorId: id },
  });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition: transition || 'transform 200ms ease',
    position: 'relative',
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`group/floor relative ${
        isDragging
          ? 'z-50 scale-[1.01] rounded-lg opacity-60 shadow-xl ring-2 ring-blue-400 ring-offset-2'
          : ''
      }`}
    >
      {/* Drag Handle */}
      {!readOnly && (
        <div
          {...attributes}
          {...listeners}
          className={`absolute top-1/2 -left-8 z-10 -translate-y-1/2 cursor-grab rounded-md p-1.5 transition-all duration-200 active:cursor-grabbing ${
            isDragging
              ? 'bg-blue-100 text-blue-500 opacity-100'
              : 'bg-gray-100 text-gray-400 opacity-0 group-hover/floor:opacity-100 hover:bg-gray-200'
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

      <FloorSection {...rest} readOnly={readOnly} />
    </div>
  );
};

export default SortableFloorSection;
