import React, { useState } from 'react';
import { ArrowDown, ArrowUp, Maximize2 } from 'lucide-react';
import type { DesignerMode, DslBlockV3, ModelFieldDefinition, PageSchemaV3 } from '../types';
import { readModelFieldPayload, readPaletteBlockType } from '../utils/dragPayload';

const SPAN_PRESETS = [3, 4, 6, 8, 12] as const;

type DropIntent = 'before' | 'inside';
type DropIntentState = { blockId: string; intent: DropIntent } | null;

interface CanvasHostProps {
  document: PageSchemaV3;
  mode: DesignerMode;
  selectedBlockId: string | null;
  draggingPaletteBlockType: string | null;
  draggingModelField: ModelFieldDefinition | null;
  onSelect: (blockId: string) => void;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
  onPatchBlock: (blockId: string, updater: (block: DslBlockV3) => DslBlockV3) => void;
  canAddBlockToParent: (parentBlockId: string, blockType: string) => boolean;
  onAddBlockToParent: (parentBlockId: string, blockType: string) => void;
  canAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => boolean;
  onAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => void;
  canAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => void;
  canAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => void;
  canAddBlockToRoot: (blockType: string) => boolean;
  onAddBlockToRoot: (blockType: string) => void;
  onPaletteDragEnd: () => void;
  onModelFieldDragEnd: () => void;
}

export function CanvasHost({
  document,
  mode,
  selectedBlockId,
  draggingPaletteBlockType,
  draggingModelField,
  onSelect,
  onMoveBefore,
  onPatchBlock,
  canAddBlockToParent,
  onAddBlockToParent,
  canAddBlockBeforeTarget,
  onAddBlockBeforeTarget,
  canAddModelFieldToParent,
  onAddModelFieldToParent,
  canAddModelFieldBeforeTarget,
  onAddModelFieldBeforeTarget,
  canAddBlockToRoot,
  onAddBlockToRoot,
  onPaletteDragEnd,
  onModelFieldDragEnd,
}: CanvasHostProps) {
  const [draggingBlockId, setDraggingBlockId] = useState<string | null>(null);
  const [activeDropIntent, setActiveDropIntent] = useState<DropIntentState>(null);
  const rootCanAcceptPaletteBlock = Boolean(
    draggingPaletteBlockType && canAddBlockToRoot(draggingPaletteBlockType),
  );
  const clearDropIntent = (blockId?: string) => {
    setActiveDropIntent((current) => {
      if (!current) return current;
      return !blockId || current.blockId === blockId ? null : current;
    });
  };
  const patchWidgetLayout = (blockId: string, layoutPatch: Record<string, number>) => {
    onPatchBlock(blockId, (block) => ({
      ...block,
      layout: { ...block.layout, ...layoutPatch },
    }));
  };
  const patchBlockSpan = (blockId: string, span: number) => {
    onPatchBlock(blockId, (block) => ({
      ...block,
      layout: { ...block.layout, span },
    }));
  };

  return (
    <main
      className="min-h-[420px] flex-1 overflow-auto bg-slate-100 p-3 lg:p-6 xl:overflow-auto"
      data-testid="unified-canvas-host"
      onPointerUp={() => {
        clearDropIntent();
        if (draggingPaletteBlockType) onPaletteDragEnd();
        if (draggingModelField) onModelFieldDragEnd();
      }}
      onMouseUp={() => {
        clearDropIntent();
        if (draggingPaletteBlockType) onPaletteDragEnd();
        if (draggingModelField) onModelFieldDragEnd();
      }}
    >
      <div className="mx-auto min-w-[720px] max-w-7xl space-y-4 xl:min-w-0">
        <div
          data-testid="canvas-root-drop-zone"
          data-can-drop={rootCanAcceptPaletteBlock ? 'true' : 'false'}
          onDragOver={(event) => {
            const paletteBlockType =
              readPaletteBlockType(event.dataTransfer) || draggingPaletteBlockType;
            if (paletteBlockType && canAddBlockToRoot(paletteBlockType)) {
              setActiveDropIntent(null);
              event.preventDefault();
              event.stopPropagation();
            }
          }}
          onDrop={(event) => {
            const paletteBlockType =
              readPaletteBlockType(event.dataTransfer) || draggingPaletteBlockType;
            if (!paletteBlockType || !canAddBlockToRoot(paletteBlockType)) return;

            event.preventDefault();
            event.stopPropagation();
            onAddBlockToRoot(paletteBlockType);
            clearDropIntent();
            onPaletteDragEnd();
          }}
          onPointerUp={(event) => {
            handleRootPalettePointerRelease({
              event,
              draggingPaletteBlockType,
              canAddBlockToRoot,
              onAddBlockToRoot,
              onPaletteDragEnd,
            });
          }}
          onMouseUp={(event) => {
            handleRootPalettePointerRelease({
              event,
              draggingPaletteBlockType,
              canAddBlockToRoot,
              onAddBlockToRoot,
              onPaletteDragEnd,
            });
          }}
          className={`rounded-md border bg-white p-4 transition ${
            rootCanAcceptPaletteBlock
              ? 'border-blue-400 bg-blue-50/60 ring-2 ring-blue-100'
              : 'border-slate-200'
          }`}
        >
          <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            Composite canvas
          </div>
          <div className="mt-1 text-lg font-semibold text-slate-900">{document.id}</div>
          <div className="mt-2 text-xs text-slate-500">
            Mode: <span className="font-medium text-blue-700">{mode}</span>
          </div>
        </div>
        <div className="grid grid-cols-12 gap-4">
          {document.blocks.map((block) => (
            <BlockFrame
              key={block.id}
              block={block}
              siblingBlocks={document.blocks}
              mode={mode}
              selectedBlockId={selectedBlockId}
              draggingBlockId={draggingBlockId}
              draggingPaletteBlockType={draggingPaletteBlockType}
              draggingModelField={draggingModelField}
              activeDropIntent={activeDropIntent}
              onDropIntentChange={setActiveDropIntent}
              onDropIntentClear={clearDropIntent}
              onSelect={onSelect}
              onDragStart={setDraggingBlockId}
              onDragEnd={() => setDraggingBlockId(null)}
              onMoveBefore={onMoveBefore}
              canAddBlockToParent={canAddBlockToParent}
              onAddBlockToParent={onAddBlockToParent}
              canAddBlockBeforeTarget={canAddBlockBeforeTarget}
              onAddBlockBeforeTarget={onAddBlockBeforeTarget}
              canAddModelFieldToParent={canAddModelFieldToParent}
              onAddModelFieldToParent={onAddModelFieldToParent}
              canAddModelFieldBeforeTarget={canAddModelFieldBeforeTarget}
              onAddModelFieldBeforeTarget={onAddModelFieldBeforeTarget}
              onPaletteDragEnd={onPaletteDragEnd}
              onModelFieldDragEnd={onModelFieldDragEnd}
              onMoveWidget={patchWidgetLayout}
              onResizeWidget={patchWidgetLayout}
              onResizeSpan={patchBlockSpan}
            />
          ))}
        </div>
      </div>
    </main>
  );
}

function BlockFrame({
  block,
  siblingBlocks,
  mode,
  selectedBlockId,
  draggingBlockId,
  draggingPaletteBlockType,
  draggingModelField,
  activeDropIntent,
  onDropIntentChange,
  onDropIntentClear,
  dashboardSiblings,
  onSelect,
  onDragStart,
  onDragEnd,
  onMoveBefore,
  canAddBlockToParent,
  onAddBlockToParent,
  canAddBlockBeforeTarget,
  onAddBlockBeforeTarget,
  canAddModelFieldToParent,
  onAddModelFieldToParent,
  canAddModelFieldBeforeTarget,
  onAddModelFieldBeforeTarget,
  onPaletteDragEnd,
  onModelFieldDragEnd,
  onMoveWidget,
  onResizeWidget,
  onResizeSpan,
}: {
  block: DslBlockV3;
  siblingBlocks?: DslBlockV3[];
  mode: DesignerMode;
  selectedBlockId: string | null;
  draggingBlockId: string | null;
  draggingPaletteBlockType: string | null;
  draggingModelField: ModelFieldDefinition | null;
  activeDropIntent: DropIntentState;
  onDropIntentChange: (intent: DropIntentState) => void;
  onDropIntentClear: (blockId?: string) => void;
  dashboardSiblings?: DslBlockV3[];
  onSelect: (blockId: string) => void;
  onDragStart: (blockId: string) => void;
  onDragEnd: () => void;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
  canAddBlockToParent: (parentBlockId: string, blockType: string) => boolean;
  onAddBlockToParent: (parentBlockId: string, blockType: string) => void;
  canAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => boolean;
  onAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => void;
  canAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => void;
  canAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => void;
  onPaletteDragEnd: () => void;
  onModelFieldDragEnd: () => void;
  onMoveWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeSpan: (blockId: string, span: number) => void;
}) {
  const selected = selectedBlockId === block.id;
  const isDashboardWidget = block.blockType === 'widget';
  const siblingIndex = siblingBlocks?.findIndex((sibling) => sibling.id === block.id) ?? -1;
  const previousSibling = siblingIndex > 0 ? siblingBlocks?.[siblingIndex - 1] : undefined;
  const nextSibling =
    siblingIndex >= 0 && siblingBlocks ? siblingBlocks[siblingIndex + 1] : undefined;
  const span = typeof block.layout?.span === 'number' ? block.layout.span : 12;
  const widgetX = getGridNumber(block.layout?.x, 0);
  const widgetY = getGridNumber(block.layout?.y, 0);
  const widgetW = getGridNumber(
    block.layout?.w,
    typeof block.layout?.span === 'number' ? block.layout.span : 3,
  );
  const widgetH = getGridNumber(block.layout?.h, 2);
  const columnSpan = Math.max(1, Math.min(12, isDashboardWidget ? widgetW : span));
  const maxWidgetX = Math.max(0, 12 - columnSpan);
  const gridColumn = isDashboardWidget
    ? `${Math.max(0, Math.min(maxWidgetX, widgetX)) + 1} / span ${columnSpan}`
    : `span ${columnSpan} / span ${columnSpan}`;
  const gridRow = isDashboardWidget
    ? `${Math.max(0, widgetY) + 1} / span ${Math.max(1, Math.min(12, widgetH))}`
    : undefined;
  const minHeight =
    block.blockType === 'widget' && typeof block.layout?.h === 'number'
      ? Math.max(120, block.layout.h * 64)
      : undefined;
  const currentDropIntent =
    activeDropIntent?.blockId === block.id ? activeDropIntent.intent : 'none';

  return (
    <section
      data-testid={`canvas-block-${block.id}`}
      data-selected={selected ? 'true' : 'false'}
      data-layout-x={isDashboardWidget ? widgetX : undefined}
      data-layout-y={isDashboardWidget ? widgetY : undefined}
      data-layout-span={columnSpan}
      data-drop-intent={currentDropIntent}
      draggable={false}
      onDragStart={(event) => {
        event.dataTransfer.setData('text/plain', block.id);
        onDragStart(block.id);
      }}
      onDragEnd={onDragEnd}
      onDragOver={(event) => {
        const paletteBlockType = draggingPaletteBlockType || readPaletteBlockType(event.dataTransfer);
        const modelField = draggingModelField || readModelFieldPayload(event.dataTransfer);
        const nextDropIntent = resolveDropIntent({
          blockId: block.id,
          mode,
          movingBlockId: draggingBlockId,
          paletteBlockType,
          modelField,
          canAddBlockBeforeTarget,
          canAddBlockToParent,
          canAddModelFieldBeforeTarget,
          canAddModelFieldToParent,
        });
        if (nextDropIntent) {
          onDropIntentChange({ blockId: block.id, intent: nextDropIntent });
          event.preventDefault();
          event.stopPropagation();
        }
      }}
      onDragLeave={() => {
        onDropIntentClear(block.id);
      }}
      onDrop={(event) => {
        event.preventDefault();
        event.stopPropagation();
        const paletteBlockType = readPaletteBlockType(event.dataTransfer) || draggingPaletteBlockType;
        const modelField = readModelFieldPayload(event.dataTransfer) || draggingModelField;
        if (modelField) {
          if (canAddModelFieldBeforeTarget(block.id, modelField)) {
            onAddModelFieldBeforeTarget(block.id, modelField);
          } else if (canAddModelFieldToParent(block.id, modelField)) {
            onAddModelFieldToParent(block.id, modelField);
          }
          onDropIntentClear(block.id);
          onModelFieldDragEnd();
          return;
        }
        if (paletteBlockType) {
          if (canAddBlockBeforeTarget(block.id, paletteBlockType)) {
            onAddBlockBeforeTarget(block.id, paletteBlockType);
          } else if (canAddBlockToParent(block.id, paletteBlockType)) {
            onAddBlockToParent(block.id, paletteBlockType);
          }
          onDropIntentClear(block.id);
          onPaletteDragEnd();
          return;
        }
        const movingId = event.dataTransfer.getData('text/plain') || draggingBlockId;
        if (mode === 'layout' && movingId) onMoveBefore(movingId, block.id);
        onDropIntentClear(block.id);
      }}
      onPointerUp={(event) => {
        handlePalettePointerRelease({
          event,
          block,
          draggingPaletteBlockType,
          canAddBlockToParent,
          onAddBlockToParent,
          canAddBlockBeforeTarget,
          onAddBlockBeforeTarget,
          onDropIntentClear,
          onPaletteDragEnd,
        });
        handleModelFieldPointerRelease({
          event,
          block,
          draggingModelField,
          canAddModelFieldToParent,
          onAddModelFieldToParent,
          canAddModelFieldBeforeTarget,
          onAddModelFieldBeforeTarget,
          onDropIntentClear,
          onModelFieldDragEnd,
        });
      }}
      onMouseUp={(event) => {
        handlePalettePointerRelease({
          event,
          block,
          draggingPaletteBlockType,
          canAddBlockToParent,
          onAddBlockToParent,
          canAddBlockBeforeTarget,
          onAddBlockBeforeTarget,
          onDropIntentClear,
          onPaletteDragEnd,
        });
        handleModelFieldPointerRelease({
          event,
          block,
          draggingModelField,
          canAddModelFieldToParent,
          onAddModelFieldToParent,
          canAddModelFieldBeforeTarget,
          onAddModelFieldBeforeTarget,
          onDropIntentClear,
          onModelFieldDragEnd,
        });
      }}
      onPointerDown={(event) => {
        if (mode !== 'layout') return;
        if (block.blockType === 'widget') {
          handleWidgetMovePointerDown(
            event,
            block,
            dashboardSiblings ?? [block],
            onSelect,
            onMoveWidget,
          );
          return;
        }
        handleBlockReorderPointerDown(
          event,
          block,
          onSelect,
          onMoveBefore,
          onDragStart,
          onDragEnd,
        );
      }}
      onMouseDown={(event) => {
        if (mode !== 'layout' || block.blockType === 'widget') return;
        handleBlockReorderPointerDown(
          event,
          block,
          onSelect,
          onMoveBefore,
          onDragStart,
          onDragEnd,
        );
      }}
      onClick={(event) => {
        event.stopPropagation();
        onSelect(block.id);
      }}
      className={`group relative rounded-lg border bg-white transition ${
        currentDropIntent === 'inside'
          ? 'border-blue-500 bg-blue-50/50 ring-2 ring-blue-100'
          : selected
          ? 'border-blue-500 ring-2 ring-blue-100'
          : 'border-slate-200 hover:border-blue-300'
      } ${mode === 'layout' ? 'cursor-move' : 'cursor-pointer'}`}
      style={{ gridColumn, gridRow, minHeight }}
    >
      {currentDropIntent === 'before' ? (
        <div
          aria-hidden="true"
          data-testid={`drop-indicator-before-${block.id}`}
          className="pointer-events-none absolute -top-2 left-2 right-2 z-10 h-1 rounded-full bg-blue-500 shadow-[0_0_0_3px_rgba(59,130,246,0.18)]"
        />
      ) : null}
      {currentDropIntent === 'inside' ? (
        <div
          aria-hidden="true"
          data-testid={`drop-indicator-inside-${block.id}`}
          className="pointer-events-none absolute inset-1 z-10 rounded-lg border-2 border-dashed border-blue-400"
        />
      ) : null}
      <div className="border-b border-slate-100 px-3 py-2">
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <div className="truncate text-sm font-semibold text-slate-900">
              {getBlockLabel(block)}
            </div>
            <div className="truncate font-mono text-[11px] text-slate-400">
              {block.blockType}
            </div>
          </div>
          {mode === 'layout' && !isDashboardWidget ? (
            <BlockOrderControls
              blockId={block.id}
              previousBlockId={previousSibling?.id}
              nextBlockId={nextSibling?.id}
              onSelect={onSelect}
              onMoveBefore={onMoveBefore}
            />
          ) : null}
        </div>
        {mode === 'layout' && !isDashboardWidget ? (
          <div className="mt-2">
            <SpanQuickControls
              blockId={block.id}
              currentSpan={span}
              onResizeSpan={onResizeSpan}
            />
          </div>
        ) : null}
      </div>
      <BlockContent
        block={block}
        mode={mode}
        selectedBlockId={selectedBlockId}
        draggingBlockId={draggingBlockId}
        draggingPaletteBlockType={draggingPaletteBlockType}
        draggingModelField={draggingModelField}
        activeDropIntent={activeDropIntent}
        onDropIntentChange={onDropIntentChange}
        onDropIntentClear={onDropIntentClear}
        onSelect={onSelect}
        onDragStart={onDragStart}
        onDragEnd={onDragEnd}
        onMoveBefore={onMoveBefore}
        canAddBlockToParent={canAddBlockToParent}
        onAddBlockToParent={onAddBlockToParent}
        canAddBlockBeforeTarget={canAddBlockBeforeTarget}
        onAddBlockBeforeTarget={onAddBlockBeforeTarget}
        canAddModelFieldToParent={canAddModelFieldToParent}
        onAddModelFieldToParent={onAddModelFieldToParent}
        canAddModelFieldBeforeTarget={canAddModelFieldBeforeTarget}
        onAddModelFieldBeforeTarget={onAddModelFieldBeforeTarget}
        onPaletteDragEnd={onPaletteDragEnd}
        onModelFieldDragEnd={onModelFieldDragEnd}
        onMoveWidget={onMoveWidget}
        onResizeWidget={onResizeWidget}
        onResizeSpan={onResizeSpan}
      />
      {mode === 'layout' && block.blockType === 'widget' ? (
        <button
          type="button"
          aria-label={`Resize ${getBlockLabel(block)}`}
          title="Resize widget"
          data-testid={`widget-resize-${block.id}`}
          onPointerDown={(event) => {
            handleWidgetResizePointerDown(event, block, onSelect, onResizeWidget);
          }}
          className="absolute bottom-2 right-2 grid h-7 w-7 place-items-center rounded-md border border-blue-200 bg-white text-blue-700 shadow-sm hover:bg-blue-50"
        >
          <Maximize2 className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      ) : null}
    </section>
  );
}

function BlockContent(props: {
  block: DslBlockV3;
  mode: DesignerMode;
  selectedBlockId: string | null;
  draggingBlockId: string | null;
  draggingPaletteBlockType: string | null;
  draggingModelField: ModelFieldDefinition | null;
  activeDropIntent: DropIntentState;
  onDropIntentChange: (intent: DropIntentState) => void;
  onDropIntentClear: (blockId?: string) => void;
  dashboardSiblings?: DslBlockV3[];
  onSelect: (blockId: string) => void;
  onDragStart: (blockId: string) => void;
  onDragEnd: () => void;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
  canAddBlockToParent: (parentBlockId: string, blockType: string) => boolean;
  onAddBlockToParent: (parentBlockId: string, blockType: string) => void;
  canAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => boolean;
  onAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => void;
  canAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => void;
  canAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => void;
  onPaletteDragEnd: () => void;
  onModelFieldDragEnd: () => void;
  onMoveWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeSpan: (blockId: string, span: number) => void;
}) {
  const { block } = props;
  if (block.blockType === 'form' || block.blockType === 'form-section') {
    return <FormBlockContent {...props} />;
  }
  if (block.blockType === 'list' || block.blockType === 'table' || block.blockType === 'filter-bar') {
    return <ListBlockContent {...props} />;
  }
  if (block.blockType === 'dashboard') {
    return <DashboardBlockContent {...props} />;
  }
  if (block.blocks?.length) {
    return <NestedBlocks {...props} />;
  }
  return <LeafBlock block={block} />;
}

function NestedBlocks(props: Parameters<typeof BlockContent>[0]) {
  const children = props.block.blocks ?? [];
  return (
    <div className="grid grid-cols-12 gap-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function FormBlockContent(props: Parameters<typeof BlockContent>[0]) {
  const children = props.block.blocks ?? [];
  return (
    <div className="grid grid-cols-12 gap-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function ListBlockContent(props: Parameters<typeof BlockContent>[0]) {
  const children = props.block.blocks ?? [];
  return (
    <div className="space-y-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function DashboardBlockContent(props: Parameters<typeof BlockContent>[0]) {
  const widgets = props.block.blocks ?? [];
  return (
    <div className="grid min-h-[220px] grid-cols-12 gap-3 bg-slate-50 p-3 [grid-auto-rows:64px]">
      {widgets.map((widget) => {
        const w = typeof widget.layout?.w === 'number' ? widget.layout.w : 3;
        return (
          <BlockFrame
            key={widget.id}
            {...props}
            dashboardSiblings={widgets}
            siblingBlocks={widgets}
            block={{ ...widget, layout: { ...widget.layout, span: w } }}
          />
        );
      })}
    </div>
  );
}

function LeafBlock({ block }: { block: DslBlockV3 }) {
  return (
    <div className="p-3">
      <div className="rounded-md border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600">
        {getBlockLabel(block)}
      </div>
    </div>
  );
}

function BlockOrderControls({
  blockId,
  previousBlockId,
  nextBlockId,
  onSelect,
  onMoveBefore,
}: {
  blockId: string;
  previousBlockId?: string;
  nextBlockId?: string;
  onSelect: (blockId: string) => void;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
}) {
  const stopControlEvent = (event: React.MouseEvent<HTMLDivElement>) => {
    event.stopPropagation();
  };

  return (
    <div
      aria-label="Block order"
      className="flex shrink-0 items-center gap-1 rounded-md bg-slate-100 p-0.5"
      onClick={stopControlEvent}
      onMouseDown={stopControlEvent}
      onPointerDown={stopControlEvent}
    >
      <button
        type="button"
        aria-label="Move block up"
        title="Move up"
        data-testid={`block-move-up-${blockId}`}
        disabled={!previousBlockId}
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
          onSelect(blockId);
          if (previousBlockId) onMoveBefore(blockId, previousBlockId);
        }}
        className="grid h-6 w-6 place-items-center rounded text-slate-500 hover:bg-white hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
      >
        <ArrowUp className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
      <button
        type="button"
        aria-label="Move block down"
        title="Move down"
        data-testid={`block-move-down-${blockId}`}
        disabled={!nextBlockId}
        onClick={(event) => {
          event.preventDefault();
          event.stopPropagation();
          onSelect(blockId);
          if (nextBlockId) onMoveBefore(nextBlockId, blockId);
        }}
        className="grid h-6 w-6 place-items-center rounded text-slate-500 hover:bg-white hover:text-blue-700 disabled:cursor-not-allowed disabled:opacity-40 disabled:hover:bg-transparent disabled:hover:text-slate-500"
      >
        <ArrowDown className="h-3.5 w-3.5" aria-hidden="true" />
      </button>
    </div>
  );
}

function SpanQuickControls({
  blockId,
  currentSpan,
  onResizeSpan,
}: {
  blockId: string;
  currentSpan: number;
  onResizeSpan: (blockId: string, span: number) => void;
}) {
  return (
    <div
      aria-label="Span presets"
      data-testid={`field-span-controls-${blockId}`}
      className="grid w-full grid-cols-5 gap-1 rounded-md bg-slate-100 p-0.5"
      onClick={(event) => event.stopPropagation()}
      onMouseDown={(event) => event.stopPropagation()}
      onPointerDown={(event) => event.stopPropagation()}
    >
      {SPAN_PRESETS.map((preset) => (
        <button
          key={preset}
          type="button"
          aria-label={`Set span ${preset}`}
          data-testid={`field-span-${blockId}-${preset}`}
          onClick={(event) => {
            event.preventDefault();
            event.stopPropagation();
            onResizeSpan(blockId, preset);
          }}
          className={`h-6 min-w-6 rounded px-1.5 text-[11px] font-semibold ${
            currentSpan === preset
              ? 'bg-blue-600 text-white'
              : 'text-slate-500 hover:bg-white hover:text-blue-700'
          }`}
        >
          {preset}
        </button>
      ))}
    </div>
  );
}

function getBlockLabel(block: DslBlockV3): string {
  if (typeof block.title === 'string') return block.title;
  if (block.title?.en) return block.title.en;
  if (block.title?.['zh-CN']) return block.title['zh-CN'];
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function resolveDropIntent({
  blockId,
  mode,
  movingBlockId,
  paletteBlockType,
  modelField,
  canAddBlockBeforeTarget,
  canAddBlockToParent,
  canAddModelFieldBeforeTarget,
  canAddModelFieldToParent,
}: {
  blockId: string;
  mode: DesignerMode;
  movingBlockId: string | null;
  paletteBlockType: string | null;
  modelField: ModelFieldDefinition | null;
  canAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => boolean;
  canAddBlockToParent: (parentBlockId: string, blockType: string) => boolean;
  canAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => boolean;
  canAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => boolean;
}): DropIntent | null {
  if (modelField) {
    if (canAddModelFieldBeforeTarget(blockId, modelField)) return 'before';
    if (canAddModelFieldToParent(blockId, modelField)) return 'inside';
    return null;
  }

  if (paletteBlockType) {
    if (canAddBlockBeforeTarget(blockId, paletteBlockType)) return 'before';
    if (canAddBlockToParent(blockId, paletteBlockType)) return 'inside';
    return null;
  }

  if (mode === 'layout' && movingBlockId && movingBlockId !== blockId) return 'before';
  return null;
}

function handlePalettePointerRelease({
  event,
  block,
  draggingPaletteBlockType,
  canAddBlockToParent,
  onAddBlockToParent,
  canAddBlockBeforeTarget,
  onAddBlockBeforeTarget,
  onDropIntentClear,
  onPaletteDragEnd,
}: {
  event: React.PointerEvent<HTMLElement> | React.MouseEvent<HTMLElement>;
  block: DslBlockV3;
  draggingPaletteBlockType: string | null;
  canAddBlockToParent: (parentBlockId: string, blockType: string) => boolean;
  onAddBlockToParent: (parentBlockId: string, blockType: string) => void;
  canAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => boolean;
  onAddBlockBeforeTarget: (targetBlockId: string, blockType: string) => void;
  onDropIntentClear: (blockId?: string) => void;
  onPaletteDragEnd: () => void;
}) {
  if (!draggingPaletteBlockType) return;
  const canInsertBeforeTarget = canAddBlockBeforeTarget(block.id, draggingPaletteBlockType);
  const canAppendToParent = canAddBlockToParent(block.id, draggingPaletteBlockType);
  if (!canInsertBeforeTarget && !canAppendToParent) return;

  event.preventDefault();
  event.stopPropagation();
  if (canInsertBeforeTarget) {
    onAddBlockBeforeTarget(block.id, draggingPaletteBlockType);
  } else {
    onAddBlockToParent(block.id, draggingPaletteBlockType);
  }
  onDropIntentClear(block.id);
  onPaletteDragEnd();
}

function handleModelFieldPointerRelease({
  event,
  block,
  draggingModelField,
  canAddModelFieldToParent,
  onAddModelFieldToParent,
  canAddModelFieldBeforeTarget,
  onAddModelFieldBeforeTarget,
  onDropIntentClear,
  onModelFieldDragEnd,
}: {
  event: React.PointerEvent<HTMLElement> | React.MouseEvent<HTMLElement>;
  block: DslBlockV3;
  draggingModelField: ModelFieldDefinition | null;
  canAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldToParent: (parentBlockId: string, field: ModelFieldDefinition) => void;
  canAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => boolean;
  onAddModelFieldBeforeTarget: (targetBlockId: string, field: ModelFieldDefinition) => void;
  onDropIntentClear: (blockId?: string) => void;
  onModelFieldDragEnd: () => void;
}) {
  if (!draggingModelField) return;

  if (canAddModelFieldBeforeTarget(block.id, draggingModelField)) {
    event.preventDefault();
    event.stopPropagation();
    onAddModelFieldBeforeTarget(block.id, draggingModelField);
    onDropIntentClear(block.id);
    onModelFieldDragEnd();
    return;
  }

  if (canAddModelFieldToParent(block.id, draggingModelField)) {
    event.preventDefault();
    event.stopPropagation();
    onAddModelFieldToParent(block.id, draggingModelField);
    onDropIntentClear(block.id);
    onModelFieldDragEnd();
  }
}

function handleRootPalettePointerRelease({
  event,
  draggingPaletteBlockType,
  canAddBlockToRoot,
  onAddBlockToRoot,
  onPaletteDragEnd,
}: {
  event: React.PointerEvent<HTMLElement> | React.MouseEvent<HTMLElement>;
  draggingPaletteBlockType: string | null;
  canAddBlockToRoot: (blockType: string) => boolean;
  onAddBlockToRoot: (blockType: string) => void;
  onPaletteDragEnd: () => void;
}) {
  if (!draggingPaletteBlockType) return;
  if (!canAddBlockToRoot(draggingPaletteBlockType)) return;

  event.preventDefault();
  event.stopPropagation();
  onAddBlockToRoot(draggingPaletteBlockType);
  onPaletteDragEnd();
}

function handleBlockReorderPointerDown(
  event: React.PointerEvent<HTMLElement> | React.MouseEvent<HTMLElement>,
  block: DslBlockV3,
  onSelect: (blockId: string) => void,
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void,
  onDragStart: (blockId: string) => void,
  onDragEnd: () => void,
) {
  if (typeof event.button === 'number' && event.button !== 0) return;
  if (isInteractivePointerTarget(event.target)) return;

  event.preventDefault();
  event.stopPropagation();
  onSelect(block.id);

  const startClientX = toFiniteCoord(event.clientX);
  const startClientY = toFiniteCoord(event.clientY);
  let hasMoved = false;

  const suppressNextClick = (clickEvent: MouseEvent) => {
    clickEvent.preventDefault();
    clickEvent.stopPropagation();
    onSelect(block.id);
    window.removeEventListener('click', suppressNextClick, true);
  };

  const handleMove = (moveEvent: MouseEvent | PointerEvent) => {
    const distanceX = Math.abs(toFiniteCoord(moveEvent.clientX) - startClientX);
    const distanceY = Math.abs(toFiniteCoord(moveEvent.clientY) - startClientY);
    if (hasMoved || Math.max(distanceX, distanceY) < 6) return;
    hasMoved = true;
    onDragStart(block.id);
  };

  const handleEnd = (endEvent: MouseEvent | PointerEvent) => {
    window.removeEventListener('pointermove', handleMove);
    window.removeEventListener('mousemove', handleMove);
    window.removeEventListener('pointerup', handleEnd);
    window.removeEventListener('mouseup', handleEnd);

    if (hasMoved) {
      const targetBlockId = resolveCanvasBlockIdAtPoint(endEvent.clientX, endEvent.clientY, block.id);
      if (targetBlockId && targetBlockId !== block.id) {
        onMoveBefore(block.id, targetBlockId);
      }
      window.addEventListener('click', suppressNextClick, true);
      window.setTimeout(() => window.removeEventListener('click', suppressNextClick, true), 0);
    }

    onDragEnd();
    onSelect(block.id);
  };

  window.addEventListener('pointermove', handleMove);
  window.addEventListener('mousemove', handleMove);
  window.addEventListener('pointerup', handleEnd, { once: true });
  window.addEventListener('mouseup', handleEnd, { once: true });
}

function handleWidgetMovePointerDown(
  event: React.PointerEvent<HTMLElement>,
  block: DslBlockV3,
  siblingWidgets: DslBlockV3[],
  onSelect: (blockId: string) => void,
  onMoveWidget: (blockId: string, layoutPatch: Record<string, number>) => void,
) {
  event.preventDefault();
  event.stopPropagation();
  onSelect(block.id);

  const startClientX = toFiniteCoord(event.clientX);
  const startClientY = toFiniteCoord(event.clientY);
  const startX = getGridNumber(block.layout?.x, 0);
  const startY = getGridNumber(block.layout?.y, 0);
  const width = getGridNumber(block.layout?.w, 3);
  const height = getGridNumber(block.layout?.h, 2);
  let hasMoved = false;

  const suppressNextClick = (clickEvent: MouseEvent) => {
    clickEvent.preventDefault();
    clickEvent.stopPropagation();
    onSelect(block.id);
    window.removeEventListener('click', suppressNextClick, true);
  };

  const handleMove = (moveEvent: MouseEvent | PointerEvent) => {
    const nextX = clampGrid(
      startX + Math.round((toFiniteCoord(moveEvent.clientX) - startClientX) / 80),
      0,
      Math.max(0, 12 - width),
    );
    const nextY = clampGrid(
      startY + Math.round((toFiniteCoord(moveEvent.clientY) - startClientY) / 80),
      0,
      99,
    );
    if (nextX === startX && nextY === startY) return;

    hasMoved = hasMoved || nextX !== startX || nextY !== startY;
    if (
      !isWidgetPlacementAvailable(block.id, { x: nextX, y: nextY, w: width, h: height }, siblingWidgets)
    ) {
      return;
    }
    onMoveWidget(block.id, { x: nextX, y: nextY });
  };

  const handleEnd = () => {
    window.removeEventListener('pointermove', handleMove);
    window.removeEventListener('mousemove', handleMove);
    window.removeEventListener('pointerup', handleEnd);
    window.removeEventListener('mouseup', handleEnd);
    if (hasMoved) {
      window.addEventListener('click', suppressNextClick, true);
      window.setTimeout(() => window.removeEventListener('click', suppressNextClick, true), 0);
    }
    onSelect(block.id);
  };

  window.addEventListener('pointermove', handleMove);
  window.addEventListener('mousemove', handleMove);
  window.addEventListener('pointerup', handleEnd, { once: true });
  window.addEventListener('mouseup', handleEnd, { once: true });
}

function handleWidgetResizePointerDown(
  event: React.PointerEvent<HTMLButtonElement>,
  block: DslBlockV3,
  onSelect: (blockId: string) => void,
  onResizeWidget: (blockId: string, layoutPatch: Record<string, number>) => void,
) {
  event.preventDefault();
  event.stopPropagation();
  onSelect(block.id);

  const startX = toFiniteCoord(event.clientX);
  const startY = toFiniteCoord(event.clientY);
  const startW =
    typeof block.layout?.w === 'number'
      ? block.layout.w
      : typeof block.layout?.span === 'number'
        ? block.layout.span
        : 3;
  const startH = typeof block.layout?.h === 'number' ? block.layout.h : 2;

  const handleMove = (moveEvent: MouseEvent | PointerEvent) => {
    const nextW = clampGrid(
      startW + Math.round((toFiniteCoord(moveEvent.clientX) - startX) / 80),
      1,
      12,
    );
    const nextH = clampGrid(
      startH + Math.round((toFiniteCoord(moveEvent.clientY) - startY) / 64),
      1,
      12,
    );
    onResizeWidget(block.id, { w: nextW, h: nextH, span: nextW });
  };

  const handleEnd = () => {
    window.removeEventListener('pointermove', handleMove);
    window.removeEventListener('mousemove', handleMove);
    window.removeEventListener('pointerup', handleEnd);
    window.removeEventListener('mouseup', handleEnd);
  };

  window.addEventListener('pointermove', handleMove);
  window.addEventListener('mousemove', handleMove);
  window.addEventListener('pointerup', handleEnd, { once: true });
  window.addEventListener('mouseup', handleEnd, { once: true });
}

function clampGrid(value: number, min: number, max: number) {
  return Math.max(min, Math.min(max, value));
}

function toFiniteCoord(value: number) {
  return Number.isFinite(value) ? value : 0;
}

function resolveCanvasBlockIdAtPoint(
  clientX: number,
  clientY: number,
  movingBlockId?: string,
): string | null {
  const containingBlocks = Array.from(
    document.querySelectorAll<HTMLElement>('[data-testid^="canvas-block-"]'),
  )
    .flatMap((element) => {
      const testId = element.getAttribute('data-testid');
      const blockId = testId?.replace(/^canvas-block-/, '');
      if (!blockId || blockId === movingBlockId) return [];

      const rect = element.getBoundingClientRect();
      const contains =
        clientX >= rect.left &&
        clientX <= rect.right &&
        clientY >= rect.top &&
        clientY <= rect.bottom;
      return contains ? [{ blockId, area: rect.width * rect.height }] : [];
    })
    .sort((left, right) => left.area - right.area);

  if (containingBlocks[0]) return containingBlocks[0].blockId;

  const element = document.elementFromPoint(clientX, clientY);
  const blockElement = element?.closest<HTMLElement>('[data-testid^="canvas-block-"]');
  const testId = blockElement?.getAttribute('data-testid');
  const blockId = testId?.replace(/^canvas-block-/, '') ?? null;
  return blockId && blockId !== movingBlockId ? blockId : null;
}

function isInteractivePointerTarget(target: EventTarget | null) {
  return (
    target instanceof Element &&
    Boolean(
      target.closest(
        'a,button,input,select,textarea,[role="button"],[contenteditable="true"],[data-no-block-drag="true"]',
      ),
    )
  );
}

function getGridNumber(value: unknown, fallback: number) {
  return typeof value === 'number' && Number.isFinite(value) ? value : fallback;
}

function isWidgetPlacementAvailable(
  movingBlockId: string,
  movingRect: GridRect,
  siblingWidgets: DslBlockV3[],
) {
  return siblingWidgets.every((widget) => {
    if (widget.id === movingBlockId) return true;
    return !rectsOverlap(movingRect, getWidgetGridRect(widget));
  });
}

function getWidgetGridRect(widget: DslBlockV3): GridRect {
  return {
    x: getGridNumber(widget.layout?.x, 0),
    y: getGridNumber(widget.layout?.y, 0),
    w: getGridNumber(widget.layout?.w, 3),
    h: getGridNumber(widget.layout?.h, 2),
  };
}

function rectsOverlap(a: GridRect, b: GridRect) {
  return a.x < b.x + b.w && a.x + a.w > b.x && a.y < b.y + b.h && a.y + a.h > b.y;
}

interface GridRect {
  x: number;
  y: number;
  w: number;
  h: number;
}
