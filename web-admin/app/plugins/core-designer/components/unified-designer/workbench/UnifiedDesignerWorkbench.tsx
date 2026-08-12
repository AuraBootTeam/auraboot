import React, { useMemo, useRef, useState } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import { DESIGNER_I18N, resolveDesignerText } from '~/shared/designer';
import {
  DndContext,
  DragOverlay,
  closestCenter,
  pointerWithin,
  type CollisionDetection,
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
import { getByPath, setByPath } from '../utils/dotPath';
import { validatePageSchemaV3 } from '../validation/validatePageSchemaV3';
import {
  parseDocumentSnapshot,
  serializeDocument,
  useDesignerDocument,
} from '../document/useDesignerDocument';
import { useDesignerSelection } from '../selection/useDesignerSelection';
import { useDesignerDnd } from '../dnd/useDesignerDnd';
import { createDefaultBlockRegistryV3 } from '../registry/BlockRegistry';
import { pruneStaleFieldComponentProps } from '../registry/InspectorSchemaRegistry';
import {
  DEVICE_PREVIEW_PRESETS,
  DEFAULT_DEVICE_PREVIEW_ID,
  getDeviceFrameStyle,
  getDevicePreviewPreset,
} from '../preview/devicePreviewPresets';
import {
  getPageTemplate,
  getPageTemplates,
  instantiatePageTemplate,
} from '../templates/pageTemplateRegistry';
import { CORE_PAGE_TEMPLATES } from '../templates/corePageTemplates';
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
import { collectBlockIds, createUniqueBlockId, toStableBlockId } from '../utils/blockIds';
import { buildDesignerCollisionCandidates, type DragData } from '../dnd/dndShared';
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
import { CanvasHost } from '../canvas/CanvasHost';
import { InspectorHost } from './InspectorHost';
import { RecursiveBlockRenderer } from '../runtime/RecursiveBlockRenderer';
import { defaultRuntimeExecutionServices } from '../runtime/runtimeExecution';
import {
  createRoleStructurePermissionEvaluator,
  roleStructurePreviewRuntimeServices,
  sanitizeRoleStructurePreviewDocument,
  summarizeRoleStructureDecisions,
} from '../preview/roleStructurePreview';
import {
  applySyntheticPreviewToDocument,
  createSyntheticPreviewRuntimeServices,
} from '../preview/syntheticPreview';
import {
  endAuthoringIdentitySimulation,
  loadAuthoringIdentitySimulation,
  loadAuthoringRolePreviewTargets,
  loadAuthoringRoleStructurePreview,
  loadAuthoringSyntheticPreview,
  startAuthoringIdentitySimulation,
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringSession,
  CapabilityRegistry,
  AuthoringIdentitySimulation,
  AuthoringRolePreviewTarget,
  AuthoringRoleStructureDecision,
  AuthoringRoleStructurePreview,
  AuthoringSyntheticPreview,
} from '~/framework/meta/authoring/types';
import { AiDesignDialog } from '../ai/AiDesignDialog';
import { buildDesignCopilotPrompt, applyDesignBlocks, type ParsedDesign } from '../ai/designCopilot';
import { GovernedAiPatchProposalDialog } from '../ai/GovernedAiPatchProposalDialog';

// Pointer-based collision for real users, with a closestCenter fallback when the
// pointer isn't inside any droppable.
const designerCollisionDetection: CollisionDetection = (args) => {
  return buildDesignerCollisionCandidates(
    pointerWithin(args),
    closestCenter(args),
    args.droppableRects,
  );
};

const SYNTHETIC_PREVIEW_OPTION = '__synthetic_fixture__';
const AUTHORING_COPY_LINEAGE_PATH = '/extension/authoringCopyLineage';

export interface UnifiedDesignerWorkbenchProps {
  initialDocument: PageSchemaV3;
  /** Optional authoritative baseline when initialDocument is a recovered local Mine. */
  initialSavedDocument?: PageSchemaV3;
  modelFieldsByModel?: ModelFieldsByModel;
  returnHref?: string;
  onSave?: (document: PageSchemaV3) => Promise<PageSchemaV3 | void> | PageSchemaV3 | void;
  /** Receives every local document transition so a host can persist crash recovery state. */
  onDocumentChange?: (document: PageSchemaV3, dirty: boolean) => void;
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
  governedAiCopilot?: {
    sessionPid: string;
    revision: number;
    capabilities: CapabilityRegistry;
    onApplied: (session: AuthoringSession) => void;
  };
  initialSelectedBlockId?: string;
  contextualReadOnly?: boolean;
  contextualEditablePropertyPaths?: Record<string, string[]>;
  contextualReorderableBlockTypes?: string[];
  contextualCreatableBlockTypes?: string[];
  contextualRemovableBlockTypes?: string[];
  contextualRelocatableBlockTypes?: string[];
  contextualPageKindSwitchEnabled?: boolean;
  /** Active governed authoring session; enables target-role structure preview in Preview mode. */
  roleStructurePreviewSessionPid?: string;
  /** Security-admin capability for starting a short-lived, audited, read-only role simulation. */
  identitySimulationAllowed?: boolean;
  /** Fill a parent shell instead of claiming another viewport-height workspace. */
  embedded?: boolean;
}

export function UnifiedDesignerWorkbench({
  initialDocument,
  initialSavedDocument,
  modelFieldsByModel = {},
  returnHref,
  onSave,
  onDocumentChange,
  pageId,
  initialPublished = false,
  onPublish,
  onUnpublish,
  onReloadDocument,
  aiCopilot,
  governedAiCopilot,
  initialSelectedBlockId,
  contextualReadOnly = false,
  contextualEditablePropertyPaths,
  contextualReorderableBlockTypes,
  contextualCreatableBlockTypes,
  contextualRemovableBlockTypes,
  contextualRelocatableBlockTypes,
  contextualPageKindSwitchEnabled = false,
  roleStructurePreviewSessionPid,
  identitySimulationAllowed = false,
  embedded = false,
}: UnifiedDesignerWorkbenchProps) {
  const { locale } = useI18n();
  const initialSnapshot = serializeDocument(initialDocument);
  const initialSavedSnapshot = serializeDocument(initialSavedDocument ?? initialDocument);
  const [savedSnapshot, setSavedSnapshot] = useState(initialSavedSnapshot);
  const savedSnapshotRef = useRef(initialSavedSnapshot);
  const [saveStatus, setSaveStatus] = useState<DesignerSaveStatus>(
    initialSnapshot === initialSavedSnapshot ? 'saved' : 'dirty',
  );
  const [saveError, setSaveError] = useState<string | null>(null);
  const [validationErrorCount, setValidationErrorCount] = useState(0);
  const [publishStatus, setPublishStatus] = useState<DesignerPublishStatus>(
    initialPublished ? 'published' : 'draft',
  );
  const [publishError, setPublishError] = useState<string | null>(null);
  const [mode, setMode] = useState<WorkbenchMode>('edit');
  const [previewDeviceId, setPreviewDeviceId] = useState<string>(DEFAULT_DEVICE_PREVIEW_ID);
  const [rolePreviewTargets, setRolePreviewTargets] = useState<AuthoringRolePreviewTarget[]>([]);
  const [selectedRolePreviewPid, setSelectedRolePreviewPid] = useState('');
  const [roleStructurePreview, setRoleStructurePreview] =
    useState<AuthoringRoleStructurePreview | null>(null);
  const [rolePreviewLoading, setRolePreviewLoading] = useState(false);
  const [rolePreviewError, setRolePreviewError] = useState<string | null>(null);
  const [syntheticPreview, setSyntheticPreview] = useState<AuthoringSyntheticPreview | null>(null);
  const [syntheticPreviewLoading, setSyntheticPreviewLoading] = useState(false);
  const [syntheticPreviewError, setSyntheticPreviewError] = useState<string | null>(null);
  const [identitySimulation, setIdentitySimulation] = useState<AuthoringIdentitySimulation | null>(
    null,
  );
  const [identitySimulationFormOpen, setIdentitySimulationFormOpen] = useState(false);
  const [identitySimulationDuration, setIdentitySimulationDuration] = useState<5 | 10 | 15>(5);
  const [identitySimulationReason, setIdentitySimulationReason] = useState('');
  const [identitySimulationPending, setIdentitySimulationPending] = useState(false);
  const [identitySimulationEnding, setIdentitySimulationEnding] = useState(false);
  const [identitySimulationError, setIdentitySimulationError] = useState<string | null>(null);
  const [identitySimulationRemainingSeconds, setIdentitySimulationRemainingSeconds] = useState(0);
  const identityTerminalRefreshPendingRef = useRef(false);
  const syntheticPreviewSelected = selectedRolePreviewPid === SYNTHETIC_PREVIEW_OPTION;
  const selectedTargetRolePid = syntheticPreviewSelected ? '' : selectedRolePreviewPid;
  const identitySimulationActive = identitySimulation?.status === 'ACTIVE';
  const identitySimulationPid = identitySimulation?.simulationPid;
  const identitySimulationExpiresAt = identitySimulation?.expiresAt;
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
  const contextualRestricted = contextualEditablePropertyPaths !== undefined;
  const contextualReorderableTypes = useMemo(
    () => new Set(contextualReorderableBlockTypes ?? []),
    [contextualReorderableBlockTypes],
  );
  const contextualCreatableTypes = useMemo(
    () => new Set(contextualCreatableBlockTypes ?? []),
    [contextualCreatableBlockTypes],
  );
  const contextualRemovableTypes = useMemo(
    () => new Set(contextualRemovableBlockTypes ?? []),
    [contextualRemovableBlockTypes],
  );
  const contextualRelocatableTypes = useMemo(
    () => new Set(contextualRelocatableBlockTypes ?? []),
    [contextualRelocatableBlockTypes],
  );
  const prepareCreatedBlock = (block: DslBlockV3): DslBlockV3 => {
    if (!contextualRestricted) return block;
    let projected = { id: block.id, blockType: block.blockType } as DslBlockV3;
    for (const pointer of contextualEditablePropertyPaths?.[block.blockType] ?? []) {
      const path = pointerToDotPath(pointer);
      const value = getByPath(block as unknown as Record<string, unknown>, path);
      if (value !== undefined) {
        projected = setByPath(
          projected as unknown as Record<string, unknown>,
          path,
          value,
        ) as unknown as DslBlockV3;
      }
    }
    if (block.blocks?.length) {
      projected = { ...projected, blocks: block.blocks.map(prepareCreatedBlock) };
    }
    return projected;
  };

  React.useEffect(() => {
    if (initialSelectedBlockId) setSelectedBlockId(initialSelectedBlockId);
  }, [initialSelectedBlockId, setSelectedBlockId]);

  React.useEffect(() => {
    setRolePreviewTargets([]);
    setSelectedRolePreviewPid('');
    setRoleStructurePreview(null);
    setRolePreviewError(null);
    setSyntheticPreview(null);
    setSyntheticPreviewError(null);
    if (!roleStructurePreviewSessionPid || mode !== 'preview') return;
    let cancelled = false;
    void loadAuthoringRolePreviewTargets(roleStructurePreviewSessionPid)
      .then((targets) => {
        if (!cancelled) setRolePreviewTargets(targets);
      })
      .catch((targetError: unknown) => {
        if (!cancelled) {
          setRolePreviewError(
            targetError instanceof Error ? targetError.message : '无法加载可预览角色',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [mode, roleStructurePreviewSessionPid]);

  React.useEffect(() => {
    setRoleStructurePreview(null);
    setRolePreviewError(null);
    if (!roleStructurePreviewSessionPid || !selectedTargetRolePid) {
      setRolePreviewLoading(false);
      return;
    }
    let cancelled = false;
    setRolePreviewLoading(true);
    void loadAuthoringRoleStructurePreview(
      roleStructurePreviewSessionPid,
      selectedTargetRolePid,
    )
      .then((preview) => {
        if (!cancelled) setRoleStructurePreview(preview);
      })
      .catch((previewError: unknown) => {
        if (!cancelled) {
          setRolePreviewError(
            previewError instanceof Error
              ? previewError.message
              : '无法生成角色权限结构预览',
          );
        }
      })
      .finally(() => {
        if (!cancelled) setRolePreviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [roleStructurePreviewSessionPid, selectedTargetRolePid]);

  React.useEffect(() => {
    setSyntheticPreview(null);
    setSyntheticPreviewError(null);
    if (!roleStructurePreviewSessionPid || !syntheticPreviewSelected) {
      setSyntheticPreviewLoading(false);
      return;
    }
    let cancelled = false;
    setSyntheticPreviewLoading(true);
    void loadAuthoringSyntheticPreview(roleStructurePreviewSessionPid)
      .then((preview) => {
        if (!cancelled) setSyntheticPreview(preview);
      })
      .catch((previewError: unknown) => {
        if (!cancelled) {
          setSyntheticPreviewError(
            previewError instanceof Error ? previewError.message : '无法生成隔离合成数据',
          );
        }
      })
      .finally(() => {
        if (!cancelled) setSyntheticPreviewLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [roleStructurePreviewSessionPid, syntheticPreviewSelected]);

  React.useEffect(() => {
    setIdentitySimulation(null);
    setIdentitySimulationFormOpen(false);
    setIdentitySimulationReason('');
    setIdentitySimulationError(null);
  }, [roleStructurePreviewSessionPid]);

  React.useEffect(() => {
    if (!identitySimulationActive || !identitySimulationPid || !identitySimulationExpiresAt) {
      setIdentitySimulationRemainingSeconds(0);
      identityTerminalRefreshPendingRef.current = false;
      return;
    }
    let cancelled = false;
    const refreshRemaining = () => {
      const remaining = Math.max(
        0,
        Math.ceil((Date.parse(identitySimulationExpiresAt) - Date.now()) / 1000),
      );
      setIdentitySimulationRemainingSeconds(remaining);
      if (remaining === 0 && !identityTerminalRefreshPendingRef.current) {
        identityTerminalRefreshPendingRef.current = true;
        void loadAuthoringIdentitySimulation(identitySimulationPid)
          .then((refreshed) => {
            if (!cancelled) setIdentitySimulation(refreshed);
          })
          .catch((refreshError: unknown) => {
            if (!cancelled) {
              setIdentitySimulationError(
                refreshError instanceof Error ? refreshError.message : '无法确认身份模拟是否已到期',
              );
            }
          })
          .finally(() => {
            identityTerminalRefreshPendingRef.current = false;
          });
      }
    };
    refreshRemaining();
    const timer = window.setInterval(refreshRemaining, 1000);
    return () => {
      cancelled = true;
      window.clearInterval(timer);
    };
  }, [identitySimulationActive, identitySimulationExpiresAt, identitySimulationPid]);

  const handleStartIdentitySimulation = async () => {
    const reason = identitySimulationReason.trim();
    if (!roleStructurePreviewSessionPid || !selectedTargetRolePid || !reason) return;
    setIdentitySimulationPending(true);
    setIdentitySimulationError(null);
    try {
      const started = await startAuthoringIdentitySimulation(
        roleStructurePreviewSessionPid,
        selectedTargetRolePid,
        identitySimulationDuration,
        reason,
      );
      setIdentitySimulation(started);
      setIdentitySimulationFormOpen(false);
      setIdentitySimulationReason('');
    } catch (startError: unknown) {
      setIdentitySimulationError(
        startError instanceof Error ? startError.message : '无法启动审计身份模拟',
      );
    } finally {
      setIdentitySimulationPending(false);
    }
  };

  const handleEndIdentitySimulation = async () => {
    if (!identitySimulationActive || !identitySimulation) return;
    setIdentitySimulationEnding(true);
    setIdentitySimulationError(null);
    try {
      setIdentitySimulation(await endAuthoringIdentitySimulation(identitySimulation.simulationPid));
    } catch (endError: unknown) {
      setIdentitySimulationError(
        endError instanceof Error ? endError.message : '无法结束审计身份模拟',
      );
    } finally {
      setIdentitySimulationEnding(false);
    }
  };

  // Toolbar save indicator follows the live document snapshot; wired into the
  // document kernel's onChange so every edit / undo / redo refreshes it.
  const syncSaveStateForSnapshot = (snapshot: string) => {
    const dirty = snapshot !== savedSnapshotRef.current;
    setSaveStatus(dirty ? 'dirty' : 'saved');
    setSaveError(null);
    setValidationErrorCount(0);
    onDocumentChange?.(parseDocumentSnapshot(snapshot), dirty);
  };

  // Shared block-tree document + history kernel. Selection, drag-and-drop, the
  // block registry, and save/publish state are layered on top by this workbench.
  const documentKernel = useDesignerDocument({
    initialDocument,
    onChange: syncSaveStateForSnapshot,
  });
  const document = documentKernel.document;
  const availablePageTemplates = useMemo(
    () =>
      [...CORE_PAGE_TEMPLATES, ...getPageTemplates()]
        .filter((template) => {
          if (template.kinds) return template.kinds.includes(document.kind);
          const roots = template.build();
          return roots.length === 1 && roots[0].blockType === document.kind;
        })
        .filter((template) => {
          if (!contextualRestricted) return true;
          const roots = template.build();
          return (
            roots.length === 1 &&
            templateDescendantsAreGoverned(roots[0].blocks, contextualCreatableTypes)
          );
        }),
    [contextualCreatableTypes, contextualRestricted, document.kind],
  );
  const updateDocument = documentKernel.update;
  const handleUndo = documentKernel.undo;
  const handleRedo = documentKernel.redo;
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
    if (contextualReadOnly || (contextualRestricted && !contextualPageKindSwitchEnabled)) return;
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
    if (contextualReadOnly) return;
    const template = availablePageTemplates.find((candidate) => candidate.id === templateId)
      ?? getPageTemplate(templateId);
    if (!template) return;
    const built = instantiatePageTemplate(template, document.blocks);
    const governedBlocks = contextualRestricted
      ? projectTemplateIntoGovernedRoot(document, built, prepareCreatedBlock)
      : built;
    if (!governedBlocks) return;
    updateDocument((current) => ({
      ...current,
      title: contextualRestricted ? current.title : (template.title ?? current.title),
      blocks: governedBlocks,
    }));
    setSelectedBlockId(null);
    setMultiSelectedIds(new Set());
  };

  const updateSelectedBlock = (path: string, value: unknown) => {
    if (contextualReadOnly) return;
    if (!selectedBlockId) return;
    if (
      contextualRestricted &&
      !isDotPathAllowed(
        path,
        contextualEditablePropertyPaths?.[selectedBlock?.blockType ?? ''] ?? [],
      )
    ) {
      return;
    }
    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, selectedBlockId, (block) => {
        if (path === 'field' && typeof value === 'string') {
          return syncFieldLikeBlockFromModelField(block, value, selectedModelFields);
        }

        const updated = setByPath(
          block as unknown as Record<string, unknown>,
          path,
          value,
        ) as unknown as typeof block;

        // When a field's `component` changes, reconcile its props: drop props that
        // were authored for the OLD component and don't apply to the new one (e.g.
        // upload's multiple/accept/maxFiles when switching to select, or a picker's
        // pickerSource/displayField when switching to input). The inspector only
        // renders the control for the CURRENT component, so such leftovers are
        // invisible yet still change the new control's runtime behaviour. The prune
        // rule is derived from the inspector schema registry (which props belong to
        // which component), so it stays in sync with the schema.
        if (path === 'props.component') {
          const prunedProps = pruneStaleFieldComponentProps(updated.props, value);
          if (prunedProps !== updated.props) {
            return { ...updated, props: prunedProps };
          }
        }

        return updated;
      }),
    }));
  };

  const handleMoveBefore = (movingBlockId: string, targetBlockId: string) => {
    if (contextualReadOnly) return;
    if (contextualRestricted && !canContextualMoveBefore(movingBlockId, targetBlockId)) return;
    updateDocument((current) => ({
      ...current,
      blocks: moveBlockBefore(current.blocks, movingBlockId, targetBlockId),
    }));
  };

  const handleMoveToParent = (movingBlockId: string, parentBlockId: string) => {
    if (contextualReadOnly) return;
    if (contextualRestricted && !canContextualRelocate(movingBlockId)) return;
    updateDocument((current) => ({
      ...current,
      blocks: moveBlockToParent(current.blocks, movingBlockId, parentBlockId),
    }));
    setSelectedBlockId(movingBlockId);
  };

  // The single top-level kind container (form/list/detail/dashboard root) defines
  // the page; it cannot be deleted, only its descendants can.
  const canDeleteBlock = (blockId: string) => {
    if (contextualReadOnly) return false;
    const result = findBlockById(document.blocks, blockId);
    return Boolean(result)
      && result!.path.length > 1
      && (!contextualRestricted || contextualRemovableTypes.has(result!.block.blockType));
  };

  const handleDeleteBlock = (blockId: string) => {
    if (contextualReadOnly) return;
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

  const canDuplicateBlock = (blockId: string | null) => {
    if (contextualReadOnly || !blockId) return false;
    const result = findBlockById(document.blocks, blockId);
    if (!result || result.path.length <= 1) return false;
    if (!contextualRestricted) return true;
    return duplicateSubtreeIsGoverned(
      result.block,
      contextualCreatableTypes,
      contextualEditablePropertyPaths ?? {},
    );
  };

  const handleDuplicateBlock = () => {
    if (!canDuplicateBlock(selectedBlockId) || !selectedBlockId) return;
    let copiedRootId: string | null = null;
    updateDocument((current) => {
      const source = findBlockById(current.blocks, selectedBlockId);
      if (!source || source.path.length <= 1) return current;
      const usedIds = collectBlockIds(current.blocks);
      const copied = prepareCreatedBlock(duplicateBlockSubtree(source.block, usedIds));
      copiedRootId = copied.id;
      return {
        ...current,
        blocks: insertBlockAfterTarget(current.blocks, source.block.id, copied),
      };
    });
    if (copiedRootId) {
      setSelectedBlockId(copiedRootId);
      setMultiSelectedIds(new Set());
    }
  };


  // Batch-delete every deletable block in the multi-selection in a single
  // history step (one updateDocument → one undo). Undeletable blocks (the root
  // kind container) are silently skipped. Selection is cleared afterwards.
  const handleDeleteMultiSelected = () => {
    if (contextualReadOnly) return;
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
    if (contextualReadOnly) return false;
    if (contextualRestricted && !contextualCreatableTypes.has(blockType)) return false;
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
      const preparedBlock = prepareCreatedBlock(
        applyParentPlacementDefaults(nextBlock, selectedBlock),
      );
      updateDocument((current) => ({
        ...current,
        blocks: updateBlockById(current.blocks, selectedBlockId, (block) => ({
          ...block,
          blocks: [...(block.blocks ?? []), preparedBlock],
        })),
      }));
    } else if (selectedBlockId && beforeTarget) {
      const preparedBlock = prepareCreatedBlock(beforeTarget.parentBlock
        ? applyParentPlacementDefaults(nextBlock, beforeTarget.parentBlock)
        : nextBlock);
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
        blocks: [...current.blocks, prepareCreatedBlock(nextBlock)],
      }));
    }

    setSelectedBlockId(nextBlock.id);
  };

  const canAddBlockToParent = (parentBlockId: string, blockType: string) => {
    if (contextualReadOnly) return false;
    if (contextualRestricted && !contextualCreatableTypes.has(blockType)) return false;
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    const parentBlock = findBlockById(document.blocks, parentBlockId)?.block;
    return parentBlock ? blockRegistry.canContain(parentBlock.blockType, blockType) : false;
  };

  const canAddBlockBeforeTarget = (targetBlockId: string, blockType: string) => {
    if (contextualReadOnly) return false;
    if (contextualRestricted && !contextualCreatableTypes.has(blockType)) return false;
    if (!isBlockTypeAllowedForKind(document.kind, blockType)) return false;
    return Boolean(resolveBlockDropBeforeTarget(targetBlockId, blockType));
  };

  const canMoveBlockBeforeTarget = (movingBlockId: string, targetBlockId: string) => {
    if (contextualRestricted && !canContextualMoveBefore(movingBlockId, targetBlockId)) return false;
    return canMoveExistingBlockBeforeTarget({
      blocks: document.blocks,
      kind: document.kind,
      blockRegistry,
      movingBlockId,
      targetBlockId,
    });
  };

  const canMoveBlockToParent = (movingBlockId: string, parentBlockId: string) => {
    if (contextualRestricted && !canContextualRelocate(movingBlockId)) return false;
    return canMoveExistingBlockToParent({
      blocks: document.blocks,
      kind: document.kind,
      blockRegistry,
      movingBlockId,
      parentBlockId,
    });
  };

  const canContextualMoveBefore = (movingBlockId: string, targetBlockId: string): boolean => {
    const movingResult = findBlockById(document.blocks, movingBlockId);
    const targetResult = findBlockById(document.blocks, targetBlockId);
    if (!movingResult || !targetResult) return false;
    const movingParent = movingResult.path.at(-2)?.id ?? null;
    const targetParent = targetResult.path.at(-2)?.id ?? null;
    return movingParent === targetParent
      ? contextualReorderableTypes.has(movingResult.block.blockType)
      : contextualRelocatableTypes.has(movingResult.block.blockType);
  };

  const canContextualRelocate = (movingBlockId: string): boolean => {
    const block = findBlockById(document.blocks, movingBlockId)?.block;
    return Boolean(block && contextualRelocatableTypes.has(block.blockType));
  };

  const canContextualResizeSpan = (blockId: string): boolean => {
    const block = findBlockById(document.blocks, blockId)?.block;
    if (!block) return false;
    return contextualEditablePropertyPaths?.[block.blockType]?.includes('/layout/span') ?? false;
  };

  const canAddBlockToRoot = (blockType: string) => {
    if (contextualReadOnly) return false;
    if (contextualRestricted && !contextualCreatableTypes.has(blockType)) return false;
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
      blocks: [...current.blocks, prepareCreatedBlock(nextBlock)],
    }));
    setSelectedBlockId(nextBlock.id);
  };

  const handleAddBlockToParent = (parentBlockId: string, blockType: string) => {
    if (!canAddBlockToParent(parentBlockId, blockType)) return;

    const nextBlock = createBlockTemplate(blockType, collectBlockIds(document.blocks));
    if (!nextBlock) return;
    const parentBlock = findBlockById(document.blocks, parentBlockId)?.block;
    if (!parentBlock) return;
    const preparedBlock = prepareCreatedBlock(applyParentPlacementDefaults(nextBlock, parentBlock));

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
    const preparedBlock = prepareCreatedBlock(resolution.parentBlock
      ? applyParentPlacementDefaults(nextBlock, resolution.parentBlock)
      : nextBlock);

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
    const targetBlockType = resolveModelFieldDropTarget(parentBlockId, field);
    return Boolean(
      targetBlockType
      && (!contextualRestricted || contextualCreatableTypes.has(targetBlockType)),
    );
  };

  const canAddModelFieldBeforeTarget = (targetBlockId: string, field: ModelFieldDefinition) => {
    const resolution = resolveModelFieldDropBeforeTarget(targetBlockId, field);
    return Boolean(
      resolution
      && (!contextualRestricted || contextualCreatableTypes.has(resolution.targetBlockType)),
    );
  };

  const handleAddModelFieldToParent = (parentBlockId: string, field: ModelFieldDefinition) => {
    const targetBlockType = resolveModelFieldDropTarget(parentBlockId, field);
    if (!targetBlockType) return;

    const nextBlock = createModelFieldBlock(
      field,
      targetBlockType,
      collectBlockIds(document.blocks),
    );
    const preparedBlock = prepareCreatedBlock(nextBlock);

    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, parentBlockId, (block) => ({
        ...block,
        blocks: [...(block.blocks ?? []), preparedBlock],
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
    const preparedBlock = prepareCreatedBlock(nextBlock);

    updateDocument((current) => ({
      ...current,
      blocks: updateBlockById(current.blocks, resolution.parentBlock.id, (block) => ({
        ...block,
        blocks: insertChildBlockBefore(block.blocks ?? [], targetBlockId, preparedBlock),
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
    if (isModelFieldUsedInParent(selectedBlockId, field)) return true;
    const selected = findBlockById(document.blocks, selectedBlockId);
    if (!selected) return false;
    return selected.path
      .slice(0, -1)
      .reverse()
      .some(({ block }) => isModelFieldUsedInParent(block.id, field));
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
    if (contextualReadOnly) return;
    if (contextualRestricted && !canContextualResizeSpan(blockId)) return;
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
  // Block-tree drag-and-drop kernel: owns the active-drag / drop-intent state
  // and the @dnd-kit start/over/end glue, dispatching the resolved drop action
  // back here so this workbench keeps its own add/move executors.
  const {
    activeDrag,
    activeDropIntent,
    rootAccepts,
    sensors,
    handleDragStart,
    handleDragOver,
    handleDragEnd,
    clearActiveDrag,
  } = useDesignerDnd({
    dropCapabilities,
    canAddBlockToRoot,
    getBlockPath: (blockId) =>
      findBlockById(document.blocks, blockId)?.path.map((item) => item.id) ?? [],
    getBlockType: (blockId) => findBlockById(document.blocks, blockId)?.block.blockType,
    onSelectCanvasBlock: setSelectedBlockId,
    onDropAction: (action) => {
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
    },
  });

  const handleSave = async () => {
    if (contextualReadOnly) return;
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
      const savedDocument = await onSave?.(document);
      const canonicalDocument = savedDocument ?? document;
      if (savedDocument) documentKernel.reset(savedDocument);
      const snapshot = serializeDocument(canonicalDocument);
      savedSnapshotRef.current = snapshot;
      setSavedSnapshot(snapshot);
      setSaveStatus('saved');
      onDocumentChange?.(canonicalDocument, false);
    } catch (error) {
      setSaveStatus('error');
      setSaveError(resolveSaveErrorMessage(error, locale));
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
    if (contextualReadOnly) return;
    setSaveError(null);
    const reader = new FileReader();
    reader.onload = () => {
      const imported = parseImportedDocument(reader.result);
      if (!imported) {
        setSaveStatus('import-error');
        setSaveError(resolveDesignerText(DESIGNER_I18N.unified.importInvalid, locale));
        return;
      }
      const nextDocument = contextualRestricted
        ? normalizeGovernedImport(
            document,
            imported,
            prepareCreatedBlock,
            contextualCreatableTypes,
            contextualRemovableTypes,
            contextualReorderableTypes,
            contextualRelocatableTypes,
            contextualPageKindSwitchEnabled,
          )
        : imported;
      if (!nextDocument) {
        setSaveStatus('import-error');
        setSaveError(resolveDesignerText(DESIGNER_I18N.unified.importInvalid, locale));
        return;
      }
      updateDocument(() => nextDocument);
      setSelectedBlockId(null);
      setMultiSelectedIds(new Set());
    };
    reader.onerror = () => {
      setSaveStatus('import-error');
      setSaveError(resolveDesignerText(DESIGNER_I18N.unified.importInvalid, locale));
    };
    reader.readAsText(file);
  };

  const legacyAiCopilotEnabled = !!aiCopilot && !contextualRestricted;
  const governedAiCopilotEnabled = Boolean(
    governedAiCopilot && contextualRestricted && !contextualReadOnly,
  );
  const aiCopilotEnabled = legacyAiCopilotEnabled || governedAiCopilotEnabled;
  const aiDomainGuidance =
    typeof aiCopilot === 'object' && aiCopilot ? aiCopilot.domainGuidance : undefined;
  const aiKindPolicy = getKindPolicy(document.kind);
  const aiRootBlockType = aiKindPolicy.rootBlockType;
  const aiSystemPrompt = useMemo(() => {
    if (!legacyAiCopilotEnabled) return '';
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
    legacyAiCopilotEnabled,
    aiKindPolicy,
    aiRootBlockType,
    blockDefinitions,
    document,
    modelFieldsByModel,
    aiDomainGuidance,
  ]);

  const handleApplyAiDesign = (parsed: ParsedDesign) => {
    if (contextualReadOnly || contextualRestricted) return;
    updateDocument((current) =>
      applyDesignBlocks(current, parsed, getKindPolicy(current.kind).rootBlockType),
    );
    setSelectedBlockId(null);
  };

  const effectiveRoleStructurePreview = useMemo<AuthoringRoleStructurePreview | null>(() => {
    if (!identitySimulationActive || !identitySimulation) return roleStructurePreview;
    return {
      mode: 'STRUCTURE',
      pagePid: identitySimulation.pagePid,
      targetRole: identitySimulation.targetRole,
      actorIntersectionApplied: true,
      businessDataIncluded: false,
      exportAllowed: false,
      businessActionsAllowed: false,
      decisions: identitySimulation.decisions,
    };
  }, [identitySimulation, identitySimulationActive, roleStructurePreview]);
  const previewDocument = useMemo(() => {
    if (effectiveRoleStructurePreview) return sanitizeRoleStructurePreviewDocument(document);
    if (syntheticPreview) return applySyntheticPreviewToDocument(document, syntheticPreview);
    return document;
  }, [document, effectiveRoleStructurePreview, syntheticPreview]);
  const rolePreviewPermissionEvaluator = useMemo(
    () =>
      effectiveRoleStructurePreview
        ? createRoleStructurePermissionEvaluator(effectiveRoleStructurePreview)
        : undefined,
    [effectiveRoleStructurePreview],
  );
  const rolePreviewSummary = useMemo(
    () => summarizeRoleStructureDecisions(effectiveRoleStructurePreview?.decisions ?? []),
    [effectiveRoleStructurePreview],
  );
  const syntheticPreviewRuntimeServices = useMemo(
    () => (syntheticPreview ? createSyntheticPreviewRuntimeServices(syntheticPreview) : undefined),
    [syntheticPreview],
  );

  return (
    <div
      className={`flex flex-col overflow-hidden bg-slate-100 text-slate-900 ${
        embedded ? 'h-full min-h-[36rem]' : 'h-[calc(100vh-64px)] min-h-[656px]'
      }`}
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
        aiCopilotGoverned={governedAiCopilotEnabled}
        pageId={pageId}
        publishStatus={publishStatus}
        publishError={publishError}
        onModeChange={setMode}
        onSwitchKind={
          contextualRestricted && !contextualPageKindSwitchEnabled ? undefined : handleSwitchKind
        }
        onUndo={handleUndo}
        onRedo={handleRedo}
        onSave={handleSave}
        onPublish={onPublish ? handlePublish : undefined}
        onUnpublish={onUnpublish ? handleUnpublish : undefined}
        onExport={
          !contextualReadOnly &&
          !identitySimulationActive &&
          effectiveRoleStructurePreview?.exportAllowed !== false
            ? handleExport
            : undefined
        }
        onImportFile={contextualReadOnly ? undefined : handleImportFile}
        onOpenAiCopilot={() => setAiDialogOpen(true)}
        onOpenVersions={
          pageId && !contextualRestricted ? () => setVersionPanelOpen(true) : undefined
        }
        readOnly={contextualReadOnly}
        contextualRestricted={contextualRestricted}
      />
      {pageId && !contextualRestricted ? (
        <VersionHistoryPanel
          pid={pageId}
          open={versionPanelOpen}
          onClose={() => setVersionPanelOpen(false)}
          onRolledBack={handleVersionRolledBack}
        />
      ) : null}
      {governedAiCopilotEnabled && governedAiCopilot ? (
        <GovernedAiPatchProposalDialog
          open={aiDialogOpen}
          onClose={() => setAiDialogOpen(false)}
          sessionPid={governedAiCopilot.sessionPid}
          revision={governedAiCopilot.revision}
          document={document}
          capabilities={governedAiCopilot.capabilities}
          onApplied={governedAiCopilot.onApplied}
        />
      ) : legacyAiCopilotEnabled ? (
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
          <div className="mx-auto mb-3 flex max-w-7xl flex-wrap items-center gap-3">
            <label className="flex items-center gap-2 text-xs font-medium text-slate-500">
              {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.device, locale)}
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
            </label>
            {roleStructurePreviewSessionPid ? (
              <label className="flex items-center gap-2 text-xs font-medium text-slate-500">
                {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.perspective, locale)}
                <select
                  data-testid="role-preview-target-select"
                  value={selectedRolePreviewPid}
                  onChange={(event) => setSelectedRolePreviewPid(event.target.value)}
                  disabled={identitySimulationActive || identitySimulationPending}
                  className="min-w-48 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm text-slate-700 outline-none focus:border-blue-500"
                >
                  <option value="">
                    {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.currentActor, locale)}
                  </option>
                  <option value={SYNTHETIC_PREVIEW_OPTION}>
                    {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.option, locale)}
                  </option>
                  {rolePreviewTargets.map((target) => (
                    <option key={target.rolePid} value={target.rolePid}>
                      {target.roleName} · {target.roleCode}
                    </option>
                  ))}
                </select>
              </label>
            ) : null}
            {rolePreviewLoading || syntheticPreviewLoading || identitySimulationPending ? (
              <span className="text-xs text-blue-700" data-testid="role-preview-loading">
                {resolveDesignerText(
                  identitySimulationPending
                    ? DESIGNER_I18N.unified.identitySimulation.starting
                    : syntheticPreviewSelected
                      ? DESIGNER_I18N.unified.syntheticPreview.calculating
                      : DESIGNER_I18N.unified.rolePreview.calculating,
                  locale,
                )}
              </span>
            ) : null}
          </div>
          {rolePreviewError && !selectedRolePreviewPid ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-amber-200 bg-amber-50 px-4 py-2 text-xs text-amber-800"
              data-testid="role-preview-targets-error"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.targetsFailed, locale, {
                error: rolePreviewError,
              })}
            </div>
          ) : null}
          {roleStructurePreview && !identitySimulation ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-950"
              data-testid="role-structure-preview-banner"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <span className="font-semibold">
                    {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.title, locale, {
                      role: roleStructurePreview.targetRole.roleName,
                    })}
                  </span>
                  <span className="ml-2 text-xs text-blue-700">
                    {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.intersection, locale)}
                  </span>
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  {identitySimulationAllowed ? (
                    <button
                      type="button"
                      data-testid="identity-simulation-open"
                      onClick={() => {
                        setIdentitySimulationFormOpen((current) => !current);
                        setIdentitySimulationError(null);
                      }}
                      className="rounded-md bg-rose-700 px-2.5 py-1 text-xs font-semibold text-white hover:bg-rose-800"
                    >
                      {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.open, locale)}
                    </button>
                  ) : null}
                  <button
                    type="button"
                    data-testid="role-preview-exit"
                    onClick={() => setSelectedRolePreviewPid('')}
                    className="rounded-md border border-blue-300 bg-white px-2.5 py-1 text-xs font-medium text-blue-800 hover:bg-blue-100"
                  >
                    {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.exit, locale)}
                  </button>
                </div>
              </div>
              {identitySimulationFormOpen ? (
                <div
                  className="mt-3 rounded-lg border border-rose-200 bg-white p-3 text-slate-800"
                  data-testid="identity-simulation-form"
                >
                  <div className="font-semibold text-rose-900">
                    {resolveDesignerText(
                      DESIGNER_I18N.unified.identitySimulation.confirmTitle,
                      locale,
                    )}
                  </div>
                  <p className="mt-1 text-xs text-slate-600">
                    {resolveDesignerText(
                      DESIGNER_I18N.unified.identitySimulation.confirmDescription,
                      locale,
                    )}
                  </p>
                  <div className="mt-3 grid gap-3 sm:grid-cols-[160px_minmax(240px,1fr)_auto] sm:items-end">
                    <label className="grid gap-1 text-xs font-medium text-slate-600">
                      {resolveDesignerText(
                        DESIGNER_I18N.unified.identitySimulation.duration,
                        locale,
                      )}
                      <select
                        data-testid="identity-simulation-duration"
                        value={identitySimulationDuration}
                        onChange={(event) =>
                          setIdentitySimulationDuration(Number(event.target.value) as 5 | 10 | 15)
                        }
                        className="rounded-md border border-slate-300 bg-white px-2 py-2 text-sm"
                      >
                        {[5, 10, 15].map((duration) => (
                          <option key={duration} value={duration}>
                            {resolveDesignerText(
                              DESIGNER_I18N.unified.identitySimulation.minutes,
                              locale,
                              { count: duration },
                            )}
                          </option>
                        ))}
                      </select>
                    </label>
                    <label className="grid gap-1 text-xs font-medium text-slate-600">
                      {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.reason, locale)}
                      <textarea
                        data-testid="identity-simulation-reason"
                        value={identitySimulationReason}
                        maxLength={1000}
                        rows={2}
                        onChange={(event) => setIdentitySimulationReason(event.target.value)}
                        placeholder={resolveDesignerText(
                          DESIGNER_I18N.unified.identitySimulation.reasonPlaceholder,
                          locale,
                        )}
                        className="resize-none rounded-md border border-slate-300 px-2 py-2 text-sm"
                      />
                    </label>
                    <button
                      type="button"
                      data-testid="identity-simulation-start"
                      disabled={identitySimulationPending || !identitySimulationReason.trim()}
                      onClick={() => void handleStartIdentitySimulation()}
                      className="rounded-md bg-rose-700 px-3 py-2 text-xs font-semibold text-white hover:bg-rose-800 disabled:cursor-not-allowed disabled:opacity-50"
                    >
                      {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.start, locale)}
                    </button>
                  </div>
                </div>
              ) : null}
              <div className="mt-2 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-blue-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.noTargetData, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-blue-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.exportOff, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-blue-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.actionsOff, locale)}
                </span>
                {rolePreviewSummary.map((summary) => (
                  <span
                    key={summary.nodeType}
                    className="rounded-full bg-white px-2 py-1 ring-1 ring-blue-200"
                    data-testid={`role-preview-summary-${summary.nodeType.toLowerCase()}`}
                  >
                    {rolePreviewNodeLabel(summary.nodeType, locale)} {summary.allowed}/{summary.total}
                  </span>
                ))}
              </div>
              <details className="mt-2 text-xs">
                <summary className="cursor-pointer font-medium text-blue-800">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.inspect, locale)}
                </summary>
                <div className="mt-2 max-h-40 overflow-auto rounded-md bg-white ring-1 ring-blue-100">
                  {roleStructurePreview.decisions.map((decision) => (
                    <div
                      key={`${decision.nodeType}:${decision.nodeId}`}
                      className="grid grid-cols-[64px_minmax(120px,1fr)_auto] gap-2 border-b border-blue-50 px-3 py-2 last:border-b-0"
                      data-testid={`role-preview-decision-${decision.nodeId}`}
                    >
                      <span className="text-slate-500">
                        {rolePreviewNodeLabel(decision.nodeType, locale)}
                      </span>
                      <span className="truncate" title={decision.permissionCode || undefined}>
                        {decision.label || decision.nodeId}
                      </span>
                      <span
                        className={decision.visible ? 'text-emerald-700' : 'font-medium text-amber-700'}
                      >
                        {decision.visible
                          ? resolveDesignerText(
                              decision.writable
                                ? DESIGNER_I18N.unified.rolePreview.visibleWritable
                                : DESIGNER_I18N.unified.rolePreview.visibleReadOnly,
                              locale,
                            )
                          : resolveDesignerText(
                              DESIGNER_I18N.unified.rolePreview.hidden,
                              locale,
                            )}
                      </span>
                    </div>
                  ))}
                </div>
              </details>
            </div>
          ) : null}
          {identitySimulation ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-rose-300 bg-rose-50 px-4 py-3 text-sm text-rose-950 shadow-sm"
              data-testid="identity-simulation-banner"
              data-status={identitySimulation.status}
            >
              <div className="flex flex-wrap items-center justify-between gap-3">
                <div>
                  <span className="font-semibold">
                    {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.title, locale, {
                      role: identitySimulation.targetRole.roleName,
                    })}
                  </span>
                  <span className="ml-2 text-xs font-medium text-rose-700">
                    {resolveDesignerText(
                      identitySimulation.status === 'ACTIVE'
                        ? DESIGNER_I18N.unified.identitySimulation.active
                        : identitySimulation.status === 'EXPIRED'
                          ? DESIGNER_I18N.unified.identitySimulation.expired
                          : DESIGNER_I18N.unified.identitySimulation.ended,
                      locale,
                    )}
                  </span>
                </div>
                {identitySimulationActive ? (
                  <div className="flex items-center gap-2">
                    <span
                      className="font-mono text-xs font-semibold text-rose-800"
                      data-testid="identity-simulation-countdown"
                    >
                      {String(Math.floor(identitySimulationRemainingSeconds / 60)).padStart(2, '0')}
                      :{String(identitySimulationRemainingSeconds % 60).padStart(2, '0')}
                    </span>
                    <button
                      type="button"
                      data-testid="identity-simulation-end"
                      disabled={identitySimulationEnding}
                      onClick={() => void handleEndIdentitySimulation()}
                      className="rounded-md border border-rose-300 bg-white px-2.5 py-1 text-xs font-semibold text-rose-800 hover:bg-rose-100 disabled:opacity-50"
                    >
                      {resolveDesignerText(
                        identitySimulationEnding
                          ? DESIGNER_I18N.unified.identitySimulation.ending
                          : DESIGNER_I18N.unified.identitySimulation.end,
                        locale,
                      )}
                    </button>
                  </div>
                ) : (
                  <button
                    type="button"
                    data-testid="identity-simulation-dismiss"
                    onClick={() => setIdentitySimulation(null)}
                    className="rounded-md border border-rose-300 bg-white px-2.5 py-1 text-xs font-semibold text-rose-800 hover:bg-rose-100"
                  >
                    {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.dismiss, locale)}
                  </button>
                )}
              </div>
              <div className="mt-2 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.intersection, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.readOnly, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200">
                  {resolveDesignerText(
                    DESIGNER_I18N.unified.identitySimulation.noBusinessRecords,
                    locale,
                  )}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.exportOff, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.actionsOff, locale)}
                </span>
                {identitySimulationActive
                  ? rolePreviewSummary.map((summary) => (
                      <span
                        key={summary.nodeType}
                        className="rounded-full bg-white px-2 py-1 ring-1 ring-rose-200"
                      >
                        {rolePreviewNodeLabel(summary.nodeType, locale)} {summary.allowed}/
                        {summary.total}
                      </span>
                    ))
                  : null}
              </div>
            </div>
          ) : null}
          {syntheticPreview ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-violet-200 bg-violet-50 px-4 py-3 text-sm text-violet-950"
              data-testid="synthetic-preview-banner"
            >
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div>
                  <span className="font-semibold">
                    {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.title, locale)}
                  </span>
                  <span className="ml-2 text-xs text-violet-700">
                    {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.source, locale)}
                  </span>
                </div>
                <button
                  type="button"
                  data-testid="synthetic-preview-exit"
                  onClick={() => setSelectedRolePreviewPid('')}
                  className="rounded-md border border-violet-300 bg-white px-2.5 py-1 text-xs font-medium text-violet-800 hover:bg-violet-100"
                >
                  {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.exit, locale)}
                </button>
              </div>
              <div className="mt-2 flex flex-wrap gap-2 text-xs">
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-violet-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.isolated, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-violet-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.notPersisted, locale)}
                </span>
                <span className="rounded-full bg-white px-2 py-1 ring-1 ring-violet-200">
                  {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.actionsOff, locale)}
                </span>
                <span
                  className="rounded-full bg-white px-2 py-1 ring-1 ring-violet-200"
                  data-testid="synthetic-preview-record-count"
                >
                  {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.recordCount, locale, {
                    count: syntheticPreview.records.length,
                  })}
                </span>
              </div>
            </div>
          ) : null}
          {rolePreviewError && selectedTargetRolePid ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
              data-testid="role-preview-error"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.rolePreview.failed, locale, {
                error: rolePreviewError,
              })}
            </div>
          ) : null}
          {syntheticPreviewError && syntheticPreviewSelected ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
              data-testid="synthetic-preview-error"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.syntheticPreview.failed, locale, {
                error: syntheticPreviewError,
              })}
            </div>
          ) : null}
          {identitySimulationError ? (
            <div
              className="mx-auto mb-3 max-w-7xl rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-800"
              data-testid="identity-simulation-error"
            >
              {resolveDesignerText(DESIGNER_I18N.unified.identitySimulation.failed, locale, {
                error: identitySimulationError,
              })}
            </div>
          ) : null}
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
            {(selectedTargetRolePid && (rolePreviewLoading || rolePreviewError)) ||
            (syntheticPreviewSelected && (syntheticPreviewLoading || syntheticPreviewError)) ||
            (identitySimulationActive &&
              identitySimulationRemainingSeconds === 0 &&
              identitySimulationError) ? (
              <div
                className="grid min-h-64 place-items-center bg-white p-6 text-sm text-slate-500"
                data-testid={
                  identitySimulationActive && identitySimulationRemainingSeconds === 0
                    ? 'identity-simulation-fail-closed'
                    : syntheticPreviewSelected
                      ? 'synthetic-preview-fail-closed'
                      : 'role-preview-fail-closed'
                }
              >
                {identitySimulationActive && identitySimulationRemainingSeconds === 0
                  ? resolveDesignerText(
                      DESIGNER_I18N.unified.identitySimulation.safeFailure,
                      locale,
                    )
                  : syntheticPreviewSelected
                    ? resolveDesignerText(
                        syntheticPreviewLoading
                          ? DESIGNER_I18N.unified.syntheticPreview.safeLoading
                          : DESIGNER_I18N.unified.syntheticPreview.safeFailure,
                        locale,
                      )
                    : resolveDesignerText(
                        rolePreviewLoading
                          ? DESIGNER_I18N.unified.rolePreview.safeLoading
                          : DESIGNER_I18N.unified.rolePreview.safeFailure,
                        locale,
                      )}
              </div>
            ) : (
              <RecursiveBlockRenderer
                key={
                  syntheticPreview
                    ? `synthetic:${syntheticPreview.fixtureRevision}`
                    : effectiveRoleStructurePreview
                      ? identitySimulationActive
                        ? `identity:${identitySimulation.simulationPid}`
                        : `role:${effectiveRoleStructurePreview.targetRole.rolePid}`
                      : 'actor'
                }
                schema={previewDocument}
                runtimeServices={
                  effectiveRoleStructurePreview
                    ? roleStructurePreviewRuntimeServices
                    : syntheticPreviewRuntimeServices ?? defaultRuntimeExecutionServices
                }
                permissionEvaluator={rolePreviewPermissionEvaluator}
                interactionDisabled={Boolean(effectiveRoleStructurePreview || syntheticPreview)}
                previewInitialFormValues={syntheticPreview?.formValues}
                modelFields={
                  effectiveRoleStructurePreview || syntheticPreview
                    ? []
                    : document.modelCode
                      ? modelFieldsByModel[document.modelCode] ?? []
                      : []
                }
              />
            )}
          </div>
        </div>
      ) : (
        <DndContext
          sensors={sensors}
          collisionDetection={designerCollisionDetection}
          onDragStart={handleDragStart}
          onDragOver={handleDragOver}
          onDragEnd={handleDragEnd}
          onDragCancel={clearActiveDrag}
        >
          {!contextualReadOnly && availablePageTemplates.length > 0 ? (
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
                {availablePageTemplates.map((template) => (
                  <option key={template.id} value={template.id}>
                    {template.label}
                  </option>
                ))}
              </select>
            </div>
          ) : null}
          {!contextualReadOnly && multiSelectedIds.size >= 2 ? (
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
              canAddCustomField={!contextualRestricted && canAddBlock('field')}
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
              structuralReadOnly={contextualRestricted}
              canReorderBlock={
                contextualRestricted
                  ? (blockId) => {
                      const block = findBlockById(document.blocks, blockId)?.block;
                      return Boolean(
                        block
                        && (contextualReorderableTypes.has(block.blockType)
                          || contextualRelocatableTypes.has(block.blockType)),
                      );
                    }
                  : undefined
              }
              canResizeSpan={contextualRestricted ? canContextualResizeSpan : undefined}
              onSelect={handleCanvasSelect}
              onMoveBefore={handleMoveBefore}
              onPatchBlock={patchBlock}
              canDeleteBlock={canDeleteBlock}
              onDeleteBlock={handleDeleteBlock}
              onMarqueeSelect={handleMarqueeSelect}
              modelFields={
                document.modelCode ? modelFieldsByModel[document.modelCode] ?? [] : []
              }
            />
            <InspectorHost
              selectedBlock={selectedBlock}
              modelFields={selectedModelFields}
              editablePropertyPaths={
                contextualRestricted
                  ? (contextualEditablePropertyPaths?.[selectedBlock?.blockType ?? ''] ?? [])
                  : undefined
              }
              canDuplicateBlock={canDuplicateBlock(selectedBlockId)}
              onDuplicateBlock={handleDuplicateBlock}
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

function pointerToDotPath(pointer: string): string {
  return pointer
    .replace(/^\//, '')
    .split('/')
    .map((segment) => segment.replace(/~1/g, '/').replace(/~0/g, '~'))
    .join('.');
}

function localizedLabel(value: ModelFieldDefinition['label']): string {
  if (typeof value === 'string') return value;
  return value['zh-CN'] || value['en-US'] || Object.values(value)[0] || '';
}

function formatValidationSaveError(errorCount: number): string {
  return `Fix ${errorCount} validation issue${errorCount === 1 ? '' : 's'} before saving.`;
}

function resolveSaveErrorMessage(error: unknown, locale: string): string {
  const context = (error as { context?: unknown } | null)?.context;
  const policyToken =
    typeof context === 'string'
      ? context
      : context && typeof context === 'object' && 'reason' in context
        ? String((context as { reason?: unknown }).reason ?? '')
        : '';
  if (policyToken === 'authoring.policy.protected_semantic_invalid') {
    return resolveDesignerText(DESIGNER_I18N.unified.protectedSemanticInvalid, locale);
  }
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

function projectTemplateIntoGovernedRoot(
  current: PageSchemaV3,
  templateBlocks: DslBlockV3[],
  project: (block: DslBlockV3) => DslBlockV3,
): DslBlockV3[] | null {
  if (
    current.blocks.length !== 1 ||
    templateBlocks.length !== 1 ||
    current.blocks[0].blockType !== current.kind ||
    templateBlocks[0].blockType !== current.kind
  ) {
    return null;
  }
  const projected = project(templateBlocks[0]);
  const { authoringTemplateLineage: _rootLineage, ...projectedRootExtension } =
    projected.extension ?? {};
  const governedRootExtension = {
    ...(current.blocks[0].extension ?? {}),
    ...projectedRootExtension,
  };
  const governedRoot: DslBlockV3 = {
    ...current.blocks[0],
    ...projected,
    id: current.blocks[0].id,
    blockType: current.kind,
    extension: Object.keys(governedRootExtension).length ? governedRootExtension : undefined,
    blocks: projected.blocks ?? [],
  };
  if (!governedRoot.extension) delete governedRoot.extension;
  return [governedRoot];
}

/** Governed imports may replace design content, but never page identity, binding or ownership. */
function normalizeGovernedImport(
  current: PageSchemaV3,
  imported: PageSchemaV3,
  project: (block: DslBlockV3) => DslBlockV3,
  creatableTypes: Set<string>,
  removableTypes: Set<string>,
  reorderableTypes: Set<string>,
  relocatableTypes: Set<string>,
  pageKindSwitchEnabled: boolean,
): PageSchemaV3 | null {
  if (
    imported.kind === 'composite' ||
    (imported.kind !== current.kind && !pageKindSwitchEnabled) ||
    current.blocks.length !== 1 ||
    imported.blocks.length !== 1 ||
    current.blocks[0].blockType !== current.kind ||
    imported.blocks[0].blockType !== imported.kind ||
    !validatePageSchemaV3(imported).valid
  ) {
    return null;
  }

  const stableRootId = current.blocks[0].id;
  const importedRoot: DslBlockV3 = {
    ...imported.blocks[0],
    id: stableRootId,
    blockType: imported.kind,
  };
  const currentBlocks = indexImportBlocks(current.blocks);
  const importedBlocks = indexImportBlocks([importedRoot]);
  for (const [id, entry] of importedBlocks) {
    const existing = currentBlocks.get(id);
    if (
      existing &&
      existing.block.blockType !== entry.block.blockType &&
      id !== stableRootId
    ) {
      return null;
    }
    if (!existing && !creatableTypes.has(entry.block.blockType)) return null;
    if (
      existing &&
      existing.parentId !== entry.parentId &&
      id !== current.blocks[0].id &&
      !relocatableTypes.has(entry.block.blockType)
    ) {
      return null;
    }
  }
  for (const [id, entry] of currentBlocks) {
    if (!importedBlocks.has(id) && !removableTypes.has(entry.block.blockType)) return null;
  }
  if (hasUnauthorizedExistingSiblingReorder(currentBlocks, importedBlocks, reorderableTypes)) {
    return null;
  }

  const projectImportedBlock = (block: DslBlockV3): DslBlockV3 => {
    const projected = project({ ...block, blocks: undefined });
    const existing = currentBlocks.get(block.id)?.block;
    const projectedExtension = { ...(projected.extension ?? {}) };
    if (existing) {
      delete projectedExtension.authoringTemplateLineage;
      delete projectedExtension.authoringCopyLineage;
    }
    if (existing?.extension?.authoringTemplateLineage) {
      projectedExtension.authoringTemplateLineage =
        existing.extension.authoringTemplateLineage;
    }
    if (existing?.extension?.authoringCopyLineage) {
      projectedExtension.authoringCopyLineage =
        existing.extension.authoringCopyLineage;
    }
    const governedExtension = {
      ...(existing?.extension ?? {}),
      ...projectedExtension,
    };
    const normalizedBlock: DslBlockV3 = {
      ...(existing ?? {}),
      ...projected,
      id: block.id,
      blockType: block.blockType,
      extension: Object.keys(governedExtension).length ? governedExtension : undefined,
      blocks: block.blocks?.map(projectImportedBlock),
    };
    if (!normalizedBlock.extension) delete normalizedBlock.extension;
    return normalizedBlock;
  };
  const projectedRoot = projectImportedBlock(importedRoot);
  const normalized: PageSchemaV3 = {
    ...imported,
    id: current.id,
    pageKey: current.pageKey,
    modelCode: current.modelCode,
    title: current.title,
    layout: current.layout,
    extension: current.extension,
    blocks: [{ ...projectedRoot, id: current.blocks[0].id, blockType: imported.kind }],
  };
  return validatePageSchemaV3(normalized).valid ? normalized : null;
}

type ImportBlockEntry = { block: DslBlockV3; parentId: string | null; siblingIndex: number };

function indexImportBlocks(blocks: DslBlockV3[] | undefined): Map<string, ImportBlockEntry> {
  const indexed = new Map<string, ImportBlockEntry>();
  const visit = (items: DslBlockV3[] | undefined, parentId: string | null) => {
    for (const [siblingIndex, block] of (items ?? []).entries()) {
      indexed.set(block.id, { block, parentId, siblingIndex });
      visit(block.blocks, block.id);
    }
  };
  visit(blocks, null);
  return indexed;
}

function hasUnauthorizedExistingSiblingReorder(
  current: Map<string, ImportBlockEntry>,
  imported: Map<string, ImportBlockEntry>,
  reorderableTypes: Set<string>,
): boolean {
  const parentIds = new Set([...current.values()].map((entry) => entry.parentId));
  for (const parentId of parentIds) {
    const currentOrder = [...current.entries()]
      .filter(([id, entry]) => entry.parentId === parentId && imported.get(id)?.parentId === parentId)
      .sort((left, right) => left[1].siblingIndex - right[1].siblingIndex)
      .map(([id]) => id);
    const importedOrder = [...imported.entries()]
      .filter(([id, entry]) => entry.parentId === parentId && current.get(id)?.parentId === parentId)
      .sort((left, right) => left[1].siblingIndex - right[1].siblingIndex)
      .map(([id]) => id);
    if (
      currentOrder.join('\0') !== importedOrder.join('\0') &&
      importedOrder.some((id) => !reorderableTypes.has(imported.get(id)!.block.blockType))
    ) {
      return true;
    }
  }
  return false;
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

function insertBlockAfterTarget(
  blocks: DslBlockV3[],
  targetBlockId: string,
  nextBlock: DslBlockV3,
): DslBlockV3[] {
  const directIndex = blocks.findIndex((block) => block.id === targetBlockId);
  if (directIndex >= 0) {
    const next = [...blocks];
    next.splice(directIndex + 1, 0, nextBlock);
    return next;
  }
  let changed = false;
  const next = blocks.map((block) => {
    if (!block.blocks?.length) return block;
    const children = insertBlockAfterTarget(block.blocks, targetBlockId, nextBlock);
    if (children === block.blocks) return block;
    changed = true;
    return { ...block, blocks: children };
  });
  return changed ? next : blocks;
}

function duplicateBlockSubtree(source: DslBlockV3, usedIds: Set<string>): DslBlockV3 {
  const id = createUniqueBlockId(toStableBlockId(source.id, 'copy') || 'block_copy', usedIds);
  usedIds.add(id);
  return {
    ...source,
    id,
    extension: {
      ...(source.extension ?? {}),
      authoringCopyLineage: { sourceBlockId: source.id },
    },
    blocks: source.blocks?.map((child) => duplicateBlockSubtree(child, usedIds)),
  };
}

function duplicateSubtreeIsGoverned(
  block: DslBlockV3,
  creatableTypes: Set<string>,
  editablePropertyPaths: Record<string, string[]>,
): boolean {
  return (
    creatableTypes.has(block.blockType)
    && (editablePropertyPaths[block.blockType] ?? []).includes(AUTHORING_COPY_LINEAGE_PATH)
    && (block.blocks ?? []).every((child) =>
      duplicateSubtreeIsGoverned(child, creatableTypes, editablePropertyPaths),
    )
  );
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

function templateDescendantsAreGoverned(
  blocks: DslBlockV3[] | undefined,
  creatableTypes: Set<string>,
): boolean {
  return (blocks ?? []).every(
    (block) =>
      creatableTypes.has(block.blockType) &&
      templateDescendantsAreGoverned(block.blocks, creatableTypes),
  );
}

function isDotPathAllowed(dotPath: string, capabilityPointers: string[]): boolean {
  const pointer = `/${dotPath
    .split('.')
    .map((segment) => segment.replace(/~/g, '~0').replace(/\//g, '~1'))
    .join('/')}`;
  return capabilityPointers.some(
    (capabilityPointer) =>
      pointer === capabilityPointer || pointer.startsWith(`${capabilityPointer}/`),
  );
}

function rolePreviewNodeLabel(
  nodeType: AuthoringRoleStructureDecision['nodeType'],
  locale: string,
): string {
  switch (nodeType) {
    case 'MENU':
      return resolveDesignerText(DESIGNER_I18N.unified.rolePreview.node.menu, locale);
    case 'FIELD':
      return resolveDesignerText(DESIGNER_I18N.unified.rolePreview.node.field, locale);
    case 'ACTION':
      return resolveDesignerText(DESIGNER_I18N.unified.rolePreview.node.action, locale);
    default:
      return resolveDesignerText(DESIGNER_I18N.unified.rolePreview.node.block, locale);
  }
}
