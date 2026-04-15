/**
 * CanvasEditor — Main 3-panel layout for composite page editing
 *
 * CSS Grid layout: 240px | 1fr | 300px
 * - Left panel: Block palette (placeholder for Task 6)
 * - Center: CanvasBody with title, blocks, empty state
 * - Right panel: Block config (placeholder for Task 7)
 *
 * Manages state via useCanvasBlocks and converts to/from PageSchema.
 *
 * @since 4.0.0
 */

import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  DndContext,
  DragOverlay,
  closestCenter,
  PointerSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core';
import { initRegistry } from '~/plugins/core-designer/components/studio/registry/init';

// Initialize widget & block registries once at module load time.
// This ensures WidgetPalette, BlockPalette, and config panels all see
// the full registry before any component renders.
initRegistry();
import type { PageSchema } from '~/plugins/core-designer/components/studio/domain/dsl/types';
import { CURRENT_SCHEMA_VERSION } from '~/framework/meta/migration';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { useCanvasBlocks } from '~/plugins/core-designer/components/studio/hooks/canvas/useCanvasBlocks';
import { useCanvasDnd } from '~/plugins/core-designer/components/studio/hooks/canvas/useCanvasDnd';
import { CanvasBody } from './CanvasBody';
import { BlockPalette } from './left/BlockPalette';
import { FieldPalette } from './left/FieldPalette';
import { OutlinePanel } from './left/OutlinePanel';
import { WidgetPalette } from './left/WidgetPalette';
import { BlockConfigPanel } from './right/BlockConfigPanel';
import {
  appendFieldLikeToFormSection,
  createFormSectionWithFieldLike,
  createWidgetFieldConfig,
  resolveAdjacentSectionInsertIndex,
} from './canvasFormSectionAdd';
import { useCanvasDragState } from './useCanvasDragState';

export interface CanvasEditorProps {
  dsl: PageSchema;
  onDslChange: (dsl: PageSchema) => void;
  onSave?: (dsl: PageSchema) => Promise<void>;
  modelCode?: string;
  readonly?: boolean;
  previewMode?: boolean;
  /** Device preview width in pixels (null = default 980px) */
  deviceWidth?: number | null;
}

/**
 * Extract canvas blocks from DSL schema
 */
function extractBlocks(dsl: PageSchema): CanvasBlock[] {
  const raw = dsl as unknown as Record<string, unknown>;
  if (Array.isArray(raw.blocks)) {
    return raw.blocks as CanvasBlock[];
  }
  return [];
}

/**
 * Extract title string from DSL
 */
function extractTitle(dsl: PageSchema): string {
  const raw = dsl as unknown as Record<string, unknown>;
  if (typeof raw.title === 'string') return raw.title;
  if (raw.title && typeof raw.title === 'object') {
    const titleObj = raw.title as Record<string, string>;
    return titleObj['en-US'] || titleObj['zh-CN'] || '';
  }
  return '';
}

/**
 * Extract description from DSL
 */
function extractDescription(dsl: PageSchema): string {
  const raw = dsl as unknown as Record<string, unknown>;
  return typeof raw.description === 'string' ? raw.description : '';
}

type LeftPanelTab = 'components' | 'fields' | 'widgets' | 'outline';

export const CanvasEditor: React.FC<CanvasEditorProps> = ({
  dsl,
  onDslChange,
  modelCode,
  readonly = false,
  previewMode = false,
  deviceWidth,
}) => {
  const [title, setTitle] = useState(() => extractTitle(dsl));
  const [description, setDescription] = useState(() => extractDescription(dsl));
  const [leftTab, setLeftTab] = useState<LeftPanelTab>('components');
  const [selectedFieldIndex, setSelectedFieldIndex] = useState<number | null>(null);

  const {
    blocks,
    selectedBlockId,
    selectedBlock,
    addBlock,
    addBlockAt,
    removeBlock,
    moveBlock,
    updateBlock,
    updateBlockLayouts,
    setSelectedBlockId,
    setBlocks,
  } = useCanvasBlocks(extractBlocks(dsl));

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
  );

  // Track whether we should fire onSchemaChange (avoid initial render)
  const isInitialMount = useRef(true);
  // Flag: blocks/title/description changed internally (user edit), not from undo/redo
  const isInternalChange = useRef(false);

  // Sync state changes back to DSL
  useEffect(() => {
    if (isInitialMount.current) {
      isInitialMount.current = false;
      return;
    }

    // Mark this DSL emission as coming from an internal edit so the
    // dsl-sync effect below can skip it and avoid a double-update loop.
    isInternalChange.current = true;

    // Build updated DSL — strip areas/floors (not used by composite)
    const { areas: _a, floors: _f, components: _c, ...baseDsl } = dsl as PageSchema & {
      areas?: unknown;
      floors?: unknown;
      components?: unknown;
    };
    const updatedDsl = {
      ...baseDsl,
      kind: 'composite' as const,
      blocks,
      layout: { type: 'grid' as const, cols: 12 },
      title: title || undefined,
      description: description || undefined,
      schemaVersion: CURRENT_SCHEMA_VERSION,
    } as unknown as PageSchema;

    onDslChange(updatedDsl);
  }, [blocks, title, description]);

  // Sync canvas state when DSL changes externally (undo / redo / reload).
  // Skip if the change originated from our own internal edit above.
  useEffect(() => {
    if (isInternalChange.current) {
      isInternalChange.current = false;
      return;
    }
    // Skip the very first render — useCanvasBlocks already seeded from dsl.
    if (isInitialMount.current) return;

    const newBlocks = extractBlocks(dsl);
    setBlocks(newBlocks);
    setTitle(extractTitle(dsl));
    setDescription(extractDescription(dsl));
    setSelectedBlockId(null);
  }, [dsl]);

  // Clear field selection when block selection changes
  const handleSelectBlock = useCallback((id: string | null) => {
    setSelectedBlockId(id);
    setSelectedFieldIndex(null);
  }, [setSelectedBlockId]);

  const handleAddBlock = useCallback(
    (blockType: string) => {
      addBlock(blockType);
    },
    [addBlock],
  );

  const insertIntoSelectedOrNewFormSection = useCallback(
    (fieldLike: unknown, selectedIndexAfterInsert: number) => {
      if (selectedBlock?.blockType === 'form-section') {
        setSelectedFieldIndex(
          appendFieldLikeToFormSection(selectedBlock, updateBlock, fieldLike),
        );
        return;
      }

      createFormSectionWithFieldLike(
        addBlock,
        fieldLike,
        resolveAdjacentSectionInsertIndex(blocks, selectedBlockId),
      );
      setSelectedFieldIndex(selectedIndexAfterInsert);
    },
    [selectedBlock, selectedBlockId, blocks, updateBlock, addBlock],
  );

  const handleAddWidget = useCallback(
    (component: string) => {
      insertIntoSelectedOrNewFormSection(createWidgetFieldConfig(component), 0);
    },
    [insertIntoSelectedOrNewFormSection],
  );

  const handleAddField = useCallback(
    (fieldCode: string) => {
      insertIntoSelectedOrNewFormSection(fieldCode, 0);
    },
    [insertIntoSelectedOrNewFormSection],
  );

  const handleBlockUpdate = useCallback(
    (patch: Partial<import('~/plugins/core-designer/components/studio/domain/canvas/types').CanvasBlock>) => {
      if (selectedBlockId) {
        updateBlock(selectedBlockId, patch);
      }
    },
    [selectedBlockId, updateBlock],
  );

  const handleRemoveField = useCallback(
    (blockId: string, fieldIndex: number) => {
      const block = blocks.find(b => b.id === blockId);
      if (!block || block.blockType !== 'form-section') return;
      const fields = (block.config.fields as unknown[]) ?? [];
      const newFields = fields.filter((_, i) => i !== fieldIndex);
      updateBlock(blockId, { config: { ...block.config, fields: newFields } });
      setSelectedFieldIndex(null);
    },
    [blocks, updateBlock],
  );

  const handleReorderFields = useCallback(
    (blockId: string, fromIndex: number, toIndex: number) => {
      const block = blocks.find(b => b.id === blockId);
      if (!block || block.blockType !== 'form-section') return;
      const fields = [...((block.config.fields as unknown[]) ?? [])];
      // Move field from fromIndex to toIndex
      const [moved] = fields.splice(fromIndex, 1);
      fields.splice(toIndex, 0, moved);
      updateBlock(blockId, { config: { ...block.config, fields } });
    },
    [blocks, updateBlock],
  );

  const { handleDragEnd, handleRglDrop, handleRglDropDragOver } = useCanvasDnd({ blocks, addBlock, addBlockAt, moveBlock, updateBlock, reorderFields: handleReorderFields });

  // Extract pageKey from DSL (readonly, used by Page settings)
  const pageKey = (() => {
    const raw = dsl as unknown as Record<string, unknown>;
    return typeof raw.pageKey === 'string' ? raw.pageKey : undefined;
  })();

  const {
    activeDragKind,
    activeDragLabel,
    activeOverId,
    onDragCancel,
    onDragEnd,
    onDragOver,
    onDragStart,
  } = useCanvasDragState({ blocks, handleDragEnd });

  return (
    <DndContext
      sensors={sensors}
      collisionDetection={closestCenter}
      onDragStart={onDragStart}
      onDragOver={onDragOver}
      onDragEnd={onDragEnd}
      onDragCancel={onDragCancel}
    >
    <div
      className="h-full"
      style={{
        display: 'grid',
        gridTemplateColumns: '240px 1fr 300px',
      }}
      data-testid="canvas-editor"
    >
      {/* Left panel — Block / Field / Outline tabs */}
      <div
        className="flex flex-col border-r border-gray-200 bg-white"
        data-testid="canvas-left-panel"
      >
        {/* Tab bar */}
        <div className="flex border-b border-gray-200" data-testid="canvas-left-tabs">
          {(
            [
              { id: 'components', label: 'Components' },
              { id: 'fields', label: 'Fields' },
              { id: 'widgets', label: 'Widgets' },
              { id: 'outline', label: 'Outline' },
            ] as { id: LeftPanelTab; label: string }[]
          ).map((tab) => (
            <button
              key={tab.id}
              onClick={() => setLeftTab(tab.id)}
              data-testid={`canvas-left-tab-${tab.id}`}
              className={`flex-1 px-1 py-2 text-[11px] font-medium transition-colors ${
                leftTab === tab.id
                  ? 'border-b-2 border-purple-500 bg-purple-50 text-purple-700'
                  : 'text-gray-500 hover:bg-gray-50 hover:text-gray-700'
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>

        {/* Tab content */}
        <div className="min-h-0 flex-1 overflow-hidden">
          {leftTab === 'components' && (
            <BlockPalette onAddBlock={handleAddBlock} readonly={readonly} />
          )}
          {leftTab === 'fields' && (
            <FieldPalette onAddField={handleAddField} modelCode={modelCode} readonly={readonly} />
          )}
          {leftTab === 'widgets' && (
            <WidgetPalette onAddWidget={handleAddWidget} readonly={readonly} />
          )}
          {leftTab === 'outline' && (
            <OutlinePanel
              blocks={blocks}
              selectedBlockId={selectedBlockId}
              onSelectBlock={handleSelectBlock}
            />
          )}
        </div>
      </div>

      {/* Center — Canvas body */}
      <CanvasBody
        title={title}
        description={description}
        blocks={blocks}
        selectedBlockId={selectedBlockId}
        onTitleChange={setTitle}
        onDescriptionChange={setDescription}
        onAddBlock={handleAddBlock}
        onSelectBlock={handleSelectBlock}
        onRemoveBlock={removeBlock}
        onLayoutChange={updateBlockLayouts}
        selectedFieldIndex={selectedFieldIndex}
        onSelectField={setSelectedFieldIndex}
        onRemoveField={handleRemoveField}
        onReorderFields={handleReorderFields}
        onRglDrop={handleRglDrop}
        onRglDropDragOver={handleRglDropDragOver}
        deviceWidth={deviceWidth}
        activeDragKind={activeDragKind}
        activeOverId={activeOverId}
      />

      {/* Right panel — Block config panel */}
      <div
        className="flex flex-col border-l border-gray-200 bg-white"
        data-testid="canvas-right-panel"
      >
        <BlockConfigPanel
          selectedBlock={selectedBlock}
          onBlockUpdate={handleBlockUpdate}
          pageTitle={title}
          pageKey={pageKey}
          pageDescription={description}
          onTitleChange={setTitle}
          onDescriptionChange={setDescription}
          selectedFieldIndex={selectedFieldIndex}
          onClearFieldSelection={() => setSelectedFieldIndex(null)}
        />
      </div>
    </div>

      {/* DragOverlay — shown while dragging; renders a floating chip with the item label */}
      <DragOverlay>
        {activeDragLabel ? (
          <div className="rounded-md border border-purple-300 bg-white px-3 py-1.5 text-xs font-semibold text-purple-700 shadow-lg opacity-90">
            {activeDragLabel}
          </div>
        ) : null}
      </DragOverlay>
    </DndContext>
  );
};

export default CanvasEditor;
