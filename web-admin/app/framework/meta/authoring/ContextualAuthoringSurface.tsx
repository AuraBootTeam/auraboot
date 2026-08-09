import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate } from 'react-router';
import {
  AlertTriangle,
  ChevronRight,
  Eye,
  GitCompare,
  Layers3,
  LockKeyhole,
  Loader2,
  MousePointer2,
  PanelLeft,
  PanelRight,
  Plus,
  Save,
  Settings2,
  ShieldCheck,
  X,
} from 'lucide-react';
import { usePermission } from '~/contexts/AuthContext';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import {
  activateAuthoringPreviewGuard,
  AUTHORING_WRITE_BLOCKED_EVENT,
} from '~/shared/services/http-client';
import {
  applyAuthoringPatch,
  createAuthoringHandoff,
  loadAuthoringSession,
  loadAuthoringCapabilities,
  openAuthoringSession,
  submitAuthoringSession,
  takeoverAuthoringWriterLease,
} from './authoringService';
import { AuthoringWriterLeaseNotice } from './AuthoringWriterLeaseNotice';
import { storeAuthoringConflictTransfer } from './authoringConflictTransfer';
import type {
  AuthoringMode,
  AuthoringNode,
  AuthoringSession,
  CapabilityManifest,
  CapabilityRegistry,
  ContextualAuthoringSurfaceProps,
  PendingAuthoringEdit,
  PropertyCapability,
} from './types';
import type { UnifiedSchema } from '~/framework/meta/schemas/types';

type HandoffIntent = 'PAGE_STRUCTURE' | 'NEW_PAGE' | 'MENU_STRUCTURE';

interface ExplainState {
  intent: HandoffIntent;
  title: string;
  reason: string;
  propertyPath?: string;
}

interface ContextualConflictState {
  baseRevision: number;
  latestSession: AuthoringSession;
  baseSnapshot: Record<string, unknown>;
  mineSnapshot: Record<string, unknown>;
  pendingCount: number;
}

export function ContextualAuthoringSurface({
  schema,
  recordPid,
  children,
  renderRuntime,
}: ContextualAuthoringSurfaceProps) {
  const navigate = useNavigate();
  const canReadDesigner = usePermission('meta.designer.read');
  const canManageDesigner = usePermission('meta.designer.update');
  const canAdministerDesigner = usePermission('meta.designer.admin');
  const canConfigure = canReadDesigner && canManageDesigner;
  const [session, setSession] = useState<AuthoringSession | null>(null);
  const [workingSchema, setWorkingSchema] = useState<UnifiedSchema>(schema);
  const [pendingEdits, setPendingEdits] = useState<Map<string, PendingAuthoringEdit>>(
    () => new Map(),
  );
  const [saving, setSaving] = useState(false);
  const [submitting, setSubmitting] = useState(false);
  const [diffOpen, setDiffOpen] = useState(false);
  const [stale, setStale] = useState(false);
  const [capabilities, setCapabilities] = useState<CapabilityRegistry | null>(null);
  const [opening, setOpening] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [mode, setMode] = useState<AuthoringMode>('select');
  const [altPressed, setAltPressed] = useState(false);
  const [selectedId, setSelectedId] = useState(schema.id);
  const [outlineOpen, setOutlineOpen] = useState(false);
  const [inspectorOpen, setInspectorOpen] = useState(false);
  const [explain, setExplain] = useState<ExplainState | null>(null);
  const [handoffPending, setHandoffPending] = useState(false);
  const [writeBlocked, setWriteBlocked] = useState(false);
  const [leaseTakeoverPending, setLeaseTakeoverPending] = useState(false);
  const [contextualConflict, setContextualConflict] = useState<ContextualConflictState | null>(
    null,
  );
  const runtimeRootRef = useRef<HTMLDivElement | null>(null);
  const entryScrollRef = useRef({ x: 0, y: 0 });
  const returnResumeAttemptedRef = useRef(false);

  const rootNode = useMemo(() => buildAuthoringTree(workingSchema), [workingSchema]);
  const nodeIndex = useMemo(() => indexTree(rootNode), [rootNode]);
  const selectedNode = nodeIndex.byId.get(selectedId) ?? rootNode;
  const effectiveMode: AuthoringMode = altPressed
    ? mode === 'select'
      ? 'interact'
      : 'select'
    : mode;
  const manifestByType = useMemo(
    () => new Map(capabilities?.manifests.map((manifest) => [manifest.blockType, manifest]) ?? []),
    [capabilities],
  );
  const authoringReadOnly =
    Boolean(contextualConflict) || !isAuthoringSessionWritable(session, canConfigure);
  const activeSessionPid = session?.sessionPid;
  const activeSessionRevision = session?.revision;

  useEffect(() => {
    if (returnResumeAttemptedRef.current) return;
    const resume = readAuthoringReturnRequest();
    if (!resume) return;
    returnResumeAttemptedRef.current = true;
    if (!canConfigure) {
      setError('当前账号无权恢复配置会话');
      return;
    }

    let cancelled = false;
    setOpening(true);
    setError(null);
    void Promise.all([loadAuthoringSession(resume.sessionPid), loadAuthoringCapabilities()])
      .then(([restored, registry]) => {
        if (restored.pagePid !== schema.id) {
          throw new Error('返回的配置会话与当前页面不一致');
        }
        if (cancelled) return;
        const restoredSchema = schemaFromSnapshot(schema, restored.snapshot);
        const restoredTree = buildAuthoringTree(restoredSchema);
        const restoredIndex = indexTree(restoredTree);
        const contextSelection = contextSelectionId(restored.interactionContext);
        const selected = [resume.focusBlockId, contextSelection, schema.id].find(
          (candidate) => candidate && restoredIndex.byId.has(candidate),
        );
        const scroll = contextScroll(restored.interactionContext);
        entryScrollRef.current = scroll;
        setSession(restored);
        setWorkingSchema(restoredSchema);
        setPendingEdits(new Map());
        setStale(false);
        setContextualConflict(null);
        setCapabilities(registry);
        setSelectedId(selected ?? schema.id);
        setOutlineOpen(true);
        setInspectorOpen(true);
        clearAuthoringReturnParams();
        requestAnimationFrame(() => {
          window.scrollTo(scroll.x, scroll.y);
          if (selected && selected !== schema.id) {
            runtimeRootRef.current
              ?.querySelector<HTMLElement>(`[data-aura-block-id="${cssEscape(selected)}"]`)
              ?.scrollIntoView({ block: 'center' });
          }
        });
      })
      .catch((resumeError) => {
        if (!cancelled) {
          setError(resumeError instanceof Error ? resumeError.message : '无法恢复返回的配置会话');
        }
      })
      .finally(() => {
        if (!cancelled) setOpening(false);
      });

    return () => {
      cancelled = true;
    };
  }, [canConfigure, schema]);

  const enter = useCallback(async () => {
    if (!canConfigure || opening) return;
    setOpening(true);
    setError(null);
    entryScrollRef.current = { x: window.scrollX, y: window.scrollY };
    try {
      const interactionContext = captureInteractionContext(recordPid, schema.id);
      const [opened, registry] = await Promise.all([
        openAuthoringSession(schema.id, interactionContext),
        loadAuthoringCapabilities(),
      ]);
      setSession(opened);
      setWorkingSchema(schemaFromSnapshot(schema, opened.snapshot));
      setPendingEdits(new Map());
      setStale(false);
      setContextualConflict(null);
      setCapabilities(registry);
      setSelectedId(schema.id);
    } catch (enterError) {
      setError(enterError instanceof Error ? enterError.message : '无法进入配置模式');
    } finally {
      setOpening(false);
    }
  }, [canConfigure, opening, recordPid, schema]);

  const exit = useCallback(() => {
    setSession(null);
    setWorkingSchema(schema);
    setPendingEdits(new Map());
    setStale(false);
    setContextualConflict(null);
    setCapabilities(null);
    setExplain(null);
    setError(null);
    setWriteBlocked(false);
    setOutlineOpen(false);
    setInspectorOpen(false);
    requestAnimationFrame(() =>
      window.scrollTo(entryScrollRef.current.x, entryScrollRef.current.y),
    );
  }, [schema]);

  useEffect(() => {
    if (!session) return;
    return activateAuthoringPreviewGuard(session.sessionPid);
  }, [session]);

  useEffect(() => {
    if (!activeSessionPid) return;
    let cancelled = false;
    const interval = window.setInterval(() => {
      void loadAuthoringSession(activeSessionPid)
        .then((latest) => {
          if (cancelled || latest.revision < (activeSessionRevision ?? -1)) return;
          setSession(latest);
          setWorkingSchema(materializePendingSchema(schema, latest.snapshot, pendingEdits));
          const conflicts = conflictingPendingEdits(latest.snapshot, pendingEdits);
          if (conflicts.length > 0) {
            setContextualConflict(
              createContextualConflictState(
                latest,
                pendingEdits,
                Math.min(...conflicts.map((edit) => edit.baseRevision)),
              ),
            );
            setStale(true);
            setError(null);
          }
        })
        .catch(() => {
          // Background lease/revision refresh must not replace the foreground error state.
        });
    }, 15_000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [activeSessionPid, activeSessionRevision, pendingEdits, schema]);

  useEffect(() => {
    if (!session) return;
    const handleBlocked = () => {
      setWriteBlocked(true);
      window.setTimeout(() => setWriteBlocked(false), 4000);
    };
    window.addEventListener(AUTHORING_WRITE_BLOCKED_EVENT, handleBlocked);
    return () => window.removeEventListener(AUTHORING_WRITE_BLOCKED_EVENT, handleBlocked);
  }, [session]);

  useEffect(() => {
    if (!session) return;
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltPressed(true);
      if (event.altKey && event.key === 'ArrowUp') {
        event.preventDefault();
        const parentId = nodeIndex.byId.get(selectedId)?.parentId;
        if (parentId) setSelectedId(parentId);
      }
      if (event.key === 'Escape') {
        if (explain) setExplain(null);
        else if (inspectorOpen) setInspectorOpen(false);
        else if (outlineOpen) setOutlineOpen(false);
        else if (selectedId !== schema.id) setSelectedId(schema.id);
        else exit();
      }
    };
    const keyUp = (event: KeyboardEvent) => {
      if (event.key === 'Alt') setAltPressed(false);
    };
    window.addEventListener('keydown', keyDown);
    window.addEventListener('keyup', keyUp);
    return () => {
      window.removeEventListener('keydown', keyDown);
      window.removeEventListener('keyup', keyUp);
    };
  }, [exit, explain, inspectorOpen, nodeIndex.byId, outlineOpen, schema.id, selectedId, session]);

  useEffect(() => {
    const root = runtimeRootRef.current;
    if (!root || !session) return;
    const selectable = root.querySelectorAll<HTMLElement>(
      '[data-aura-block-id], [data-block-id], [data-authoring-node-id]',
    );
    selectable.forEach((element) => {
      element.classList.add('outline', 'outline-1', 'outline-blue-300', 'outline-offset-2');
      if (effectiveMode === 'select') element.classList.add('cursor-crosshair');
      const sourceId =
        element.dataset.authoringNodeId ||
        element.dataset.auraBlockId ||
        element.dataset.blockId ||
        element.dataset.auraElementId;
      if (sourceId === selectedNode.sourceId) {
        element.classList.remove('outline-1', 'outline-blue-300');
        element.classList.add('outline-2', 'outline-blue-600');
      }
    });
    return () => {
      selectable.forEach((element) =>
        element.classList.remove(
          'outline',
          'outline-1',
          'outline-2',
          'outline-blue-300',
          'outline-blue-600',
          'outline-offset-2',
          'cursor-crosshair',
        ),
      );
    };
  }, [effectiveMode, selectedNode.sourceId, session]);

  const selectNode = useCallback(
    (nodeId: string) => {
      setSelectedId(nodeId);
      setInspectorOpen(true);
      const sourceId = nodeIndex.byId.get(nodeId)?.sourceId;
      if (sourceId) {
        runtimeRootRef.current
          ?.querySelector<HTMLElement>(
            `[data-authoring-node-id="${cssEscape(sourceId)}"], [data-aura-block-id="${cssEscape(sourceId)}"], [data-block-id="${cssEscape(sourceId)}"]`,
          )
          ?.scrollIntoView({ block: 'center', behavior: 'smooth' });
      }
    },
    [nodeIndex.byId],
  );

  const handleRuntimeClick = (event: React.MouseEvent<HTMLDivElement>) => {
    if (effectiveMode === 'select') {
      event.preventDefault();
      event.stopPropagation();
      const element = (event.target as HTMLElement).closest<HTMLElement>(
        '[data-authoring-node-id], [data-aura-block-id], [data-block-id]',
      );
      const sourceId =
        element?.dataset.authoringNodeId ||
        element?.dataset.auraBlockId ||
        element?.dataset.blockId;
      setSelectedId(sourceId ? (nodeIndex.bySourceId.get(sourceId)?.id ?? schema.id) : schema.id);
      setInspectorOpen(true);
      return;
    }

    if (!isSafePreviewInteraction(event.target as HTMLElement)) {
      event.preventDefault();
      event.stopPropagation();
      setWriteBlocked(true);
      window.setTimeout(() => setWriteBlocked(false), 4000);
    }
  };

  const createHandoff = async () => {
    if (
      contextualConflict ||
      !isAuthoringSessionWritable(session, canConfigure) ||
      !explain ||
      handoffPending
    )
      return;
    setHandoffPending(true);
    setError(null);
    try {
      const handoff = await createAuthoringHandoff(
        session.sessionPid,
        session.revision,
        explain.intent,
        selectedNode.kind === 'page' ? undefined : selectedNode.sourceId,
        explain.propertyPath,
      );
      navigate(`${handoff.targetRoute}?contextId=${encodeURIComponent(handoff.contextId)}`);
    } catch (handoffError) {
      setError(handoffError instanceof Error ? handoffError.message : '无法移交到应用设计中心');
      setExplain(null);
    } finally {
      setHandoffPending(false);
    }
  };

  const stageEdit = useCallback(
    (node: AuthoringNode, property: PropertyCapability, value: unknown, remove = false) => {
      if (contextualConflict || !isAuthoringSessionWritable(session, canConfigure)) return;
      const manifestChecksum = manifestByType.get(node.blockType)?.checksum;
      if (!manifestChecksum) {
        setError(`未找到 ${node.blockType} 的能力清单，无法保存该变更`);
        return;
      }
      const previousValue = readSnapshotProperty(
        session.snapshot,
        node.sourceId,
        property.propertyPath,
      );
      const key = `${node.sourceId}:${property.propertyPath}`;
      setPendingEdits((current) => {
        const next = new Map(current);
        const existing = current.get(key);
        const baseValue = existing?.previousValue ?? previousValue;
        const baseRevision = existing?.baseRevision ?? session.revision;
        const operation = remove ? 'REMOVE' : baseValue === undefined ? 'ADD' : 'REPLACE';
        if ((remove && baseValue === undefined) || (!remove && valuesEqual(value, baseValue))) {
          next.delete(key);
        } else {
          next.set(key, {
            key,
            baseRevision,
            blockId: node.sourceId,
            blockLabel: node.label,
            manifestChecksum,
            property,
            operation,
            previousValue: baseValue,
            value,
          });
        }
        setWorkingSchema(materializePendingSchema(schema, session.snapshot, next));
        return next;
      });
      setError(null);
      setStale(false);
    },
    [canConfigure, contextualConflict, manifestByType, schema, session],
  );

  const saveChanges = useCallback(async () => {
    if (
      !isAuthoringSessionWritable(session, canConfigure) ||
      contextualConflict ||
      saving ||
      pendingEdits.size === 0 ||
      session.state !== 'ACTIVE'
    )
      return;
    setSaving(true);
    setError(null);
    let currentSession = session;
    const remaining = new Map(pendingEdits);
    try {
      const conflictingEdits = conflictingPendingEdits(currentSession.snapshot, remaining);
      if (conflictingEdits.length > 0) {
        setContextualConflict(
          createContextualConflictState(
            currentSession,
            remaining,
            Math.min(...conflictingEdits.map((edit) => edit.baseRevision)),
          ),
        );
        setStale(true);
        setError(null);
        return;
      }
      for (const edit of pendingEdits.values()) {
        const latestValue = readSnapshotProperty(
          currentSession.snapshot,
          edit.blockId,
          edit.property.propertyPath,
        );
        const mineValue = edit.operation === 'REMOVE' ? undefined : edit.value;
        if (valuesEqual(latestValue, mineValue)) {
          remaining.delete(edit.key);
          setPendingEdits(new Map(remaining));
          continue;
        }
        const result = await applyAuthoringPatch(
          currentSession.sessionPid,
          currentSession.revision,
          edit.blockId,
          edit.property.propertyPath,
          edit.operation,
          edit.value,
          edit.manifestChecksum,
        );
        currentSession = result.session;
        remaining.delete(edit.key);
        setSession(currentSession);
        setPendingEdits(new Map(remaining));
      }
      setWorkingSchema(schemaFromSnapshot(schema, currentSession.snapshot));
      setStale(false);
    } catch (saveFailure) {
      setPendingEdits(new Map(remaining));
      setWorkingSchema(materializePendingSchema(schema, currentSession.snapshot, remaining));
      setStale(true);
      let latestSession: AuthoringSession | null = null;
      try {
        latestSession = await loadAuthoringSession(currentSession.sessionPid);
      } catch {
        // Keep the original failure when the conflict probe cannot refresh.
      }
      if (
        latestSession &&
        latestSession.revision > currentSession.revision &&
        (!latestSession.writerLease || latestSession.writerLease.status === 'OWNED')
      ) {
        setSession(latestSession);
        setContextualConflict(
          createContextualConflictState(
            latestSession,
            remaining,
            remaining.size > 0
              ? Math.min(...[...remaining.values()].map((edit) => edit.baseRevision))
              : currentSession.revision,
          ),
        );
        setError(null);
      } else {
        setSession(currentSession);
        setError(
          saveFailure instanceof Error
            ? `${saveFailure.message}；本地未保存变更已保留`
            : '保存失败；本地未保存变更已保留',
        );
      }
    } finally {
      setSaving(false);
    }
  }, [canConfigure, contextualConflict, pendingEdits, saving, schema, session]);

  const continueConflictInStudio = useCallback(() => {
    if (!contextualConflict) return;
    const contextId = storeAuthoringConflictTransfer({
      sessionPid: contextualConflict.latestSession.sessionPid,
      changeSetPid: contextualConflict.latestSession.changeSetPid,
      pagePid: contextualConflict.latestSession.pagePid,
      baseRevision: contextualConflict.baseRevision,
      baseSnapshot: contextualConflict.baseSnapshot,
      mineSnapshot: contextualConflict.mineSnapshot,
    });
    navigate(
      `/unified-designer?authoringSession=${encodeURIComponent(contextualConflict.latestSession.sessionPid)}&conflictContext=${encodeURIComponent(contextId)}`,
    );
  }, [contextualConflict, navigate]);

  const refreshDraft = useCallback(async () => {
    if (!canConfigure || !session || contextualConflict) return;
    setError(null);
    try {
      const latest = await loadAuthoringSession(session.sessionPid);
      setSession(latest);
      setWorkingSchema(materializePendingSchema(schema, latest.snapshot, pendingEdits));
      setStale(false);
    } catch (refreshFailure) {
      setError(refreshFailure instanceof Error ? refreshFailure.message : '无法刷新配置草稿');
    }
  }, [canConfigure, contextualConflict, pendingEdits, schema, session]);

  const submitForReview = useCallback(async () => {
    if (
      !isAuthoringSessionWritable(session, canConfigure) ||
      contextualConflict ||
      pendingEdits.size > 0 ||
      submitting ||
      session.state !== 'ACTIVE'
    )
      return;
    setSubmitting(true);
    setError(null);
    try {
      await submitAuthoringSession(session.sessionPid, session.revision);
      const latest = await loadAuthoringSession(session.sessionPid);
      setSession(latest);
      setWorkingSchema(schemaFromSnapshot(schema, latest.snapshot));
    } catch (submitFailure) {
      setError(submitFailure instanceof Error ? submitFailure.message : '无法提交评审');
    } finally {
      setSubmitting(false);
    }
  }, [canConfigure, contextualConflict, pendingEdits.size, schema, session, submitting]);

  const takeoverWriterLease = useCallback(
    async (reason: string) => {
      if (!canAdministerDesigner || !session || leaseTakeoverPending) return;
      setLeaseTakeoverPending(true);
      setError(null);
      try {
        const taken = await takeoverAuthoringWriterLease(
          session.sessionPid,
          session.revision,
          reason,
        );
        setSession(taken);
        setWorkingSchema(materializePendingSchema(schema, taken.snapshot, pendingEdits));
        if (pendingEdits.size > 0 && taken.revision > session.revision) {
          setContextualConflict(
            createContextualConflictState(
              taken,
              pendingEdits,
              Math.min(...[...pendingEdits.values()].map((edit) => edit.baseRevision)),
            ),
          );
          setStale(true);
        } else {
          setContextualConflict(null);
          setStale(false);
        }
      } catch (takeoverFailure) {
        setError(
          takeoverFailure instanceof Error ? takeoverFailure.message : '无法接管 ChangeSet 编辑权',
        );
      } finally {
        setLeaseTakeoverPending(false);
      }
    },
    [canAdministerDesigner, leaseTakeoverPending, pendingEdits, schema, session],
  );

  if (!session) {
    return (
      <div className="relative" data-testid="contextual-authoring-runtime">
        {children}
        {canConfigure ? (
          <button
            type="button"
            onClick={enter}
            disabled={opening}
            className="border-border-strong bg-panel text-text hover:bg-hover fixed right-6 bottom-6 z-30 inline-flex min-h-11 items-center gap-2 rounded-full border px-4 py-2 text-sm font-semibold shadow-lg disabled:cursor-wait disabled:opacity-70"
            data-testid="contextual-authoring-enter"
          >
            <Settings2 className="h-4 w-4" />
            {opening ? '正在进入配置模式…' : '配置此页'}
          </button>
        ) : null}
        {error ? (
          <div
            role="alert"
            className="border-status-red bg-status-red-bg fixed right-6 bottom-20 z-30 max-w-sm rounded-lg border px-4 py-3 text-sm text-red-800 shadow-lg"
          >
            {error}
          </div>
        ) : null}
      </div>
    );
  }

  const breadcrumbs = ancestorChain(selectedNode, nodeIndex.byId);
  const selectedManifest = manifestByType.get(selectedNode.blockType);

  return (
    <section
      className="border-border bg-subtle relative flex min-h-[calc(100vh-4rem)] flex-col overflow-hidden border"
      data-testid="contextual-authoring-surface"
      data-mode={effectiveMode}
      data-read-only={authoringReadOnly ? 'true' : 'false'}
    >
      <AuthoringToolbar
        mode={mode}
        temporaryMode={altPressed}
        onModeChange={setMode}
        onOutline={() => setOutlineOpen(true)}
        onInspector={() => setInspectorOpen(true)}
        onNewPage={() =>
          setExplain({
            intent: 'NEW_PAGE',
            title: '在应用设计中心创建页面',
            reason: '新页面会改变页面树、路由和发布资源，不属于当前页面的局部展示调整。',
          })
        }
        onExit={exit}
      />

      {writeBlocked ? (
        <div
          role="status"
          className="border-status-amber bg-status-amber-bg text-status-amber mx-3 mt-3 flex items-center gap-2 rounded-md border px-3 py-2 text-sm"
          data-testid="authoring-write-blocked"
        >
          <LockKeyhole className="h-4 w-4" />
          已拦截真实业务写入；交互预览只保留本地状态。
        </div>
      ) : null}
      {!canConfigure ? (
        <div
          role="alert"
          className="border-status-amber bg-status-amber-bg text-status-amber mx-3 mt-3 flex items-start gap-2 rounded-md border px-3 py-2 text-sm"
          data-testid="authoring-permission-revoked"
        >
          <LockKeyhole className="mt-0.5 h-4 w-4 shrink-0" />
          <span>
            配置权限已收回，当前会话已即时转为只读。浏览器中的未保存差异仍会保留，但不会保存、提交或移交；可退出配置模式，或在权限恢复后继续。
          </span>
        </div>
      ) : null}
      {canConfigure ? (
        <div className="mx-3 mt-3">
          <AuthoringWriterLeaseNotice
            lease={session.writerLease}
            canTakeover={canAdministerDesigner}
            pending={leaseTakeoverPending}
            onTakeover={takeoverWriterLease}
          />
        </div>
      ) : null}
      {contextualConflict ? (
        <div
          className="border-status-amber bg-status-amber-bg text-status-amber mx-3 mt-3 flex flex-wrap items-center justify-between gap-3 rounded-md border px-3 py-3 text-sm"
          data-testid="contextual-authoring-conflict"
          role="alert"
        >
          <div className="flex min-w-0 items-start gap-2">
            <GitCompare className="mt-0.5 h-4 w-4 shrink-0" />
            <div>
              <div className="font-semibold">并发变更必须在应用设计中心裁决</div>
              <div className="mt-1 text-xs">
                Base r{contextualConflict.baseRevision} / Latest r
                {contextualConflict.latestSession.revision}；已保留{' '}
                {contextualConflict.pendingCount} 项 Mine。原地配置不会刷新后直接覆盖 Latest。
              </div>
            </div>
          </div>
          <button
            type="button"
            onClick={continueConflictInStudio}
            className="min-h-9 rounded-md bg-amber-700 px-3 text-sm font-semibold text-white hover:bg-amber-800"
            data-testid="contextual-authoring-conflict-studio"
          >
            查看 Base / Mine / Latest
          </button>
        </div>
      ) : null}
      {error ? (
        <div
          role="alert"
          className="border-status-red bg-status-red-bg mx-3 mt-3 rounded-md border px-3 py-2 text-sm text-red-800"
        >
          {error}
        </div>
      ) : null}

      <div className="relative flex min-h-0 flex-1 overflow-hidden">
        {(outlineOpen || inspectorOpen) && (
          <button
            type="button"
            aria-label="关闭侧栏"
            onClick={() => {
              setOutlineOpen(false);
              setInspectorOpen(false);
            }}
            className="absolute inset-0 z-30 bg-slate-950/20 lg:hidden"
          />
        )}
        <OutlinePanel
          root={rootNode}
          selectedId={selectedNode.id}
          open={outlineOpen}
          onClose={() => setOutlineOpen(false)}
          onSelect={selectNode}
        />

        <main className="min-w-0 flex-1 overflow-auto bg-slate-100 p-2 sm:p-4">
          <div className="border-border bg-panel mb-3 flex min-h-9 flex-wrap items-center gap-1 rounded-md border px-3 py-1.5 text-xs text-slate-500">
            {breadcrumbs.map((node, index) => (
              <React.Fragment key={node.id}>
                {index > 0 ? <ChevronRight className="h-3.5 w-3.5" /> : null}
                <button
                  type="button"
                  className={
                    node.id === selectedNode.id
                      ? 'font-semibold text-blue-700'
                      : 'hover:text-slate-900'
                  }
                  onClick={() => selectNode(node.id)}
                >
                  {node.label}
                </button>
              </React.Fragment>
            ))}
          </div>
          <div
            ref={runtimeRootRef}
            className="border-border bg-panel rounded-lg border shadow-sm"
            onClickCapture={handleRuntimeClick}
            onSubmitCapture={(event) => {
              event.preventDefault();
              event.stopPropagation();
              setWriteBlocked(true);
            }}
            data-testid="contextual-authoring-canvas"
          >
            {renderRuntime ? renderRuntime(workingSchema) : children}
          </div>
        </main>

        <InspectorPanel
          node={selectedNode}
          manifest={selectedManifest}
          session={session}
          readOnly={authoringReadOnly}
          open={inspectorOpen}
          onClose={() => setInspectorOpen(false)}
          onHandoff={(property) =>
            setExplain({
              intent: 'PAGE_STRUCTURE',
              title: '进入应用设计中心',
              reason: explainHandoffReason(selectedNode, property),
              propertyPath: property?.propertyPath,
            })
          }
          onEdit={stageEdit}
        />
      </div>

      <ChangeDock
        session={session}
        edits={[...pendingEdits.values()]}
        saving={saving}
        submitting={submitting}
        stale={stale}
        readOnly={authoringReadOnly}
        readOnlyLabel={authoringReadOnlyLabel(session, canConfigure, Boolean(contextualConflict))}
        onDiff={() => setDiffOpen(true)}
        onSave={saveChanges}
        onRefresh={refreshDraft}
        onSubmit={submitForReview}
      />
      {diffOpen ? (
        <DiffDialog edits={[...pendingEdits.values()]} onClose={() => setDiffOpen(false)} />
      ) : null}
      {explain ? (
        <ExplainDialog
          state={explain}
          node={selectedNode}
          pending={handoffPending}
          onCancel={() => setExplain(null)}
          onContinue={createHandoff}
        />
      ) : null}
    </section>
  );
}

function AuthoringToolbar({
  mode,
  temporaryMode,
  onModeChange,
  onOutline,
  onInspector,
  onNewPage,
  onExit,
}: {
  mode: AuthoringMode;
  temporaryMode: boolean;
  onModeChange: (mode: AuthoringMode) => void;
  onOutline: () => void;
  onInspector: () => void;
  onNewPage: () => void;
  onExit: () => void;
}) {
  return (
    <header className="border-border bg-panel sticky top-0 z-20 flex min-h-14 flex-wrap items-center gap-2 border-b px-3 py-2">
      <div className="mr-auto flex min-w-0 items-center gap-2">
        <ShieldCheck className="h-5 w-5 shrink-0 text-blue-600" />
        <div className="min-w-0">
          <div className="truncate text-sm font-semibold text-slate-900">配置模式</div>
          <div className="truncate text-xs text-emerald-700">
            安全编辑写入隔离 ChangeSet；不会写入业务数据
          </div>
        </div>
      </div>
      <button
        type="button"
        className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50 lg:hidden"
        onClick={onOutline}
      >
        <PanelLeft className="h-4 w-4" />
        大纲
      </button>
      <div
        className="border-border flex rounded-md border bg-slate-50 p-0.5"
        role="group"
        aria-label="配置模式"
      >
        <ModeButton active={mode === 'select'} onClick={() => onModeChange('select')}>
          <MousePointer2 className="h-4 w-4" />
          选择
        </ModeButton>
        <ModeButton active={mode === 'interact'} onClick={() => onModeChange('interact')}>
          <Eye className="h-4 w-4" />
          交互预览
        </ModeButton>
      </div>
      {temporaryMode ? <span className="text-xs text-blue-700">Alt 临时切换</span> : null}
      <select
        aria-label="角色结构预览"
        className="border-border bg-panel rounded-md border px-2 py-1.5 text-sm text-slate-700"
        defaultValue="current"
      >
        <option value="current">当前角色结构</option>
      </select>
      <button
        type="button"
        className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50"
        onClick={onNewPage}
      >
        <Plus className="h-4 w-4" />
        新页面 / 菜单
      </button>
      <button
        type="button"
        className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50 lg:hidden"
        onClick={onInspector}
      >
        <PanelRight className="h-4 w-4" />
        属性
      </button>
      <button
        type="button"
        className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-2.5 text-sm text-slate-700 hover:bg-slate-50"
        onClick={onExit}
      >
        <X className="h-4 w-4" />
        退出
      </button>
    </header>
  );
}

function ModeButton({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      aria-pressed={active}
      onClick={onClick}
      className={`inline-flex min-h-8 items-center gap-1.5 rounded px-2.5 text-sm ${
        active
          ? 'bg-white font-medium text-blue-700 shadow-sm'
          : 'text-slate-500 hover:text-slate-900'
      }`}
    >
      {children}
    </button>
  );
}

function OutlinePanel({
  root,
  selectedId,
  open,
  onClose,
  onSelect,
}: {
  root: AuthoringNode;
  selectedId: string;
  open: boolean;
  onClose: () => void;
  onSelect: (id: string) => void;
}) {
  return (
    <aside
      className={`border-border bg-panel z-40 w-[280px] shrink-0 overflow-auto border-r lg:relative lg:block ${
        open ? 'absolute inset-y-0 left-0 block shadow-2xl' : 'hidden'
      }`}
      aria-label="页面大纲"
      data-testid="authoring-outline"
    >
      <PanelHeader title="页面大纲" onClose={onClose} />
      <div className="p-2">
        <OutlineNode node={root} selectedId={selectedId} onSelect={onSelect} />
      </div>
    </aside>
  );
}

function OutlineNode({
  node,
  selectedId,
  onSelect,
}: {
  node: AuthoringNode;
  selectedId: string;
  onSelect: (id: string) => void;
}) {
  return (
    <div>
      <button
        type="button"
        onClick={() => onSelect(node.id)}
        className={`flex min-h-9 w-full items-center gap-2 rounded-md px-2 text-left text-sm ${
          node.id === selectedId
            ? 'bg-blue-50 font-medium text-blue-700'
            : 'text-slate-700 hover:bg-slate-50'
        }`}
        style={{ paddingLeft: `${Math.min(node.depth * 14 + 8, 64)}px` }}
        data-testid={`authoring-outline-${node.id}`}
      >
        {node.kind === 'page' ? (
          <Layers3 className="h-4 w-4" />
        ) : (
          <span className="h-1.5 w-1.5 rounded-full bg-slate-300" />
        )}
        <span className="truncate">{node.label}</span>
        <span className="ml-auto text-[10px] text-slate-400 uppercase">{node.kind}</span>
      </button>
      {node.children.map((child) => (
        <OutlineNode key={child.id} node={child} selectedId={selectedId} onSelect={onSelect} />
      ))}
    </div>
  );
}

function InspectorPanel({
  node,
  manifest,
  session,
  readOnly,
  open,
  onClose,
  onHandoff,
  onEdit,
}: {
  node: AuthoringNode;
  manifest?: CapabilityManifest;
  session: AuthoringSession;
  readOnly: boolean;
  open: boolean;
  onClose: () => void;
  onHandoff: (property?: PropertyCapability) => void;
  onEdit: (
    node: AuthoringNode,
    property: PropertyCapability,
    value: unknown,
    remove?: boolean,
  ) => void;
}) {
  const properties = Object.values(manifest?.properties ?? {}).sort((left, right) =>
    left.propertyPath.localeCompare(right.propertyPath),
  );
  return (
    <aside
      className={`border-border bg-panel z-40 w-[360px] max-w-[calc(100vw-2rem)] shrink-0 overflow-auto border-l lg:relative lg:block ${
        open ? 'absolute inset-y-0 right-0 block shadow-2xl' : 'hidden'
      }`}
      aria-label="属性检查器"
      data-testid="authoring-inspector"
    >
      <PanelHeader title="属性检查器" onClose={onClose} />
      <div className="space-y-4 p-4">
        <div>
          <div className="text-xs font-semibold tracking-wide text-slate-400 uppercase">
            当前对象
          </div>
          <div className="mt-1 text-base font-semibold text-slate-900">{node.label}</div>
          <div className="mt-1 font-mono text-xs break-all text-slate-500">{node.sourceId}</div>
        </div>
        <div className="grid grid-cols-2 gap-2 text-xs">
          <StatusCell label="风险" value={session.riskLevel} />
          <StatusCell label="发布" value={publishLabel(session.publishPolicy)} />
          <StatusCell label="校验" value={session.validationState} />
          <StatusCell label="修订" value={`r${session.revision}`} />
        </div>
        <div className="border-status-amber bg-status-amber-bg rounded-md border p-3 text-xs text-amber-900">
          下列能力来自服务端可信清单；就地修改先保存在浏览器，点击“保存”后仅写入隔离 ChangeSet。
        </div>
        <div>
          <div className="mb-2 text-xs font-semibold tracking-wide text-slate-400 uppercase">
            可配置属性
          </div>
          {properties.length ? (
            <div className="space-y-2">
              {properties.map((property) => (
                <PropertyEditor
                  key={property.propertyPath}
                  node={node}
                  property={property}
                  disabled={readOnly}
                  onEdit={onEdit}
                  onHandoff={() => onHandoff(property)}
                />
              ))}
            </div>
          ) : (
            <div className="rounded-md border border-dashed border-slate-300 p-3 text-xs text-slate-500">
              此对象未声明现场配置能力，默认进入应用设计中心。
            </div>
          )}
        </div>
        <button
          type="button"
          disabled={readOnly}
          onClick={() => onHandoff()}
          className="inline-flex min-h-10 w-full items-center justify-center gap-2 rounded-md bg-blue-600 px-3 text-sm font-semibold text-white hover:bg-blue-700 disabled:cursor-not-allowed disabled:bg-slate-300"
        >
          <Settings2 className="h-4 w-4" />
          高级设置
        </button>
      </div>
    </aside>
  );
}

function PropertyEditor({
  node,
  property,
  disabled,
  onEdit,
  onHandoff,
}: {
  node: AuthoringNode;
  property: PropertyCapability;
  disabled: boolean;
  onEdit: (
    node: AuthoringNode,
    property: PropertyCapability,
    value: unknown,
    remove?: boolean,
  ) => void;
  onHandoff: () => void;
}) {
  const value = readPointer(node.source, property.propertyPath);
  const editable = property.route === 'INLINE' || property.route === 'GUIDED_INLINE';
  const kind = propertyEditorKind(property.propertyPath, value);
  const fieldId = `authoring-property-${node.id}-${property.propertyPath}`.replace(
    /[^A-Za-z0-9_-]/g,
    '-',
  );

  if (!editable) {
    return (
      <button
        type="button"
        onClick={property.route === 'HANDOFF_STUDIO' ? onHandoff : undefined}
        disabled={property.route !== 'HANDOFF_STUDIO'}
        className={`border-border w-full rounded-md border p-2 text-left ${
          property.route === 'HANDOFF_STUDIO'
            ? 'hover:border-blue-300 hover:bg-blue-50'
            : 'cursor-not-allowed opacity-70'
        }`}
      >
        <PropertyHeader property={property} />
        <div className="mt-1 flex items-center justify-between text-[11px] text-slate-500">
          <span>{routeLabel(property.route)}</span>
          {property.route === 'HANDOFF_STUDIO' ? (
            <span className="text-blue-700">高级设置 ↗</span>
          ) : null}
        </div>
      </button>
    );
  }

  return (
    <div
      className="border-border rounded-md border p-2"
      data-testid={`authoring-property-${property.propertyPath}`}
    >
      <label htmlFor={fieldId} className="block">
        <PropertyHeader property={property} />
        <span className="mt-1 block text-[11px] text-slate-500">
          {routeLabel(property.route)}
          {property.rolePreviewRequired ? ' · 保存后需角色复核' : ''}
        </span>
      </label>
      <div className="mt-2 flex items-start gap-2">
        {kind === 'boolean' ? (
          <select
            id={fieldId}
            value={value === true ? 'true' : value === false ? 'false' : ''}
            disabled={disabled}
            onChange={(event) => {
              if (event.target.value === '') onEdit(node, property, undefined, true);
              else onEdit(node, property, event.target.value === 'true');
            }}
            className="border-border min-h-9 min-w-0 flex-1 rounded-md border bg-white px-2 text-sm"
          >
            <option value="">未设置</option>
            <option value="true">显示 / 是</option>
            <option value="false">隐藏 / 否</option>
          </select>
        ) : kind === 'number' ? (
          <input
            id={fieldId}
            type="number"
            value={typeof value === 'number' ? value : ''}
            disabled={disabled}
            onChange={(event) => {
              if (event.target.value === '') onEdit(node, property, undefined, true);
              else onEdit(node, property, Number(event.target.value));
            }}
            className="border-border min-h-9 min-w-0 flex-1 rounded-md border px-2 text-sm"
          />
        ) : kind === 'json' ? (
          <textarea
            id={fieldId}
            rows={3}
            value={formatEditorValue(value)}
            disabled={disabled}
            onChange={(event) => {
              const parsed = parseEditorJson(event.target.value);
              if (parsed.ok) onEdit(node, property, parsed.value);
            }}
            className="border-border min-w-0 flex-1 rounded-md border px-2 py-1.5 font-mono text-xs"
            aria-describedby={`${fieldId}-hint`}
          />
        ) : (
          <input
            id={fieldId}
            type="text"
            value={typeof value === 'string' ? value : ''}
            disabled={disabled}
            onChange={(event) => onEdit(node, property, event.target.value)}
            className="border-border min-h-9 min-w-0 flex-1 rounded-md border px-2 text-sm"
          />
        )}
        <button
          type="button"
          disabled={disabled || value === undefined}
          onClick={() => onEdit(node, property, undefined, true)}
          className="border-border min-h-9 rounded-md border px-2 text-xs text-slate-500 hover:bg-slate-50 disabled:opacity-40"
        >
          重置
        </button>
      </div>
      {kind === 'json' ? (
        <p id={`${fieldId}-hint`} className="mt-1 text-[11px] text-slate-500">
          JSON 格式；无效输入不会进入待保存变更。
        </p>
      ) : null}
    </div>
  );
}

function PropertyHeader({ property }: { property: PropertyCapability }) {
  return (
    <span className="flex items-center gap-2">
      <code className="min-w-0 flex-1 truncate text-xs text-slate-700">
        {propertyLabel(property.propertyPath)}
      </code>
      <RiskBadge risk={property.risk} />
    </span>
  );
}

function ChangeDock({
  session,
  edits,
  saving,
  submitting,
  stale,
  readOnly,
  readOnlyLabel,
  onDiff,
  onSave,
  onRefresh,
  onSubmit,
}: {
  session: AuthoringSession;
  edits: PendingAuthoringEdit[];
  saving: boolean;
  submitting: boolean;
  stale: boolean;
  readOnly: boolean;
  readOnlyLabel: string;
  onDiff: () => void;
  onSave: () => void;
  onRefresh: () => void;
  onSubmit: () => void;
}) {
  const validationErrors = session.validationState === 'INVALID' ? 1 : 0;
  return (
    <footer className="border-border bg-panel sticky bottom-0 z-20 flex min-h-14 flex-wrap items-center gap-3 border-t px-3 py-2 text-sm">
      <div className="mr-auto flex flex-wrap items-center gap-3">
        <strong className="text-slate-900">{edits.length} 项未保存</strong>
        <span className="text-slate-600">{Math.max(0, session.revision - 1)} 项草稿变更</span>
        <span className={validationErrors ? 'text-red-700' : 'text-slate-600'}>
          {validationErrors} 个校验错误
        </span>
        {readOnly ? (
          <span className="rounded bg-blue-50 px-2 py-1 text-xs font-medium text-blue-700">
            {readOnlyLabel}
          </span>
        ) : null}
      </div>
      {stale && !readOnly ? (
        <button
          type="button"
          onClick={onRefresh}
          disabled={readOnly}
          className="border-status-amber text-status-amber inline-flex min-h-9 items-center gap-1.5 rounded-md border px-3 text-sm"
        >
          刷新基线并保留本地变更
        </button>
      ) : null}
      <DockButton
        icon={<GitCompare className="h-4 w-4" />}
        label="差异"
        disabled={edits.length === 0}
        onClick={onDiff}
      />
      <DockButton
        icon={saving ? <Loader2 className="h-4 w-4 animate-spin" /> : <Save className="h-4 w-4" />}
        label={saving ? '保存中…' : '保存'}
        disabled={readOnly || saving || edits.length === 0}
        onClick={onSave}
      />
      <DockButton icon={<Eye className="h-4 w-4" />} label="实时预览" disabled />
      <DockButton
        label={submitting ? '提交中…' : '提交评审'}
        disabled={
          readOnly ||
          submitting ||
          edits.length > 0 ||
          validationErrors > 0 ||
          session.revision <= 1
        }
        onClick={onSubmit}
      />
    </footer>
  );
}

function isAuthoringSessionWritable(
  session: AuthoringSession | null,
  canConfigure: boolean,
): session is AuthoringSession {
  return Boolean(
    canConfigure &&
    session?.state === 'ACTIVE' &&
    (!session.writerLease || session.writerLease.status === 'OWNED'),
  );
}

function authoringReadOnlyLabel(
  session: AuthoringSession,
  canConfigure: boolean,
  hasConflict: boolean,
): string {
  if (!canConfigure) return '权限已收回，当前只读';
  if (hasConflict) return '并发冲突待专业裁决';
  if (session.writerLease?.status === 'EXPIRED') return '编辑租约已过期';
  if (session.writerLease && session.writerLease.status !== 'OWNED') return '编辑权由其他会话持有';
  if (session.state === 'READ_ONLY') return '已冻结，当前只读';
  return session.state;
}

function DockButton({
  icon,
  label,
  disabled = false,
  onClick,
}: {
  icon?: React.ReactNode;
  label: string;
  disabled?: boolean;
  onClick?: () => void;
}) {
  return (
    <button
      type="button"
      disabled={disabled}
      onClick={onClick}
      className="border-border inline-flex min-h-9 items-center gap-1.5 rounded-md border px-3 text-sm text-slate-700 hover:bg-slate-50 disabled:cursor-not-allowed disabled:text-slate-400"
    >
      {icon}
      {label}
    </button>
  );
}

function DiffDialog({ edits, onClose }: { edits: PendingAuthoringEdit[]; onClose: () => void }) {
  return (
    <div
      className="fixed inset-0 z-[70] grid place-items-center bg-slate-950/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="authoring-diff-title"
    >
      <div className="bg-panel flex max-h-[80vh] w-full max-w-3xl flex-col rounded-xl border border-slate-200 shadow-2xl">
        <div className="flex items-center justify-between border-b border-slate-200 p-4">
          <div>
            <h2 id="authoring-diff-title" className="font-semibold text-slate-900">
              待保存差异
            </h2>
            <p className="mt-1 text-xs text-slate-500">
              仅展示当前浏览器尚未写入 ChangeSet 的变更。
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            aria-label="关闭差异"
            className="rounded p-2 hover:bg-slate-100"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <div className="min-h-0 flex-1 overflow-auto p-4">
          {edits.length ? (
            <div className="space-y-3">
              {edits.map((edit) => (
                <div key={edit.key} className="border-border rounded-lg border p-3 text-sm">
                  <div className="flex flex-wrap items-center gap-2">
                    <strong>{edit.blockLabel}</strong>
                    <code className="text-xs text-slate-500">{edit.property.propertyPath}</code>
                    <RiskBadge risk={edit.property.risk} />
                    <span className="ml-auto text-xs text-slate-500">{edit.operation}</span>
                  </div>
                  <div className="mt-3 grid gap-2 sm:grid-cols-2">
                    <DiffValue label="之前" value={edit.previousValue} tone="old" />
                    <DiffValue
                      label="之后"
                      value={edit.operation === 'REMOVE' ? undefined : edit.value}
                      tone="new"
                    />
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <div className="rounded-md border border-dashed p-6 text-center text-sm text-slate-500">
              没有待保存变更
            </div>
          )}
        </div>
        <div className="flex justify-end border-t border-slate-200 p-4">
          <button
            type="button"
            onClick={onClose}
            className="border-border min-h-10 rounded-md border px-4 text-sm"
          >
            返回配置
          </button>
        </div>
      </div>
    </div>
  );
}

function DiffValue({ label, value, tone }: { label: string; value: unknown; tone: 'old' | 'new' }) {
  return (
    <div className={tone === 'old' ? 'rounded bg-red-50 p-2' : 'rounded bg-emerald-50 p-2'}>
      <div className="text-[11px] font-semibold text-slate-500 uppercase">{label}</div>
      <pre className="mt-1 text-xs break-all whitespace-pre-wrap text-slate-700">
        {formatDiffValue(value)}
      </pre>
    </div>
  );
}

function ExplainDialog({
  state,
  node,
  pending,
  onCancel,
  onContinue,
}: {
  state: ExplainState;
  node: AuthoringNode;
  pending: boolean;
  onCancel: () => void;
  onContinue: () => void;
}) {
  return (
    <div
      className="fixed inset-0 z-[70] grid place-items-center bg-slate-950/40 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="handoff-title"
    >
      <div className="bg-panel w-full max-w-lg rounded-xl border border-slate-200 shadow-2xl">
        <div className="flex items-start gap-3 border-b border-slate-200 p-5">
          <div className="rounded-full bg-amber-100 p-2 text-amber-700">
            <AlertTriangle className="h-5 w-5" />
          </div>
          <div>
            <h2 id="handoff-title" className="font-semibold text-slate-900">
              {state.title}
            </h2>
            <p className="mt-1 text-sm text-slate-600">{state.reason}</p>
          </div>
        </div>
        <div className="space-y-3 p-5 text-sm">
          <div className="rounded-md bg-slate-50 p-3">
            <div>
              <span className="text-slate-500">目标对象：</span>
              {node.label}
            </div>
            <div className="mt-1">
              <span className="text-slate-500">携带内容：</span>当前 ChangeSet、选择对象、返回位置
            </div>
            <div className="mt-1">
              <span className="text-slate-500">安全方式：</span>10
              分钟、本人/本租户/本环境绑定、一次性 contextId
            </div>
          </div>
          <p className="text-xs text-slate-500">
            URL 不包含 pagePid、recordPid 或业务筛选；应用设计中心会重新检查权限。
          </p>
        </div>
        <div className="flex justify-end gap-2 border-t border-slate-200 p-4">
          <button
            type="button"
            onClick={onCancel}
            className="border-border min-h-10 rounded-md border px-4 text-sm text-slate-700 hover:bg-slate-50"
          >
            取消
          </button>
          <button
            type="button"
            onClick={onContinue}
            disabled={pending}
            className="min-h-10 rounded-md bg-blue-600 px-4 text-sm font-semibold text-white hover:bg-blue-700 disabled:opacity-60"
          >
            {pending ? '正在建立安全上下文…' : '继续到应用设计中心'}
          </button>
        </div>
      </div>
    </div>
  );
}

function PanelHeader({ title, onClose }: { title: string; onClose: () => void }) {
  return (
    <div className="border-border flex min-h-12 items-center justify-between border-b px-4">
      <h2 className="text-sm font-semibold text-slate-900">{title}</h2>
      <button
        type="button"
        onClick={onClose}
        className="rounded p-1 text-slate-500 hover:bg-slate-100 lg:hidden"
        aria-label={`关闭${title}`}
      >
        <X className="h-4 w-4" />
      </button>
    </div>
  );
}

function StatusCell({ label, value }: { label: string; value: string }) {
  return (
    <div className="rounded-md bg-slate-50 p-2">
      <div className="text-slate-400">{label}</div>
      <div className="mt-0.5 font-semibold text-slate-700">{value}</div>
    </div>
  );
}

function RiskBadge({ risk }: { risk: string }) {
  const tone =
    risk === 'L0'
      ? 'bg-emerald-100 text-emerald-700'
      : risk === 'L1'
        ? 'bg-blue-100 text-blue-700'
        : risk === 'L2'
          ? 'bg-amber-100 text-amber-700'
          : 'bg-red-100 text-red-700';
  return <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${tone}`}>{risk}</span>;
}

function captureInteractionContext(recordPid?: string, selection?: string) {
  const url = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const search = new URLSearchParams(window.location.search);
  const filters = collectQueryState(
    search,
    (key) =>
      key === 'q' ||
      key === 'search' ||
      key === 'filter' ||
      key === 'filters' ||
      key.startsWith('filter.') ||
      key.startsWith('filter['),
  );
  const sort = collectQueryState(
    search,
    (key) => key === 'sort' || key === 'order' || key === 'orderBy',
  );
  return {
    route: url,
    ...(recordPid ? { recordPid } : {}),
    ...(search.get('tab') ? { tabId: search.get('tab')! } : {}),
    ...(Object.keys(filters).length > 0 ? { filters } : {}),
    ...(Object.keys(sort).length > 0 ? { sort } : {}),
    scroll: { x: window.scrollX, y: window.scrollY },
    viewport: {
      width: window.innerWidth,
      height: window.innerHeight,
      scale: window.devicePixelRatio,
    },
    ...(selection ? { selection, outlinePath: [selection] } : {}),
  };
}

function collectQueryState(
  search: URLSearchParams,
  include: (key: string) => boolean,
): Record<string, string[]> {
  const result: Record<string, string[]> = {};
  search.forEach((value, key) => {
    if (!include(key)) return;
    (result[key] ??= []).push(value);
  });
  return result;
}

function readAuthoringReturnRequest(): { sessionPid: string; focusBlockId: string | null } | null {
  if (typeof window === 'undefined') return null;
  const search = new URLSearchParams(window.location.search);
  const sessionPid = search.get('authoringReturn');
  if (!sessionPid) return null;
  return {
    sessionPid,
    focusBlockId: search.get('authoringFocus'),
  };
}

function clearAuthoringReturnParams(): void {
  const url = new URL(window.location.href);
  url.searchParams.delete('authoringReturn');
  url.searchParams.delete('authoringFocus');
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
}

function contextSelectionId(context: Record<string, unknown>): string | null {
  if (typeof context.selection === 'string' && context.selection) return context.selection;
  if (!Array.isArray(context.outlinePath)) return null;
  const last = context.outlinePath
    .filter((value): value is string => typeof value === 'string')
    .at(-1);
  return last || null;
}

function contextScroll(context: Record<string, unknown>): { x: number; y: number } {
  const scroll = context.scroll;
  if (!scroll || typeof scroll !== 'object' || Array.isArray(scroll)) return { x: 0, y: 0 };
  const value = scroll as Record<string, unknown>;
  return {
    x: finiteNumber(value.x),
    y: finiteNumber(value.y),
  };
}

function finiteNumber(value: unknown): number {
  return typeof value === 'number' && Number.isFinite(value) ? value : 0;
}

function buildAuthoringTree(schema: ContextualAuthoringSurfaceProps['schema']): AuthoringNode {
  const page: AuthoringNode = {
    id: schema.id,
    sourceId: schema.id,
    kind: 'page',
    blockType: 'page',
    label: localizedLabel(schema.title, schema.pageKey || '页面'),
    parentId: null,
    depth: 0,
    source: schema as unknown as Record<string, unknown>,
    children: [],
  };
  page.children = (schema.blocks || []).map((block, index) =>
    buildBlockNode(block as Record<string, unknown>, page.id, 1, `block-${index}`),
  );
  return page;
}

function buildBlockNode(
  block: Record<string, unknown>,
  parentId: string,
  depth: number,
  fallback: string,
): AuthoringNode {
  const sourceId = String(block.id || `${parentId}/${fallback}`);
  const blockType = String(block.blockType || 'block');
  const node: AuthoringNode = {
    id: sourceId,
    sourceId,
    kind: 'block',
    blockType,
    label: localizedLabel(block.title, blockTypeLabel(blockType)),
    parentId,
    depth,
    source: block,
    children: [],
  };
  const nestedBlocks = Array.isArray(block.blocks) ? block.blocks : [];
  nestedBlocks.forEach((child, index) => {
    if (child && typeof child === 'object') {
      node.children.push(
        buildBlockNode(child as Record<string, unknown>, node.id, depth + 1, `block-${index}`),
      );
    }
  });
  addLeafNodes(node, 'field', listValues(block.fields));
  const table =
    block.table && typeof block.table === 'object'
      ? (block.table as Record<string, unknown>)
      : null;
  addLeafNodes(
    node,
    'field',
    listValues(block.columns).length ? listValues(block.columns) : listValues(table?.columns),
  );
  addLeafNodes(node, 'action', listValues(block.buttons));
  addLeafNodes(node, 'action', listValues(block.rowActions));
  addLeafNodes(node, 'action', listValues(table?.rowActions));
  listValues(block.tabs).forEach((tab, tabIndex) => {
    listValues(tab.blocks).forEach((child, childIndex) => {
      node.children.push(
        buildBlockNode(child, node.id, depth + 1, `tab-${tabIndex}-${childIndex}`),
      );
    });
  });
  return node;
}

function addLeafNodes(
  parent: AuthoringNode,
  kind: 'field' | 'action',
  values: Record<string, unknown>[],
) {
  values.forEach((value, index) => {
    const identity = String(value.id || value.field || value.code || `${kind}-${index}`);
    parent.children.push({
      id: `${parent.id}/${kind}:${identity}`,
      sourceId: String(value.id || identity),
      kind,
      blockType: kind === 'field' ? 'field' : 'action',
      label: localizedLabel(value.label, String(value.field || value.code || identity)),
      parentId: parent.id,
      depth: parent.depth + 1,
      source: value,
      children: [],
    });
  });
}

function listValues(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> =>
        Boolean(item && typeof item === 'object'),
      )
    : [];
}

function indexTree(root: AuthoringNode) {
  const byId = new Map<string, AuthoringNode>();
  const bySourceId = new Map<string, AuthoringNode>();
  const visit = (node: AuthoringNode) => {
    byId.set(node.id, node);
    if (!bySourceId.has(node.sourceId)) bySourceId.set(node.sourceId, node);
    node.children.forEach(visit);
  };
  visit(root);
  return { byId, bySourceId };
}

function ancestorChain(node: AuthoringNode, index: Map<string, AuthoringNode>): AuthoringNode[] {
  const chain = [node];
  let parentId = node.parentId;
  while (parentId) {
    const parent = index.get(parentId);
    if (!parent) break;
    chain.unshift(parent);
    parentId = parent.parentId;
  }
  return chain;
}

function localizedLabel(value: unknown, fallback: string): string {
  if (typeof value === 'string' && value.trim()) return value;
  if (value && typeof value === 'object') {
    try {
      return getLocalizedText(value as Record<string, string>, 'zh-CN', (key) => key) || fallback;
    } catch {
      return fallback;
    }
  }
  return fallback;
}

function blockTypeLabel(blockType: string): string {
  const labels: Record<string, string> = {
    table: '表格',
    list: '列表',
    filters: '筛选区',
    toolbar: '操作栏',
    form: '表单',
    'form-section': '表单分组',
    'detail-section': '详情分组',
    chart: '图表',
    tabs: '标签页',
    description: '说明',
    'rich-text': '富文本',
  };
  return labels[blockType] || blockType;
}

function isSafePreviewInteraction(target: HTMLElement): boolean {
  const interactive = target.closest<HTMLElement>(
    'button, a, input, select, textarea, [role="tab"], [role="button"]',
  );
  if (!interactive) return true;
  if (interactive.matches('a, input, select, textarea, [role="tab"]')) return true;
  const testId = interactive.dataset.testid || '';
  return (
    interactive.getAttribute('aria-expanded') !== null ||
    interactive.getAttribute('aria-controls') !== null ||
    /(tab|toggle|expand|collapse|pagination|page-size|filter-search|filter-reset)/i.test(testId)
  );
}

function explainHandoffReason(node: AuthoringNode, property?: PropertyCapability): string {
  if (property?.route === 'HANDOFF_STUDIO') {
    return `属性 ${property.propertyPath} 涉及 ${property.effectTags.join('、') || '业务语义'}，需要依赖分析和专业发布治理。`;
  }
  if (node.kind === 'page') return '页面级结构、路由和资源关系需要在应用设计中心统一治理。';
  return '此对象未声明可安全就地写入的属性，系统不会猜测影响范围。';
}

function routeLabel(route: string): string {
  return (
    (
      {
        INLINE: '可就地配置',
        GUIDED_INLINE: '引导式配置',
        HANDOFF_STUDIO: '应用设计中心',
        DENY: '禁止',
      } as Record<string, string>
    )[route] || route
  );
}

function publishLabel(policy: string): string {
  return (
    (
      {
        DIRECT_ALLOWED: '可直发',
        DEFAULT_REVIEW: '默认评审',
        REQUIRED_REVIEW: '必须评审',
        STUDIO_APPROVAL: '专项审批',
        DENIED: '禁止',
      } as Record<string, string>
    )[policy] || policy
  );
}

function schemaFromSnapshot(
  schema: UnifiedSchema,
  snapshot: Record<string, unknown>,
): UnifiedSchema {
  if (!snapshot || typeof snapshot !== 'object') return schema;
  return {
    ...schema,
    ...snapshot,
    id: schema.id,
    blocks: Array.isArray(snapshot.blocks) ? snapshot.blocks : schema.blocks,
  } as UnifiedSchema;
}

function materializePendingSchema(
  schema: UnifiedSchema,
  snapshot: Record<string, unknown>,
  edits: Map<string, PendingAuthoringEdit>,
): UnifiedSchema {
  const result = cloneJson(schemaFromSnapshot(schema, snapshot));
  edits.forEach((edit) => {
    const block = findObjectById(result as unknown, edit.blockId);
    if (!block) return;
    applyPointer(
      block,
      edit.property.propertyPath,
      edit.operation === 'REMOVE' ? undefined : edit.value,
      edit.operation === 'REMOVE',
    );
  });
  return result;
}

function materializePendingSnapshot(
  snapshot: Record<string, unknown>,
  edits: Map<string, PendingAuthoringEdit>,
): Record<string, unknown> {
  const result = cloneJson(snapshot);
  edits.forEach((edit) => {
    const block = findObjectById(result, edit.blockId);
    if (!block) return;
    applyPointer(
      block,
      edit.property.propertyPath,
      edit.operation === 'REMOVE' ? undefined : edit.value,
      edit.operation === 'REMOVE',
    );
  });
  return result;
}

function conflictingPendingEdits(
  latestSnapshot: Record<string, unknown>,
  edits: Map<string, PendingAuthoringEdit>,
): PendingAuthoringEdit[] {
  return [...edits.values()].filter((edit) => {
    const latestValue = readSnapshotProperty(
      latestSnapshot,
      edit.blockId,
      edit.property.propertyPath,
    );
    const mineValue = edit.operation === 'REMOVE' ? undefined : edit.value;
    return !valuesEqual(latestValue, edit.previousValue) && !valuesEqual(latestValue, mineValue);
  });
}

function createContextualConflictState(
  latestSession: AuthoringSession,
  edits: Map<string, PendingAuthoringEdit>,
  baseRevision: number,
): ContextualConflictState {
  const baseSnapshot = reconstructPendingBaseSnapshot(latestSession.snapshot, edits);
  return {
    baseRevision,
    latestSession,
    baseSnapshot,
    mineSnapshot: materializePendingSnapshot(baseSnapshot, edits),
    pendingCount: edits.size,
  };
}

function reconstructPendingBaseSnapshot(
  latestSnapshot: Record<string, unknown>,
  edits: Map<string, PendingAuthoringEdit>,
): Record<string, unknown> {
  const result = cloneJson(latestSnapshot);
  edits.forEach((edit) => {
    const block = findObjectById(result, edit.blockId);
    if (!block) return;
    applyPointer(
      block,
      edit.property.propertyPath,
      edit.previousValue,
      edit.previousValue === undefined,
    );
  });
  return result;
}

function readSnapshotProperty(
  snapshot: Record<string, unknown>,
  blockId: string,
  propertyPath: string,
): unknown {
  const block = findObjectById(snapshot, blockId);
  return block ? readPointer(block, propertyPath) : undefined;
}

function findObjectById(value: unknown, id: string): Record<string, unknown> | null {
  if (Array.isArray(value)) {
    for (const item of value) {
      const found = findObjectById(item, id);
      if (found) return found;
    }
    return null;
  }
  if (!value || typeof value !== 'object') return null;
  const object = value as Record<string, unknown>;
  if (object.id === id) return object;
  for (const child of Object.values(object)) {
    const found = findObjectById(child, id);
    if (found) return found;
  }
  return null;
}

function readPointer(object: Record<string, unknown>, propertyPath: string): unknown {
  const segments = pointerSegments(propertyPath);
  let value: unknown = object;
  for (const segment of segments) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return undefined;
    value = (value as Record<string, unknown>)[segment];
  }
  return value;
}

function applyPointer(
  object: Record<string, unknown>,
  propertyPath: string,
  value: unknown,
  remove: boolean,
) {
  const segments = pointerSegments(propertyPath);
  if (segments.length === 0) return;
  let parent = object;
  for (const segment of segments.slice(0, -1)) {
    const child = parent[segment];
    if (!child || typeof child !== 'object' || Array.isArray(child)) {
      parent[segment] = {};
    }
    parent = parent[segment] as Record<string, unknown>;
  }
  const leaf = segments[segments.length - 1];
  if (remove) delete parent[leaf];
  else parent[leaf] = value;
}

function pointerSegments(propertyPath: string): string[] {
  if (!propertyPath.startsWith('/')) return [];
  return propertyPath
    .slice(1)
    .split('/')
    .map((part) => part.replace(/~1/g, '/').replace(/~0/g, '~'));
}

function cloneJson<T>(value: T): T {
  return JSON.parse(JSON.stringify(value)) as T;
}

function valuesEqual(left: unknown, right: unknown): boolean {
  return JSON.stringify(left) === JSON.stringify(right);
}

function propertyEditorKind(
  propertyPath: string,
  value: unknown,
): 'text' | 'number' | 'boolean' | 'json' {
  if (typeof value === 'boolean' || /\/(visible|enabled)$/.test(propertyPath)) return 'boolean';
  if (typeof value === 'number' || /\/(span|height|pageSize)$/.test(propertyPath)) return 'number';
  if (
    (value != null && typeof value === 'object') ||
    /\/(defaultSort|defaultFilter|visibleWhen)$/.test(propertyPath)
  ) {
    return 'json';
  }
  return 'text';
}

function propertyLabel(propertyPath: string): string {
  const labels: Record<string, string> = {
    '/title': '标题',
    '/layout/span': '布局跨度',
    '/props/label': '显示名称',
    '/props/visible': '是否显示',
    '/props/icon': '图标',
    '/props/variant': '按钮样式',
    '/props/density': '表格密度',
    '/props/pageSize': '每页条数',
    '/props/defaultSort': '默认排序',
    '/props/defaultFilter': '默认筛选',
    '/props/content': '内容',
    '/props/height': '高度',
    '/props/defaultTab': '默认标签页',
  };
  return labels[propertyPath] || propertyPath;
}

function formatEditorValue(value: unknown): string {
  if (value === undefined) return '';
  if (typeof value === 'string') return value;
  return JSON.stringify(value, null, 2);
}

function parseEditorJson(value: string): { ok: true; value: unknown } | { ok: false } {
  if (!value.trim()) return { ok: true, value: null };
  try {
    return { ok: true, value: JSON.parse(value) };
  } catch {
    return { ok: false };
  }
}

function formatDiffValue(value: unknown): string {
  if (value === undefined) return '（未设置）';
  if (typeof value === 'string') return value || '（空字符串）';
  return JSON.stringify(value, null, 2);
}

function cssEscape(value: string): string {
  if (typeof CSS !== 'undefined' && typeof CSS.escape === 'function') return CSS.escape(value);
  return value.replace(/["\\]/g, '\\$&');
}

export const contextualAuthoringTestUtils = {
  buildAuthoringTree,
  indexTree,
  isSafePreviewInteraction,
};
