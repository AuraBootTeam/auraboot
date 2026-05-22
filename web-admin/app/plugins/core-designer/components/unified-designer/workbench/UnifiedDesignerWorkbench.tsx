import React, { useMemo, useState } from 'react';
import type {
  DslBlockV3,
  ModelFieldDefinition,
  ModelFieldsByModel,
  PageSchemaV3,
  WorkbenchMode,
} from '../types';
import { findBlockById, moveBlockBefore, updateBlockById } from '../utils/recursiveBlockWalker';
import { setByPath } from '../utils/dotPath';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { getKindPolicy, isBlockTypeAllowedForKind } from '../registry/kindPolicy';
import {
  createBlockTemplate,
  createModelFieldBlock,
  type ModelFieldTargetBlockType,
} from '../registry/createBlockTemplate';
import { collectBlockIds } from '../utils/blockIds';
import { WorkbenchToolbar, type DesignerSaveStatus } from './WorkbenchToolbar';
import { ResourcePanel } from './ResourcePanel';
import { CanvasHost } from '../canvas/CanvasHost';
import { InspectorHost } from './InspectorHost';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import { defaultRuntimeExecutionServices } from '../runtime/runtimeExecution';

export interface UnifiedDesignerWorkbenchProps {
  initialDocument: PageSchemaV3;
  modelFieldsByModel?: ModelFieldsByModel;
  returnHref?: string;
  onSave?: (document: PageSchemaV3) => Promise<void> | void;
}

export function UnifiedDesignerWorkbench({
  initialDocument,
  modelFieldsByModel = {},
  returnHref,
  onSave,
}: UnifiedDesignerWorkbenchProps) {
  const [document, setDocument] = useState<PageSchemaV3>(initialDocument);
  const [savedSnapshot, setSavedSnapshot] = useState(() => serializeDocument(initialDocument));
  const [saveStatus, setSaveStatus] = useState<DesignerSaveStatus>('saved');
  const [saveError, setSaveError] = useState<string | null>(null);
  const [validationErrorCount, setValidationErrorCount] = useState(0);
  const [mode, setMode] = useState<WorkbenchMode>('edit');
  const [selectedBlockId, setSelectedBlockId] = useState<string | null>(null);
  const [draggingPaletteBlockType, setDraggingPaletteBlockType] = useState<string | null>(null);
  const [draggingModelField, setDraggingModelField] = useState<ModelFieldDefinition | null>(null);
  const blockRegistry = useMemo(() => createDefaultBlockRegistryV3(), []);
  const blockDefinitions = useMemo(
    () =>
      blockRegistry
        .getAll()
        .filter((definition) => isBlockTypeAllowedForKind(document.kind, definition.blockType)),
    [blockRegistry, document.kind],
  );

  const currentSnapshot = serializeDocument(document);
  const isDirty = currentSnapshot !== savedSnapshot;

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

  const updateDocument = (updater: (current: PageSchemaV3) => PageSchemaV3) => {
    setDocument(updater);
    setSaveStatus('dirty');
    setSaveError(null);
    setValidationErrorCount(0);
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
      setSavedSnapshot(serializeDocument(document));
      setSaveStatus('saved');
    } catch (error) {
      setSaveStatus('error');
      setSaveError(resolveSaveErrorMessage(error));
    }
  };

  return (
    <div
      className="flex h-[calc(100vh-64px)] min-h-[720px] flex-col overflow-hidden bg-slate-100 text-slate-900"
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
        returnHref={returnHref}
        onModeChange={setMode}
        onSave={handleSave}
      />
      {mode === 'preview' ? (
        <div
          className="min-h-0 flex-1 overflow-auto bg-slate-100 p-4 lg:p-6"
          data-testid="unified-runtime-preview"
        >
          <div className="mx-auto max-w-7xl">
            <RecursiveBlockRenderer
              schema={document}
              runtimeServices={defaultRuntimeExecutionServices}
            />
          </div>
        </div>
      ) : (
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
            onPaletteDragStart={setDraggingPaletteBlockType}
            onPaletteDragEnd={() => setDraggingPaletteBlockType(null)}
            onModelFieldDragStart={setDraggingModelField}
            onModelFieldDragEnd={() => setDraggingModelField(null)}
          />
          <CanvasHost
            document={document}
            mode={mode}
            selectedBlockId={selectedBlockId}
            draggingPaletteBlockType={draggingPaletteBlockType}
            draggingModelField={draggingModelField}
            onSelect={setSelectedBlockId}
            onMoveBefore={handleMoveBefore}
            onPatchBlock={patchBlock}
            canAddBlockToParent={canAddBlockToParent}
            onAddBlockToParent={handleAddBlockToParent}
            canAddBlockBeforeTarget={canAddBlockBeforeTarget}
            onAddBlockBeforeTarget={handleAddBlockBeforeTarget}
            canAddModelFieldToParent={canAddModelFieldToParent}
            onAddModelFieldToParent={handleAddModelFieldToParent}
            canAddModelFieldBeforeTarget={canAddModelFieldBeforeTarget}
            onAddModelFieldBeforeTarget={handleAddModelFieldBeforeTarget}
            canAddBlockToRoot={canAddBlockToRoot}
            onAddBlockToRoot={handleAddBlockToRoot}
            onPaletteDragEnd={() => setDraggingPaletteBlockType(null)}
            onModelFieldDragEnd={() => setDraggingModelField(null)}
          />
          <InspectorHost
            selectedBlock={selectedBlock}
            modelFields={selectedModelFields}
            onChange={updateSelectedBlock}
          />
        </div>
      )}
    </div>
  );
}

function serializeDocument(document: PageSchemaV3): string {
  return JSON.stringify(document);
}

function formatValidationSaveError(errorCount: number): string {
  return `Fix ${errorCount} validation issue${errorCount === 1 ? '' : 's'} before saving.`;
}

function resolveSaveErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message;
  if (typeof error === 'string' && error.trim()) return error;
  return 'Failed to save page schema.';
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
