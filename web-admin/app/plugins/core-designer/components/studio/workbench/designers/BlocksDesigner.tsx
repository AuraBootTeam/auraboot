/**
 * Blocks Designer
 *
 * Unified designer for list/form/detail pages using V2 flat PageSchema.blocks.
 * Unified designer for list/form/detail pages (replaced legacy AreasDesigner, Task 4.4).
 *
 * Layout:
 *   Left sidebar  — BlockLibrary (palette filtered by schema.kind)
 *   Center canvas — Flat sortable list of schema.blocks
 *   Right sidebar — BlockPropertyPanel for selected block
 */

import React, { useState, useCallback, useMemo, useEffect } from 'react';
import {
  DndContext,
  DragOverlay,
  pointerWithin,
  useSensor,
  useSensors,
  PointerSensor,
  KeyboardSensor,
} from '@dnd-kit/core';
import {
  SortableContext,
  arrayMove,
  sortableKeyboardCoordinates,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import type { DragStartEvent, DragEndEvent, Active } from '@dnd-kit/core';
import type {
  PageSchema,
  DslBlock,
  BlockType,
  DslFieldRef,
  DslFieldOverride,
} from '~/plugins/core-designer/components/studio/domain/dsl/types';
import {
  parseFieldShorthand,
  serializeFieldOverride,
  resolveLocalizedText,
} from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { BlockLibrary } from './areas/BlockLibrary';
import { BlockPropertyPanel } from './areas/BlockPropertyPanel';
import { BlockDragPreview } from './areas/BlockDragPreview';
import { SortableBlock } from './areas/SortableBlock';
import { DRAG_TYPES } from '~/plugins/core-designer/components/studio/workbench/constants';
import { useApiSchemaDetection } from '~/plugins/core-designer/components/studio/hooks/fields/useApiSchemaDetection';

export interface BlocksDesignerProps {
  schema: PageSchema;
  onSchemaChange: (schema: PageSchema) => void;
  onSave?: (schema: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
  isCustomApiMode?: boolean;
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

export const BlocksDesigner: React.FC<BlocksDesignerProps> = ({
  schema,
  onSchemaChange,
  onSave,
  modelCode,
  readonly = false,
  previewMode = false,
  isCustomApiMode,
}) => {
  // API schema detection for custom API mode
  const apiDataSource = isCustomApiMode ? (schema as any).dataSource : undefined;
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
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null);
  const [selectedFieldInfo, setSelectedFieldInfo] = useState<SelectedFieldInfo | null>(null);
  const [activeDragId, setActiveDragId] = useState<string | null>(null);
  const [draggedBlockType, setDraggedBlockType] = useState<BlockType | null>(null);
  const [leftPanelTab, setLeftPanelTab] = useState<LeftPanelTab>('fields');

  // Drag state for visual feedback
  const [activeFieldDrag, setActiveFieldDrag] = useState<Active | null>(null);
  const [draggedBlock, setDraggedBlock] = useState<DslBlock | null>(null);
  const [draggedFieldName, setDraggedFieldName] = useState<string | null>(null);

  // Configure sensors with activation constraint (8px movement before drag starts)
  const sensors = useSensors(
    useSensor(PointerSensor, {
      activationConstraint: { distance: 8 },
    }),
    useSensor(KeyboardSensor, {
      coordinateGetter: sortableKeyboardCoordinates,
    }),
  );

  // Flat blocks array from V2 schema
  const blocks = schema.blocks ?? [];

  // Find selected block
  const selectedBlock = useMemo(
    () => (selectedBlockId ? (blocks.find((b) => b.id === selectedBlockId) ?? null) : null),
    [selectedBlockId, blocks],
  );

  // Block IDs for SortableContext
  const blockIds = useMemo(() => blocks.map((b) => b.id), [blocks]);

  // ── Core block mutation helpers ────────────────────────────────────────────

  const addBlock = useCallback(
    (blockType: BlockType) => {
      if (readonly) return;

      // ID combines timestamp + random suffix to avoid same-millisecond
      // collisions (rapid sequential addBlock calls in tests / drag-spam).
      // Same-id blocks confuse SortableContext + React reconciliation,
      // resulting in stale render order vs blocks[] state.
      const newBlock: DslBlock = {
        id: `block_${Date.now()}_${Math.random().toString(36).slice(2, 8)}`,
        blockType,
        span: blockType === 'table' ? 12 : undefined,
      };

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

      onSchemaChange({ ...schema, blocks: [...blocks, newBlock] });
      setSelectedBlockId(newBlock.id);
    },
    [schema, blocks, onSchemaChange, readonly],
  );

  const removeBlock = useCallback(
    (id: string) => {
      if (readonly) return;
      onSchemaChange({ ...schema, blocks: blocks.filter((b) => b.id !== id) });
      if (selectedBlockId === id) {
        setSelectedBlockId(null);
        setSelectedFieldInfo(null);
      }
    },
    [schema, blocks, onSchemaChange, readonly, selectedBlockId],
  );

  const updateBlock = useCallback(
    (id: string, patch: Partial<DslBlock>) => {
      if (readonly) return;
      onSchemaChange({
        ...schema,
        blocks: blocks.map((b) => (b.id === id ? { ...b, ...patch } : b)),
      });
    },
    [schema, blocks, onSchemaChange, readonly],
  );

  const reorderBlocks = useCallback(
    (activeId: string, overId: string) => {
      if (readonly || activeId === overId) return;
      const oldIndex = blocks.findIndex((b) => b.id === activeId);
      const newIndex = blocks.findIndex((b) => b.id === overId);
      if (oldIndex === -1 || newIndex === -1) return;
      onSchemaChange({ ...schema, blocks: arrayMove(blocks, oldIndex, newIndex) });
    },
    [schema, blocks, onSchemaChange, readonly],
  );

  // ── Field mutation helpers ─────────────────────────────────────────────────

  const handleFieldDropToBlock = useCallback(
    (blockId: string, fieldCode: string, targetType: 'fields' | 'columns') => {
      if (readonly) return;
      const blockIdx = blocks.findIndex((b) => b.id === blockId);
      if (blockIdx === -1) return;

      const block = { ...blocks[blockIdx] };

      if (targetType === 'fields') {
        const current = block.fields || [];
        if (!current.includes(fieldCode)) block.fields = [...current, fieldCode];
      } else {
        const current = block.columns || [];
        const exists = current.some((col) =>
          typeof col === 'string' ? col === fieldCode : col.field === fieldCode,
        );
        if (!exists) block.columns = [...current, fieldCode];
      }

      const newBlocks = [...blocks];
      newBlocks[blockIdx] = block;
      onSchemaChange({ ...schema, blocks: newBlocks });
    },
    [schema, blocks, onSchemaChange, readonly],
  );

  const handleFieldReorder = useCallback(
    (blockId: string, oldIndex: number, newIndex: number) => {
      if (readonly || oldIndex === newIndex) return;
      const blockIdx = blocks.findIndex((b) => b.id === blockId);
      if (blockIdx === -1) return;

      const block = { ...blocks[blockIdx] };
      const fields = block.fields || [];
      if (oldIndex >= fields.length || newIndex >= fields.length) return;

      block.fields = arrayMove(fields, oldIndex, newIndex);
      const newBlocks = [...blocks];
      newBlocks[blockIdx] = block;
      onSchemaChange({ ...schema, blocks: newBlocks });
    },
    [schema, blocks, onSchemaChange, readonly],
  );

  const handleFieldUpdate = useCallback(
    (blockId: string, fieldIndex: number, updates: Partial<DslFieldOverride>) => {
      if (readonly) return;
      const blockIdx = blocks.findIndex((b) => b.id === blockId);
      if (blockIdx === -1) return;

      const block = { ...blocks[blockIdx] };
      const fields = [...(block.fields || [])];

      if (fieldIndex < fields.length) {
        const existing = parseFieldShorthand(fields[fieldIndex]);
        const merged = { ...existing, ...updates };
        fields[fieldIndex] = serializeFieldOverride(merged);
        block.fields = fields;

        if (
          selectedFieldInfo?.blockId === blockId &&
          selectedFieldInfo?.fieldIndex === fieldIndex
        ) {
          setSelectedFieldInfo({ blockId, fieldIndex, fieldRef: fields[fieldIndex] });
        }
      }

      const newBlocks = [...blocks];
      newBlocks[blockIdx] = block;
      onSchemaChange({ ...schema, blocks: newBlocks });
    },
    [schema, blocks, onSchemaChange, readonly, selectedFieldInfo],
  );

  // ── Selection handlers ─────────────────────────────────────────────────────

  const handleBlockSelect = useCallback((blockId: string) => {
    setSelectedBlockId(blockId);
    setSelectedFieldInfo(null);
  }, []);

  const handleFieldSelect = useCallback(
    (blockId: string, fieldIndex: number, fieldRef: DslFieldRef) => {
      setSelectedBlockId(blockId);
      setSelectedFieldInfo({ blockId, fieldIndex, fieldRef });
    },
    [],
  );

  const handleFieldDeselect = useCallback(() => setSelectedFieldInfo(null), []);

  // ── DnD handlers ───────────────────────────────────────────────────────────

  const handleDragStart = useCallback(
    (event: DragStartEvent) => {
      const { active } = event;
      setActiveDragId(active.id as string);

      if (typeof active.id === 'string' && active.id.startsWith('library:')) {
        setDraggedBlockType(active.id.replace('library:', '') as BlockType);
      }
      if (active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
        setActiveFieldDrag(active);
      }
      if (active.data.current?.type === 'block') {
        const block = blocks.find((b) => b.id === active.data.current?.blockId);
        if (block) setDraggedBlock(block);
      }
      if (active.data.current?.type === 'block-field') {
        setDraggedFieldName(active.data.current.fieldName || null);
      }
    },
    [blocks],
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

      const activeId = String(active.id);
      const overId = String(over.id);

      // ── Field reorder within a block ──
      if (active.data.current?.type === 'block-field') {
        const activeMatch = activeId.match(/^(.+):field:(.+)$/);
        const overMatch = overId.match(/^(.+):field:(.+)$/);

        if (activeMatch && overMatch && activeMatch[1] === overMatch[1] && activeId !== overId) {
          const blockId = activeMatch[1];
          const block = blocks.find((b) => b.id === blockId);
          if (block?.fields) {
            const activeKey = activeMatch[2];
            const overKey = overMatch[2];
            const findIdx = (key: string) =>
              block.fields!.findIndex((f, idx) =>
                typeof f === 'string'
                  ? f.split('|')[0] === key || String(idx) === key
                  : f.field === key || String(idx) === key,
              );
            const oldIndex = findIdx(activeKey);
            const newIndex = findIdx(overKey);
            if (oldIndex !== -1 && newIndex !== -1 && oldIndex !== newIndex) {
              handleFieldReorder(blockId, oldIndex, newIndex);
            }
          }
        }
        return;
      }

      // ── Drop from block library ──
      if (activeId.startsWith('library:')) {
        const blockType = activeId.replace('library:', '') as BlockType;
        if (
          overId === 'blocks-canvas' ||
          overId.startsWith('block-drop:') ||
          blocks.some((b) => b.id === overId)
        ) {
          addBlock(blockType);
        }
        return;
      }

      // ── Block reorder ──
      const isBlockDrag =
        active.data.current?.type === 'block' || blocks.some((b) => b.id === activeId);
      if (isBlockDrag) {
        let targetId = overId;
        if (overId.startsWith('block-drop:')) targetId = overId.split(':')[1];
        reorderBlocks(activeId, targetId);
        return;
      }

      // ── Field palette drop onto a block ──
      if (active.data.current?.type === DRAG_TYPES.PALETTE_ITEM) {
        const fieldCode = active.data.current.component?.props?.name;
        if (!fieldCode) return;

        if (overId.startsWith('block-drop:')) {
          const parts = overId.split(':');
          const blockId = parts[1];
          const targetTypeStr = parts[2];
          if (blockId && (targetTypeStr === 'fields' || targetTypeStr === 'columns')) {
            handleFieldDropToBlock(blockId, fieldCode, targetTypeStr);
          }
        } else if (overId.startsWith('fields-drop:')) {
          handleFieldDropToBlock(overId.replace('fields-drop:', ''), fieldCode, 'fields');
        } else if (overId.startsWith('columns-drop:')) {
          handleFieldDropToBlock(overId.replace('columns-drop:', ''), fieldCode, 'columns');
        }
      }
    },
    [blocks, addBlock, reorderBlocks, handleFieldDropToBlock, handleFieldReorder],
  );

  // ── Render ─────────────────────────────────────────────────────────────────

  const kindLabel =
    schema.kind === 'list' ? 'List Page' : schema.kind === 'form' ? 'Form Page' : 'Detail Page';

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={pointerWithin}
      onDragStart={handleDragStart}
      onDragEnd={handleDragEnd}
    >
      <div className="flex h-full flex-1 overflow-hidden">
        {/* Left Panel */}
        {!previewMode && (
          <div className="flex w-64 flex-col border-r border-gray-200 bg-white">
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

            <div className="flex-1 overflow-hidden">
              {leftPanelTab === 'blocks' && (
                <BlockLibrary pageKind={schema.kind} readonly={readonly} onAddBlock={addBlock} />
              )}
              {leftPanelTab === 'outline' && (
                <div className="overflow-auto p-3">
                  {blocks.length === 0 ? (
                    <p className="py-4 text-center text-xs text-gray-400">No blocks yet</p>
                  ) : (
                    <ul className="space-y-1">
                      {blocks.map((block) => (
                        <li key={block.id}>
                          <button
                            onClick={() => handleBlockSelect(block.id)}
                            className={`w-full rounded px-2 py-1.5 text-left text-xs transition-colors ${
                              selectedBlockId === block.id
                                ? 'bg-blue-50 font-medium text-blue-700'
                                : 'text-gray-600 hover:bg-gray-50'
                            }`}
                          >
                            {resolveLocalizedText(block.title) || block.blockType}
                          </button>
                        </li>
                      ))}
                    </ul>
                  )}
                </div>
              )}
            </div>
          </div>
        )}

        {/* Center: Blocks Canvas */}
        <div className="flex-1 overflow-auto bg-gray-50" data-testid="designer-canvas">
          <div className="space-y-4 p-6">
            {/* Page header */}
            <div className="rounded-lg border border-gray-200 bg-white p-4">
              <div className="flex items-center justify-between">
                <div>
                  <h2 className="text-lg font-medium text-gray-900">{schema.id}</h2>
                  <p className="text-sm text-gray-500">
                    {kindLabel} — {schema.modelCode}
                  </p>
                </div>
                {onSave && !readonly && (
                  <button
                    onClick={() => onSave(schema)}
                    className="rounded-md bg-blue-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700"
                  >
                    Save
                  </button>
                )}
              </div>
            </div>

            {/* Flat blocks canvas */}
            <div id="blocks-canvas" className="min-h-[200px] rounded-lg" data-testid="blocks-canvas">
              {blocks.length === 0 ? (
                <div className="flex min-h-[200px] flex-col items-center justify-center rounded-lg border-2 border-dashed border-gray-300 bg-white">
                  <svg
                    className="mb-3 h-10 w-10 text-gray-300"
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
                  <p className="text-sm font-medium text-gray-400">Drag blocks from the left panel</p>
                  <p className="mt-1 text-xs text-gray-300">or switch to the Blocks tab to add</p>
                </div>
              ) : (
                <SortableContext items={blockIds} strategy={verticalListSortingStrategy}>
                  <div className="space-y-3 pl-8">
                    {blocks.map((block) => (
                      <SortableBlock
                        key={block.id}
                        block={block}
                        isSelected={selectedBlockId === block.id}
                        selectedFieldInfo={
                          selectedFieldInfo?.blockId === block.id ? selectedFieldInfo : null
                        }
                        onSelect={() => handleBlockSelect(block.id)}
                        onDelete={() => removeBlock(block.id)}
                        onFieldReorder={handleFieldReorder}
                        onFieldSelect={handleFieldSelect}
                        readonly={readonly}
                      />
                    ))}
                  </div>
                </SortableContext>
              )}
            </div>

            {/* Add block shortcut */}
            {!readonly && leftPanelTab !== 'blocks' && (
              <button
                onClick={() => setLeftPanelTab('blocks')}
                className="w-full rounded-lg border-2 border-dashed border-gray-200 py-3 text-sm text-gray-400 transition-colors hover:border-blue-300 hover:text-blue-500"
              >
                + Add Block (open Blocks panel)
              </button>
            )}
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
              modelCode={modelCode || schema.modelCode}
              selectedFieldInfo={selectedFieldInfo}
              onChange={(updates) => {
                if (selectedBlockId) updateBlock(selectedBlockId, updates);
              }}
              onFieldChange={handleFieldUpdate}
              onFieldDeselect={handleFieldDeselect}
              readonly={readonly}
              isCustomApiMode={isCustomApiMode}
              dataSource={apiDataSource}
              onDataSourceChange={(ds) => {
                onSchemaChange({ ...schema, dataSource: ds } as any);
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
      <DragOverlay dropAnimation={{ duration: 200, easing: 'cubic-bezier(0.18, 0.67, 0.6, 1.22)' }}>
        {activeDragId && draggedBlockType && <BlockDragPreview blockType={draggedBlockType} />}
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
                {resolveLocalizedText(draggedBlock.title) || draggedBlock.blockType}
              </span>
            </div>
            <div className="mt-1 truncate text-xs text-gray-400">{draggedBlock.blockType}</div>
          </div>
        )}
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

export default BlocksDesigner;
