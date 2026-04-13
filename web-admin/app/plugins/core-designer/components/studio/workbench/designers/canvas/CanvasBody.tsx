/**
 * CanvasBody — Center panel of the canvas editor (react-grid-layout version)
 *
 * Scrollable container with centered content area (max 980px).
 * Renders InlineTitle, block grid (or EmptyCanvas).
 *
 * Blocks are laid out with react-grid-layout on a 12-column grid.
 * A .block-drag-handle CSS class on the drag handle enables drag.
 * Blocks are resizable and vertically compacted.
 *
 * @since 4.0.0
 */

import React, { useRef, useState, useEffect, useCallback, useMemo } from 'react';
import { useDroppable } from '@dnd-kit/core';
import {
  useDroppable as useFieldSlotDroppable,
} from '@dnd-kit/core';
import { GridLayout, verticalCompactor } from 'react-grid-layout';
import 'react-grid-layout/css/styles.css';
import { rglToDslLayout, autoFlow } from '~/plugins/core-designer/components/studio/core/layout';
import type { BlockLayoutConfig } from '~/meta/schemas/types';
import { InlineTitle } from './InlineTitle';
import { EmptyCanvas } from './EmptyCanvas';
import type { CanvasBlock } from '~/plugins/core-designer/components/studio/domain/canvas/types';
import { GridOverlay } from './GridOverlay';
import { WidgetRegistry } from '~/plugins/core-designer/components/studio/registry';
import type { CanvasDragKind } from '~/plugins/core-designer/components/studio/hooks/canvas/useCanvasDnd';
import { blockAcceptsFieldLikeDrop } from '~/plugins/core-designer/components/studio/hooks/canvas/useCanvasDnd';
import { getCanvasBlockLabel } from './canvasBlockLabel';
import './canvas-grid.css';
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
} from '@dnd-kit/sortable';
import { CSS } from '@dnd-kit/utilities';

export interface CanvasBodyProps {
  title: string;
  description: string;
  blocks: CanvasBlock[];
  selectedBlockId: string | null;
  onTitleChange: (value: string) => void;
  onDescriptionChange: (value: string) => void;
  onAddBlock: (blockType: string) => void;
  onSelectBlock: (id: string | null) => void;
  onRemoveBlock: (id: string) => void;
  onLayoutChange: (updates: Array<{ id: string; layout: BlockLayoutConfig }>) => void;
  selectedFieldIndex?: number | null;
  onSelectField?: (index: number | null) => void;
  onRemoveField?: (blockId: string, fieldIndex: number) => void;
  onReorderFields?: (blockId: string, fromIndex: number, toIndex: number) => void;
  /** Column indices to highlight during drag (passed from CanvasEditor) */
  highlightCols?: Set<number>;
  /** Called by RGL when external item is dropped on the grid */
  onRglDrop?: (layout: any[], item: any | undefined, e: Event) => void;
  /** Called by RGL while external item is dragged over the grid */
  onRglDropDragOver?: (e: DragEvent) => { w?: number; h?: number } | false | void;
  /** Device preview width in pixels (null = default 980px) */
  deviceWidth?: number | null;
  activeDragKind?: CanvasDragKind;
  activeOverId?: string | null;
}

function isFieldLikeDrag(activeDragKind?: CanvasDragKind): boolean {
  return activeDragKind === 'field' || activeDragKind === 'widget';
}

function getFormSectionDropVerb(activeDragKind?: CanvasDragKind): string {
  return activeDragKind === 'field' ? 'field' : 'widget';
}

// ---------------------------------------------------------------------------
// GridBlockCard — individual block card rendered inside a grid cell
// ---------------------------------------------------------------------------

interface GridBlockCardProps {
  block: CanvasBlock;
  isSelected: boolean;
  onSelect: () => void;
  onRemove: () => void;
  selectedFieldIndex?: number | null;
  onSelectField?: (index: number | null) => void;
  onRemoveField?: (fieldIndex: number) => void;
  onReorderFields?: (fromIndex: number, toIndex: number) => void;
  activeDragKind?: CanvasDragKind;
  isDropTarget?: boolean;
  activeOverId?: string | null;
}

const GridBlockCard: React.FC<GridBlockCardProps> = ({ block, isSelected, onSelect, onRemove, selectedFieldIndex, onSelectField, onRemoveField, onReorderFields, activeDragKind, isDropTarget, activeOverId }) => {
  const colSpan = block.layout?.colSpan ?? 12;
  const headerLabel = getCanvasBlockLabel(block);

  return (
    <div
      className={`h-full rounded-lg border bg-white transition-all overflow-hidden ${
        isSelected
          ? 'border-purple-500 shadow-md shadow-purple-100'
          : 'border-gray-200 hover:border-gray-300'
      }`}
      onClick={(e) => {
        e.stopPropagation();
        onSelect();
      }}
      data-testid={`canvas-block-${block.id}`}
    >
      {/* Header bar */}
      <div className="flex items-center justify-between border-b border-gray-100 px-3 py-2">
        {/* Drag handle */}
        <button
          className="block-drag-handle mr-2 flex h-5 w-5 cursor-grab items-center justify-center rounded text-gray-300 hover:text-gray-500 active:cursor-grabbing"
          data-testid={`canvas-block-drag-handle-${block.id}`}
          onClick={(e) => e.stopPropagation()}
          title="Drag to reorder"
        >
          ⠿
        </button>
        <span className="flex-1 text-xs font-semibold uppercase tracking-wider text-gray-500 truncate">
          {headerLabel}
        </span>
        {/* ColSpan badge */}
        <span className="mr-2 rounded bg-gray-100 px-1.5 py-0.5 text-[10px] font-medium text-gray-400">
          {colSpan}col
        </span>
        <button
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="flex h-5 w-5 items-center justify-center rounded text-gray-400 hover:bg-red-50 hover:text-red-500"
          data-testid={`canvas-block-remove-${block.id}`}
        >
          &times;
        </button>
      </div>
      {/* Body — render block preview or fallback to type label */}
      <div className="min-h-[40px] overflow-auto" data-testid={`canvas-block-content-${block.id}`}>
        <CanvasBlockPreview block={block} selectedFieldIndex={isSelected ? selectedFieldIndex : null} onSelectField={onSelectField} onRemoveField={isSelected ? onRemoveField : undefined} onReorderFields={isSelected ? onReorderFields : undefined} activeDragKind={activeDragKind} isDropTarget={isDropTarget} activeOverId={activeOverId} isSelected={isSelected} />
      </div>
    </div>
  );
};

// ---------------------------------------------------------------------------
// FormSectionPreview — sortable field chips inside form-section
// ---------------------------------------------------------------------------

function fieldDisplayName(f: unknown, i: number): { name: string; component?: string } {
  if (typeof f === 'string') return { name: f };
  const obj = f as Record<string, any>;
  const label = obj.label;
  const component = obj.component;
  const field = obj.field;
  const name = label || (component ? WidgetRegistry.getName(component) : null) || field || `field_${i}`;
  return { name, component };
}

const SortableFieldChip: React.FC<{
  blockId: string;
  field: unknown;
  index: number;
  isSelected: boolean;
  onSelect: () => void;
  onRemove?: () => void;
}> = ({ blockId, field, index, isSelected, onSelect, onRemove }) => {
  const { name, component } = fieldDisplayName(field, index);
  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({ id: `field-item:${blockId}:${index}` });

  const style: React.CSSProperties = {
    transform: CSS.Transform.toString(transform),
    transition,
    opacity: isDragging ? 0.5 : 1,
  };

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...attributes}
      {...listeners}
      onClick={(e) => { e.stopPropagation(); onSelect(); }}
      className={`flex items-center cursor-grab active:cursor-grabbing rounded border px-2 py-1 text-[11px] transition-colors ${
        isSelected
          ? 'border-purple-300 bg-purple-50 text-purple-700'
          : 'border-gray-200 bg-gray-50 text-gray-600 hover:border-blue-300'
      }`}
      title={component ? `${name} (${component}) — drag to reorder` : `${name} — drag to reorder`}
    >
      {component && <span className="mr-1.5 text-blue-500 flex-shrink-0">{WidgetRegistry.getIcon(component)}</span>}
      <span className="truncate flex-1">{name}</span>
      {onRemove && (
        <button
          onClick={(e) => { e.stopPropagation(); onRemove(); }}
          className="ml-1 flex-shrink-0 text-gray-300 hover:text-red-500 text-xs"
          title="Remove field"
        >&times;</button>
      )}
    </div>
  );
};

const FieldInsertSlot: React.FC<{
  blockId: string;
  index: number;
  activeDragKind?: CanvasDragKind;
  isExpanded?: boolean;
}> = ({ blockId, index, activeDragKind, isExpanded = false }) => {
  const { setNodeRef, isOver } = useFieldSlotDroppable({ id: `field-slot:${blockId}:${index}` });
  const shouldRender = isFieldLikeDrag(activeDragKind);

  if (!shouldRender) return null;
  if (!isExpanded && !isOver) return null;

  return (
    <div
      ref={setNodeRef}
      className={`rounded transition-all ${
        isOver
          ? 'min-h-[30px] border border-dashed border-purple-400 bg-purple-50 px-2 py-1 text-purple-700 shadow-sm'
          : 'flex min-h-[14px] items-center justify-center px-1 py-0.5 text-purple-300 opacity-90'
      }`}
      data-testid={`form-section-slot-${blockId}-${index}`}
    >
      {isOver ? (
        <div className="flex items-center justify-center text-[10px] font-medium">
          Release to insert here
        </div>
      ) : (
        <div className="flex w-full items-center gap-1">
          <div className="h-px flex-1 bg-purple-200/80" />
          <div className="h-1.5 w-1.5 rounded-full bg-purple-200/90" />
          <div className="h-px flex-1 bg-purple-200/80" />
        </div>
      )}
    </div>
  );
};

const FormSectionPreview: React.FC<{
  blockId: string;
  config: Record<string, any>;
  selectedFieldIndex?: number | null;
  onSelectField?: (index: number | null) => void;
  onRemoveField?: (fieldIndex: number) => void;
  onReorderFields?: (fromIndex: number, toIndex: number) => void;
  activeDragKind?: CanvasDragKind;
  isDropTarget?: boolean;
  activeOverId?: string | null;
  isSelected?: boolean;
}> = ({ blockId, config, selectedFieldIndex, onSelectField, onRemoveField, onReorderFields, activeDragKind, isDropTarget, activeOverId, isSelected = false }) => {
  const fields = ((config as any)?.fields as unknown[]) ?? [];
  const isFieldLikeDragActive = isFieldLikeDrag(activeDragKind);
  const fieldIds = fields.map((_, i) => `field-item:${blockId}:${i}`);
  const hasActiveFieldContext = Boolean(
    activeOverId === blockId
      || activeOverId?.startsWith(`field-slot:${blockId}:`)
      || activeOverId?.startsWith(`field-item:${blockId}:`),
  );
  const shouldShowSlots = isFieldLikeDragActive && (hasActiveFieldContext || isSelected);
  const useInsertionLayout = shouldShowSlots;

  if (fields.length === 0) {
    return (
      <div
        className={`m-3 rounded-lg border border-dashed px-4 py-5 text-center transition-colors ${
          isDropTarget && isFieldLikeDragActive
            ? 'border-purple-400 bg-purple-50 text-purple-700'
            : 'border-gray-200 bg-gray-50 text-gray-400'
        }`}
        data-testid="form-section-empty-drop-hint"
      >
        <div className="text-xs font-medium">
          {isDropTarget && activeDragKind === 'widget'
            ? 'Release to add widget here'
          : isDropTarget && activeDragKind === 'field'
              ? 'Release to add field here'
              : 'Drag widgets or fields here'}
        </div>
        <div className="mt-1 text-[11px]">
          {isDropTarget && isFieldLikeDragActive
            ? 'The item will be added to this form section.'
            : 'Widgets and fields are added inside form sections.'}
        </div>
      </div>
    );
  }

  return (
    <SortableContext items={fieldIds} strategy={verticalListSortingStrategy}>
      <div className={`grid gap-1 p-2 ${useInsertionLayout ? 'grid-cols-1' : 'grid-cols-2'}`}>
        {fields.slice(0, 8).map((f, i) => (
          <React.Fragment key={fieldIds[i]}>
            <FieldInsertSlot
              blockId={blockId}
              index={i}
              activeDragKind={activeDragKind}
              isExpanded={shouldShowSlots}
            />
            <SortableFieldChip
              blockId={blockId}
              field={f}
              index={i}
              isSelected={selectedFieldIndex === i}
              onSelect={() => onSelectField?.(i)}
              onRemove={onRemoveField ? () => onRemoveField(i) : undefined}
            />
          </React.Fragment>
        ))}
        <FieldInsertSlot blockId={blockId} index={Math.min(fields.length, 8)} activeDragKind={activeDragKind} isExpanded={shouldShowSlots} />
        {fields.length > 8 && (
          <span className="col-span-2 text-[10px] text-gray-400 text-center">+{fields.length - 8} more</span>
        )}
      </div>
    </SortableContext>
  );
};

// ---------------------------------------------------------------------------
// CanvasBlockPreview — lightweight design-time block preview
// Reads structure from block.config, does not need SchemaRuntime
// ---------------------------------------------------------------------------

const CanvasBlockPreview: React.FC<{ block: CanvasBlock; selectedFieldIndex?: number | null; onSelectField?: (index: number | null) => void; onRemoveField?: (fieldIndex: number) => void; onReorderFields?: (fromIndex: number, toIndex: number) => void; activeDragKind?: CanvasDragKind; isDropTarget?: boolean; activeOverId?: string | null; isSelected?: boolean }> = ({ block, selectedFieldIndex, onSelectField, onRemoveField, onReorderFields, activeDragKind, isDropTarget, activeOverId, isSelected }) => {
  const { blockType, config } = block;

  switch (blockType) {
    case 'table': {
      const modelCode = (config as any)?.dataSource?.modelCode || (config as any)?.modelCode || '';
      return (
        <div className="p-3">
          <div className="mb-2 flex items-center gap-2 text-xs text-gray-500">
            <span className="rounded bg-blue-50 px-1.5 py-0.5 text-blue-600">Table</span>
            {modelCode && <span className="font-mono text-gray-400">{modelCode}</span>}
          </div>
          <div className="rounded border border-gray-200 bg-gray-50 p-2 text-center text-[11px] text-gray-400">
            Data table preview
          </div>
        </div>
      );
    }

    case 'form-section':
      return (
        <FormSectionPreview
          blockId={block.id}
          config={config}
          selectedFieldIndex={selectedFieldIndex}
          onSelectField={onSelectField}
          onRemoveField={onRemoveField}
          onReorderFields={onReorderFields}
          activeDragKind={activeDragKind}
          isDropTarget={isDropTarget}
          activeOverId={activeOverId}
          isSelected={isSelected}
        />
      );

    case 'chart': {
      const chartType = (config as any)?.chartType || 'bar';
      return (
        <div className="p-3">
          <div className="mb-2 flex items-center gap-2 text-xs text-gray-500">
            <span className="rounded bg-purple-50 px-1.5 py-0.5 text-purple-600">Chart</span>
            <span className="text-gray-400">{chartType}</span>
          </div>
          <div className="flex h-12 items-end justify-center gap-1">
            {[40, 65, 45, 80, 55, 70, 50].map((h, i) => (
              <div key={i} className="w-4 rounded-t bg-purple-200" style={{ height: `${h}%` }} />
            ))}
          </div>
        </div>
      );
    }

    case 'toolbar':
    case 'form-buttons': {
      const buttons = ((config as any)?.buttons as any[]) ?? [];
      return (
        <div className="flex items-center gap-2 p-3">
          <span className="rounded bg-orange-50 px-1.5 py-0.5 text-[10px] text-orange-600">Toolbar</span>
          {buttons.length > 0 ? (
            buttons.slice(0, 5).map((btn: any, i: number) => (
              <span
                key={i}
                onClick={(e) => { e.stopPropagation(); onSelectField?.(i); }}
                className={`cursor-pointer rounded px-2 py-0.5 text-[10px] transition-colors ${
                  selectedFieldIndex === i
                    ? 'bg-purple-100 text-purple-700 ring-1 ring-purple-300'
                    : 'bg-gray-100 text-gray-500 hover:bg-blue-50'
                }`}
              >
                {btn.code || btn.label || `btn_${i}`}
              </span>
            ))
          ) : (
            <span className="text-[11px] text-gray-400">No buttons</span>
          )}
        </div>
      );
    }

    case 'filters':
      return (
        <div className="flex items-center gap-2 p-3">
          <span className="rounded bg-cyan-50 px-1.5 py-0.5 text-[10px] text-cyan-600">Filters</span>
          <div className="h-6 flex-1 rounded border border-dashed border-gray-200" />
        </div>
      );

    case 'tabs': {
      const tabs = ((config as any)?.tabs as any[]) ?? [];
      return (
        <div className="p-3">
          <div className="flex gap-1 border-b border-gray-200 pb-1">
            {(tabs.length > 0 ? tabs : [{ key: 'tab1' }, { key: 'tab2' }]).slice(0, 6).map((tab: any, i: number) => (
              <span
                key={i}
                className={`rounded-t px-2 py-0.5 text-[10px] ${i === 0 ? 'bg-blue-50 text-blue-600' : 'text-gray-400'}`}
              >
                {tab.label || tab.key || `Tab ${i + 1}`}
              </span>
            ))}
          </div>
        </div>
      );
    }

    case 'stat-card':
      return (
        <div className="flex items-center gap-3 p-3">
          <span className="rounded bg-amber-50 px-1.5 py-0.5 text-[10px] text-amber-600">Stats</span>
          {[1, 2, 3].map((n) => (
            <div key={n} className="flex-1 rounded bg-gray-50 p-2 text-center">
              <div className="text-lg font-bold text-gray-300">--</div>
              <div className="text-[9px] text-gray-400">Metric {n}</div>
            </div>
          ))}
        </div>
      );

    case 'monthly-grid': {
      const parentModel = (config as any)?.monthlyGrid?.parentModel || '';
      return (
        <div className="p-3">
          <div className="mb-2 flex items-center gap-2 text-xs text-gray-500">
            <span className="rounded bg-teal-50 px-1.5 py-0.5 text-teal-600">Monthly</span>
            {parentModel && <span className="font-mono text-gray-400">{parentModel}</span>}
          </div>
          <div className="grid grid-cols-13 gap-px rounded border border-gray-200 bg-gray-100 text-[9px]">
            <div className="bg-gray-50 p-1 font-medium text-gray-500">Row</div>
            {Array.from({ length: 12 }, (_, i) => (
              <div key={i} className="bg-white p-1 text-center text-gray-400">{i + 1}</div>
            ))}
          </div>
        </div>
      );
    }

    default:
      return (
        <div className="px-4 py-6 text-center text-sm text-gray-400">
          {blockType}
        </div>
      );
  }
};

// ---------------------------------------------------------------------------
// EmptyCanvasDropZone — wraps EmptyCanvas with a droppable target
// ---------------------------------------------------------------------------

const NewFormSectionHint: React.FC<{ activeDragKind?: CanvasDragKind; compact?: boolean }> = ({ activeDragKind, compact = false }) => {
  const detail =
    activeDragKind === 'widget'
      ? 'The widget will be added inside the new form section.'
      : activeDragKind === 'field'
        ? 'The field will be added inside the new form section.'
        : 'Widgets and fields must be added inside a form section.';

  return (
    <div
      className={`${compact ? 'mb-3 px-4 py-3' : 'mb-4 px-4 py-4'} rounded-xl border-2 border-dashed border-purple-400 bg-white text-center shadow-sm`}
      data-testid="canvas-new-form-section-hint"
    >
      <div className="text-sm font-semibold text-purple-700">Release to create a new form section</div>
      <div className="mt-1 text-xs text-purple-600">{detail}</div>
    </div>
  );
};

const EmptyCanvasDropZone: React.FC<{ onAddBlock: (type: string) => void; activeDragKind?: CanvasDragKind }> = ({ onAddBlock, activeDragKind }) => {
  const { setNodeRef, isOver } = useDroppable({ id: 'canvas-drop-zone' });
  const showFormSectionHint = isOver && (activeDragKind === 'widget' || activeDragKind === 'field');

  return (
    <div
      ref={setNodeRef}
      className={`rounded-lg transition-colors ${isOver ? 'ring-2 ring-purple-400 ring-offset-2' : ''}`}
      data-testid="empty-canvas-drop-zone"
    >
      {showFormSectionHint && <NewFormSectionHint activeDragKind={activeDragKind} />}
      <EmptyCanvas onAddBlock={onAddBlock} />
    </div>
  );
};

// ---------------------------------------------------------------------------
// CanvasDropZone — wraps the non-empty canvas with a droppable target
// so @dnd-kit palette/widget/field drags can land on the canvas area
// ---------------------------------------------------------------------------

const CanvasDropZone: React.FC<{ children: React.ReactNode; activeDragKind?: CanvasDragKind; activeOverId?: string | null }> = ({ children, activeDragKind, activeOverId }) => {
  const { setNodeRef, isOver } = useDroppable({ id: 'canvas-drop-zone' });
  const showFormSectionHint = isOver && activeOverId === 'canvas-drop-zone' && (activeDragKind === 'widget' || activeDragKind === 'field');

  return (
    <div
      ref={setNodeRef}
      className={`transition-colors ${isOver ? 'ring-2 ring-purple-400 ring-offset-2 rounded-lg' : ''}`}
      data-testid="canvas-drop-target"
    >
      {showFormSectionHint && <NewFormSectionHint activeDragKind={activeDragKind} compact />}
      {children}
    </div>
  );
};

// ---------------------------------------------------------------------------
// DroppableBlockWrapper — makes each block a drop target for @dnd-kit
// so fields/widgets can be dropped onto specific blocks (e.g., form-section)
// ---------------------------------------------------------------------------

const DroppableBlockWrapper: React.FC<{
  block: CanvasBlock;
  blockId: string;
  isSelected: boolean;
  children: React.ReactNode;
  activeDragKind?: CanvasDragKind;
}> = ({ block, blockId, isSelected, children, activeDragKind }) => {
  const { setNodeRef, isOver } = useDroppable({ id: blockId });
  const hasFieldLikeDrag = isFieldLikeDrag(activeDragKind);
  const canAccept = hasFieldLikeDrag && blockAcceptsFieldLikeDrop(block.blockType);
  const showValidHint = isOver && canAccept;
  const showInvalidHint = isOver && hasFieldLikeDrag && !canAccept;

  return (
    <div
      ref={setNodeRef}
      className={`relative ${isSelected ? 'selected' : ''} ${
        showValidHint
          ? 'ring-2 ring-purple-400 ring-offset-1 rounded-lg'
          : showInvalidHint
            ? 'ring-2 ring-rose-400 ring-offset-1 rounded-lg'
            : isOver
              ? 'ring-2 ring-blue-400 ring-offset-1 rounded-lg'
              : ''
      }`}
      style={{ height: '100%' }}
      data-testid={`canvas-block-drop-wrapper-${blockId}`}
    >
      {showValidHint && (
        <div
          className="pointer-events-none absolute right-3 bottom-3 left-3 z-10 rounded-md border border-purple-200 bg-white/95 px-3 py-1.5 text-center text-[11px] font-medium text-purple-700 shadow-sm backdrop-blur-sm"
          data-testid={`canvas-block-drop-valid-${blockId}`}
        >
          {`Release to add this ${getFormSectionDropVerb(activeDragKind)} to the form section`}
        </div>
      )}
      {showInvalidHint && (
        <div
          className="pointer-events-none absolute right-3 bottom-3 left-3 z-10 rounded-md border border-rose-200 bg-white/95 px-3 py-1.5 text-center text-[11px] font-medium text-rose-700 shadow-sm backdrop-blur-sm"
          data-testid={`canvas-block-drop-invalid-${blockId}`}
        >
          Widgets and fields can only be dropped into form sections
        </div>
      )}
      {children}
    </div>
  );
};

// ---------------------------------------------------------------------------
// CanvasBody
// ---------------------------------------------------------------------------

export const CanvasBody: React.FC<CanvasBodyProps> = ({
  title,
  description,
  blocks,
  selectedBlockId,
  onTitleChange,
  onDescriptionChange,
  onAddBlock,
  onSelectBlock,
  onRemoveBlock,
  onLayoutChange,
  selectedFieldIndex,
  onSelectField,
  onRemoveField,
  onReorderFields,
  highlightCols,
  onRglDrop,
  onRglDropDragOver,
  deviceWidth,
  activeDragKind,
  activeOverId,
}) => {
  const containerRef = useRef<HTMLDivElement>(null);
  const [containerWidth, setContainerWidth] = useState(980);

  // Measure container width for react-grid-layout
  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const observer = new ResizeObserver((entries) => {
      for (const entry of entries) {
        setContainerWidth(entry.contentRect.width);
      }
    });
    observer.observe(el);
    return () => observer.disconnect();
  }, []);

  // Build react-grid-layout layout from blocks using auto-flow to compute y positions.
  // Without auto-flow, blocks with sequential order values (0,1,2) would stack vertically
  // even when they could fit side-by-side (e.g., three stat-cards at col 0,4,8 with colSpan 4).
  const rglLayout = useMemo(() => {
    const layoutItems = blocks.map((block, index) => ({
      id: block.id,
      col: block.layout?.col ?? 0,
      colSpan: block.layout?.colSpan ?? 12,
      rowSpan: block.layout?.rowSpan ?? 1,
      order: block.layout?.order ?? index,
    }));

    const resolved = autoFlow(layoutItems, 12);

    return resolved.map((item) => ({
      i: item.id,
      x: item.col,
      y: item.y,
      w: item.colSpan,
      h: item.rowSpan,
      minW: 1,
      minH: 1,
    }));
  }, [blocks]);

  const handleLayoutChange = useCallback(
    (layout: ReadonlyArray<{ i: string; x: number; y: number; w: number; h: number }>) => {
      const dslItems = rglToDslLayout(layout as Array<{ i: string; x: number; y: number; w: number; h: number }>);
      const updates = dslItems.map((item) => ({
        id: item.id,
        layout: {
          col: item.col,
          colSpan: item.colSpan,
          rowSpan: item.rowSpan,
          order: item.order,
        } as BlockLayoutConfig,
      }));
      onLayoutChange(updates);
    },
    [onLayoutChange],
  );

  // react-grid-layout v2 config objects
  const gridConfig = useMemo(() => ({
    cols: 12,
    rowHeight: 80,
    margin: [16, 16] as const,
    containerPadding: [0, 0] as const,
    maxRows: Infinity,
  }), []);

  const dragConfig = useMemo(() => ({
    enabled: true,
    bounded: false,
    handle: '.block-drag-handle',
    threshold: 3,
  }), []);

  const resizeConfig = useMemo(() => ({
    enabled: true,
    handles: ['e', 'w', 's'] as const,
  }), []);

  const dropConfig = useMemo(() => ({
    enabled: true,
    defaultItem: { w: 6, h: 1 },
  }), []);

  return (
    <div
      className="h-full overflow-y-auto"
      style={{ backgroundColor: '#f7f8fa' }}
      onClick={() => onSelectBlock(null)}
      data-testid="canvas-body"
    >
      <div
        ref={containerRef}
        className="relative mx-auto px-8 py-8 transition-all duration-300"
        style={{ maxWidth: deviceWidth ? `${Math.min(deviceWidth, 980)}px` : '980px' }}
      >
          <GridOverlay highlightCols={highlightCols} />
        <InlineTitle
          title={title}
          description={description}
          onTitleChange={onTitleChange}
          onDescriptionChange={onDescriptionChange}
        />

        {blocks.length === 0 ? (
          <EmptyCanvasDropZone onAddBlock={onAddBlock} activeDragKind={activeDragKind} />
        ) : (
          <CanvasDropZone activeDragKind={activeDragKind} activeOverId={activeOverId}>
            <div className="canvas-grid">
              <GridLayout
                layout={rglLayout}
                width={containerWidth}
                gridConfig={gridConfig}
                dragConfig={dragConfig}
                resizeConfig={resizeConfig}
                dropConfig={dropConfig}
                compactor={verticalCompactor}
                onLayoutChange={handleLayoutChange as any}
                onDrop={onRglDrop as any}
                onDropDragOver={onRglDropDragOver as any}
              >
                {blocks.map((block) => (
                  <div key={block.id} className={selectedBlockId === block.id ? 'selected' : ''}>
                    <DroppableBlockWrapper
                      block={block}
                      blockId={block.id}
                      isSelected={selectedBlockId === block.id}
                      activeDragKind={activeDragKind}
                    >
                      <GridBlockCard
                        block={block}
                        isSelected={selectedBlockId === block.id}
                        onSelect={() => onSelectBlock(block.id)}
                        onRemove={() => onRemoveBlock(block.id)}
                        selectedFieldIndex={selectedBlockId === block.id ? selectedFieldIndex : null}
                        onSelectField={onSelectField}
                        onRemoveField={onRemoveField ? (fieldIndex) => onRemoveField(block.id, fieldIndex) : undefined}
                        onReorderFields={onReorderFields ? (from, to) => onReorderFields(block.id, from, to) : undefined}
                        activeDragKind={activeDragKind}
                        isDropTarget={activeOverId === block.id}
                        activeOverId={activeOverId}
                      />
                    </DroppableBlockWrapper>
                  </div>
                ))}
              </GridLayout>
            </div>
          </CanvasDropZone>
        )}
      </div>
    </div>
  );
};

export default CanvasBody;
