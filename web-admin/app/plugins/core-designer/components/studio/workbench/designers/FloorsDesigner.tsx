/**
 * Floors Designer
 *
 * Designer for detail/home pages that use floors structure.
 * 3-panel layout matching AreasDesigner:
 * - Left: tabs for [Component Library, Outline]
 * - Center: FloorCanvas with DndContext for drag-and-drop
 * - Right: FloorPropertyPanel
 * - Top toolbar: Add Floor button
 */

import React, { useState, useCallback, useMemo } from 'react';
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  closestCenter,
} from '@dnd-kit/core';
import type { DragStartEvent, DragEndEvent } from '@dnd-kit/core';
import { arrayMove } from '@dnd-kit/sortable';
import type { PageSchema, DslFloor, DslComponent } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { useFloorsDsl } from './floors/hooks/useFloorsDsl';
import { FloorCanvas } from './floors/FloorCanvas';
import { FloorPropertyPanel } from './floors/FloorPropertyPanel';
import { FloorComponentLibrary } from './floors/FloorComponentLibrary';
import { FloorOutline } from './floors/FloorOutline';

export interface FloorsDesignerProps {
  dsl: PageSchema;
  onDslChange: (dsl: PageSchema) => void;
  onSave?: (dsl: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
}

/**
 * Left panel tab types
 */
type LeftPanelTab = 'components' | 'outline';

export const FloorsDesigner: React.FC<FloorsDesignerProps> = ({
  dsl,
  onDslChange,
  onSave,
  modelCode,
  readonly = false,
  previewMode = false,
}) => {
  // Selection state
  const [selectedFloorId, setSelectedFloorId] = useState<string | null>(null);
  const [selectedComponentId, setSelectedComponentId] = useState<string | null>(null);
  const [leftPanelTab, setLeftPanelTab] = useState<LeftPanelTab>('components');
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [activeDragType, setActiveDragType] = useState<string | null>(null);

  // DnD sensors: 8px pointer distance + keyboard
  const pointerSensor = useSensor(PointerSensor, {
    activationConstraint: { distance: 8 },
  });
  const keyboardSensor = useSensor(KeyboardSensor);
  const sensors = useSensors(pointerSensor, keyboardSensor);

  // Floor DSL operations
  const {
    floors,
    addFloor,
    removeFloor,
    updateFloor,
    moveFloor,
    addComponent,
    removeComponent,
    updateComponent,
    reorderComponents,
    convertToTabsFloor,
    convertToNormalFloor,
  } = useFloorsDsl(dsl, onDslChange);

  // Find selected floor
  const selectedFloor = useMemo(() => {
    if (!selectedFloorId) return null;
    return floors.find((f) => f.id === selectedFloorId) || null;
  }, [selectedFloorId, floors]);

  // Find selected component
  const selectedComponent = useMemo(() => {
    if (!selectedComponentId || !selectedFloorId) return null;
    const floor = floors.find((f) => f.id === selectedFloorId);
    if (!floor?.components) return null;
    return floor.components.find((c) => c.id === selectedComponentId) || null;
  }, [selectedComponentId, selectedFloorId, floors]);

  // Handlers
  const handleSelectFloor = useCallback((id: string | null) => {
    setSelectedFloorId(id);
    setSelectedComponentId(null);
  }, []);

  const handleSelectComponent = useCallback((floorId: string, componentId: string) => {
    setSelectedFloorId(floorId);
    setSelectedComponentId(componentId);
  }, []);

  const handleRemoveFloor = useCallback(
    (floorId: string) => {
      removeFloor(floorId);
      if (selectedFloorId === floorId) {
        setSelectedFloorId(null);
        setSelectedComponentId(null);
      }
    },
    [removeFloor, selectedFloorId],
  );

  const handleRemoveComponent = useCallback(
    (floorId: string, componentId: string) => {
      removeComponent(floorId, componentId);
      if (selectedComponentId === componentId) {
        setSelectedComponentId(null);
      }
    },
    [removeComponent, selectedComponentId],
  );

  const handleAddFloor = useCallback(() => {
    const id = addFloor(`Floor ${floors.length + 1}`);
    setSelectedFloorId(id);
    setSelectedComponentId(null);
  }, [addFloor, floors.length]);

  // Handler for adding component from library (click)
  const handleAddComponentFromLibrary = useCallback(
    (component: Omit<DslComponent, 'id'>) => {
      if (!selectedFloorId) {
        // If no floor selected, create a new floor first
        const floorId = addFloor(`Floor ${floors.length + 1}`);
        addComponent(floorId, component);
        setSelectedFloorId(floorId);
      } else {
        const compId = addComponent(selectedFloorId, component);
        setSelectedComponentId(compId);
      }
    },
    [selectedFloorId, addFloor, addComponent, floors.length],
  );

  // Handler for floor property changes
  const handleFloorChange = useCallback(
    (floorId: string, updates: Partial<DslFloor>) => {
      if (readonly) return;
      updateFloor(floorId, updates);
    },
    [updateFloor, readonly],
  );

  // Handler for component property changes
  const handleComponentChange = useCallback(
    (floorId: string, componentId: string, updates: Partial<DslComponent>) => {
      if (readonly) return;
      updateComponent(floorId, componentId, updates);
    },
    [updateComponent, readonly],
  );

  // Outline handlers
  const handleOutlineFloorClick = useCallback(
    (floorId: string) => {
      handleSelectFloor(floorId);
    },
    [handleSelectFloor],
  );

  const handleOutlineComponentClick = useCallback(
    (floorId: string, componentId: string) => {
      handleSelectComponent(floorId, componentId);
    },
    [handleSelectComponent],
  );

  // ---- DnD handlers ----

  const handleDragStart = useCallback((event: DragStartEvent) => {
    setActiveDragId(event.active.id as string);
    setActiveDragType(event.active.data.current?.type || null);
  }, []);

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      setActiveDragId(null);
      setActiveDragType(null);

      if (!over) return;

      const activeData = active.data.current;
      const overData = over.data.current;

      if (!activeData) return;

      // Case 1: Floor reordering
      if (activeData.type === 'floor' && overData?.type === 'floor') {
        const oldIndex = floors.findIndex((f) => f.id === active.id);
        const newIndex = floors.findIndex((f) => f.id === over.id);
        if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
          moveFloor(oldIndex, newIndex);
        }
        return;
      }

      // Case 2: Component reordering within same floor
      if (activeData.type === 'component' && overData?.type === 'component') {
        if (activeData.floorId === overData.floorId) {
          const floor = floors.find((f) => f.id === activeData.floorId);
          if (floor?.components) {
            const oldIndex = floor.components.findIndex((c) => c.id === active.id);
            const newIndex = floor.components.findIndex((c) => c.id === over.id);
            if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
              reorderComponents(activeData.floorId, oldIndex, newIndex);
            }
          }
        }
        return;
      }

      // Case 3: Library component dropped onto a floor
      if (activeData.type === 'library-component') {
        let targetFloorId: string | undefined;

        // Dropped on floor-drop zone
        if (overData?.type === 'floor-drop') {
          targetFloorId = overData.floorId;
        }
        // Dropped on a floor item (SortableFloorSection)
        else if (overData?.type === 'floor') {
          targetFloorId = overData.floorId;
        }
        // Dropped on a component within a floor
        else if (overData?.type === 'component') {
          targetFloorId = overData.floorId;
        }

        if (targetFloorId && activeData.componentConfig) {
          const compId = addComponent(targetFloorId, activeData.componentConfig);
          setSelectedFloorId(targetFloorId);
          setSelectedComponentId(compId);
        }
        return;
      }
    },
    [floors, moveFloor, reorderComponents, addComponent],
  );

  // Drag overlay content
  const renderDragOverlay = () => {
    if (!activeDragId || !activeDragType) return null;

    if (activeDragType === 'floor') {
      const floor = floors.find((f) => f.id === activeDragId);
      if (!floor) return null;
      return (
        <div className="w-64 rounded-lg border-2 border-blue-400 bg-white p-3 opacity-90 shadow-xl">
          <div className="text-sm font-medium text-gray-900">{floor.title || 'Floor'}</div>
          <div className="mt-1 text-xs text-gray-400">
            {floor.components?.length || 0} components
          </div>
        </div>
      );
    }

    if (activeDragType === 'component') {
      return (
        <div className="rounded-lg border-2 border-blue-400 bg-white p-2 opacity-90 shadow-xl">
          <div className="text-xs text-gray-600">Moving component...</div>
        </div>
      );
    }

    if (activeDragType === 'library-component') {
      return (
        <div className="w-48 rounded-lg border-2 border-blue-400 bg-white p-3 opacity-90 shadow-xl">
          <div className="text-xs font-medium text-blue-600">Drop into a floor</div>
        </div>
      );
    }

    return null;
  };

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <div className="flex h-full flex-1 overflow-hidden">
        {/* Left Panel: Component Library + Outline */}
        {!previewMode && (
          <div className="flex w-64 flex-col border-r border-gray-200 bg-white">
            {/* Tab Buttons */}
            <div className="flex border-b border-gray-200">
              <button
                onClick={() => setLeftPanelTab('components')}
                data-testid="floors-tab-components"
                className={`flex-1 px-2 py-2 text-xs font-medium transition-colors ${
                  leftPanelTab === 'components'
                    ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
                }`}
              >
                Components
              </button>
              <button
                onClick={() => setLeftPanelTab('outline')}
                data-testid="floors-tab-outline"
                className={`flex-1 px-2 py-2 text-xs font-medium transition-colors ${
                  leftPanelTab === 'outline'
                    ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
                }`}
              >
                Outline
              </button>
            </div>

            {/* Tab Content */}
            <div className="flex-1 overflow-hidden">
              {leftPanelTab === 'components' && (
                <FloorComponentLibrary
                  pageKind={dsl.kind}
                  onAddComponent={handleAddComponentFromLibrary}
                  readOnly={readonly}
                />
              )}
              {leftPanelTab === 'outline' && (
                <FloorOutline
                  dsl={dsl}
                  selectedFloorId={selectedFloorId}
                  selectedComponentId={selectedComponentId}
                  onFloorClick={handleOutlineFloorClick}
                  onComponentClick={handleOutlineComponentClick}
                />
              )}
            </div>
          </div>
        )}

        {/* Center: Floor Canvas */}
        <div className="flex-1 overflow-auto bg-gray-50" data-testid="floors-designer-canvas">
          <div className="space-y-6 p-6">
            {/* Page header */}
            <div className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-medium text-gray-900">{dsl.id}</h2>
                  <p className="text-sm text-gray-500">
                    {dsl.kind === 'detail' ? 'Detail Page' : 'Home Page'} - {dsl.modelCode}
                  </p>
                </div>
                <div className="flex items-center gap-2">
                  {!readonly && (
                    <button
                      onClick={handleAddFloor}
                      data-testid="floors-add-floor-btn"
                      className="flex items-center gap-1.5 rounded-md border border-gray-300 bg-white px-3 py-1.5 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50"
                    >
                      <svg
                        className="h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M12 6v6m0 0v6m0-6h6m-6 0H6"
                        />
                      </svg>
                      Add Floor
                    </button>
                  )}
                  {onSave && !readonly && (
                    <button
                      onClick={() => onSave(dsl)}
                      className="rounded-md bg-blue-600 px-4 py-1.5 text-sm font-medium text-white transition-colors hover:bg-blue-700"
                    >
                      Save
                    </button>
                  )}
                </div>
              </div>
            </div>

            {/* Floor Canvas */}
            <div className="pl-8">
              <FloorCanvas
                floors={floors}
                selectedFloorId={selectedFloorId}
                selectedComponentId={selectedComponentId}
                onSelectFloor={handleSelectFloor}
                onSelectComponent={handleSelectComponent}
                onUpdateFloor={handleFloorChange}
                onRemoveFloor={handleRemoveFloor}
                onMoveFloor={moveFloor}
                onRemoveComponent={handleRemoveComponent}
                readOnly={readonly}
              />
            </div>
          </div>
        </div>

        {/* Right Panel: Properties */}
        {!previewMode && (
          <div
            className="w-80 overflow-hidden border-l border-gray-200 bg-white"
            data-testid="floors-properties-panel"
          >
            <FloorPropertyPanel
              selectedFloor={selectedFloor}
              selectedComponent={selectedComponent}
              selectedFloorId={selectedFloorId}
              onFloorChange={handleFloorChange}
              onComponentChange={handleComponentChange}
              onConvertToTabs={convertToTabsFloor}
              onConvertToNormal={convertToNormalFloor}
              readOnly={readonly}
            />
          </div>
        )}
      </div>

      {/* Drag Overlay */}
      <DragOverlay>{renderDragOverlay()}</DragOverlay>
    </DndContext>
  );
};

export default FloorsDesigner;
