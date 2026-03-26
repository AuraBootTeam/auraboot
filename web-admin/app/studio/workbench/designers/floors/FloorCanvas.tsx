/**
 * Floor Canvas
 *
 * Center panel that renders floors as vertical sortable sections.
 * Uses @dnd-kit/sortable for drag-and-drop reordering.
 * Retains up/down arrow buttons as accessible fallback.
 */

import React from 'react';
import { SortableContext, verticalListSortingStrategy } from '@dnd-kit/sortable';
import type { DslFloor } from '~/studio/domain/dsl/types';
import { SortableFloorSection } from './SortableFloorSection';

export interface FloorCanvasProps {
  floors: DslFloor[];
  selectedFloorId: string | null;
  selectedComponentId: string | null;
  onSelectFloor: (id: string | null) => void;
  onSelectComponent: (floorId: string, componentId: string) => void;
  onUpdateFloor: (id: string, updates: Partial<DslFloor>) => void;
  onRemoveFloor: (id: string) => void;
  onMoveFloor: (oldIndex: number, newIndex: number) => void;
  onRemoveComponent: (floorId: string, componentId: string) => void;
  readOnly?: boolean;
}

export const FloorCanvas: React.FC<FloorCanvasProps> = ({
  floors,
  selectedFloorId,
  selectedComponentId,
  onSelectFloor,
  onSelectComponent,
  onUpdateFloor,
  onRemoveFloor,
  onMoveFloor,
  onRemoveComponent,
  readOnly,
}) => {
  return (
    <div className="space-y-4" data-testid="floor-canvas">
      {floors.length === 0 ? (
        <EmptyCanvasPlaceholder />
      ) : (
        <SortableContext items={floors.map((f) => f.id)} strategy={verticalListSortingStrategy}>
          {floors.map((floor, index) => (
            <SortableFloorSection
              key={floor.id}
              id={floor.id}
              floor={floor}
              index={index}
              isSelected={selectedFloorId === floor.id}
              selectedComponentId={selectedComponentId}
              onSelect={() => onSelectFloor(floor.id)}
              onSelectComponent={onSelectComponent}
              onUpdateFloor={onUpdateFloor}
              onRemoveFloor={onRemoveFloor}
              onRemoveComponent={onRemoveComponent}
              readOnly={readOnly}
            />
          ))}
        </SortableContext>
      )}
    </div>
  );
};

/**
 * Empty canvas placeholder
 */
const EmptyCanvasPlaceholder: React.FC = () => {
  return (
    <div className="rounded-lg border-2 border-dashed border-gray-200 p-12 text-center">
      <div className="text-gray-400">
        <svg
          className="mx-auto mb-3 h-12 w-12"
          fill="none"
          stroke="currentColor"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth={1.5}
            d="M19 11H5m14 0a2 2 0 012 2v6a2 2 0 01-2 2H5a2 2 0 01-2-2v-6a2 2 0 012-2m14 0V9a2 2 0 00-2-2M5 11V9a2 2 0 012-2m0 0V5a2 2 0 012-2h6a2 2 0 012 2v2M7 7h10"
          />
        </svg>
        <p className="mb-1 text-sm font-medium">No floors yet</p>
        <p className="text-xs text-gray-400">Click "Add Floor" above to start designing</p>
      </div>
    </div>
  );
};

export default FloorCanvas;
