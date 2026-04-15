/**
 * Areas Designer
 *
 * Designer for list/form pages that use areas structure (filters/toolbar/main).
 * Directly edits DSL V4 format without intermediate conversion.
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  DndContext,
  DragOverlay,
  closestCenter,
  pointerWithin,
  useSensor,
  useSensors,
  PointerSensor,
  KeyboardSensor,
} from '@dnd-kit/core';
import { arrayMove, sortableKeyboardCoordinates } from '@dnd-kit/sortable';
import type { DragStartEvent, DragEndEvent, DragOverEvent, Active } from '@dnd-kit/core';
import type {
  PageSchema,
  DslBlock,
  DslArea,
  AreaName,
  BlockType,
  DslFieldRef,
  DslFieldOverride,
} from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { parseFieldShorthand, serializeFieldOverride } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { BlockLibrary } from './areas/BlockLibrary';
import { BlockPropertyPanel } from './areas/BlockPropertyPanel';
import { BlockDragPreview } from './areas/BlockDragPreview';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import { useApiSchemaDetection } from '~/plugins/core-designer/components/studio/hooks/fields/useApiSchemaDetection';

export interface AreasDesignerProps {
  dsl: PageSchema;
  onDslChange: (dsl: PageSchema) => void;
  onSave?: (dsl: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
  isCustomApiMode?: boolean;
}

/**
 * Areas order for display
 */
const AREA_ORDER: AreaName[] = ['filters', 'toolbar', 'main'];

/**
 * Area display configuration
 */
const AREA_CONFIG: Record<AreaName, { title: string; description: string }> = {
  filters: { title: 'Filters', description: 'Filter form at the top of the page' },
  toolbar: { title: 'Toolbar', description: 'Action buttons area' },
  main: { title: 'Main Content', description: 'Primary content area' },
};

/**
 * Get visible areas based on page kind
 */
function getVisibleAreas(kind: string): AreaName[] {
  switch (kind) {
    case 'list':
      return ['filters', 'toolbar', 'main'];
    case 'form':
      return ['main'];
    default:
      return ['main'];
  }
}

/**
 * Left panel tab types
 */
type LeftPanelTab = 'fields' | 'blocks' | 'outline';

/**
 * Selected field info structure
 */
interface SelectedFieldInfo {
  blockId: string;
  fieldIndex: number;
  fieldRef: DslFieldRef;
}

export const AreasDesigner: React.FC<AreasDesignerProps> = ({
  dsl,
  onDslChange,
  onSave,
  modelCode,
  readonly = false,
  previewMode = false,
  isCustomApiMode,
}) => {
  // API schema detection for custom API mode
  const apiDataSource = isCustomApiMode ? (dsl as any).dataSource : undefined;
  const apiDetection = useApiSchemaDetection(isCustomApiMode ? apiDataSource : undefined);

  // Auto-detect on mount for GET APIs
  useEffect(() => {
    if (
      isCustomApiMode &&
      apiDataSource?.endpoint &&
      (!apiDataSource.method || apiDataSource.method === 'get')
    ) {
      apiDetection.detect();
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps -- detect is stable (useCallback with endpoint dep)
  }, [isCustomApiMode, apiDataSource?.endpoint]);

  // State
  const [selectedArea, setSelectedArea] = useState<AreaName>('main');
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null);
  const [selectedFieldInfo, setSelectedFieldInfo] = useState<SelectedFieldInfo | null>(null);
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [draggedBlockType, setDraggedBlockType] = useState<BlockType | null>(null);
  const [leftPanelTab, setLeftPanelTab] = useState<LeftPanelTab>('fields');
  const [viewModelCode, setViewModelCode] = useState<string | null>(
    modelCode || dsl.modelCode || null,
  );

  // Drag state for field drops
  const [activeFieldDrag, setActiveFieldDrag] = useState<Active | null>(null);

  // Drag state for visual feedback
  const [draggedBlock, setDraggedBlock] = useState<DslBlock | null>(null);
  const [draggedFieldName, setDraggedFieldName] = useState<string | null>(null);

  // Configure sensors with activation constraint
  // This requires mouse to move 8px before drag starts, allowing clicks to work
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: {
        distance: 8, // 8px movement required before drag starts
      },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  // Derived state
  const visibleAreas = useMemo(() => getVisibleAreas(dsl.kind), [dsl.kind]);
  const areas = dsl.areas || {};

  // Find selected block
  const selectedBlock = useMemo(() => {
    if (!selectedBlockId) return null;
    for (const area of Object.values(areas)) {
      const block = area.blocks?.find((b) => b.id === selectedBlockId);
      if (block) return block;
    }
    return null;
  }, [selectedBlockId, areas]);

  // Handlers
  const handleAreaSelect = useCallback((areaName: AreaName) => {
    setSelectedArea(areaName);
    setSelectedBlockId(null);
    setSelectedFieldInfo(null);
  }, []);

  const handleBlockSelect = useCallback(
    (blockId: string) => {
      setSelectedBlockId(blockId);
      setSelectedFieldInfo(null); // Clear field selection when selecting a block
      // Also select the area containing this block
      for (const [areaName, area] of Object.entries(areas)) {
        if (area.blocks?.some((b) => b.id === blockId)) {
          setSelectedArea(areaName as AreaName);
          break;
        }
      }
    },
    [areas],
  );

  // Handle field selection within a block
  const handleFieldSelect = useCallback(
    (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => {
      setSelectedBlockId(blockId); // Also select the block
      setSelectedFieldInfo({ blockId, fieldIndex, fieldRef });
      // Also select the area containing this block
      for (const [areaName, area] of Object.entries(areas)) {
        if (area.blocks?.some((b) => b.id === blockId)) {
          setSelectedArea(areaName as AreaName);
          break;
        }
      }
    },
    [areas],
  );

  // Clear field selection
  const handleFieldDeselect = useCallback(() => {
    setSelectedFieldInfo(null);
  }, []);

  const handleBlockUpdate = useCallback(
    (blockId: string, updates: Partial<DslBlock>) => {
      if (readonly) return;

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };

      for (const [areaName, area] of Object.entries(newAreas)) {
        const blockIndex = area.blocks?.findIndex((b) => b.id === blockId);
        if (blockIndex !== undefined && blockIndex >= 0) {
          const newBlocks = [...(area.blocks || [])];
          newBlocks[blockIndex] = { ...newBlocks[blockIndex], ...updates };
          newAreas[areaName] = { ...area, blocks: newBlocks };
          break;
        }
      }

      newDsl.areas = newAreas;
      onDslChange(newDsl);
    },
    [dsl, onDslChange, readonly],
  );

  const handleBlockDelete = useCallback(
    (blockId: string) => {
      if (readonly) return;

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };

      for (const [areaName, area] of Object.entries(newAreas)) {
        const blockIndex = area.blocks?.findIndex((b) => b.id === blockId);
        if (blockIndex !== undefined && blockIndex >= 0) {
          const newBlocks = [...(area.blocks || [])];
          newBlocks.splice(blockIndex, 1);
          newAreas[areaName] = { ...area, blocks: newBlocks };
          break;
        }
      }

      newDsl.areas = newAreas;
      onDslChange(newDsl);

      if (selectedBlockId === blockId) {
        setSelectedBlockId(null);
      }
    },
    [dsl, onDslChange, readonly, selectedBlockId],
  );

  const handleAddBlock = useCallback(
    (areaName: AreaName, blockType: BlockType) => {
      if (readonly) return;

      const newBlock: DslBlock = {
        id: `block_${Date.now()}`,
        blockType,
        span: blockType === 'table' ? 12 : undefined,
      };

      // Initialize default content based on block type
      switch (blockType) {
        case 'filters':
          newBlock.fields = [];
          newBlock.actions = ['search', 'reset'];
          break;
        case 'form-section':
        case 'detail-section':
          newBlock.title = 'Section Title';
          newBlock.fields = [];
          break;
        case 'table':
          newBlock.columns = [];
          newBlock.dataSource = 'tableData';
          break;
        case 'toolbar':
        case 'form-buttons':
          newBlock.buttons = [];
          break;
      }

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };
      const currentArea = newAreas[areaName] || { blocks: [] };
      newAreas[areaName] = {
        ...currentArea,
        blocks: [...(currentArea.blocks || []), newBlock],
      };
      newDsl.areas = newAreas;

      onDslChange(newDsl);
      setSelectedBlockId(newBlock.id);
    },
    [dsl, onDslChange, readonly],
  );

  // Handler for adding a field to a block
  const handleFieldDropToBlock = useCallback(
    (blockId: string, fieldCode: string, targetType: 'fields' | 'columns') => {
      if (readonly) return;

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };

      for (const [areaName, area] of Object.entries(newAreas)) {
        const blockIndex = area.blocks?.findIndex((b) => b.id === blockId);
        if (blockIndex !== undefined && blockIndex >= 0) {
          const newBlocks = [...(area.blocks || [])];
          const block = { ...newBlocks[blockIndex] };

          if (targetType === 'fields') {
            const currentFields = block.fields || [];
            if (!currentFields.includes(fieldCode)) {
              block.fields = [...currentFields, fieldCode];
            }
          } else if (targetType === 'columns') {
            const currentColumns = block.columns || [];
            const hasColumn = currentColumns.some((col) =>
              typeof col === 'string' ? col === fieldCode : col.field === fieldCode,
            );
            if (!hasColumn) {
              block.columns = [...currentColumns, fieldCode];
            }
          }

          newBlocks[blockIndex] = block;
          newAreas[areaName] = { ...area, blocks: newBlocks };
          break;
        }
      }

      newDsl.areas = newAreas;
      onDslChange(newDsl);
    },
    [dsl, onDslChange, readonly],
  );

  // Handler for reordering blocks within an area
  const handleBlockReorder = useCallback(
    (areaName: AreaName, activeId: string, overId: string) => {
      if (readonly) return;
      if (activeId === overId) return;

      const area = areas[areaName];
      if (!area?.blocks) return;

      const oldIndex = area.blocks.findIndex((b) => b.id === activeId);
      const newIndex = area.blocks.findIndex((b) => b.id === overId);

      if (oldIndex === -1 || newIndex === -1) return;

      const newBlocks = arrayMove(area.blocks, oldIndex, newIndex);
      const newDsl = {
        ...dsl,
        areas: {
          ...dsl.areas,
          [areaName]: { ...area, blocks: newBlocks },
        },
      };

      onDslChange(newDsl);
    },
    [dsl, areas, onDslChange, readonly],
  );

  // Handler for reordering fields within a block
  const handleFieldReorder = useCallback(
    (blockId: string, oldIndex: number, newIndex: number) => {
      if (readonly) return;
      if (oldIndex === newIndex) return;

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };

      for (const [areaName, area] of Object.entries(newAreas)) {
        const blockIndex = area.blocks?.findIndex((b) => b.id === blockId);
        if (blockIndex !== undefined && blockIndex >= 0) {
          const newBlocks = [...(area.blocks || [])];
          const block = { ...newBlocks[blockIndex] };
          const currentFields = block.fields || [];

          if (oldIndex < currentFields.length && newIndex < currentFields.length) {
            block.fields = arrayMove(currentFields, oldIndex, newIndex);
            newBlocks[blockIndex] = block;
            newAreas[areaName] = { ...area, blocks: newBlocks };
          }
          break;
        }
      }

      newDsl.areas = newAreas;
      onDslChange(newDsl);
    },
    [dsl, onDslChange, readonly],
  );

  // Handler for updating field properties
  const handleFieldUpdate = useCallback(
    (blockId: string, fieldIndex: number, updates: Partial<DslFieldOverride>) => {
      if (readonly) return;

      const newDsl = { ...dsl };
      const newAreas = { ...newDsl.areas };

      for (const [areaName, area] of Object.entries(newAreas)) {
        const blockIdx = area.blocks?.findIndex((b) => b.id === blockId);
        if (blockIdx !== undefined && blockIdx >= 0) {
          const newBlocks = [...(area.blocks || [])];
          const block = { ...newBlocks[blockIdx] };
          const fields = [...(block.fields || [])];

          if (fieldIndex < fields.length) {
            // Parse existing field and merge updates
            const existing = parseFieldShorthand(fields[fieldIndex]);
            const merged = { ...existing, ...updates };
            // Serialize back to DSL format
            fields[fieldIndex] = serializeFieldOverride(merged);
            block.fields = fields;
            newBlocks[blockIdx] = block;
            newAreas[areaName] = { ...area, blocks: newBlocks };

            // Update selectedFieldInfo with new fieldRef
            if (
              selectedFieldInfo?.blockId === blockId &&
              selectedFieldInfo?.fieldIndex === fieldIndex
            ) {
              setSelectedFieldInfo({
                blockId,
                fieldIndex,
                fieldRef: fields[fieldIndex],
              });
            }
          }
          break;
        }
      }

      newDsl.areas = newAreas;
      onDslChange(newDsl);
    },
    [dsl, onDslChange, readonly, selectedFieldInfo],
  );

  // Find which area contains a block
  const findAreaForBlock = useCallback(
    (blockId: string): AreaName | null => {
      for (const [areaName, area] of Object.entries(areas)) {
        if (area.blocks?.some((b) => b.id === blockId)) {
          return areaName as AreaName;
        }
      }
      return null;
    },
    [areas],
  );

  // Drag handlers
  const handleDragStart = useCallback(
    (event: DragStartEvent) => {
      const { active } = event;
      setActiveDragId(active.id as string);

      // Check if dragging from library (blocks)
      if (typeof active.id === 'string' && active.id.startsWith('library:')) {
        setDraggedBlockType(active.id.replace('library:', '') as BlockType);
      }

      // Check if dragging a field
      if (active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
        setActiveFieldDrag(active);
      }

      // Check if dragging a block for reordering
      if (active.data.current?.type === 'block') {
        const blockId = active.data.current.blockId;
        for (const area of Object.values(areas)) {
          const block = area.blocks?.find((b) => b.id === blockId);
          if (block) {
            setDraggedBlock(block);
            break;
          }
        }
      }

      // Check if dragging a field within a block
      if (active.data.current?.type === 'block-field') {
        setDraggedFieldName(active.data.current.fieldName || null);
      }
    },
    [areas],
  );

  const handleDragEnd = useCallback(
    (event: DragEndEvent) => {
      const { active, over } = event;
      setActiveDragId(null);
      setDraggedBlockType(null);
      setActiveFieldDrag(null);
      setDraggedBlock(null);
      setDraggedFieldName(null);

      if (!over) return;

      // Handle field reordering within a block (type: 'block-field')
      // Field IDs are in format: {blockId}:field:{fieldNameOrIndex}
      if (active.data.current?.type === 'block-field') {
        const activeId = String(active.id);
        const overId = String(over.id);

        // Parse the field IDs to extract blockId
        const activeMatch = activeId.match(/^(.+):field:(.+)$/);
        const overMatch = overId.match(/^(.+):field:(.+)$/);

        if (activeMatch && overMatch) {
          const activeBlockId = activeMatch[1];
          const overBlockId = overMatch[1];

          // Only allow reordering within the same block
          if (activeBlockId === overBlockId && activeId !== overId) {
            // Find the block to get field indices
            for (const area of Object.values(areas)) {
              const block = area.blocks?.find((b) => b.id === activeBlockId);
              if (block?.fields) {
                const activeFieldKey = activeMatch[2];
                const overFieldKey = overMatch[2];

                // Find indices by matching field names
                const oldIndex = block.fields.findIndex((f, idx) => {
                  if (typeof f === 'string') {
                    const fieldName = f.split('|')[0];
                    return fieldName === activeFieldKey || String(idx) === activeFieldKey;
                  }
                  return f.field === activeFieldKey || String(idx) === activeFieldKey;
                });

                const newIndex = block.fields.findIndex((f, idx) => {
                  if (typeof f === 'string') {
                    const fieldName = f.split('|')[0];
                    return fieldName === overFieldKey || String(idx) === overFieldKey;
                  }
                  return f.field === overFieldKey || String(idx) === overFieldKey;
                });

                if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
                  handleFieldReorder(activeBlockId, oldIndex, newIndex);
                }
                break;
              }
            }
          }
        }
        return;
      }

      // Dropped from block library to area
      if (typeof active.id === 'string' && active.id.startsWith('library:')) {
        const blockType = active.id.replace('library:', '') as BlockType;
        const targetArea = over.id as AreaName;
        if (visibleAreas.includes(targetArea)) {
          handleAddBlock(targetArea, blockType);
        }
        return;
      }

      // Check if we're dragging a block (for reordering)
      // Must check this BEFORE field drop handling since block drops may land on block-drop zones
      const activeId = String(active.id);
      const activeArea = findAreaForBlock(activeId);
      if (activeArea) {
        // This is a block being dragged for reordering
        const overIdStr = String(over.id);
        let targetBlockId: string | null = null;

        // If dropped on a block-drop zone, extract the target block ID
        if (overIdStr.startsWith('block-drop:')) {
          const parts = overIdStr.split(':');
          targetBlockId = parts[1];
        } else {
          // Dropped directly on another block
          targetBlockId = overIdStr;
        }

        if (targetBlockId) {
          const overArea = findAreaForBlock(targetBlockId);
          if (overArea && activeArea === overArea && activeId !== targetBlockId) {
            handleBlockReorder(activeArea, activeId, targetBlockId);
          }
        }
        return;
      }

      // Dropped a field to a block's field/column editor or to the block itself on canvas
      if (active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
        const overIdStr = String(over.id);

        // Format: block-drop:{blockId}:{targetType} - direct drop on canvas block
        if (overIdStr.startsWith('block-drop:')) {
          const parts = overIdStr.split(':');
          const blockId = parts[1];
          const targetTypeStr = parts[2];
          const fieldCode = active.data.current.component?.props?.name;
          // Only process if targetType is valid ('fields' or 'columns')
          if (fieldCode && blockId && (targetTypeStr === 'fields' || targetTypeStr === 'columns')) {
            handleFieldDropToBlock(blockId, fieldCode, targetTypeStr);
          }
          return;
        }

        // Format: fields-drop:{blockId} or columns-drop:{blockId} - drop on property panel
        if (overIdStr.startsWith('fields-drop:')) {
          const blockId = overIdStr.replace('fields-drop:', '');
          const fieldCode = active.data.current.component?.props?.name;
          if (fieldCode) {
            handleFieldDropToBlock(blockId, fieldCode, 'fields');
          }
        } else if (overIdStr.startsWith('columns-drop:')) {
          const blockId = overIdStr.replace('columns-drop:', '');
          const fieldCode = active.data.current.component?.props?.name;
          if (fieldCode) {
            handleFieldDropToBlock(blockId, fieldCode, 'columns');
          }
        }
        return;
      }
    },
    [
      visibleAreas,
      handleAddBlock,
      handleFieldDropToBlock,
      handleBlockReorder,
      handleFieldReorder,
      findAreaForBlock,
      areas,
    ],
  );

  // Handler for outline block click
  const handleOutlineBlockClick = useCallback(
    (blockId: string) => {
      handleBlockSelect(blockId);
    },
    [handleBlockSelect],
  );

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={pointerWithin}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <div className="flex h-full flex-1 overflow-hidden">
        {/* Left Panel: Area Navigator + Tabs (Fields/Blocks/Outline) */}
        {!previewMode && (
          <div className="flex w-64 flex-col border-r border-gray-200 bg-white">
            {/* Tab Buttons */}
            <div className="flex border-t border-b border-gray-200">
              <button
                onClick={() => setLeftPanelTab('fields')}
                data-testid="designer-tab-fields"
                className={`flex-1 px-2 py-2 text-xs font-medium transition-colors ${
                  leftPanelTab === 'fields'
                    ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
                }`}
              >
                Fields
              </button>
              <button
                onClick={() => setLeftPanelTab('blocks')}
                data-testid="designer-tab-blocks"
                className={`flex-1 px-2 py-2 text-xs font-medium transition-colors ${
                  leftPanelTab === 'blocks'
                    ? 'border-b-2 border-blue-600 bg-blue-50 text-blue-600'
                    : 'text-gray-600 hover:bg-gray-50 hover:text-gray-800'
                }`}
              >
                Blocks
              </button>
              <button
                onClick={() => setLeftPanelTab('outline')}
                data-testid="designer-tab-outline"
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
              {leftPanelTab === 'blocks' && (
                <BlockLibrary pageKind={dsl.kind} readonly={readonly} />
              )}
            </div>
          </div>
        )}

        {/* Center: Areas Canvas */}
        <div className="flex-1 overflow-auto bg-gray-50" data-testid="designer-canvas">
          <div className="space-y-6 p-6">
            {/* Page header */}
            <div className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-medium text-gray-900">{dsl.id}</h2>
                  <p className="text-sm text-gray-500">
                    {dsl.kind === 'list' ? 'List Page' : 'Form Page'} - {dsl.modelCode}
                  </p>
                </div>
                {onSave && !readonly && (
                  <button
                    onClick={() => onSave(dsl)}
                    className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
                  >
                    Save
                  </button>
                )}
              </div>
            </div>

            {/* Areas */}
            {/* Areas UI components removed - this designer is being replaced */}
          </div>
        </div>

        {/* Right Panel: Block Properties */}
        {!previewMode && (
          <div
            className="w-80 overflow-hidden border-l border-gray-200 bg-white"
            data-testid="designer-properties-panel"
          >
            <BlockPropertyPanel
              block={selectedBlock}
              modelCode={modelCode || dsl.modelCode}
              selectedFieldInfo={selectedFieldInfo}
              onChange={(updates) => {
                if (selectedBlockId) {
                  handleBlockUpdate(selectedBlockId, updates);
                }
              }}
              onFieldChange={(blockId, fieldIndex, updates) => {
                handleFieldUpdate(blockId, fieldIndex, updates);
              }}
              onFieldDeselect={handleFieldDeselect}
              readonly={readonly}
              isCustomApiMode={isCustomApiMode}
              dataSource={apiDataSource}
              onDataSourceChange={(ds) => {
                const updated = { ...dsl, dataSource: ds } as any;
                onDslChange(updated);
              }}
              onTestDetect={() => apiDetection.detect()}
              testStatus={{
                connected: apiDetection.connected,
                recordCount: apiDetection.recordCount,
                error: apiDetection.error,
              }}
            />
          </div>
        )}
      </div>

      {/* Drag overlay */}
      <DragOverlay
        dropAnimation={{
          duration: 200,
          easing: 'cubic-bezier(0.18, 0.67, 0.6, 1.22)',
        }}
      >
        {/* Library block being dragged */}
        {activeDragId && draggedBlockType && <BlockDragPreview blockType={draggedBlockType} />}
        {/* Block being reordered */}
        {draggedBlock && (
          <div className="max-w-[300px] min-w-[200px] rounded-lg border-2 border-blue-400 bg-white px-4 py-3 shadow-2xl">
            <div className="flex items-center gap-2">
              <svg className="h-4 w-4 text-blue-500" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="9" cy="5" r="1.5" />
                <circle cx="15" cy="5" r="1.5" />
                <circle cx="9" cy="12" r="1.5" />
                <circle cx="15" cy="12" r="1.5" />
                <circle cx="9" cy="19" r="1.5" />
                <circle cx="15" cy="19" r="1.5" />
              </svg>
              <span className="truncate text-sm font-medium text-gray-700">
                {draggedBlock.title || draggedBlock.blockType}
              </span>
            </div>
            <div className="mt-1 truncate text-xs text-gray-400">{draggedBlock.blockType}</div>
          </div>
        )}
        {/* Field being reordered */}
        {draggedFieldName && !draggedBlock && (
          <div className="min-w-[120px] rounded-lg border-2 border-blue-400 bg-white px-3 py-2 shadow-2xl">
            <div className="flex items-center gap-2">
              <svg className="h-3 w-3 text-blue-500" viewBox="0 0 24 24" fill="currentColor">
                <circle cx="9" cy="7" r="1.5" />
                <circle cx="15" cy="7" r="1.5" />
                <circle cx="9" cy="12" r="1.5" />
                <circle cx="15" cy="12" r="1.5" />
                <circle cx="9" cy="17" r="1.5" />
                <circle cx="15" cy="17" r="1.5" />
              </svg>
              <span className="text-sm text-gray-700">{draggedFieldName}</span>
            </div>
          </div>
        )}
      </DragOverlay>
    </DndContext>
  );
};

export default AreasDesigner;
