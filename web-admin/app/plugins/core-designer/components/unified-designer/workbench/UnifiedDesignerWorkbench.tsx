import React, { useMemo, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import {
  DndContext,
  DragOverlay,
  KeyboardSensor,
  PointerSensor,
  closestCenter,
  pointerWithin,
  useSensor,
  useSensors,
  type CollisionDetection,
  type DragEndEvent,
  type DragOverEvent,
  type DragStartEvent,
} from '@dnd-kit/core';
import type {
  DslBlockV3,
  ModelFieldDefinition,
  ModelFieldsByModel,
  PageSchemaV3,
  WorkbenchMode,
} from '../types';
import {
  findBlockById,
  moveBlockBefore,
  moveBlockToParent,
  removeBlockById,
  updateBlockById,
} from '../utils/recursiveBlockWalker';
import { setByPath } from '../utils/dotPath';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';
import { useDesignerDocument, serializeDocument } from '../document/useDesignerDocument';
import { useDesignerSelection } from '../selection/useDesignerSelection';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import {
  DEVICE_PREVIEW_PRESETS,
  DEFAULT_DEVICE_PREVIEW_ID,
  getDeviceFrameStyle,
  getDevicePreviewPreset,
} from '../preview/devicePreviewPresets';
import { getPageTemplate, getPageTemplates } from '../templates/pageTemplateRegistry';
import {
  canSwitchToKind,
  getKindPolicy,
  isBlockTypeAllowedForKind,
} from '../registry/kindPolicy';
import {
  createBlockTemplate,
  createModelFieldBlock,
  type ModelFieldTargetBlockType,
} from '../registry/createBlockTemplate';
import { collectBlockIds } from '../utils/blockIds';
import {
  buildDesignerCollisionCandidates,
  readDragData,
  readDropData,
  resolveBlockDropIntent,
  resolveCanvasBlockAncestorDropAction,
  resolveDragEndAction,
  type DragData,
} from '../dnd/dndShared';
import {
  canMoveExistingBlockBeforeTarget,
  canMoveExistingBlockToParent,
} from '../dnd/moveBlockGuards';
import {
  WorkbenchToolbar,
  type DesignerPublishStatus,
  type DesignerSaveStatus,
} from './WorkbenchToolbar';
import { VersionHistoryPanel } from './VersionHistoryPanel';
import { ResourcePanel } from './ResourcePanel';
import { CanvasHost, type ActiveDropIntent } from '../canvas/CanvasHost';
import { InspectorHost } from './InspectorHost';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import { defaultRuntimeExecutionServices } from '../runtime/runtimeExecution';
import { AiDesignDialog } from '../ai/AiDesignDialog';
import { buildDesignCopilotPrompt, applyDesignBlocks, type ParsedDesign } from '../ai/designCopilot';

// Pointer-based collision for real users, with a closestCenter fallback when the
// pointer isn't inside any droppable.
const designerCollisionDetection: CollisionDetection = (args) => {
  return buildDesignerCollisionCandidates(
    pointerWithin(args),
    closestCenter(args),
    args.droppableRects,
  );
};

export interface UnifiedDesignerWorkbenchProps {
  initialDocument: PageSchemaV3;
  modelFieldsByModel?: ModelFieldsByModel;
  returnHref?: string;
  onSave?: (document: PageSchemaV3) => Promise<void> | void;
  /**
   * The persisted page id (pid) when the document is page-bound. Required to
   * enable the publish / unpublish action points (a local/new document has none).
   */
  pageId?: string;
  /** Initial publish state of the page-bound document (defaults to draft). */
  initialPublished?: boolean;
  /**
   * Publish the saved page. Resolves true on success. Errors are caught by the
   * toolbar and surfaced as inline feedback (mirrors onSave).
   */
  onPublish?: (pid: string) => Promise<boolean> | boolean;
  /** Unpublish the saved page, returning it to draft. */
  onUnpublish?: (pid: string) => Promise<boolean> | boolean;
  /**
   * Reload the page document from the backend (e.g. after a version rollback,
   * which restores the target snapshot's blocks onto the live page). Resolves
   * the freshly-loaded document so the workbench can reset its canvas + undo
   * history to the restored state. When omitted, the version-history rollback
   * action point is not wired.
   */
  onReloadDocument?: (pid: string) => Promise<PageSchemaV3 | null>;
  /**
   * Enable the in-designer AI copilot (tools-off /generate-page). Pass `true` for
   * defaults, or an object with `domainGuidance` to flavor the system prompt for a
   * specific surface (e.g. a QR scan-landing page).
   */
  aiCopilot?: boolean | { domainGuidance?: string };
}

export function UnifiedDesignerWorkbench({
  initialDocument,
  modelFieldsByModel = {},
  returnHref,
  onSave,
  pageId,
  initialPublished = false,
  onPublish,
  onUnpublish,
  onReloadDocument,
  aiCopilot,
}: UnifiedDesignerWorkbenchProps) {
  const { locale } = useI18n();
  const initialSnapshot = serializeDocument(initialDocument);
  const [savedSnapshot, setSavedSnapshot] = useState(initialSnapshot);
  const savedSnapshotRef = useRef(initialSnapshot);
  const [saveStatus, setSaveStatus] = useState<DesignerSaveStatus>('saved');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [validationErrorCount, setValidationErrorCount] = useState(0);
  const [publishStatus, setPublishStatus] = useState<DesignerPublishStatus>(
    initialPublished ? 'published' : 'draft',
  );
  const [publishError, setPublishError] = useState<string | null>(null);
  const [mode, setMode] = useState<WorkbenchMode>('edit');
  const [previewDeviceId, setPreviewDeviceId] = useState<string>(DEFAULT_DEVICE_PREVIEW_ID);
  // Primary + additive multi-selection model, extracted to a shared kernel so
  // the report designer (block-tree family) reuses the same modifier-click /
  // marquee rules. `selectedBlockId` is dual-purpose: the inspector target AND
  // the drop-placement context (palette drops land inside / before it);
  // multi-selection tracks its own ids without perturbing it.
  const {
    selectedBlockId,
    multiSelectedIds,
    setSelectedBlockId,
    setMultiSelectedIds,
    selectFromCanvas: handleCanvasSelect,
    selectFromMarquee: handleMarqueeSelect,
    clearMultiSelection,
  } = useDesignerSelection();
  const [aiDialogOpen, setAiDialogOpen] = useState(false);
  const [versionPanelOpen, setVersionPanelOpen] = useState(false);
  const [activeDrag, setActiveDrag] = useState<DragData | null>(null);
  const [activeDropIntent, setActiveDropIntent] = useState<ActiveDropIntent>(null);

  // Toolbar save indicator follows the live document snapshot; wired into the
  // document kernel's onChange so every edit / undo / redo refreshes it.
  const syncSaveStateForSnapshot = (snapshot: string) => {
    setSaveStatus(snapshot === savedSnapshotRef.current ? 'saved' : 'dirty');
    setSaveError(null);
    setValidationErrorCount(0);
  };

  // Shared block-tree document + history kernel. Selection, drag-and-drop, the
  // block registry, and save/publish state are layered on top by this workbench.
  const documentKernel = useDesignerDocument({
    initialDocument,
    onChange: syncSaveStateForSnapshot,
  });
  const document = documentKernel.document;
  const updateDocument = documentKernel.update;
  const handleUndo = documentKernel.undo;
  const handleRedo = documentKernel.redo;
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor),
  );
  const blockRegistry = useMemo(() => createDefaultBlockRegistryV3(), []);
  const blockDefinitions = useMemo(
    () =>
      blockRegistry
        .getAll()
        .filter((definition) => isBlockTypeAllowedForKind(document.kind, definition.blockType)),
    [blockRegistry, document.kind],
  );

  const currentSnapshot = documentKernel.currentSnapshot;
  const isDirty = currentSnapshot !== savedSnapshot;
  const canUndo = documentKernel.canUndo;
  const canRedo = documentKernel.canRedo;

  const selectedBlockResult = useMemo(
    () => (selectedBlockId ? findBlockById(document.blocks, selectedBlockId) : null),
    [document.blocks, selectedBlockId],
  );
  const selectedBlock = selectedBlockResult?.block ?? null;
  const selectedModelCode =
    findModelCodeForSelection(selectedBlockResult?.path.map((item) => item.block) ?? []) ??
    document.modelCode ??
    null;
  const selectedModelFields = selectedModelCode ? (modelFieldsByModel[selectedModelCode] ?? []) : [];

  // C4 — switch the page kind. Per the owner design decision (2026-06-18), the
  // switch is BLOCKED whenever a descendant block is incompatible with the target
  // kind (no silent data loss); the toolbar disables such targets. On a valid
  // switch we change document.kind and swap the single root container's blockType
  // to the target kind's root (e.g. detail → form), keeping all children. The
  // whole switch is one undoable step.
  const handleSwitchKind = (targetKind: PageSchemaV3['kind']) => {
    if (targetKind === document.kind) return;
    if (!canSwitchToKind(document.blocks, targetKind)) return;
    const rootBlockType = getKindPolicy(targetKind).rootBlockType;
    updateDocument((current) => ({
      ...current,
      kind: targetKind,
      blocks: current.blocks.map((block, index) =>
        index === 0 && rootBlockType ? { ...block, blockType: rootBlockType } : block,
      ),
    }));
    setSelectedBlockId(null);
    setMultiSelectedIds(new Set());
  };

  // D6 — apply a scenario template: replace the page's blocks (and title) with a
  // fresh tree built by the registered template, then clear the selection.
  const applyTemplate = (templateId: string) => {
    const template = getPageTemplate(templateId);
    if (!template) return;
    updateDocument((current) => ({
      ...current,
      title: template.title ?? current.title,
      blocks: template.build(),
    }));
    setSelectedBlockId(null);
  };

  const updateSelectedBlock = (path: string, value: unknown) => {
    if (!selectedBlockId) return;
    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, selectedBlockId, (block) => {
        if (path === 'field' && typeof value === 'string') {
          return syncFieldLikeBlockFromModelField(block, value, selectedModelFields);
        }

        return setByPath(
          block as unknown as Record<string, unknown>,
          path,
          value,
        ) as unknown as typeof block;
      }),
    }));
  };

  const handleMoveBefore = (movingBlockId: string, targetBlockId: string) => {
    updateDocument((current) => ({
      ...current,
      blocks: moveBlockBefore(current.blocks, movingBlockId, targetBlockId),
    }));
  };

  const handleMoveToParent = (movingBlockId: string, parentBlockId: string) => {
    updateDocument((current) => ({
      ...current,
      blocks: moveBlockToParent(current.blocks, movingBlockId, parentBlockId),
    }));
    setSelectedBlockId(movingBlockId);
  };

  // The single top-level kind container (form/list/detail/dashboard root) defines
  // the page; it cannot be deleted, only its descendants can.
  const canDeleteBlock = (blockId: string) => {
    const result = findBlockById(document.blocks, blockId);
    return Boolean(result) && result!.path.length > 1;
  };

  const handleDeleteBlock = (blockId: string) => {
    if (!canDeleteBlock(blockId)) return;
    updateDocument((current) => ({
      ...current,
      blocks: removeBlockById(current.blocks, blockId),
    }));
    setSelectedBlockId((current) => (current === blockId ? null : current));
    setMultiSelectedIds((current) => {
      if (!current.has(blockId)) return current;
      const next = new Set(current);
      next.delete(blockId);
      return next;
    });
  };


  // Batch-delete every deletable block in the multi-selection in a single
  // history step (one updateDocument → one undo). Undeletable blocks (the root
  // kind container) are silently skipped. Selection is cleared afterwards.
  const handleDeleteMultiSelected = () => {
    const deletableIds = [...multiSelectedIds].filter((id) => canDeleteBlock(id));
    if (deletableIds.length === 0) {
      clearMultiSelection();
      return;
    }
    updateDocument((current) => {
      let nextBlocks = current.blocks;
      for (const id of deletableIds) {
        nextBlocks = removeBlockById(nextBlocks, id);
      }
      return { ...current, blocks: nextBlocks };
    });
    const deleted = new Set(deletableIds);
    setSelectedBlockId((current) => (current && deleted.has(current) ? null : current));
    setMultiSelectedIds(new Set());
  };

  const canAddBlock = (blockType: string) => {
    const definition = blockRegistry.get(blockType);
    if (!definition) return false;
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    if (selectedBlock && blockRegistry.canContain(selectedBlock.blockType, blockType)) return true;
    if (selectedBlockId && resolveBlockDropBeforeTarget(selectedBlockId, blockType)) return true;
    return canAddBlockToRoot(blockType);
  };

  const handleAddBlock = (blockType: string) => {
    if (!canAddBlock(blockType)) return;

    const nextBlock = createBlockTemplate(blockType, collectBlockIds(document.blocks));
    if (!nextBlock) return;

    const beforeTarget = selectedBlockId ? resolveBlockDropBeforeTarget(selectedBlockId, blockType) : null;

    if (selectedBlockId && selectedBlock && blockRegistry.canContain(selectedBlock.blockType, blockType)) {
      const preparedBlock = applyParentPlacementDefaults(nextBlock, selectedBlock);
      updateDocument((current) => ({
        ...current,
        blocks: updateBlockById(current.blocks, selectedBlockId, (block) => ({
          ...block,
          blocks: [...(block.blocks ?? []), preparedBlock],
        })),
      }));
    } else if (selectedBlockId && beforeTarget) {
      const preparedBlock = beforeTarget.parentBlock
        ? applyParentPlacementDefaults(nextBlock, beforeTarget.parentBlock)
        : nextBlock;
      updateDocument((current) => ({
        ...current,
        blocks: insertBlockBeforeTarget(
          current.blocks,
          selectedBlockId,
          preparedBlock,
          beforeTarget.parentBlockId,
        ),
      }));
    } else {
      updateDocument((current) => ({
        ...current,
        blocks: [...current.blocks, nextBlock],
      }));
    }

    setSelectedBlockId(nextBlock.id);
  };

  const canAddBlockToParent = (parentBlockId: string, blockType: string) => {
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    const parentBlock = findBlockById(document.blocks, parentBlockId)?.block;
    return parentBlock ? blockRegistry.canContain(parentBlock.blockType, blockType) : false;
  };

  const canAddBlockBeforeTarget = (targetBlockId: string, blockType: string) => {
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    return Boolean(resolveBlockDropBeforeTarget(targetBlockId, blockType));
  };

  const canMoveBlockBeforeTarget = (movingBlockId: string, targetBlockId: string) => {
    return canMoveExistingBlockBeforeTarget({
      blocks: document.blocks,
      kind: document.kind,
      blockRegistry,
      movingBlockId,
      targetBlockId,
    });
  };

  const canMoveBlockToParent = (movingBlockId: string, parentBlockId: string) => {
    return canMoveExistingBlockToParent({
      blocks: document.blocks,
      kind: document.kind,
      blockRegistry,
      movingBlockId,
      parentBlockId,
    });
  };

  const canAddBlockToRoot = (blockType: string) => {
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    const policy = getKindPolicy(document.kind);
    if (policy.rootBlockType) {
      // Single-kind page: only its root container, and only when not already present.
      if (blockType !== policy.rootBlockType) return false;
      return !document.blocks.some((block) => block.blockType === policy.rootBlockType);
    }
    return blockRegistry.get(blockType)?.category === 'page';
  };

  const handleAddBlockToRoot = (blockType: string) => {
    if (!canAddBlockToRoot(blockType)) return;

    const nextBlock = createBlockTemplate(blockType, collectBlockIds(document.blocks));
    if (!nextBlock) return;

    updateDocument((current) => ({
      ...current,
      blocks: [...current.blocks, nextBlock],
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const handleAddBlockToParent = (parentBlockId: string, blockType: string) => {
    if (!canAddBlockToParent(parentBlockId, blockType)) return;

    const nextBlock = createBlockTemplate(blockType, collectBlockIds(document.blocks));
    if (!nextBlock) return;
    const parentBlock = findBlockById(document.blocks, parentBlockId)?.block;
    if (!parentBlock) return;
    const preparedBlock = applyParentPlacementDefaults(nextBlock, parentBlock);

    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, parentBlockId, (block) => ({
        ...block,
        blocks: [...(block.blocks ?? []), preparedBlock],
      })),
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const handleAddBlockBeforeTarget = (targetBlockId: string, blockType: string) => {
    const resolution = resolveBlockDropBeforeTarget(targetBlockId, blockType);
    if (!resolution) return;

    const nextBlock = createBlockTemplate(blockType, collectBlockIds(document.blocks));
    if (!nextBlock) return;
    const preparedBlock = resolution.parentBlock
      ? applyParentPlacementDefaults(nextBlock, resolution.parentBlock)
      : nextBlock;

    updateDocument((current) => ({
      ...current,
      blocks: insertBlockBeforeTarget(
        current.blocks,
        targetBlockId,
        preparedBlock,
        resolution.parentBlockId,
      ),
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const canAddModelFieldToParent = (parentBlockId: string, field: ModelFieldDefinition) => {
    return Boolean(resolveModelFieldDropTarget(parentBlockId, field));
  };

  const canAddModelFieldBeforeTarget = (targetBlockId: string, field: ModelFieldDefinition) => {
    return Boolean(resolveModelFieldDropBeforeTarget(targetBlockId, field));
  };

  const handleAddModelFieldToParent = (parentBlockId: string, field: ModelFieldDefinition) => {
    const targetBlockType = resolveModelFieldDropTarget(parentBlockId, field);
    if (!targetBlockType) return;

    const nextBlock = createModelFieldBlock(
      field,
      targetBlockType,
      collectBlockIds(document.blocks),
    );

    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, parentBlockId, (block) => ({
        ...block,
        blocks: [...(block.blocks ?? []), nextBlock],
      })),
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const handleAddModelFieldBeforeTarget = (targetBlockId: string, field: ModelFieldDefinition) => {
    const resolution = resolveModelFieldDropBeforeTarget(targetBlockId, field);
    if (!resolution) return;

    const nextBlock = createModelFieldBlock(
      field,
      resolution.targetBlockType,
      collectBlockIds(document.blocks),
    );

    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, resolution.parentBlock.id, (block) => ({
        ...block,
        blocks: insertChildBlockBefore(block.blocks ?? [], targetBlockId, nextBlock),
      })),
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const canAddModelField = (field: ModelFieldDefinition) => {
    if (!selectedBlockId) return false;
    if (selectedModelCode !== field.modelCode) return false;
    return (
      canAddModelFieldToParent(selectedBlockId, field) ||
      canAddModelFieldBeforeTarget(selectedBlockId, field)
    );
  };

  const isSelectedModelFieldUsed = (field: ModelFieldDefinition) => {
    if (!selectedBlockId) return false;
    return isModelFieldUsedInParent(selectedBlockId, field);
  };

  const handleAddModelField = (field: ModelFieldDefinition) => {
    if (!selectedBlockId || !canAddModelField(field)) return;
    if (canAddModelFieldToParent(selectedBlockId, field)) {
      handleAddModelFieldToParent(selectedBlockId, field);
      return;
    }
    handleAddModelFieldBeforeTarget(selectedBlockId, field);
  };

  function resolveBlockDropBeforeTarget(
    targetBlockId: string,
    blockType: string,
  ): { parentBlock: DslBlockV3 | null; parentBlockId: string | null } | null {
    const definition = blockRegistry.get(blockType);
    if (!definition) return null;

    const targetResult = findBlockById(document.blocks, targetBlockId);
    if (!targetResult) return null;

    if (targetResult.path.length === 1) {
      return definition.category === 'page' ? { parentBlock: null, parentBlockId: null } : null;
    }

    const parentBlock = targetResult.path[targetResult.path.length - 2].block;
    return blockRegistry.canContain(parentBlock.blockType, blockType)
      ? { parentBlock, parentBlockId: parentBlock.id }
      : null;
  }

  const resolveModelFieldDropTarget = (
    parentBlockId: string,
    field: ModelFieldDefinition,
  ): ModelFieldTargetBlockType | null => {
    if (!field.code) return null;

    const parentResult = findBlockById(document.blocks, parentBlockId);
    if (!parentResult) return null;

    const targetBlockType = getModelFieldTargetBlockType(parentResult.block.blockType);
    if (!targetBlockType) return null;

    const targetModelCode =
      findModelCodeForSelection(parentResult.path.map((item) => item.block)) ??
      document.modelCode ??
      null;
    if (targetModelCode !== field.modelCode) return null;

    if (hasModelFieldChild(parentResult.block, targetBlockType, field.code)) return null;

    return blockRegistry.canContain(parentResult.block.blockType, targetBlockType)
      ? targetBlockType
      : null;
  };

  const resolveModelFieldDropBeforeTarget = (
    targetBlockId: string,
    field: ModelFieldDefinition,
  ): { parentBlock: DslBlockV3; targetBlockType: ModelFieldTargetBlockType } | null => {
    if (!field.code) return null;

    const targetResult = findBlockById(document.blocks, targetBlockId);
    if (!targetResult || targetResult.path.length < 2) return null;

    const parentPath = targetResult.path.slice(0, -1);
    const parentBlock = parentPath[parentPath.length - 1].block;
    const targetBlockType = getModelFieldTargetBlockType(parentBlock.blockType);
    if (!targetBlockType || targetResult.block.blockType !== targetBlockType) return null;

    const targetModelCode =
      findModelCodeForSelection(parentPath.map((item) => item.block)) ?? document.modelCode ?? null;
    if (targetModelCode !== field.modelCode) return null;

    if (hasModelFieldChild(parentBlock, targetBlockType, field.code)) return null;

    return blockRegistry.canContain(parentBlock.blockType, targetBlockType)
      ? { parentBlock, targetBlockType }
      : null;
  };

  const isModelFieldUsedInParent = (parentBlockId: string, field: ModelFieldDefinition) => {
    const parentResult = findBlockById(document.blocks, parentBlockId);
    if (!parentResult) return false;

    const targetBlockType = getModelFieldTargetBlockType(parentResult.block.blockType);
    if (!targetBlockType) return false;

    const targetModelCode =
      findModelCodeForSelection(parentResult.path.map((item) => item.block)) ??
      document.modelCode ??
      null;
    if (targetModelCode !== field.modelCode) return false;

    return hasModelFieldChild(parentResult.block, targetBlockType, field.code);
  };

  const patchBlock = (
    blockId: string,
    updater: (block: PageSchemaV3['blocks'][number]) => PageSchemaV3['blocks'][number],
  ) => {
    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, blockId, updater),
    }));
  };

  const dropCapabilities = {
    canAddBlockBeforeTarget,
    canAddBlockToParent,
    canAddModelFieldBeforeTarget,
    canAddModelFieldToParent,
    canMoveBlockBeforeTarget,
    canMoveBlockToParent,
  };
  const rootAccepts = activeDrag?.kind === 'palette-block' && canAddBlockToRoot(activeDrag.blockType);

  const handleDragStart = (event: DragStartEvent) => {
    const drag = readDragData(event.active.data.current);
    setActiveDrag(drag);
    setActiveDropIntent(null);
    if (drag?.kind === 'canvas-block') setSelectedBlockId(drag.blockId);
  };

  const handleDragOver = (event: DragOverEvent) => {
    const drag = readDragData(event.active.data.current);
    const drop = readDropData(event.over?.data.current);
    if (!drag || !drop || drop.kind !== 'block') {
      setActiveDropIntent(null);
      return;
    }
    const intent = resolveBlockDropIntent(drag, drop.blockId, dropCapabilities);
    setActiveDropIntent(intent ? { blockId: drop.blockId, intent } : null);
  };

  const handleDragEnd = (event: DragEndEvent) => {
    const drag = readDragData(event.active.data.current);
    const drop = readDropData(event.over?.data.current);
    setActiveDrag(null);
    setActiveDropIntent(null);

    let action: ReturnType<typeof resolveDragEndAction> = null;
    if (drag?.kind === 'canvas-block' && drop?.kind === 'block') {
      const dropPath = findBlockById(document.blocks, drop.blockId)?.path.map((item) => item.id) ?? [];
      action = resolveCanvasBlockAncestorDropAction(
        drag.blockId,
        dropPath,
        {
          ...dropCapabilities,
          canAddBlockToRoot,
        },
        {
          getBlockType: (blockId) => findBlockById(document.blocks, blockId)?.block.blockType,
        },
      );
    }
    action ??= resolveDragEndAction(drag, drop, {
      ...dropCapabilities,
      canAddBlockToRoot,
    });
    if (!action) return;

    switch (action.type) {
      case 'add-block-root':
        handleAddBlockToRoot(action.blockType);
        break;
      case 'add-block-before':
        handleAddBlockBeforeTarget(action.targetBlockId, action.blockType);
        break;
      case 'add-block-inside':
        handleAddBlockToParent(action.parentBlockId, action.blockType);
        break;
      case 'add-field-before':
        handleAddModelFieldBeforeTarget(action.targetBlockId, action.field);
        break;
      case 'add-field-inside':
        handleAddModelFieldToParent(action.parentBlockId, action.field);
        break;
      case 'move-before':
        handleMoveBefore(action.movingBlockId, action.targetBlockId);
        break;
      case 'move-inside':
        handleMoveToParent(action.movingBlockId, action.parentBlockId);
        break;
    }
  };

  const handleSave = async () => {
    const validation = validatePageSchemaV3(document);
    setSaveError(null);
    if (!validation.valid) {
      setValidationErrorCount(validation.errors.length);
      setSaveStatus('invalid');
      setSaveError(formatValidationSaveError(validation.errors.length));
      return;
    }

    setValidationErrorCount(0);
    setSaveStatus('saving');
    try {
      await onSave?.(document);
      const snapshot = serializeDocument(document);
      savedSnapshotRef.current = snapshot;
      setSavedSnapshot(snapshot);
      setSaveStatus('saved');
    } catch (error) {
      setSaveStatus('error');
      setSaveError(resolveSaveErrorMessage(error));
    }
  };

  const handlePublish = async () => {
    if (!pageId || !onPublish) return;
    setPublishError(null);
    setPublishStatus('publishing');
    try {
      const ok = await onPublish(pageId);
      setPublishStatus(ok ? 'published' : 'error');
      if (!ok) setPublishError('Failed to publish page.');
    } catch (error) {
      setPublishStatus('error');
      setPublishError(resolvePublishErrorMessage(error));
    }
  };

  const handleUnpublish = async () => {
    if (!pageId || !onUnpublish) return;
    setPublishError(null);
    setPublishStatus('unpublishing');
    try {
      const ok = await onUnpublish(pageId);
      setPublishStatus(ok ? 'draft' : 'error');
      if (!ok) setPublishError('Failed to unpublish page.');
    } catch (error) {
      setPublishStatus('error');
      setPublishError(resolvePublishErrorMessage(error));
    }
  };

  // Reset the canvas + undo history to a freshly-loaded document. Used after a
  // version rollback: the backend has restored the target snapshot's blocks onto
  // the live page, so we replace local state with the reloaded document and mark
  // it clean/saved (it now matches the backend exactly).
  const resetToDocument = (nextDocument: PageSchemaV3) => {
    const snapshot = serializeDocument(nextDocument);
    documentKernel.reset(nextDocument);
    savedSnapshotRef.current = snapshot;
    setSavedSnapshot(snapshot);
    setSaveStatus('saved');
    setSaveError(null);
    setValidationErrorCount(0);
    setSelectedBlockId(null);
  };

  // After a successful rollback: reload the restored page document, reset the
  // canvas to it, and close the version panel. If the reload yields nothing
  // (e.g. the page was concurrently deleted) we leave the canvas as-is and just
  // close the panel — the rollback itself already succeeded on the backend.
  const handleVersionRolledBack = async () => {
    if (pageId && onReloadDocument) {
      const reloaded = await onReloadDocument(pageId);
      if (reloaded) resetToDocument(reloaded);
    }
    setVersionPanelOpen(false);
  };

  // Export — serialize the current document to a downloadable .page.json file.
  // Pure client-side: no backend call, exports exactly what is on the canvas
  // (including unsaved edits) so the artifact is a faithful snapshot.
  const handleExport = () => {
    const fileName = `${document.pageKey || document.id || 'page'}.page.json`;
    const json = JSON.stringify(document, null, 2);
    const blob = new Blob([json], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const anchor = window.document.createElement('a');
    anchor.href = url;
    anchor.download = fileName;
    window.document.body.appendChild(anchor);
    anchor.click();
    window.document.body.removeChild(anchor);
    URL.revokeObjectURL(url);
  };

  // Import — read a chosen JSON file, validate it is a PageSchemaV3, then load it
  // through updateDocument so it joins the undo stack (and dirties the doc). On
  // any parse/shape failure the document is left untouched and an inline error
  // is shown via the existing save-error channel.
  const handleImportFile = (file: File) => {
    setSaveError(null);
    const reader = new FileReader();
    reader.onload = () => {
      const imported = parseImportedDocument(reader.result);
      if (!imported) {
        setSaveStatus('error');
        setSaveError(resolveDesignerText(DESIGNER_I18N.unified.importInvalid, locale));
        return;
      }
      updateDocument(() => imported);
      setSelectedBlockId(null);
    };
    reader.onerror = () => {
      setSaveStatus('error');
      setSaveError(resolveDesignerText(DESIGNER_I18N.unified.importInvalid, locale));
    };
    reader.readAsText(file);
  };

  const aiCopilotEnabled = !!aiCopilot;
  const aiDomainGuidance =
    typeof aiCopilot === 'object' && aiCopilot ? aiCopilot.domainGuidance : undefined;
  const aiKindPolicy = getKindPolicy(document.kind);
  const aiRootBlockType = aiKindPolicy.rootBlockType;
  const aiSystemPrompt = useMemo(() => {
    if (!aiCopilotEnabled) return '';
    const allowed = aiKindPolicy.allowedBlockTypes
      ? [...aiKindPolicy.allowedBlockTypes].filter((type) => type !== aiRootBlockType)
      : blockDefinitions.map((definition) => definition.blockType);
    const rootChildren = aiRootBlockType
      ? (document.blocks.find((block) => block.blockType === aiRootBlockType)?.blocks ?? [])
      : document.blocks;
    const fields = (document.modelCode ? (modelFieldsByModel[document.modelCode] ?? []) : []).map(
      (field) => ({
        code: field.code,
        name: typeof field.label === 'string' ? field.label : field.code,
        type: field.type ?? 'string',
      }),
    );
    return buildDesignCopilotPrompt({
      kind: document.kind,
      allowedBlockTypes: allowed,
      rootBlockType: aiRootBlockType,
      modelFields: fields,
      currentBlocks: rootChildren,
      domainGuidance: aiDomainGuidance,
    });
  }, [
    aiCopilotEnabled,
    aiKindPolicy,
    aiRootBlockType,
    blockDefinitions,
    document,
    modelFieldsByModel,
    aiDomainGuidance,
  ]);

  const handleApplyAiDesign = (parsed: ParsedDesign) => {
    updateDocument((current) =>
      applyDesignBlocks(current, parsed, getKindPolicy(current.kind).rootBlockType),
    );
    setSelectedBlockId(null);
  };

  return (
    <div
      className="flex h-[calc(100vh-64px)] min-h-[656px] flex-col overflow-hidden bg-slate-100 text-slate-900"
      data-testid="unified-designer-workbench"
      data-mode={mode}
    >
      <WorkbenchToolbar
        document={document}
        mode={mode}
        isDirty={isDirty}
        saveStatus={saveStatus}
        saveError={saveError}
        validationErrorCount={validationErrorCount}
        canUndo={canUndo}
        canRedo={canRedo}
        returnHref={returnHref}
        aiCopilotEnabled={aiCopilotEnabled}
        pageId={pageId}
        publishStatus={publishStatus}
        publishError={publishError}
        onModeChange={setMode}
        onSwitchKind={handleSwitchKind}
        onUndo={handleUndo}
        onRedo={handleRedo}
        onSave={handleSave}
        onPublish={onPublish ? handlePublish : undefined}
        onUnpublish={onUnpublish ? handleUnpublish : undefined}
        onExport={handleExport}
        onImportFile={handleImportFile}
        onOpenAiCopilot={() => setAiDialogOpen(true)}
        onOpenVersions={pageId ? () => setVersionPanelOpen(true) : undefined}
      />
      {pageId ? (
        <VersionHistoryPanel
          pid={pageId}
          open={versionPanelOpen}
          onClose={() => setVersionPanelOpen(false)}
          onRolledBack={handleVersionRolledBack}
        />
      ) : null}
      {aiCopilotEnabled ? (
        <AiDesignDialog
          open={aiDialogOpen}
          onClose={() => setAiDialogOpen(false)}
          systemPrompt={aiSystemPrompt}
          existingIds={collectBlockIds(document.blocks)}
          onApply={handleApplyAiDesign}
        />
      ) : null}
      {mode === 'preview' ? (
        <div
          className="min-h-0 flex-1 overflow-auto bg-slate-100 p-4 lg:p-6"
          data-testid="unified-runtime-preview"
        >
          <div className="mx-auto mb-3 flex max-w-7xl items-center gap-2">
            <span className="text-xs font-medium text-slate-500">预览设备</span>
            <select
              data-testid="preview-device-select"
              value={previewDeviceId}
              onChange={(event) => setPreviewDeviceId(event.target.value)}
              className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm text-slate-700 outline-none focus:border-blue-500"
            >
              {DEVICE_PREVIEW_PRESETS.map((preset) => (
                <option key={preset.id} value={preset.id}>
                  {preset.label}
                </option>
              ))}
            </select>
          </div>
          <div
            className={
              getDevicePreviewPreset(previewDeviceId).width == null
                ? 'mx-auto max-w-7xl'
                : 'mx-auto overflow-hidden rounded-2xl bg-white shadow-sm ring-1 ring-slate-200'
            }
            data-testid="preview-device-frame"
            data-device={previewDeviceId}
            style={getDeviceFrameStyle(getDevicePreviewPreset(previewDeviceId))}
          >
            <RecursiveBlockRenderer
              schema={document}
              runtimeServices={defaultRuntimeExecutionServices}
            />
          </div>
        </div>
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={designerCollisionDetection}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDragEnd={handleDragEnd}
          onDragCancel={() => {
            setActiveDrag(null);
            setActiveDropIntent(null);
          }}
        >
          {getPageTemplates().length > 0 ? (
            <div
              className="flex items-center gap-2 border-b border-slate-200 bg-white px-4 py-2"
              data-testid="designer-template-bar"
            >
              <span className="text-xs font-medium text-slate-500">场景模板</span>
              <select
                data-testid="designer-template-select"
                value=""
                onChange={(event) => {
                  if (event.target.value) applyTemplate(event.target.value);
                }}
                className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm text-slate-700 outline-none focus:border-blue-500"
              >
                <option value="">应用模板…</option>
                {getPageTemplates().map((template) => (
                  <option key={template.id} value={template.id}>
                    {template.label}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
          {multiSelectedIds.size >= 2 ? (
            <div
              className="flex items-center gap-3 border-b border-blue-200 bg-blue-50 px-4 py-2"
              data-testid="multi-select-bar"
            >
              <span className="text-sm font-medium text-blue-800" data-testid="multi-select-count">
                {resolveDesignerText(DESIGNER_I18N.unified.multiSelectCount, locale, {
                  count: multiSelectedIds.size,
                })}
              </span>
              <button
                type="button"
                data-testid="multi-select-delete"
                onClick={handleDeleteMultiSelected}
                className="inline-flex items-center gap-1 rounded-md border border-red-200 bg-white px-2.5 py-1 text-xs font-medium text-red-600 transition hover:bg-red-50"
              >
                {resolveDesignerText(DESIGNER_I18N.unified.multiSelectDelete, locale)}
              </button>
              <button
                type="button"
                data-testid="multi-select-clear"
                onClick={clearMultiSelection}
                className="inline-flex items-center gap-1 rounded-md border border-slate-200 bg-white px-2.5 py-1 text-xs font-medium text-slate-600 transition hover:bg-slate-50"
              >
                {resolveDesignerText(DESIGNER_I18N.unified.multiSelectClear, locale)}
              </button>
            </div>
          ) : null}
          <div
            className="flex min-h-0 flex-1 flex-col overflow-auto xl:flex-row xl:overflow-hidden"
            data-testid="unified-workbench-body"
          >
            <ResourcePanel
              document={document}
              selectedBlockId={selectedBlockId}
              selectedBlock={selectedBlock}
              blockDefinitions={blockDefinitions}
              selectedModelCode={selectedModelCode}
              modelFields={selectedModelFields}
              canAddBlock={canAddBlock}
              canAddModelField={canAddModelField}
              isModelFieldUsed={isSelectedModelFieldUsed}
              onSelect={setSelectedBlockId}
              onAddBlock={handleAddBlock}
              onAddModelField={handleAddModelField}
            />
            <CanvasHost
              document={document}
              mode={mode}
              selectedBlockId={selectedBlockId}
              multiSelectedIds={multiSelectedIds}
              activeDrag={activeDrag}
              activeDropIntent={activeDropIntent}
              rootAccepts={Boolean(rootAccepts)}
              onSelect={handleCanvasSelect}
              onMoveBefore={handleMoveBefore}
              onPatchBlock={patchBlock}
              canDeleteBlock={canDeleteBlock}
              onDeleteBlock={handleDeleteBlock}
              onMarqueeSelect={handleMarqueeSelect}
            />
            <InspectorHost
              selectedBlock={selectedBlock}
              modelFields={selectedModelFields}
              onChange={updateSelectedBlock}
            />
          </div>
          <DragOverlay dropAnimation={null}>
            {activeDrag ? <DragGhost drag={activeDrag} /> : null}
          </DragOverlay>
        </DndContext>
      )}
    </div>
  );
}

function DragGhost({ drag }: { drag: DragData }) {
  const label =
    drag.kind === 'palette-block'
      ? drag.blockType
      : drag.kind === 'model-field'
      ? localizedLabel(drag.field.label) || drag.field.code
      : drag.blockId;
  return (
    <div
      data-testid="drag-overlay-ghost"
      className="pointer-events-none rounded-md border border-blue-300 bg-white px-3 py-1.5 text-xs font-medium text-slate-700 shadow-lg"
    >
      {label}
    </div>
  );
}

function localizedLabel(value: ModelFieldDefinition['label']): string {
  if (typeof value === 'string') return value;
  return value['zh-CN'] || value['en-US'] || Object.values(value)[0] || '';
}

function formatValidationSaveError(errorCount: number): string {
  return `Fix ${errorCount} validation issue${errorCount === 1 ? '' : 's'} before saving.`;
}

function resolveSaveErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  if (typeof error === 'string' && error.trim()) return error;
  return 'Failed to save page schema.';
}

function resolvePublishErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  if (typeof error === 'string' && error.trim()) return error;
  return 'Failed to publish page.';
}

/**
 * Parse and shape-validate a file read result into a PageSchemaV3. Returns null
 * for any failure (not a string, invalid JSON, or not a V3 document) so the
 * caller can leave the current document untouched and surface an inline error.
 * The contract mirrors the readLocalDocument guard in unified-designer.tsx and
 * the loader's hasRecursiveV3Blocks check: schemaVersion 3 + id/kind + blocks[].
 */
function parseImportedDocument(raw: FileReader['result']): PageSchemaV3 | null {
  if (typeof raw !== 'string') return null;
  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return null;
  }
  if (!parsed || typeof parsed !== 'object') return null;
  const candidate = parsed as Record<string, unknown>;
  const validKind =
    candidate.kind === 'form' ||
    candidate.kind === 'list' ||
    candidate.kind === 'detail' ||
    candidate.kind === 'dashboard' ||
    candidate.kind === 'composite';
  if (
    candidate.schemaVersion !== 3 ||
    typeof candidate.id !== 'string' ||
    !validKind ||
    !Array.isArray(candidate.blocks)
  ) {
    return null;
  }
  return parsed as PageSchemaV3;
}

function findModelCodeForSelection(path: PageSchemaV3['blocks']): string | null {
  for (let index = path.length - 1; index >= 0; index -= 1) {
    const model = path[index].dataSource?.model;
    if (typeof model === 'string' && model.trim()) return model;
  }
  return null;
}

function getModelFieldTargetBlockType(parentBlockType: string): ModelFieldTargetBlockType | null {
  if (
    parentBlockType === 'form-section' ||
    parentBlockType === 'detail-section' ||
    parentBlockType === 'repeater' ||
    parentBlockType === 'subform'
  ) {
    return 'field';
  }
  if (parentBlockType === 'table' || parentBlockType === 'sub-table') return 'column';
  if (parentBlockType === 'filter-bar') return 'filter-field';
  return null;
}

function hasModelFieldChild(parentBlock: PageSchemaV3['blocks'][number], blockType: string, fieldCode: string) {
  return Boolean(
    parentBlock.blocks?.some((child) => child.blockType === blockType && child.field === fieldCode),
  );
}

function insertChildBlockBefore(
  children: DslBlockV3[],
  targetBlockId: string,
  nextBlock: DslBlockV3,
): DslBlockV3[] {
  const targetIndex = children.findIndex((child) => child.id === targetBlockId);
  if (targetIndex === -1) return children;

  const nextChildren = [...children];
  nextChildren.splice(targetIndex, 0, nextBlock);
  return nextChildren;
}

function insertBlockBeforeTarget(
  blocks: DslBlockV3[],
  targetBlockId: string,
  nextBlock: DslBlockV3,
  parentBlockId: string | null,
): DslBlockV3[] {
  if (!parentBlockId) {
    return insertChildBlockBefore(blocks, targetBlockId, nextBlock);
  }

  return updateBlockById(blocks, parentBlockId, (block) => ({
    ...block,
    blocks: insertChildBlockBefore(block.blocks ?? [], targetBlockId, nextBlock),
  }));
}

function applyParentPlacementDefaults(block: DslBlockV3, parentBlock: DslBlockV3): DslBlockV3 {
  if (
    block.blockType === 'action' &&
    (parentBlock.blockType === 'table' || parentBlock.blockType === 'sub-table')
  ) {
    return {
      ...block,
      region: block.region ?? 'row-actions',
    };
  }

  return block;
}

function syncFieldLikeBlockFromModelField(
  block: DslBlockV3,
  fieldCode: string,
  modelFields: ModelFieldDefinition[],
): DslBlockV3 {
  const targetBlockType = getFieldLikeTargetBlockType(block.blockType);
  const modelField = modelFields.find((field) => field.code === fieldCode);

  if (!targetBlockType || !modelField) {
    return { ...block, field: fieldCode };
  }

  const template = createModelFieldBlock(modelField, targetBlockType, new Set([block.id]));
  return {
    ...block,
    field: modelField.code,
    props: compactObject({
      ...(block.props ?? {}),
      ...(template.props ?? {}),
    }),
  };
}

function getFieldLikeTargetBlockType(blockType: string): ModelFieldTargetBlockType | null {
  if (blockType === 'field') return 'field';
  if (blockType === 'column') return 'column';
  if (blockType === 'filter-field') return 'filter-field';
  return null;
}

function compactObject<T extends Record<string, unknown>>(value: T): T {
  return Object.fromEntries(
    Object.entries(value).filter(([, item]) => item !== undefined),
  ) as T;
}
