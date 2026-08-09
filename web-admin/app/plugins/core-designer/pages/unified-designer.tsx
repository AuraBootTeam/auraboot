import React, { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { usePermission, useUser } from '~/contexts/AuthContext';
import { UnifiedDesignerWorkbench } from '../components/unified-designer/workbench/UnifiedDesignerWorkbench';
import { sampleModelFieldsByModel } from '../components/unified-designer/fixtures/sampleModelFields';
import { samplePageSchemaV3 } from '../components/unified-designer/fixtures/samplePageSchemaV3';
import {
  loadPageSchemaV3,
  publishPageSchemaV3,
  savePageSchemaV3,
  unpublishPageSchemaV3,
  type PageSchemaV3Source,
} from '../components/unified-designer/persistence/pageSchemaV3Repository';
import {
  collectModelCodesFromDocument,
  loadModelFieldsByModelCodes,
} from '../components/unified-designer/persistence/modelFieldsRepository';
import type { ModelFieldsByModel, PageSchemaV3 } from '../components/unified-designer/types';
import {
  applyAuthoringStudioPatch,
  consumeAuthoringHandoff,
  loadAuthoringCapabilities,
  loadAuthoringReviewWorkspace,
  loadAuthoringSession,
  moveAuthoringStudioBlock,
  openAuthoringReviewWorkspace,
  observeAuthoringChangeSet,
  takeoverAuthoringWriterLease,
  transitionAuthoringGovernance,
} from '~/framework/meta/authoring/authoringService';
import { AuthoringWriterLeaseNotice } from '~/framework/meta/authoring/AuthoringWriterLeaseNotice';
import { AuthoringGovernanceNotice } from '~/framework/meta/authoring/AuthoringGovernanceNotice';
import { consumeAuthoringConflictTransfer } from '~/framework/meta/authoring/authoringConflictTransfer';
import { AuthoringConflictResolutionPanel } from '../components/unified-designer/AuthoringConflictResolutionPanel';
import type {
  AuthoringSession,
  AuthoringGovernanceAction,
  CapabilityRegistry,
  HandoffContext,
} from '~/framework/meta/authoring/types';
import {
  authoringSnapshotToPageSchemaV3,
  buildStudioThreeWayMerge,
  planStudioAuthoringPatches,
  resolveStudioThreeWayMerge,
  studioEditablePropertyPaths,
  studioReorderableBlockTypes,
  type StudioMergeResolution,
  type StudioThreeWayMerge,
} from '../components/unified-designer/persistence/contextualAuthoringAdapter';

const LOCAL_STORAGE_KEY = 'auraboot.unified-designer.sample';

interface StudioConflictState {
  baseRevision: number;
  baseDocument: PageSchemaV3;
  mineDocument: PageSchemaV3;
  latestSession: AuthoringSession;
  merge: StudioThreeWayMerge;
}

export default function UnifiedDesignerPage() {
  const canAdministerDesigner = usePermission('meta.designer.admin');
  const canManageDesigner = usePermission('meta.designer.update');
  const canReviewAuthoring = usePermission('meta.publish.update');
  const { user } = useUser();
  const [searchParams] = useSearchParams();
  const requestedPageId = searchParams.get('pageId') || searchParams.get('pid');
  const pageKey = searchParams.get('pageKey');
  const contextId = searchParams.get('contextId');
  const resumeSessionPid = searchParams.get('authoringSession');
  const resumeReviewSessionPid = searchParams.get('reviewSession');
  const observedChangeSetPid = searchParams.get('changeSetId');
  const observedReviewChangeSetPid = searchParams.get('reviewChangeSetId');
  const conflictContextId = searchParams.get('conflictContext');
  const hasAuthoringContext = Boolean(
    contextId ||
      resumeSessionPid ||
      resumeReviewSessionPid ||
      observedChangeSetPid ||
      observedReviewChangeSetPid ||
      conflictContextId,
  );
  const [handoff, setHandoff] = useState<HandoffContext | null>(null);
  const [authoringSession, setAuthoringSession] = useState<AuthoringSession | null>(null);
  const [authoringCapabilities, setAuthoringCapabilities] = useState<CapabilityRegistry | null>(null);
  const [handoffError, setHandoffError] = useState<string | null>(null);
  const [leaseTakeoverPending, setLeaseTakeoverPending] = useState(false);
  const [leaseTakeoverError, setLeaseTakeoverError] = useState<string | null>(null);
  const [studioConflict, setStudioConflict] = useState<StudioConflictState | null>(null);
  const [conflictPending, setConflictPending] = useState(false);
  const [conflictError, setConflictError] = useState<string | null>(null);
  const [governancePending, setGovernancePending] = useState<AuthoringGovernanceAction | null>(
    null,
  );
  const [governanceError, setGovernanceError] = useState<string | null>(null);
  const [reviewWorkspaceMode, setReviewWorkspaceMode] = useState(false);
  const [workbenchGeneration, setWorkbenchGeneration] = useState(0);
  const [document, setDocument] = useState<PageSchemaV3 | null>(null);
  const [source, setSource] = useState<PageSchemaV3Source>({ type: 'local' });
  const [published, setPublished] = useState(false);
  const [modelFieldsByModel, setModelFieldsByModel] = useState<ModelFieldsByModel>({});
  const [error, setError] = useState<string | null>(null);
  const documentBaselineRef = useRef<AuthoringSession | null>(null);
  const modelCodeKey = document ? collectModelCodesFromDocument(document).join('|') : '';
  const documentId = document?.id ?? null;
  const resolvingHandoff = Boolean(
    hasAuthoringContext &&
      !handoffError &&
      (!handoff || !authoringSession || !authoringCapabilities || !document),
  );
  const activeAuthoringSessionPid = authoringSession?.sessionPid;
  const activeAuthoringRevision = authoringSession?.revision;

  useEffect(() => {
    if (!hasAuthoringContext) {
      setHandoff(null);
      setAuthoringSession(null);
      setAuthoringCapabilities(null);
      setHandoffError(null);
      setLeaseTakeoverError(null);
      setStudioConflict(null);
      setConflictError(null);
      setReviewWorkspaceMode(false);
      documentBaselineRef.current = null;
      return;
    }
    let cancelled = false;
    setDocument(null);
    setHandoff(null);
    setAuthoringSession(null);
    setAuthoringCapabilities(null);
    setHandoffError(null);
    setLeaseTakeoverError(null);
    setStudioConflict(null);
    setConflictError(null);
    setReviewWorkspaceMode(false);
    documentBaselineRef.current = null;
    const resolveContext = contextId
      ? consumeAuthoringHandoff(contextId).then(async (consumed) => {
          const [session, capabilities] = await Promise.all([
            loadAuthoringSession(consumed.sessionPid),
            loadAuthoringCapabilities(),
          ]);
          assertHandoffMatchesSession(consumed, session);
          replaceAuthoringContextUrl('contextId', consumed.sessionPid);
          return { handoff: consumed, session, capabilities, reviewWorkspace: false };
        })
      : observedReviewChangeSetPid
        ? openAuthoringReviewWorkspace(observedReviewChangeSetPid).then(
            ({ session, capabilities }) => {
              replaceAuthoringReviewContextUrl('reviewChangeSetId', session.sessionPid);
              return {
                handoff: resumeHandoffFromSession(session),
                session,
                capabilities,
                reviewWorkspace: true,
              };
            },
          )
        : observedChangeSetPid
          ? Promise.all([
              observeAuthoringChangeSet(observedChangeSetPid),
              loadAuthoringCapabilities(),
            ]).then(([session, capabilities]) => {
              replaceAuthoringContextUrl('changeSetId', session.sessionPid);
              return {
                handoff: resumeHandoffFromSession(session),
                session,
                capabilities,
                reviewWorkspace: false,
              };
            })
          : resumeReviewSessionPid
            ? loadAuthoringReviewWorkspace(resumeReviewSessionPid).then(
                ({ session, capabilities }) => ({
                  handoff: resumeHandoffFromSession(session),
                  session,
                  capabilities,
                  reviewWorkspace: true,
                }),
              )
            : Promise.all([
                loadAuthoringSession(resumeSessionPid!),
                loadAuthoringCapabilities(),
              ]).then(([session, capabilities]) => ({
                handoff: resumeHandoffFromSession(session),
                session,
                capabilities,
                reviewWorkspace: false,
              }));

    void resolveContext
      .then(({ handoff: resolvedHandoff, session, capabilities, reviewWorkspace }) => {
        if (!cancelled) {
          let isolatedDocument = authoringSnapshotToPageSchemaV3(session.snapshot);
          if (conflictContextId) {
            const transfer = consumeAuthoringConflictTransfer(conflictContextId, {
              sessionPid: session.sessionPid,
              changeSetPid: session.changeSetPid,
              pagePid: session.pagePid,
            });
            if (transfer.baseRevision >= session.revision) {
              throw new Error('三方冲突上下文的 Base 修订不早于 Latest');
            }
            const baseDocument = authoringSnapshotToPageSchemaV3(transfer.baseSnapshot);
            const mineDocument = authoringSnapshotToPageSchemaV3(transfer.mineSnapshot);
            const merge = buildStudioThreeWayMerge(
              baseDocument,
              mineDocument,
              isolatedDocument,
              capabilities,
            );
            isolatedDocument = mineDocument;
            setStudioConflict({
              baseRevision: transfer.baseRevision,
              baseDocument,
              mineDocument,
              latestSession: session,
              merge,
            });
            replaceAuthoringContextUrl('conflictContext', session.sessionPid);
          }
          setHandoff(resolvedHandoff);
          setAuthoringSession(session);
          setAuthoringCapabilities(capabilities);
          setReviewWorkspaceMode(reviewWorkspace || session.workspaceMode === 'REVIEW');
          documentBaselineRef.current = session;
          setDocument(isolatedDocument);
          setSource({
            type: 'page',
            pid: session.pagePid,
            pageKey: isolatedDocument.pageKey,
          });
          setPublished(false);
        }
      })
      .catch((consumeError) => {
        if (!cancelled) {
          setHandoffError(
            consumeError instanceof Error
              ? consumeError.message
              : '配置上下文已过期、已使用或无权访问',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [
    conflictContextId,
    contextId,
    hasAuthoringContext,
    observedChangeSetPid,
    observedReviewChangeSetPid,
    resumeReviewSessionPid,
    resumeSessionPid,
  ]);

  useEffect(() => {
    if (!activeAuthoringSessionPid) return;
    let cancelled = false;
    const interval = window.setInterval(() => {
      const refresh = reviewWorkspaceMode
        ? loadAuthoringReviewWorkspace(activeAuthoringSessionPid).then((value) => value.session)
        : loadAuthoringSession(activeAuthoringSessionPid);
      void refresh
        .then((latest) => {
          if (!cancelled && latest.revision >= (activeAuthoringRevision ?? -1)) {
            setAuthoringSession(latest);
          }
        })
        .catch(() => {
          // Keep the current isolated document and foreground error state on poll failure.
        });
    }, 15_000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [activeAuthoringRevision, activeAuthoringSessionPid, reviewWorkspaceMode]);

  useEffect(() => {
    let cancelled = false;

    async function loadDocument() {
      if (hasAuthoringContext) return;
      setError(null);
      if (!requestedPageId && !pageKey) {
        const localDocument = readLocalDocument();
        if (!cancelled) {
          setDocument(localDocument ?? samplePageSchemaV3);
          setSource({ type: 'local' });
        }
        return;
      }

      try {
        const loaded = await loadPageSchemaV3({ pageId: requestedPageId, pageKey });
        if (!cancelled) {
          setDocument(loaded.document);
          setSource(loaded.source);
          setPublished(loaded.published);
        }
      } catch (loadError) {
        if (!cancelled) {
          setError(loadError instanceof Error ? loadError.message : 'Failed to load page schema.');
        }
      }
    }

    void loadDocument();

    return () => {
      cancelled = true;
    };
  }, [hasAuthoringContext, pageKey, requestedPageId]);

  useEffect(() => {
    if (!modelCodeKey) {
      setModelFieldsByModel({});
      return;
    }

    if (source.type === 'local' && documentId === samplePageSchemaV3.id) {
      setModelFieldsByModel(sampleModelFieldsByModel);
      return;
    }

    let cancelled = false;
    const modelCodes = modelCodeKey.split('|').filter(Boolean);

    async function loadModelFields() {
      const loadedFields = await loadModelFieldsByModelCodes(modelCodes);
      if (!cancelled) {
        setModelFieldsByModel(mergeSampleModelFieldFallback(documentId, loadedFields));
      }
    }

    void loadModelFields();

    return () => {
      cancelled = true;
    };
  }, [documentId, modelCodeKey, source.type]);

  const handleSave = async (nextDocument: PageSchemaV3) => {
    if (source.type === 'local') {
      window.localStorage.setItem(LOCAL_STORAGE_KEY, JSON.stringify(nextDocument));
      setDocument(nextDocument);
      return;
    }

    const result = await savePageSchemaV3({ document: nextDocument, source });
    if (!result.ok) {
      throw new Error(result.error || result.validation?.errors[0]?.message || 'Failed to save page schema.');
    }
    if (!result.source) {
      throw new Error('Save response did not include a page source.');
    }
    setSource(result.source);
    setDocument(nextDocument);
  };

  const persistContextualStudioDocument = async (
    nextDocument: PageSchemaV3,
    startingSession: AuthoringSession,
  ): Promise<AuthoringSession> => {
    if (!authoringCapabilities) throw new Error('应用设计中心能力清单尚未就绪');
    const baseline = authoringSnapshotToPageSchemaV3(startingSession.snapshot);
    const plan = planStudioAuthoringPatches(baseline, nextDocument, authoringCapabilities);
    if (plan.unsupported.length > 0) throw new Error(plan.unsupported.join('；'));

    let workingSession = startingSession;
    for (const move of plan.moves) {
      const result = await moveAuthoringStudioBlock(
        workingSession.sessionPid,
        workingSession.revision,
        move.blockId,
        move.beforeBlockId,
        move.manifestChecksum,
      );
      workingSession = result.session;
      setAuthoringSession(workingSession);
    }
    for (const patch of plan.patches) {
      const result = await applyAuthoringStudioPatch(
        workingSession.sessionPid,
        workingSession.revision,
        patch.blockId,
        patch.propertyPath,
        patch.operation,
        patch.value,
        patch.manifestChecksum,
      );
      workingSession = result.session;
      setAuthoringSession(workingSession);
    }
    return workingSession;
  };

  const openStudioConflict = (
    baseSession: AuthoringSession,
    mineDocument: PageSchemaV3,
    latestSession: AuthoringSession,
  ) => {
    if (!authoringCapabilities) throw new Error('应用设计中心能力清单尚未就绪');
    const baseDocument = authoringSnapshotToPageSchemaV3(baseSession.snapshot);
    const merge = buildStudioThreeWayMerge(
      baseDocument,
      mineDocument,
      authoringSnapshotToPageSchemaV3(latestSession.snapshot),
      authoringCapabilities,
    );
    setAuthoringSession(latestSession);
    setStudioConflict({
      baseRevision: baseSession.revision,
      baseDocument,
      mineDocument,
      latestSession,
      merge,
    });
    setConflictError(null);
  };

  const handleContextualStudioSave = async (nextDocument: PageSchemaV3): Promise<PageSchemaV3> => {
    if (!handoff || !authoringSession || !authoringCapabilities) {
      throw new Error('现场配置会话尚未就绪');
    }
    if (!canAdministerDesigner) {
      throw new Error('缺少应用设计中心高级配置权限');
    }
    if (authoringSession.state !== 'ACTIVE') {
      throw new Error(`当前 ChangeSet 会话状态为 ${authoringSession.state}，不能继续编辑`);
    }
    if (!hasOwnedWriterLease(authoringSession)) {
      throw new Error('当前会话不再持有 Writer lease，不能继续编辑');
    }

    const baseSession = documentBaselineRef.current ?? authoringSession;
    if (authoringSession.revision !== baseSession.revision) {
      openStudioConflict(baseSession, nextDocument, authoringSession);
      throw new Error('服务器已有更新，已进入 Base / Mine / Latest 三方冲突裁决');
    }

    try {
      const savedSession = await persistContextualStudioDocument(nextDocument, baseSession);
      const canonicalDocument = authoringSnapshotToPageSchemaV3(savedSession.snapshot);
      documentBaselineRef.current = savedSession;
      setAuthoringSession(savedSession);
      setDocument(canonicalDocument);
      setStudioConflict(null);
      return canonicalDocument;
    } catch (saveError) {
      let latestSession: AuthoringSession | null = null;
      try {
        latestSession = await loadAuthoringSession(baseSession.sessionPid);
      } catch {
        // Preserve the original save error when the conflict probe cannot refresh.
      }
      if (
        latestSession &&
        latestSession.revision > baseSession.revision &&
        hasOwnedWriterLease(latestSession)
      ) {
        openStudioConflict(baseSession, nextDocument, latestSession);
        throw new Error('旧修订未写入；已进入 Base / Mine / Latest 三方冲突裁决');
      }
      throw saveError;
    }
  };

  const handleConflictResolution = async (
    resolutions: Record<string, StudioMergeResolution>,
  ) => {
    if (!studioConflict || conflictPending) return;
    setConflictPending(true);
    setConflictError(null);
    const baseSession = studioConflict.latestSession;
    try {
      const resolvedDocument = resolveStudioThreeWayMerge(studioConflict.merge, resolutions);
      const savedSession = await persistContextualStudioDocument(resolvedDocument, baseSession);
      const canonicalDocument = authoringSnapshotToPageSchemaV3(savedSession.snapshot);
      documentBaselineRef.current = savedSession;
      setAuthoringSession(savedSession);
      setDocument(canonicalDocument);
      setStudioConflict(null);
      setWorkbenchGeneration((current) => current + 1);
    } catch (resolutionError) {
      try {
        const newerSession = await loadAuthoringSession(baseSession.sessionPid);
        if (
          newerSession.revision > baseSession.revision &&
          hasOwnedWriterLease(newerSession)
        ) {
          const resolvedDocument = resolveStudioThreeWayMerge(studioConflict.merge, resolutions);
          openStudioConflict(baseSession, resolvedDocument, newerSession);
          setConflictError('裁决期间服务器再次更新，请基于新的 Latest 复核');
          return;
        }
      } catch {
        // Surface the original resolution error when the refresh probe also fails.
      }
      setConflictError(
        resolutionError instanceof Error ? resolutionError.message : '无法保存三方冲突裁决',
      );
    } finally {
      setConflictPending(false);
    }
  };

  const handleUseLatest = async () => {
    if (!studioConflict || conflictPending) return;
    setConflictError(null);
    setConflictPending(true);
    try {
      const refreshed = await loadAuthoringSession(studioConflict.latestSession.sessionPid);
      const latestSession =
        refreshed.revision >= studioConflict.latestSession.revision
          ? refreshed
          : studioConflict.latestSession;
      const latestDocument = authoringSnapshotToPageSchemaV3(latestSession.snapshot);
      documentBaselineRef.current = latestSession;
      setAuthoringSession(latestSession);
      setDocument(latestDocument);
      setStudioConflict(null);
      setWorkbenchGeneration((current) => current + 1);
    } catch (latestError) {
      setConflictError(
        latestError instanceof Error ? latestError.message : '无法读取服务器 Latest',
      );
    } finally {
      setConflictPending(false);
    }
  };

  const handleGovernanceAction = async (
    action: AuthoringGovernanceAction,
    reason: string,
  ) => {
    if (!authoringSession || governancePending) return;
    setGovernancePending(action);
    setGovernanceError(null);
    try {
      await transitionAuthoringGovernance(action, authoringSession, reason);
      const latest = reviewWorkspaceMode
        ? (await loadAuthoringReviewWorkspace(authoringSession.sessionPid)).session
        : await loadAuthoringSession(authoringSession.sessionPid);
      const canonicalDocument = authoringSnapshotToPageSchemaV3(latest.snapshot);
      documentBaselineRef.current = latest;
      setAuthoringSession(latest);
      setDocument(canonicalDocument);
      setStudioConflict(null);
      setConflictError(null);
      setWorkbenchGeneration((current) => current + 1);
    } catch (governanceFailure) {
      setGovernanceError(
        governanceFailure instanceof Error
          ? governanceFailure.message
          : '无法完成 ChangeSet 治理操作',
      );
    } finally {
      setGovernancePending(null);
    }
  };

  const handleWriterLeaseTakeover = async (reason: string) => {
    if (
      reviewWorkspaceMode ||
      !handoff ||
      !authoringSession ||
      leaseTakeoverPending ||
      !canAdministerDesigner
    )
      return;
    setLeaseTakeoverPending(true);
    setLeaseTakeoverError(null);
    try {
      const taken = await takeoverAuthoringWriterLease(
        authoringSession.sessionPid,
        authoringSession.revision,
        reason,
      );
      const canonicalDocument = authoringSnapshotToPageSchemaV3(taken.snapshot);
      documentBaselineRef.current = taken;
      setAuthoringSession(taken);
      if (studioConflict && authoringCapabilities) {
        setDocument(studioConflict.mineDocument);
        setStudioConflict({
          ...studioConflict,
          latestSession: taken,
          merge: buildStudioThreeWayMerge(
            studioConflict.baseDocument,
            studioConflict.mineDocument,
            canonicalDocument,
            authoringCapabilities,
          ),
        });
        setConflictError('已取得编辑权；请基于接管后的 Latest 重新复核冲突');
      } else {
        setDocument(canonicalDocument);
        setStudioConflict(null);
        setConflictError(null);
      }
      setWorkbenchGeneration((current) => current + 1);
      setHandoff((current) =>
        current
          ? { ...current, sessionPid: taken.sessionPid, revision: taken.revision }
          : current,
      );
      replaceAuthoringSessionUrl(taken.sessionPid);
    } catch (takeoverError) {
      setLeaseTakeoverError(
        takeoverError instanceof Error ? takeoverError.message : '无法接管 ChangeSet 编辑权',
      );
    } finally {
      setLeaseTakeoverPending(false);
    }
  };

  const handlePublish = async (pid: string): Promise<boolean> => {
    const result = await publishPageSchemaV3({ pid });
    if (!result.ok) {
      throw new Error(result.error || 'Failed to publish page.');
    }
    setPublished(result.status === 'published');
    return result.status === 'published';
  };

  const handleUnpublish = async (pid: string): Promise<boolean> => {
    const result = await unpublishPageSchemaV3({ pid });
    if (!result.ok) {
      throw new Error(result.error || 'Failed to unpublish page.');
    }
    setPublished(false);
    return true;
  };

  // Reload the page document from the backend after a version rollback. The
  // backend has restored the target snapshot's blocks onto the live page, so we
  // re-read it and return the V3 document for the workbench to reset its canvas.
  const handleReloadDocument = async (pid: string): Promise<PageSchemaV3 | null> => {
    const loaded = await loadPageSchemaV3({ pageId: pid });
    setSource(loaded.source);
    setPublished(loaded.published);
    setDocument(loaded.document);
    return loaded.document;
  };

  if (handoffError || error) {
    return (
      <div className="grid min-h-[420px] place-items-center bg-slate-100 p-6 text-sm text-red-700">
        <div className="max-w-lg rounded-lg border border-red-200 bg-white p-5 shadow-sm">
          <div className="font-semibold">无法恢复现场配置上下文</div>
          <div className="mt-2">{handoffError || error}</div>
          <a href="/" className="mt-4 inline-flex text-blue-700 hover:underline">返回首页</a>
        </div>
      </div>
    );
  }

  if (resolvingHandoff || !document) {
    return (
      <div className="grid min-h-[420px] place-items-center bg-slate-100 p-6 text-sm text-slate-500">
        {resolvingHandoff ? '正在验证一次性配置上下文…' : 'Loading unified designer...'}
      </div>
    );
  }

  const workbenchKey = handoff
    ? `authoring:${handoff.sessionPid}:${workbenchGeneration}`
    : getWorkbenchKey(document, source);
  const contextualEditablePropertyPaths =
    handoff
      ? !reviewWorkspaceMode && canAdministerDesigner && authoringCapabilities
        ? studioEditablePropertyPaths(authoringCapabilities)
        : {}
      : undefined;
  const contextualReorderableBlockTypes =
    handoff && !reviewWorkspaceMode && canAdministerDesigner && authoringCapabilities
      ? studioReorderableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualReadOnly = Boolean(
    handoff &&
      (reviewWorkspaceMode ||
        !canAdministerDesigner ||
        authoringSession?.state !== 'ACTIVE' ||
        !hasOwnedWriterLease(authoringSession) ||
        Boolean(studioConflict)),
  );

  const workbench = (
    <UnifiedDesignerWorkbench
      key={workbenchKey}
      initialDocument={document}
      modelFieldsByModel={modelFieldsByModel}
      returnHref={
        handoff
          ? authoringReturnHref(handoff.returnTo, handoff.sessionPid, handoff.blockId)
          : source.type === 'page'
            ? '/p/page_schema'
            : undefined
      }
      onSave={handoff ? handleContextualStudioSave : handleSave}
      pageId={!handoff && source.type === 'page' ? source.pid : undefined}
      initialPublished={source.type === 'page' ? published : false}
      onPublish={source.type === 'page' && !handoff ? handlePublish : undefined}
      onUnpublish={source.type === 'page' && !handoff ? handleUnpublish : undefined}
      onReloadDocument={source.type === 'page' && !handoff ? handleReloadDocument : undefined}
      initialSelectedBlockId={handoff?.blockId || undefined}
      contextualReadOnly={contextualReadOnly}
      contextualEditablePropertyPaths={contextualEditablePropertyPaths}
      contextualReorderableBlockTypes={contextualReorderableBlockTypes}
    />
  );

  if (!handoff) return workbench;

  return (
    <div className="flex min-h-[calc(100vh-4rem)] flex-col" data-testid="studio-handoff-context">
      <div className="border-b border-blue-200 bg-blue-50 px-4 py-3 text-sm text-blue-900">
        <strong>已从现场配置安全移交</strong>
        {contextualReadOnly ? (
          <span className="ml-2" data-testid="studio-handoff-read-only-reason">
            ChangeSet {handoff.changeSetPid} · 修订 r{authoringSession?.revision ?? handoff.revision} ·
            {studioReadOnlyReason(
              authoringSession,
              canAdministerDesigner,
              Boolean(studioConflict),
              reviewWorkspaceMode,
            )}，当前仅可查看隔离草稿。
          </span>
        ) : (
          <span className="ml-2" data-testid="studio-handoff-editable-reason">
            ChangeSet {handoff.changeSetPid} · 修订 r{authoringSession?.revision ?? handoff.revision} ·
            高级属性和已声明的同级顺序调整将写回同一隔离草稿；跨父级、增删区块等治理操作仍不开放。
          </span>
        )}
      </div>
      <div className="px-4 pt-3">
        <AuthoringGovernanceNotice
          session={authoringSession!}
          currentUserId={user?.id}
          canManage={!reviewWorkspaceMode && canManageDesigner}
          canReview={reviewWorkspaceMode && canReviewAuthoring}
          pendingAction={governancePending}
          error={governanceError}
          onAction={handleGovernanceAction}
        />
        {!reviewWorkspaceMode &&
        authoringSession?.writerLease &&
        authoringSession.writerLease.status !== 'OWNED' ? (
          <div className="mt-2">
            <AuthoringWriterLeaseNotice
              lease={authoringSession.writerLease}
              canTakeover={canAdministerDesigner}
              pending={leaseTakeoverPending}
              onTakeover={handleWriterLeaseTakeover}
            />
          </div>
        ) : null}
        {leaseTakeoverError ? (
          <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
            {leaseTakeoverError}
          </div>
        ) : null}
      </div>
      {studioConflict ? (
        <AuthoringConflictResolutionPanel
          key={`${studioConflict.baseRevision}:${studioConflict.latestSession.revision}`}
          merge={studioConflict.merge}
          baseRevision={studioConflict.baseRevision}
          latestRevision={studioConflict.latestSession.revision}
          pending={conflictPending}
          error={conflictError}
          onResolve={handleConflictResolution}
          onUseLatest={handleUseLatest}
        />
      ) : null}
      <div className="min-h-0 flex-1">{workbench}</div>
    </div>
  );
}

function assertHandoffMatchesSession(
  handoff: HandoffContext,
  session: AuthoringSession,
): void {
  if (
    handoff.sessionPid !== session.sessionPid ||
    handoff.changeSetPid !== session.changeSetPid ||
    handoff.pagePid !== session.pagePid
  ) {
    throw new Error('配置移交上下文与隔离会话不一致');
  }
}

function resumeHandoffFromSession(session: AuthoringSession): HandoffContext {
  const selection = safeContextString(session.interactionContext.selection);
  const outlinePath = Array.isArray(session.interactionContext.outlinePath)
    ? session.interactionContext.outlinePath.filter(
        (item): item is string => typeof item === 'string' && item.length > 0,
      )
    : [];
  return {
    pagePid: session.pagePid,
    changeSetPid: session.changeSetPid,
    sessionPid: session.sessionPid,
    revision: session.revision,
    intent: 'PAGE_STRUCTURE',
    targetRoute: '/unified-designer',
    returnTo: safeReturnTo(session.interactionContext.route),
    blockId: selection || outlinePath.at(-1) || null,
    propertyPath: null,
    interactionContext: session.interactionContext,
    expiresAt: session.expiresAt,
  };
}

function replaceAuthoringContextUrl(
  parameter: 'contextId' | 'changeSetId' | 'conflictContext',
  sessionPid: string,
): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.delete(parameter);
  url.searchParams.delete('reviewSession');
  url.searchParams.set('authoringSession', sessionPid);
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
}

function replaceAuthoringReviewContextUrl(
  parameter: 'reviewChangeSetId',
  sessionPid: string,
): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.delete(parameter);
  url.searchParams.delete('authoringSession');
  url.searchParams.set('reviewSession', sessionPid);
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
}

function replaceAuthoringSessionUrl(sessionPid: string): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.set('authoringSession', sessionPid);
  window.history.replaceState(window.history.state, '', `${url.pathname}${url.search}${url.hash}`);
}

function hasOwnedWriterLease(session: AuthoringSession | null): boolean {
  return Boolean(session && (!session.writerLease || session.writerLease.status === 'OWNED'));
}

function studioReadOnlyReason(
  session: AuthoringSession | null,
  canAdministerDesigner: boolean,
  hasConflict: boolean,
  reviewWorkspace: boolean,
): string {
  if (reviewWorkspace) return '评审工作区按当前 revision 只读';
  if (!canAdministerDesigner) return '缺少高级设计权限';
  if (hasConflict) return '存在待裁决的 Base / Mine / Latest 三方冲突';
  if (session?.writerLease?.status === 'EXPIRED') return 'Writer lease 已过期';
  if (session?.writerLease && session.writerLease.status !== 'OWNED') {
    return 'Writer lease 由其他会话持有';
  }
  return `会话状态为 ${session?.state ?? 'UNKNOWN'}`;
}

function safeReturnTo(value: unknown): string {
  const route = safeContextString(value);
  return route.startsWith('/') && !route.startsWith('//') ? route : '/';
}

function safeContextString(value: unknown): string {
  return typeof value === 'string' ? value : '';
}

function authoringReturnHref(
  returnTo: string,
  sessionPid: string,
  focusBlockId?: string | null,
): string {
  const safeRoute = safeReturnTo(returnTo);
  const url = new URL(safeRoute, window.location.origin);
  url.searchParams.set('authoringReturn', sessionPid);
  if (focusBlockId) url.searchParams.set('authoringFocus', focusBlockId);
  return `${url.pathname}${url.search}${url.hash}`;
}

function readLocalDocument(): PageSchemaV3 | null {
  if (typeof window === 'undefined') return null;

  const raw = window.localStorage.getItem(LOCAL_STORAGE_KEY);
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as PageSchemaV3;
    return parsed.schemaVersion === 3 && Array.isArray(parsed.blocks) ? parsed : null;
  } catch {
    return null;
  }
}

function mergeSampleModelFieldFallback(
  documentId: string | null,
  loadedFields: ModelFieldsByModel,
): ModelFieldsByModel {
  if (documentId !== samplePageSchemaV3.id) return loadedFields;

  const merged = { ...loadedFields };
  Object.entries(sampleModelFieldsByModel).forEach(([modelCode, sampleFields]) => {
    if (!merged[modelCode]?.length) {
      merged[modelCode] = sampleFields;
    }
  });
  return merged;
}

function getWorkbenchKey(document: PageSchemaV3, source: PageSchemaV3Source): string {
  if (source.type === 'page') {
    return ['page', source.pid, source.pageKey, document.id].filter(Boolean).join(':');
  }

  return `local:${document.id}`;
}
