import React from 'react';
import { useDraggable, useDroppable } from '@dnd-kit/core';
import { ArrowDown, ArrowUp, GripVertical, Lock, Maximize2, Trash2 } from 'lucide-react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import type { DesignerMode, DslBlockV3, ModelFieldDefinition, PageSchemaV3 } from '../types';
import {
  DesignerModelFieldsContext,
  EditCanvasFieldPreview,
} from '../runtime/platformFieldPreview';
import {
  ROOT_DROPPABLE_ID,
  blockDroppableId,
  canvasDraggableId,
  type DragData,
  type DropIntent,
} from '../dnd/dndShared';
import {
  blocksWithinMarquee,
  rectFromPoints,
  type BlockRect,
  type PixelRect,
} from '../utils/marqueeHitTest';

const SPAN_PRESETS = [3, 4, 6, 8, 12] as const;

// A box-select drag only begins once the pointer travels past this threshold,
// so a plain click on the empty canvas (which clears selection) is never
// mistaken for a marquee.
const MARQUEE_START_THRESHOLD_PX = 6;

/** Stable empty reference so the default context value never triggers re-renders. */
const EMPTY_CANVAS_MODEL_FIELDS: ModelFieldDefinition[] = [];

export type ActiveDropIntent = { blockId: string; intent: DropIntent } | null;

/**
 * Canvas selection callback. The optional `modifiers.additive` flag is set when
 * the user shift / cmd / ctrl + clicks a block, signalling a multi-select toggle
 * (vs. a plain click which is single-select). Internal canvas call sites (drag,
 * move controls, widget pointer handlers) omit it and behave as a single select.
 */
export type CanvasSelectModifiers = { additive?: boolean };
export type CanvasSelectHandler = (blockId: string, modifiers?: CanvasSelectModifiers) => void;

interface CanvasHostProps {
  document: PageSchemaV3;
  mode: DesignerMode;
  selectedBlockId: string | null;
  /** Ids in the additive multi-selection (rendered with a selection ring). */
  multiSelectedIds?: Set<string>;
  activeDrag: DragData | null;
  activeDropIntent: ActiveDropIntent;
  rootAccepts: boolean;
  onSelect: CanvasSelectHandler;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
  onPatchBlock: (blockId: string, updater: (block: DslBlockV3) => DslBlockV3) => void;
  canDeleteBlock: (blockId: string) => boolean;
  onDeleteBlock: (blockId: string) => void;
  /**
   * Box-select callback: invoked on marquee pointer-up with the ids of every
   * canvas block the selection rectangle covered. An empty array means the
   * marquee hit nothing (the caller may clear or leave the selection intact).
   */
  onMarqueeSelect?: (blockIds: string[]) => void;
  /**
   * Model field metadata for the page's primary model. When provided, `field` blocks on
   * the canvas render the real platform control (true WYSIWYG) instead of the field-code
   * placeholder. Absent → legacy placeholder, so nothing changes for callers that omit it.
   */
  modelFields?: ModelFieldDefinition[];
}

export function CanvasHost({
  document,
  mode,
  selectedBlockId,
  multiSelectedIds,
  activeDrag,
  activeDropIntent,
  rootAccepts,
  onSelect,
  onMoveBefore,
  onPatchBlock,
  canDeleteBlock,
  onDeleteBlock,
  onMarqueeSelect,
  modelFields,
}: CanvasHostProps) {
  const { locale } = useI18n();
  const hostRef = React.useRef<HTMLElement | null>(null);
  // The canvas host renders only in edit / layout modes (preview swaps in the
  // runtime renderer), so box-select is available in both designer modes.
  const marquee = useMarqueeSelect(hostRef, onMarqueeSelect);
  const kindLabel = resolveDesignerText(
    DESIGNER_I18N.unified.canvasKind[document.kind] ?? DESIGNER_I18N.unified.canvasKind.composite,
    locale,
  );
  // Toolbar already renders the page title; the canvas band shows the kind (the
  // actual fix for the wrong "Composite canvas" copy) plus the technical id.
  const canvasSubject =
    document.id || resolveDesignerText(DESIGNER_I18N.unified.untitledPage, locale);

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

  const canvas = (
    <main
      ref={hostRef}
      className="relative min-h-[420px] flex-1 overflow-auto bg-slate-100 p-3 lg:p-6 xl:overflow-auto"
      data-testid="unified-canvas-host"
      onPointerDown={marquee.onHostPointerDown}
    >
      {marquee.rect ? (
        <div
          aria-hidden="true"
          data-testid="marquee-rect"
          className="pointer-events-none absolute z-20 rounded-sm border border-blue-400 bg-blue-200/30"
          style={{
            left: marquee.rect.left,
            top: marquee.rect.top,
            width: marquee.rect.width,
            height: marquee.rect.height,
          }}
        />
      ) : null}
      <div className="mx-auto min-w-[720px] max-w-7xl space-y-4 xl:min-w-0">
        <RootDropZone rootAccepts={rootAccepts}>
          <div className="text-xs font-semibold uppercase tracking-wide text-slate-400">
            {kindLabel}
          </div>
          <div className="mt-1 text-lg font-semibold text-slate-900">{canvasSubject}</div>
          <div className="mt-2 text-xs text-slate-500">
            {resolveDesignerText(DESIGNER_I18N.unified.mode, locale)}:{' '}
            <span className="font-medium text-blue-700">{mode}</span>
          </div>
        </RootDropZone>
        <div className="grid grid-cols-12 gap-4">
          {document.blocks.map((block) => (
            <BlockFrame
              key={block.id}
              block={block}
              siblingBlocks={document.blocks}
              mode={mode}
              selectedBlockId={selectedBlockId}
              multiSelectedIds={multiSelectedIds}
              activeDrag={activeDrag}
              activeDropIntent={activeDropIntent}
              locale={locale}
              onSelect={onSelect}
              onMoveBefore={onMoveBefore}
              onMoveWidget={patchWidgetLayout}
              onResizeWidget={patchWidgetLayout}
              onResizeSpan={patchBlockSpan}
              canDeleteBlock={canDeleteBlock}
              onDeleteBlock={onDeleteBlock}
            />
          ))}
        </div>
      </div>
    </main>
  );

  return (
    <DesignerModelFieldsContext.Provider value={modelFields ?? EMPTY_CANVAS_MODEL_FIELDS}>
      {canvas}
    </DesignerModelFieldsContext.Provider>
  );
}

/** Marquee rectangle in host-content (scroll-content) coordinates, for rendering. */
interface MarqueeRenderRect {
  left: number;
  top: number;
  width: number;
  height: number;
}

interface MarqueeState {
  rect: MarqueeRenderRect | null;
  onHostPointerDown: (event: React.PointerEvent<HTMLElement>) => void;
}

/**
 * Box-select (geometric marquee) gesture for the canvas.
 *
 * A pointer-down on the EMPTY canvas (not on a block, not on an interactive
 * control) arms a marquee. Once the pointer travels past a small threshold the
 * selection rectangle appears and tracks the pointer; on pointer-up the blocks
 * the rectangle covers are resolved (via the pure {@link blocksWithinMarquee})
 * and reported through `onMarqueeSelect`. A pointer-up below the threshold is a
 * plain empty-canvas click and selects nothing (no marquee shown).
 *
 * The gesture never starts on a block frame (those keep their own click → select
 * + drag-handle behaviour) so single-select, palette drops and dashboard widget
 * moves are all left intact.
 */
function useMarqueeSelect(
  hostRef: React.RefObject<HTMLElement | null>,
  onMarqueeSelect?: (blockIds: string[]) => void,
): MarqueeState {
  const [rect, setRect] = React.useState<MarqueeRenderRect | null>(null);

  const onHostPointerDown = (event: React.PointerEvent<HTMLElement>) => {
    if (event.button !== 0) return; // primary button only
    if (!onMarqueeSelect) return;
    const host = hostRef.current;
    if (!host) return;
    // Only start on truly empty canvas: not on a block frame, not on an
    // interactive control / drag handle. Anything inside a canvas block keeps
    // its existing selection / drag behaviour.
    if (isOnBlockOrInteractive(event.target)) return;

    const startClientX = event.clientX;
    const startClientY = event.clientY;
    let started = false;

    const toContentPoint = (clientX: number, clientY: number) => {
      const hostRect = host.getBoundingClientRect();
      return {
        x: clientX - hostRect.left + host.scrollLeft,
        y: clientY - hostRect.top + host.scrollTop,
      };
    };

    const handleMove = (moveEvent: PointerEvent) => {
      const dx = Math.abs(moveEvent.clientX - startClientX);
      const dy = Math.abs(moveEvent.clientY - startClientY);
      if (!started && dx < MARQUEE_START_THRESHOLD_PX && dy < MARQUEE_START_THRESHOLD_PX) {
        return;
      }
      started = true;
      const start = toContentPoint(startClientX, startClientY);
      const current = toContentPoint(moveEvent.clientX, moveEvent.clientY);
      setRect({
        left: Math.min(start.x, current.x),
        top: Math.min(start.y, current.y),
        width: Math.abs(current.x - start.x),
        height: Math.abs(current.y - start.y),
      });
    };

    const handleEnd = (endEvent: PointerEvent) => {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleEnd);
      window.removeEventListener('pointercancel', handleEnd);
      setRect(null);
      if (!started) return; // a plain click, not a drag — select nothing

      // Resolve hits in viewport-pixel space (matches getBoundingClientRect),
      // so the marquee and block rects share one coordinate frame.
      const marqueeRect: PixelRect = rectFromPoints(
        { x: startClientX, y: startClientY },
        { x: endEvent.clientX, y: endEvent.clientY },
      );
      const blockRects = collectCanvasBlockRects(host);
      const hitIds = blocksWithinMarquee(marqueeRect, blockRects);
      // Prefer the innermost hits: a covered ancestor container (e.g. the page
      // root that wraps every section) is dropped when the box also covers one
      // of its descendants, so "drag across two sibling sections" selects just
      // those two, not the container around them.
      onMarqueeSelect(dropAncestorBlocks(host, hitIds));
    };

    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleEnd);
    window.addEventListener('pointercancel', handleEnd);
  };

  return { rect, onHostPointerDown };
}

/**
 * True when the pointer-down target is inside a canvas block frame or on an
 * interactive control (so the marquee must not start there). The marquee only
 * begins on the empty canvas band / scroll background.
 */
function isOnBlockOrInteractive(target: EventTarget | null): boolean {
  if (!(target instanceof Element)) return false;
  if (target.closest('[data-testid^="canvas-block-"]')) return true;
  return isInteractivePointerTarget(target);
}

/**
 * Snapshot every rendered canvas block's bounding rectangle (viewport pixels)
 * keyed by its block id, for marquee hit-testing. Reads the live DOM at
 * pointer-up time so rects reflect the current scroll/layout.
 */
function collectCanvasBlockRects(host: HTMLElement): BlockRect[] {
  const PREFIX = 'canvas-block-';
  const nodes = host.querySelectorAll<HTMLElement>(`[data-testid^="${PREFIX}"]`);
  const rects: BlockRect[] = [];
  nodes.forEach((node) => {
    const testid = node.getAttribute('data-testid');
    if (!testid) return;
    const blockId = testid.slice(PREFIX.length);
    if (!blockId) return;
    const r = node.getBoundingClientRect();
    rects.push({
      blockId,
      rect: { left: r.left, top: r.top, right: r.right, bottom: r.bottom },
    });
  });
  return rects;
}

/**
 * Drop any hit block whose DOM node is an ancestor of another hit block's node,
 * keeping only the innermost selections. This is a structural (not geometric)
 * filter: a parent container's rect always covers its children, so without this
 * a marquee over two sibling sections would also grab the page-root container
 * that wraps them. Computed from the live DOM (`node.contains`).
 */
function dropAncestorBlocks(host: HTMLElement, hitIds: string[]): string[] {
  if (hitIds.length <= 1) return hitIds;
  const nodeById = new Map<string, HTMLElement>();
  for (const id of hitIds) {
    const node = host.querySelector<HTMLElement>(`[data-testid="canvas-block-${id}"]`);
    if (node) nodeById.set(id, node);
  }
  return hitIds.filter((id) => {
    const node = nodeById.get(id);
    if (!node) return false;
    // Keep `id` only if it is NOT an ancestor of any other hit block.
    for (const [otherId, otherNode] of nodeById) {
      if (otherId !== id && node.contains(otherNode)) return false;
    }
    return true;
  });
}

function RootDropZone({
  rootAccepts,
  children,
}: {
  rootAccepts: boolean;
  children: React.ReactNode;
}) {
  const { setNodeRef, isOver } = useDroppable({ id: ROOT_DROPPABLE_ID, data: { kind: 'root' } });
  const highlight = rootAccepts && isOver;
  return (
    <div
      ref={setNodeRef}
      data-testid="canvas-root-drop-zone"
      data-can-drop={rootAccepts ? 'true' : 'false'}
      className={`rounded-md border bg-white p-4 transition ${
        highlight
          ? 'border-blue-400 bg-blue-50/60 ring-2 ring-blue-100'
          : rootAccepts
          ? 'border-blue-300 border-dashed'
          : 'border-slate-200'
      }`}
    >
      {children}
    </div>
  );
}

interface BlockFrameProps {
  block: DslBlockV3;
  siblingBlocks?: DslBlockV3[];
  mode: DesignerMode;
  selectedBlockId: string | null;
  multiSelectedIds?: Set<string>;
  activeDrag: DragData | null;
  activeDropIntent: ActiveDropIntent;
  locale: string;
  dashboardSiblings?: DslBlockV3[];
  onSelect: CanvasSelectHandler;
  onMoveBefore: (movingBlockId: string, targetBlockId: string) => void;
  onMoveWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeWidget: (blockId: string, layoutPatch: Record<string, number>) => void;
  onResizeSpan: (blockId: string, span: number) => void;
  canDeleteBlock: (blockId: string) => boolean;
  onDeleteBlock: (blockId: string) => void;
}

function BlockFrame(props: BlockFrameProps) {
  const {
    block,
    siblingBlocks,
    mode,
    selectedBlockId,
    multiSelectedIds,
    activeDrag,
    activeDropIntent,
    locale,
    dashboardSiblings,
    onSelect,
    onMoveBefore,
    onMoveWidget,
    onResizeWidget,
    onResizeSpan,
    canDeleteBlock,
    onDeleteBlock,
  } = props;
  const selected = selectedBlockId === block.id;
  const multiSelected = multiSelectedIds?.has(block.id) ?? false;
  const isDashboardWidget = block.blockType === 'widget' && Boolean(dashboardSiblings);
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

  // Drop target for palette blocks / model fields / reordered blocks.
  const { setNodeRef: setDropRef } = useDroppable({
    id: blockDroppableId(block.id),
    data: { kind: 'block', blockId: block.id },
  });
  // Reorder source — widgets use their own grid pointer handlers instead.
  const {
    setNodeRef: setDragRef,
    attributes,
    listeners,
    isDragging,
  } = useDraggable({
    id: canvasDraggableId(block.id),
    data: { kind: 'canvas-block', blockId: block.id },
    disabled: isDashboardWidget,
  });
  const setRefs = (node: HTMLElement | null) => {
    setDropRef(node);
    setDragRef(node);
  };

  return (
    <section
      ref={setRefs}
      data-testid={`canvas-block-${block.id}`}
      data-selected={selected ? 'true' : 'false'}
      data-multi-selected={multiSelected ? 'true' : 'false'}
      data-layout-x={isDashboardWidget ? widgetX : undefined}
      data-layout-y={isDashboardWidget ? widgetY : undefined}
      data-layout-span={columnSpan}
      data-drop-intent={currentDropIntent}
      onClick={(event) => {
        event.stopPropagation();
        // shift / cmd / ctrl + click toggles the block in the multi-selection;
        // a plain click is a single select (handled by the workbench).
        const additive = event.shiftKey || event.metaKey || event.ctrlKey;
        onSelect(block.id, { additive });
      }}
      onPointerDown={(event) => {
        if (!isDashboardWidget) return;
        if (mode !== 'layout') return;
        handleWidgetMovePointerDown(
          event,
          block,
          dashboardSiblings ?? [block],
          onSelect,
          onMoveWidget,
        );
      }}
      className={`group relative rounded-lg border bg-white transition ${
        currentDropIntent === 'inside'
          ? 'border-blue-500 bg-blue-50/50 ring-2 ring-blue-100'
          : selected || multiSelected
          ? 'border-blue-500 ring-2 ring-blue-100'
          : 'border-slate-200 hover:border-blue-300'
      } ${isDragging ? 'opacity-50' : ''}`}
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
          <div className="flex min-w-0 items-start gap-1.5">
            {!isDashboardWidget ? (
              <button
                type="button"
                aria-label={`Drag ${getBlockLabel(block, locale)}`}
                data-testid={`block-drag-handle-${block.id}`}
                data-no-block-drag="true"
                className="mt-0.5 shrink-0 cursor-grab touch-none text-slate-300 hover:text-blue-500 active:cursor-grabbing"
                onClick={(event) => event.stopPropagation()}
                {...attributes}
                {...listeners}
              >
                <GripVertical className="h-3.5 w-3.5" aria-hidden="true" />
              </button>
            ) : null}
            <div className="min-w-0">
              <div className="flex items-center gap-1.5">
                <span className="truncate text-sm font-semibold text-slate-900">
                  {getBlockLabel(block, locale)}
                </span>
                {(block.props as { aiLocked?: unknown } | undefined)?.aiLocked === true ? (
                  <span
                    data-testid={`ai-lock-badge-${block.id}`}
                    className="inline-flex shrink-0 items-center gap-0.5 rounded bg-amber-50 px-1 py-0.5 text-[10px] font-medium text-amber-700"
                    title={resolveDesignerText(DESIGNER_I18N.unified.aiLockBadge, locale)}
                  >
                    <Lock className="h-2.5 w-2.5" aria-hidden="true" />
                    {resolveDesignerText(DESIGNER_I18N.unified.aiLockBadge, locale)}
                  </span>
                ) : null}
              </div>
              <div className="truncate font-mono text-[11px] text-slate-400">{block.blockType}</div>
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
            <SpanQuickControls blockId={block.id} currentSpan={span} onResizeSpan={onResizeSpan} />
          </div>
        ) : null}
      </div>
      {canDeleteBlock(block.id) ? (
        <button
          type="button"
          aria-label={`${resolveDesignerText(DESIGNER_I18N.unified.deleteBlock, locale)} ${getBlockLabel(block, locale)}`}
          title={resolveDesignerText(DESIGNER_I18N.unified.deleteBlock, locale)}
          data-testid={`block-delete-${block.id}`}
          data-no-block-drag="true"
          onClick={(event) => {
            event.stopPropagation();
            onDeleteBlock(block.id);
          }}
          // Absolutely positioned so it never affects canvas layout (a header-flow
          // button would shift nested geometry and break position-based drops).
          className="absolute right-1.5 top-1.5 z-10 grid h-6 w-6 place-items-center rounded-md bg-white/80 text-slate-300 opacity-0 transition group-hover:opacity-100 hover:bg-red-50 hover:text-red-600 data-[selected=true]:opacity-100"
          data-selected={selected ? 'true' : 'false'}
        >
          <Trash2 className="h-3.5 w-3.5" aria-hidden="true" />
        </button>
      ) : null}
      <BlockContent
        block={block}
        mode={mode}
        selectedBlockId={selectedBlockId}
        multiSelectedIds={multiSelectedIds}
        activeDrag={activeDrag}
        activeDropIntent={activeDropIntent}
        locale={locale}
        onSelect={onSelect}
        onMoveBefore={onMoveBefore}
        onMoveWidget={onMoveWidget}
        onResizeWidget={onResizeWidget}
        onResizeSpan={onResizeSpan}
        canDeleteBlock={canDeleteBlock}
        onDeleteBlock={onDeleteBlock}
      />
      {mode === 'layout' && isDashboardWidget ? (
        <button
          type="button"
          aria-label={`Resize ${getBlockLabel(block, locale)}`}
          title="Resize widget"
          data-testid={`widget-resize-${block.id}`}
          data-no-block-drag="true"
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

type BlockContentProps = Omit<BlockFrameProps, 'siblingBlocks' | 'dashboardSiblings'>;

function BlockContent(props: BlockContentProps) {
  const { block } = props;
  if (block.blockType === 'form' || block.blockType === 'form-section') {
    return <FormBlockContent {...props} />;
  }
  if (block.blockType === 'columns') {
    return <ColumnsBlockContent {...props} />;
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
  if (block.blockType === 'field') {
    return <CanvasFieldLeaf block={block} locale={props.locale} />;
  }
  return <LeafBlock block={block} locale={props.locale} />;
}

function NestedBlocks(props: BlockContentProps) {
  const children = props.block.blocks ?? [];
  return (
    <div className="grid grid-cols-12 gap-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function FormBlockContent(props: BlockContentProps) {
  const children = props.block.blocks ?? [];
  return (
    <div className="grid grid-cols-12 gap-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function ColumnsBlockContent(props: BlockContentProps) {
  const children = props.block.blocks ?? [];
  const columnCount = clampColumnCount(props.block.layout?.columns);
  const gap = clampGap(props.block.layout?.gap);

  return (
    <div
      className="grid p-3"
      style={{
        gap,
        gridTemplateColumns: `repeat(${columnCount}, minmax(0, 1fr))`,
      }}
    >
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function ListBlockContent(props: BlockContentProps) {
  const children = props.block.blocks ?? [];
  return (
    <div className="space-y-3 p-3">
      {children.map((child) => (
        <BlockFrame key={child.id} {...props} block={child} siblingBlocks={children} />
      ))}
    </div>
  );
}

function DashboardBlockContent(props: BlockContentProps) {
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

function LeafBlock({ block, locale }: { block: DslBlockV3; locale: string }) {
  return (
    <div className="p-3">
      <div className="rounded-md border border-dashed border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-600">
        {getBlockLabel(block, locale)}
      </div>
    </div>
  );
}

/**
 * Field card body on the edit canvas. When the page's model metadata resolves the bound
 * field, render the real platform control (true WYSIWYG, non-interactive) via
 * {@link EditCanvasFieldPreview}; otherwise fall back to the field-code placeholder.
 */
function CanvasFieldLeaf({ block, locale }: { block: DslBlockV3; locale: string }) {
  const modelFields = React.useContext(DesignerModelFieldsContext);
  const modelField = block.field
    ? modelFields.find((candidate) => candidate.code === block.field)
    : undefined;
  if (modelField) {
    return <EditCanvasFieldPreview block={block} modelField={modelField} locale={locale} />;
  }
  return <LeafBlock block={block} locale={locale} />;
}

function clampColumnCount(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 2;
  return Math.max(1, Math.min(4, Math.round(value)));
}

function clampGap(value: unknown): number {
  if (typeof value !== 'number' || !Number.isFinite(value)) return 16;
  return Math.max(0, Math.min(48, Math.round(value)));
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
      data-no-block-drag="true"
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
      data-no-block-drag="true"
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

export function getBlockLabel(block: DslBlockV3, locale = 'en-US'): string {
  const title = block.title;
  if (typeof title === 'string') return title;
  if (title) {
    const resolved = title[locale] || title['en-US'] || title.en || title['zh-CN'];
    if (resolved) return resolved;
  }
  if (typeof block.props?.label === 'string') return block.props.label;
  if (typeof block.props?.title === 'string') return block.props.title;
  return block.field || block.widgetType || block.actionType || block.blockType;
}

function handleWidgetMovePointerDown(
  event: React.PointerEvent<HTMLElement>,
  block: DslBlockV3,
  siblingWidgets: DslBlockV3[],
  onSelect: (blockId: string) => void,
  onMoveWidget: (blockId: string, layoutPatch: Record<string, number>) => void,
) {
  if (isInteractivePointerTarget(event.target)) return;
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
