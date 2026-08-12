import React, { useEffect, useRef, useState } from 'react';
import { useSearchParams } from 'react-router';
import { Settings2, ShieldCheck, X } from 'lucide-react';
import { usePermission, useUser } from '~/contexts/AuthContext';
import { RouteAccessDenied } from '~/ui/PermissionGuard';
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
import { findBlockById } from '../components/unified-designer/utils/recursiveBlockWalker';
import {
  applyAuthoringStudioBatch,
  consumeAuthoringHandoff,
  createAuthoringNewPageWorkspace,
  isAuthoringPermissionDeniedError,
  loadAuthoringCapabilities,
  loadAuthoringPermissionSnapshot,
  loadAuthoringNewPageWorkspaceOptions,
  loadAuthoringReviewWorkspace,
  loadAuthoringSession,
  openAuthoringReviewWorkspace,
  observeAuthoringChangeSet,
  prepareAuthoringSession,
  publishAuthoringChangeSet,
  renewAuthoringWriterLease,
  submitAuthoringSession,
  takeoverAuthoringWriterLease,
  transitionAuthoringGovernance,
} from '~/framework/meta/authoring/authoringService';
import {
  AUTHORING_WRITER_LEASE_HEARTBEAT_MS,
  shouldRenewAuthoringWriterLeaseInForeground,
} from '~/framework/meta/authoring/writerLeaseHeartbeat';
import {
  describeWriterLeaseTakeoverFailure,
  reconcileWriterLeaseTakeover,
  type WriterLeaseTakeoverReconciliation,
} from '~/framework/meta/authoring/writerLeaseTakeover';
import { reconcileAuthoringStudioDocument } from '~/framework/meta/authoring/saveReconciliation';
import { AuthoringWriterLeaseNotice } from '~/framework/meta/authoring/AuthoringWriterLeaseNotice';
import { AuthoringGovernanceNotice } from '~/framework/meta/authoring/AuthoringGovernanceNotice';
import { AuthoringRiskSummary } from '~/framework/meta/authoring/AuthoringRiskSummary';
import { AuthoringValidationNotice } from '~/framework/meta/authoring/AuthoringValidationNotice';
import { AuthoringImpactNotice } from '~/framework/meta/authoring/AuthoringImpactNotice';
import { AuthoringChangeSetSplitPanel } from '~/framework/meta/authoring/AuthoringChangeSetSplitPanel';
import { AuthoringReleaseHistoryPanel } from '~/framework/meta/authoring/AuthoringReleaseHistoryPanel';
import { AuthoringOwnershipNotice } from '~/framework/meta/authoring/AuthoringOwnershipNotice';
import { consumeAuthoringConflictTransfer } from '~/framework/meta/authoring/authoringConflictTransfer';
import {
  clearAuthoringRecoveriesForActor,
  clearInlineAuthoringRecovery,
  clearStudioAuthoringRecovery,
  readStudioAuthoringRecovery,
  storeStudioAuthoringRecovery,
  type AuthoringRecoveryPolicy,
} from '~/framework/meta/authoring/authoringLocalRecovery';
import {
  describeAuthoringRecoveryFailure,
  loadAuthoringRecoveryPolicy,
} from '~/framework/meta/authoring/authoringRecoveryPolicy';
import { AuthoringConflictResolutionPanel } from '../components/unified-designer/AuthoringConflictResolutionPanel';
import type {
  AuthoringSession,
  AuthoringGovernanceAction,
  AuthoringSplitResult,
  CapabilityRegistry,
  CreateNewPageWorkspaceInput,
  HandoffContext,
  NewPageWorkspaceOptions,
} from '~/framework/meta/authoring/types';
import {
  authoringSnapshotToPageSchemaV3,
  buildStudioThreeWayMerge,
  planStudioAuthoringPatches,
  resolveStudioThreeWayMerge,
  studioCreatableBlockTypes,
  studioEditablePropertyPaths,
  studioRelocatableBlockTypes,
  studioRemovableBlockTypes,
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
  const canReadDesigner = usePermission('meta.designer.read');
  const canAdministerDesigner = usePermission('meta.designer.admin');
  const canManageDesigner = usePermission('meta.designer.update');
  const canReviewAuthoring = usePermission('meta.publish.update');
  const canReadAuthoringReleases = usePermission('meta.publish.read');
  const canPublishAuthoring = usePermission('meta.publish.admin');
  const canAuditIdentitySimulation = usePermission('audit.trail.admin');
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
  const resumeStudioIntent = searchParams.get('studioIntent');
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
  const [leaseTakeoverFeedback, setLeaseTakeoverFeedback] = useState<{
    tone: 'warning' | 'success';
    message: string;
  } | null>(null);
  const [studioSaveReconciliationFeedback, setStudioSaveReconciliationFeedback] = useState<{
    tone: 'warning' | 'success';
    message: string;
  } | null>(null);
  const [studioPermissionRevoked, setStudioPermissionRevoked] = useState(false);
  const [studioRecoveredBaseDocument, setStudioRecoveredBaseDocument] =
    useState<PageSchemaV3 | null>(null);
  const [studioLocalRecoveryAvailable, setStudioLocalRecoveryAvailable] = useState(false);
  const [studioRecoveryPolicy, setStudioRecoveryPolicy] =
    useState<AuthoringRecoveryPolicy | null>(null);
  const [contextReloadGeneration, setContextReloadGeneration] = useState(0);
  const [studioConflict, setStudioConflict] = useState<StudioConflictState | null>(null);
  const [conflictPending, setConflictPending] = useState(false);
  const [conflictError, setConflictError] = useState<string | null>(null);
  const [governancePending, setGovernancePending] = useState<AuthoringGovernanceAction | null>(
    null,
  );
  const [governanceError, setGovernanceError] = useState<string | null>(null);
  const [submissionPending, setSubmissionPending] = useState(false);
  const [submissionError, setSubmissionError] = useState<string | null>(null);
  const [newPageOptions, setNewPageOptions] = useState<NewPageWorkspaceOptions | null>(null);
  const [newPagePending, setNewPagePending] = useState(false);
  const [newPageError, setNewPageError] = useState<string | null>(null);
  const [reviewWorkspaceMode, setReviewWorkspaceMode] = useState(false);
  const [governanceOpen, setGovernanceOpen] = useState(false);
  const [workbenchGeneration, setWorkbenchGeneration] = useState(0);
  const [document, setDocument] = useState<PageSchemaV3 | null>(null);
  const [source, setSource] = useState<PageSchemaV3Source>({ type: 'local' });
  const [published, setPublished] = useState(false);
  const [modelFieldsByModel, setModelFieldsByModel] = useState<ModelFieldsByModel>({});
  const [error, setError] = useState<string | null>(null);
  const documentBaselineRef = useRef<AuthoringSession | null>(null);
  const studioSaveFeedbackTimerRef = useRef<number | null>(null);
  const studioPermissionLossObservedRef = useRef(false);
  const recoveryActorId = user?.id == null ? null : String(user.id);
  const modelCodeKey = document ? collectModelCodesFromDocument(document).join('|') : '';
  const documentId = document?.id ?? null;
  const resolvingHandoff = Boolean(
    hasAuthoringContext &&
      !handoffError &&
      (!handoff || !authoringSession || !authoringCapabilities || !document),
  );
  const activeAuthoringSessionPid = authoringSession?.sessionPid;
  const activeAuthoringRevision = authoringSession?.revision;
  const activeWriterLeaseStatus = authoringSession?.writerLease?.status;
  const activeWriterLeaseUntil = authoringSession?.writerLease?.leasedUntil;
  const canWriteContextualStudio = canAdministerDesigner && !studioPermissionRevoked;

  useEffect(() => {
    if (!canAdministerDesigner) {
      studioPermissionLossObservedRef.current = true;
      return;
    }
    if (studioPermissionLossObservedRef.current) {
      studioPermissionLossObservedRef.current = false;
      setStudioPermissionRevoked(false);
    }
  }, [canAdministerDesigner]);

  useEffect(() => {
    if (!studioPermissionRevoked) return;
    let cancelled = false;
    const recheck = () => {
      void loadAuthoringPermissionSnapshot()
        .then((permission) => {
          if (!cancelled && permission.canAdministerDesigner) {
            setStudioPermissionRevoked(false);
          }
        })
        .catch(() => {
          // Preserve the local write lock until the authoritative permission check succeeds.
        });
    };
    const interval = window.setInterval(recheck, 15_000);
    window.addEventListener('focus', recheck);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
      window.removeEventListener('focus', recheck);
    };
  }, [studioPermissionRevoked]);

  useEffect(() => {
    if (reviewWorkspaceMode) setGovernanceOpen(true);
  }, [reviewWorkspaceMode]);

  useEffect(
    () => () => {
      if (studioSaveFeedbackTimerRef.current != null) {
        window.clearTimeout(studioSaveFeedbackTimerRef.current);
      }
    },
    [],
  );

  useEffect(() => {
    if (!hasAuthoringContext) {
      setHandoff(null);
      setAuthoringSession(null);
      setAuthoringCapabilities(null);
      setHandoffError(null);
      setLeaseTakeoverError(null);
      setLeaseTakeoverFeedback(null);
      setStudioSaveReconciliationFeedback(null);
      setStudioPermissionRevoked(false);
      setStudioRecoveredBaseDocument(null);
      setStudioLocalRecoveryAvailable(false);
      setStudioRecoveryPolicy(null);
      setStudioConflict(null);
      setConflictError(null);
      setSubmissionError(null);
      setReviewWorkspaceMode(false);
      setGovernanceOpen(false);
      setNewPageOptions(null);
      setNewPageError(null);
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
    setLeaseTakeoverFeedback(null);
    setStudioSaveReconciliationFeedback(null);
    setStudioRecoveredBaseDocument(null);
    setStudioLocalRecoveryAvailable(false);
    setStudioConflict(null);
    setConflictError(null);
    setSubmissionError(null);
    setReviewWorkspaceMode(false);
    setGovernanceOpen(false);
    setNewPageOptions(null);
    setNewPageError(null);
    documentBaselineRef.current = null;
    const resolveContext = contextId
      ? consumeAuthoringHandoff(contextId).then(async (consumed) => {
          const [session, capabilities] = await Promise.all([
            loadAuthoringSession(consumed.sessionPid),
            loadAuthoringCapabilities(),
          ]);
          assertHandoffMatchesSession(consumed, session);
          replaceAuthoringContextUrl('contextId', consumed.sessionPid, consumed.intent);
          return { handoff: consumed, session, capabilities, reviewWorkspace: false };
        })
      : observedReviewChangeSetPid
        ? openAuthoringReviewWorkspace(observedReviewChangeSetPid).then(
            ({ session, capabilities }) => {
              replaceAuthoringReviewContextUrl('reviewChangeSetId', session.sessionPid);
              return {
                handoff: resumeHandoffFromSession(session, resumeStudioIntent),
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
                handoff: resumeHandoffFromSession(session, resumeStudioIntent),
                session,
                capabilities,
                reviewWorkspace: false,
              };
            })
          : resumeReviewSessionPid
            ? loadAuthoringReviewWorkspace(resumeReviewSessionPid).then(
                ({ session, capabilities }) => ({
                  handoff: resumeHandoffFromSession(session, resumeStudioIntent),
                  session,
                  capabilities,
                  reviewWorkspace: true,
                }),
              )
            : Promise.all([
                loadAuthoringSession(resumeSessionPid!),
                loadAuthoringCapabilities(),
              ]).then(([session, capabilities]) => ({
                  handoff: resumeHandoffFromSession(session, resumeStudioIntent),
                session,
                capabilities,
                reviewWorkspace: false,
              }));

    const recoveryPolicyRequest = loadAuthoringRecoveryPolicy();
    void Promise.all([resolveContext, recoveryPolicyRequest])
      .then(
        ([
          { handoff: resolvedHandoff, session, capabilities, reviewWorkspace },
          resolvedRecoveryPolicy,
        ]) => {
        if (!cancelled) {
          setStudioRecoveryPolicy(resolvedRecoveryPolicy);
          if (resolvedRecoveryPolicy === 'DISABLED' && recoveryActorId) {
            clearAuthoringRecoveriesForActor(recoveryActorId);
          }
          let isolatedDocument = authoringSnapshotToPageSchemaV3(session.snapshot);
          let recoveredSavedDocument: PageSchemaV3 | null = null;
          if (conflictContextId) {
            const transfer = consumeAuthoringConflictTransfer(conflictContextId, {
              sessionPid: session.sessionPid,
              changeSetPid: session.changeSetPid,
              pagePid: session.pagePid,
            });
            if (transfer.baseRevision >= session.revision) {
              throw new Error('三方冲突上下文的 Base 修订不早于 Latest');
            }
            const latestDocument = isolatedDocument;
            const baseDocument = authoringSnapshotToPageSchemaV3(transfer.baseSnapshot);
            const mineDocument = authoringSnapshotToPageSchemaV3(transfer.mineSnapshot);
            const merge = buildStudioThreeWayMerge(
              baseDocument,
              mineDocument,
              isolatedDocument,
              capabilities,
            );
            isolatedDocument = mineDocument;
            recoveredSavedDocument = latestDocument;
            setStudioConflict({
              baseRevision: transfer.baseRevision,
              baseDocument,
              mineDocument,
              latestSession: session,
              merge,
            });
            if (recoveryActorId) {
              const migrated = storeStudioAuthoringRecovery(
                {
                  actorId: recoveryActorId,
                  sessionPid: session.sessionPid,
                  pagePid: session.pagePid,
                  baseRevision: transfer.baseRevision,
                  state: 'DIRTY',
                  baseSnapshot: transfer.baseSnapshot,
                  mineDocument,
                },
                resolvedRecoveryPolicy,
              );
              setStudioLocalRecoveryAvailable(migrated);
              if (migrated) {
                clearInlineAuthoringRecovery(
                  recoveryActorId,
                  session.pagePid,
                  session.sessionPid,
                );
              } else {
                setStudioSaveReconciliationFeedback({
                  tone: 'warning',
                  message: describeAuthoringRecoveryFailure(resolvedRecoveryPolicy),
                });
              }
            }
            replaceAuthoringContextUrl('conflictContext', session.sessionPid);
          } else if (!reviewWorkspace && recoveryActorId) {
            const recovery = readStudioAuthoringRecovery(
              recoveryActorId,
              session.sessionPid,
              resolvedRecoveryPolicy,
            );
            if (recovery) {
              if (recovery.pagePid !== session.pagePid) {
                clearStudioAuthoringRecovery(recoveryActorId, session.sessionPid);
              } else {
                const baseDocument = authoringSnapshotToPageSchemaV3(recovery.baseSnapshot);
                const mineDocument = recovery.mineDocument;
                const reconciliation = reconcileAuthoringStudioDocument(
                  baseDocument,
                  mineDocument,
                  isolatedDocument,
                );
                if (reconciliation === 'COMMITTED') {
                  clearStudioAuthoringRecovery(recoveryActorId, session.sessionPid);
                  setStudioLocalRecoveryAvailable(false);
                  setStudioSaveReconciliationFeedback({
                    tone: 'success',
                    message:
                      '页面进程中断前的 Studio 保存已由权威草稿确认完成，已恢复最新文档且未重复写入。',
                  });
                } else {
                  recoveredSavedDocument = isolatedDocument;
                  isolatedDocument = mineDocument;
                  setStudioLocalRecoveryAvailable(true);
                  if (reconciliation === 'CONFLICT') {
                    setStudioConflict({
                      baseRevision: recovery.baseRevision,
                      baseDocument,
                      mineDocument,
                      latestSession: session,
                      merge: buildStudioThreeWayMerge(
                        baseDocument,
                        mineDocument,
                        recoveredSavedDocument,
                        capabilities,
                      ),
                    });
                    setStudioSaveReconciliationFeedback({
                      tone: 'warning',
                      message:
                        '已恢复页面进程中断前的完整 Studio 文档；权威草稿已有第三值，需先完成 Base / Mine / Latest 裁决。',
                    });
                  } else {
                    setStudioSaveReconciliationFeedback({
                      tone: 'warning',
                      message:
                        '已恢复页面进程中断前的完整 Studio 文档，并确认尚未写入权威草稿；请复核后重试保存。',
                    });
                  }
                }
              }
            }
          }
          setHandoff(resolvedHandoff);
          setAuthoringSession(session);
          setAuthoringCapabilities(capabilities);
          setReviewWorkspaceMode(reviewWorkspace || session.workspaceMode === 'REVIEW');
          documentBaselineRef.current = session;
          setStudioRecoveredBaseDocument(recoveredSavedDocument);
          setDocument(isolatedDocument);
          setSource({
            type: 'page',
            pid: session.pagePid,
            pageKey: isolatedDocument.pageKey,
          });
          setPublished(false);
          setStudioPermissionRevoked(false);
        }
      },
      )
      .catch(async (consumeError) => {
        if (!cancelled) {
          const recoverable = await recoveryPolicyRequest
            .then((policy) => {
              setStudioRecoveryPolicy(policy);
              return Boolean(
                policy !== 'DISABLED' &&
                  recoveryActorId &&
                  resumeSessionPid &&
                  readStudioAuthoringRecovery(recoveryActorId, resumeSessionPid, policy),
              );
            })
            .catch(() => false);
          if (cancelled) return;
          setStudioLocalRecoveryAvailable(recoverable);
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
    resumeStudioIntent,
    recoveryActorId,
    contextReloadGeneration,
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
    if (
      !activeAuthoringSessionPid ||
      activeWriterLeaseStatus !== 'OWNED' ||
      !activeWriterLeaseUntil ||
      reviewWorkspaceMode
    ) {
      return;
    }
    let cancelled = false;
    const refreshLease = () => {
      if (
        !shouldRenewAuthoringWriterLeaseInForeground(
          activeWriterLeaseUntil,
          window.document.visibilityState,
          window.document.hasFocus(),
        )
      ) {
        return;
      }
      void renewAuthoringWriterLease(activeAuthoringSessionPid)
        .then((renewed) => {
          if (!cancelled && renewed.revision >= (activeAuthoringRevision ?? -1)) {
            setAuthoringSession(renewed);
          }
        })
        .catch(() => {
          void loadAuthoringSession(activeAuthoringSessionPid)
            .then((latest) => {
              if (!cancelled && latest.revision >= (activeAuthoringRevision ?? -1)) {
                setAuthoringSession(latest);
              }
            })
            .catch(() => {
              // Preserve the isolated document; the next foreground action remains fail-closed.
            });
        });
    };
    const onResume = () => {
      if (window.document.visibilityState === 'visible' && window.document.hasFocus()) {
        refreshLease();
      }
    };
    const interval = window.setInterval(refreshLease, AUTHORING_WRITER_LEASE_HEARTBEAT_MS);
    window.addEventListener('focus', refreshLease);
    window.document.addEventListener('visibilitychange', onResume);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
      window.removeEventListener('focus', refreshLease);
      window.document.removeEventListener('visibilitychange', onResume);
    };
  }, [
    activeAuthoringRevision,
    activeAuthoringSessionPid,
    activeWriterLeaseStatus,
    activeWriterLeaseUntil,
    reviewWorkspaceMode,
  ]);

  useEffect(() => {
    if (handoff?.intent !== 'NEW_PAGE' || !canAdministerDesigner) return;
    let cancelled = false;
    setNewPageError(null);
    void loadAuthoringNewPageWorkspaceOptions()
      .then((options) => {
        if (!cancelled) setNewPageOptions(options);
      })
      .catch((optionsError) => {
        if (!cancelled) {
          setNewPageError(
            optionsError instanceof Error ? optionsError.message : '无法加载新页面创建选项',
          );
        }
      });
    return () => {
      cancelled = true;
    };
  }, [canAdministerDesigner, handoff?.intent]);

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

  const storeStudioRecoveryForDocument = (
    nextDocument: PageSchemaV3,
    baseSession: AuthoringSession,
    state: 'DIRTY' | 'UNKNOWN_OUTCOME',
  ) => {
    if (!recoveryActorId || !studioRecoveryPolicy) return;
    const stored = storeStudioAuthoringRecovery(
      {
        actorId: recoveryActorId,
        sessionPid: baseSession.sessionPid,
        pagePid: baseSession.pagePid,
        baseRevision: baseSession.revision,
        state,
        baseSnapshot: baseSession.snapshot,
        mineDocument: nextDocument,
      },
      studioRecoveryPolicy,
    );
    setStudioLocalRecoveryAvailable(stored);
    if (!stored) {
      setStudioSaveReconciliationFeedback({
        tone: 'warning',
        message: describeAuthoringRecoveryFailure(studioRecoveryPolicy),
      });
    }
  };

  const handleContextualStudioDocumentChange = (
    nextDocument: PageSchemaV3,
    dirty: boolean,
  ) => {
    if (!handoff || !authoringSession || !recoveryActorId) return;
    if (!dirty) {
      clearStudioAuthoringRecovery(recoveryActorId, authoringSession.sessionPid);
      setStudioLocalRecoveryAvailable(false);
      setStudioRecoveredBaseDocument(null);
      return;
    }
    storeStudioRecoveryForDocument(
      nextDocument,
      documentBaselineRef.current ?? authoringSession,
      'DIRTY',
    );
  };

  const persistContextualStudioDocument = async (
    nextDocument: PageSchemaV3,
    startingSession: AuthoringSession,
  ): Promise<AuthoringSession> => {
    if (!authoringCapabilities) throw new Error('应用设计中心能力清单尚未就绪');
    const baseline = authoringSnapshotToPageSchemaV3(startingSession.snapshot);
    const plan = planStudioAuthoringPatches(baseline, nextDocument, authoringCapabilities);
    if (plan.unsupported.length > 0) throw new Error(plan.unsupported.join('；'));

    const result = await applyAuthoringStudioBatch(
      startingSession.sessionPid,
      startingSession.revision,
      plan,
    );
    setAuthoringSession(result.session);
    return result.session;
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
    if (!canWriteContextualStudio) {
      throw new Error('缺少应用设计中心高级配置权限');
    }
    if (authoringSession.state !== 'ACTIVE') {
      throw new Error(`当前 ChangeSet 会话状态为 ${authoringSession.state}，不能继续编辑`);
    }
    if (!hasOwnedWriterLease(authoringSession)) {
      throw new Error('当前会话不再持有 Writer lease，不能继续编辑');
    }
    if (studioSaveFeedbackTimerRef.current != null) {
      window.clearTimeout(studioSaveFeedbackTimerRef.current);
      studioSaveFeedbackTimerRef.current = null;
    }
    setStudioSaveReconciliationFeedback(null);

    const baseSession = documentBaselineRef.current ?? authoringSession;
    if (authoringSession.revision !== baseSession.revision) {
      openStudioConflict(baseSession, nextDocument, authoringSession);
      throw new Error('服务器已有更新，已进入 Base / Mine / Latest 三方冲突裁决');
    }

    storeStudioRecoveryForDocument(nextDocument, baseSession, 'UNKNOWN_OUTCOME');
    try {
      const savedSession = await persistContextualStudioDocument(nextDocument, baseSession);
      const canonicalDocument = authoringSnapshotToPageSchemaV3(savedSession.snapshot);
      documentBaselineRef.current = savedSession;
      setAuthoringSession(savedSession);
      setDocument(canonicalDocument);
      setStudioConflict(null);
      if (recoveryActorId) {
        clearStudioAuthoringRecovery(recoveryActorId, savedSession.sessionPid);
      }
      setStudioLocalRecoveryAvailable(false);
      setStudioRecoveredBaseDocument(null);
      setStudioSaveReconciliationFeedback(null);
      return canonicalDocument;
    } catch (saveError) {
      const [sessionProbe, permissionProbe] = await Promise.allSettled([
        loadAuthoringSession(baseSession.sessionPid),
        loadAuthoringPermissionSnapshot(),
      ]);
      const permissionSnapshot =
        permissionProbe.status === 'fulfilled' ? permissionProbe.value : null;
      const permissionRevoked =
        isAuthoringPermissionDeniedError(saveError) ||
        Boolean(permissionSnapshot && !permissionSnapshot.canAdministerDesigner);
      if (permissionRevoked) setStudioPermissionRevoked(true);
      if (sessionProbe.status === 'rejected') {
        if (permissionRevoked) {
          throw new Error(
            '保存未完成；应用设计中心高级配置权限已收回，本地未保存变更已保留且未重放',
          );
        }
        throw new Error('保存结果暂时无法确认；无法读取权威草稿，请联网后重试');
      }
      const latestSession = sessionProbe.value;
      const latestSessionWritable =
        latestSession.state === 'ACTIVE' &&
        hasOwnedWriterLease(latestSession) &&
        !permissionRevoked;
      if (latestSession.revision > baseSession.revision) {
        const baseDocument = authoringSnapshotToPageSchemaV3(baseSession.snapshot);
        const latestDocument = authoringSnapshotToPageSchemaV3(latestSession.snapshot);
        if (
          reconcileAuthoringStudioDocument(baseDocument, nextDocument, latestDocument) ===
          'COMMITTED'
        ) {
          documentBaselineRef.current = latestSession;
          setAuthoringSession(latestSession);
          setDocument(latestDocument);
          setStudioConflict(null);
          if (recoveryActorId) {
            clearStudioAuthoringRecovery(recoveryActorId, latestSession.sessionPid);
          }
          setStudioLocalRecoveryAvailable(false);
          setStudioRecoveredBaseDocument(null);
          setStudioSaveReconciliationFeedback({
            tone: latestSessionWritable ? 'success' : 'warning',
            message: latestSessionWritable
              ? '保存已在服务端完成；响应虽中断，应用设计中心已按权威草稿恢复，未重复写入。'
              : permissionRevoked
                ? '保存已在服务端完成；应用设计中心高级配置权限已收回，应用设计中心已按权威草稿恢复为只读。'
                : `保存已在服务端完成；${studioSaveAuthorityLabel(latestSession)}，应用设计中心已按权威草稿恢复为只读。`,
          });
          studioSaveFeedbackTimerRef.current = window.setTimeout(() => {
            setStudioSaveReconciliationFeedback(null);
            studioSaveFeedbackTimerRef.current = null;
          }, 10_000);
          return latestDocument;
        }
        storeStudioRecoveryForDocument(nextDocument, baseSession, 'DIRTY');
        if (!latestSessionWritable) {
          setAuthoringSession(latestSession);
          setStudioConflict(null);
          throw new Error(
            permissionRevoked
              ? '保存未完成；应用设计中心高级配置权限已收回，本地未保存变更已保留且未重放'
              : `保存未完成；${studioSaveAuthorityLabel(latestSession)}，本地未保存变更已保留且未重放`,
          );
        }
        openStudioConflict(baseSession, nextDocument, latestSession);
        throw new Error('旧修订未写入；已进入 Base / Mine / Latest 三方冲突裁决');
      }
      if (!latestSessionWritable) {
        storeStudioRecoveryForDocument(nextDocument, baseSession, 'DIRTY');
        setAuthoringSession(latestSession);
        setStudioConflict(null);
        throw new Error(
          permissionRevoked
            ? '保存未完成；应用设计中心高级配置权限已收回，本地未保存变更已保留且未重放'
            : `保存未完成；${studioSaveAuthorityLabel(latestSession)}，本地未保存变更已保留且未重放`,
        );
      }
      storeStudioRecoveryForDocument(nextDocument, baseSession, 'DIRTY');
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
      if (recoveryActorId) {
        clearStudioAuthoringRecovery(recoveryActorId, savedSession.sessionPid);
      }
      setStudioLocalRecoveryAvailable(false);
      setStudioRecoveredBaseDocument(null);
      setStudioSaveReconciliationFeedback(null);
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
      if (recoveryActorId) {
        clearStudioAuthoringRecovery(recoveryActorId, latestSession.sessionPid);
      }
      setStudioLocalRecoveryAvailable(false);
      setStudioRecoveredBaseDocument(null);
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
      if (action === 'publish') {
        await publishAuthoringChangeSet(
          authoringSession.changeSetPid,
          authoringSession.revision,
        );
      } else {
        await transitionAuthoringGovernance(action, authoringSession, reason);
      }
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

  const handlePrepareOrSubmit = async () => {
    if (
      reviewWorkspaceMode ||
      !authoringSession ||
      !canManageDesigner ||
      authoringSession.state !== 'ACTIVE' ||
      !hasOwnedWriterLease(authoringSession) ||
      studioConflict ||
      submissionPending
    )
      return;
    setSubmissionPending(true);
    setSubmissionError(null);
    try {
      const prepared =
        authoringSession.validationState === 'VALID' &&
        authoringSession.impactState === 'KNOWN';
      const latest = prepared
        ? await submitAndReloadAuthoringSession(authoringSession)
        : await prepareAuthoringSession(
            authoringSession.sessionPid,
            authoringSession.revision,
          );
      const canonicalDocument = authoringSnapshotToPageSchemaV3(latest.snapshot);
      documentBaselineRef.current = latest;
      setAuthoringSession(latest);
      setDocument(canonicalDocument);
      setWorkbenchGeneration((current) => current + 1);
    } catch (submissionFailure) {
      setSubmissionError(
        submissionFailure instanceof Error
          ? submissionFailure.message
          : '无法完成校验或提交评审',
      );
      try {
        const latest = await loadAuthoringSession(authoringSession.sessionPid);
        const canonicalDocument = authoringSnapshotToPageSchemaV3(latest.snapshot);
        documentBaselineRef.current = latest;
        setAuthoringSession(latest);
        setDocument(canonicalDocument);
        setWorkbenchGeneration((current) => current + 1);
      } catch {
        // Keep the original prepare/submit failure visible when the refresh also fails.
      }
    } finally {
      setSubmissionPending(false);
    }
  };

  const handleReleaseRolledBack = async () => {
    if (!authoringSession || reviewWorkspaceMode) return;
    const latest = await loadAuthoringSession(authoringSession.sessionPid);
    documentBaselineRef.current = latest;
    setAuthoringSession(latest);
    setDocument(authoringSnapshotToPageSchemaV3(latest.snapshot));
    setWorkbenchGeneration((current) => current + 1);
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
    setLeaseTakeoverFeedback(null);
    const observedLeaseRevision = authoringSession.writerLease?.revision ?? 0;
    const applyTakenSession = (taken: AuthoringSession) => {
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
    };
    try {
      const taken = await takeoverAuthoringWriterLease(
        authoringSession.sessionPid,
        authoringSession.revision,
        authoringSession.writerLease?.revision ?? 0,
        reason,
      );
      applyTakenSession(taken);
    } catch (takeoverError) {
      let authoritativeReloaded = false;
      let reconciliation: WriterLeaseTakeoverReconciliation = 'UNCHANGED';
      try {
        const latest = await loadAuthoringSession(authoringSession.sessionPid);
        authoritativeReloaded = true;
        reconciliation = reconcileWriterLeaseTakeover(
          observedLeaseRevision,
          latest.writerLease,
        );
        if (reconciliation === 'COMMITTED_HERE') applyTakenSession(latest);
        else {
          documentBaselineRef.current = latest;
          setAuthoringSession(latest);
          setHandoff((current) =>
            current ? { ...current, revision: latest.revision } : current,
          );
        }
      } catch {
        // Keep the current read-only document when the authoritative reload also fails.
      }
      if (reconciliation === 'COMMITTED_HERE') {
        setLeaseTakeoverFeedback({
          tone: 'success',
          message: '接管已在服务端完成，当前页面已恢复编辑；本地内容未被覆盖。',
        });
      } else if (reconciliation === 'COMMITTED_ELSEWHERE') {
        setLeaseTakeoverFeedback({
          tone: 'warning',
          message: '编辑权刚被另一会话取得，已刷新为只读；当前页面未被覆盖。',
        });
      } else {
        setLeaseTakeoverError(
          describeWriterLeaseTakeoverFailure(takeoverError, authoritativeReloaded),
        );
      }
    } finally {
      setLeaseTakeoverPending(false);
    }
  };

  const handleChangeSetSplit = (result: AuthoringSplitResult) => {
    const sourceSession = result.sourceSession;
    const canonicalDocument = authoringSnapshotToPageSchemaV3(sourceSession.snapshot);
    documentBaselineRef.current = sourceSession;
    setAuthoringSession(sourceSession);
    setDocument(canonicalDocument);
    setStudioConflict(null);
    setConflictError(null);
    setWorkbenchGeneration((current) => current + 1);
    setHandoff((current) =>
      current
        ? {
            ...current,
            changeSetPid: sourceSession.changeSetPid,
            sessionPid: sourceSession.sessionPid,
            revision: sourceSession.revision,
          }
        : current,
    );
  };

  const handleAiProposalApplied = (appliedSession: AuthoringSession) => {
    const canonicalDocument = authoringSnapshotToPageSchemaV3(appliedSession.snapshot);
    documentBaselineRef.current = appliedSession;
    setAuthoringSession(appliedSession);
    setDocument(canonicalDocument);
    setStudioConflict(null);
    setConflictError(null);
    setWorkbenchGeneration((current) => current + 1);
    setHandoff((current) =>
      current
        ? {
            ...current,
            changeSetPid: appliedSession.changeSetPid,
            sessionPid: appliedSession.sessionPid,
            revision: appliedSession.revision,
          }
        : current,
    );
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

  const handleCreateNewPageWorkspace = async (input: CreateNewPageWorkspaceInput) => {
    if (!handoff || !authoringSession || handoff.intent !== 'NEW_PAGE') return;
    setNewPagePending(true);
    setNewPageError(null);
    try {
      const created = await createAuthoringNewPageWorkspace(
        authoringSession.sessionPid,
        authoringSession.revision,
        input,
      );
      const createdDocument = authoringSnapshotToPageSchemaV3(created.snapshot);
      const createdHandoff: HandoffContext = {
        ...handoff,
        pagePid: created.pagePid,
        changeSetPid: created.changeSetPid,
        sessionPid: created.sessionPid,
        revision: created.revision,
        intent: 'PAGE_STRUCTURE',
        blockId: null,
        propertyPath: null,
        interactionContext: created.interactionContext,
        expiresAt: created.expiresAt,
      };
      replaceAuthoringContextUrl('contextId', created.sessionPid);
      documentBaselineRef.current = created;
      setHandoff(createdHandoff);
      setAuthoringSession(created);
      setDocument(createdDocument);
      setSource({ type: 'page', pid: created.pagePid, pageKey: createdDocument.pageKey });
      setPublished(false);
      setNewPageOptions(null);
      setWorkbenchGeneration((current) => current + 1);
    } catch (createError) {
      setNewPageError(
        createError instanceof Error ? createError.message : '无法创建受治理的新页面工作区',
      );
    } finally {
      setNewPagePending(false);
    }
  };

  if (!hasAuthoringContext && !canReadDesigner) {
    return (
      <RouteAccessDenied
        title="应用设计中心不可用"
        message="当前账号缺少应用设计中心读取权限（meta.designer.read）"
      />
    );
  }

  if (handoffError || error) {
    return (
      <div className="grid min-h-[420px] place-items-center bg-slate-100 p-6 text-sm text-red-700">
        <div className="max-w-lg rounded-lg border border-red-200 bg-white p-5 shadow-sm">
          <div className="font-semibold">
            {studioLocalRecoveryAvailable
              ? '权威草稿暂不可读，本地 Studio 文档仍保留'
              : '无法恢复现场配置上下文'}
          </div>
          <div className="mt-2">
            {studioLocalRecoveryAvailable
              ? '连续读取权威草稿失败；请检查网络后重新对账。'
              : handoffError || error}
          </div>
          {studioLocalRecoveryAvailable ? (
            <p className="mt-2 text-slate-600">
              恢复只会重新读取并对账，不会自动重放保存请求。
            </p>
          ) : null}
          <div className="mt-4 flex flex-wrap items-center gap-3">
            {studioLocalRecoveryAvailable ? (
              <>
                <button
                  type="button"
                  onClick={() => {
                    setHandoffError(null);
                    setContextReloadGeneration((current) => current + 1);
                  }}
                  className="rounded-md bg-blue-700 px-3 py-1.5 font-medium text-white hover:bg-blue-800"
                  data-testid="studio-local-recovery-retry"
                >
                  重新读取并对账
                </button>
                <button
                  type="button"
                  onClick={() => {
                    if (
                      !recoveryActorId ||
                      !resumeSessionPid ||
                      !window.confirm(
                        '确定放弃页面中断前保留的本地 Studio 文档吗？此操作无法撤销。',
                      )
                    ) {
                      return;
                    }
                    clearStudioAuthoringRecovery(recoveryActorId, resumeSessionPid);
                    setStudioLocalRecoveryAvailable(false);
                  }}
                  className="rounded-md border border-slate-300 px-3 py-1.5 font-medium text-slate-700 hover:bg-slate-50"
                  data-testid="studio-local-recovery-discard"
                >
                  放弃本地恢复
                </button>
              </>
            ) : null}
            <a href="/" className="inline-flex text-blue-700 hover:underline">
              返回首页
            </a>
          </div>
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

  if (handoff?.intent === 'NEW_PAGE') {
    return (
      <NewPageWorkspaceWizard
        options={newPageOptions}
        pending={newPagePending}
        error={newPageError}
        canCreate={canWriteContextualStudio && hasOwnedWriterLease(authoringSession)}
        returnHref={authoringReturnHref(handoff.returnTo, handoff.sessionPid, handoff.blockId)}
        onCreate={handleCreateNewPageWorkspace}
      />
    );
  }

  const workbenchKey = handoff
    ? `authoring:${handoff.sessionPid}:${workbenchGeneration}`
    : getWorkbenchKey(document, source);
  const contextualEditablePropertyPaths =
    handoff
      ? !reviewWorkspaceMode && canWriteContextualStudio && authoringCapabilities
        ? studioEditablePropertyPaths(authoringCapabilities)
        : {}
      : undefined;
  const contextualReorderableBlockTypes =
    handoff && !reviewWorkspaceMode && canWriteContextualStudio && authoringCapabilities
      ? studioReorderableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualCreatableBlockTypes =
    handoff && !reviewWorkspaceMode && canWriteContextualStudio && authoringCapabilities
      ? studioCreatableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualRemovableBlockTypes =
    handoff && !reviewWorkspaceMode && canWriteContextualStudio && authoringCapabilities
      ? studioRemovableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualRelocatableBlockTypes =
    handoff && !reviewWorkspaceMode && canWriteContextualStudio && authoringCapabilities
      ? studioRelocatableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualReadOnly = Boolean(
    handoff &&
      (reviewWorkspaceMode ||
        !canWriteContextualStudio ||
        authoringSession?.state !== 'ACTIVE' ||
        !hasOwnedWriterLease(authoringSession) ||
        Boolean(studioConflict)),
  );
  const newResource = isNewAuthoringResource(authoringSession);

  const workbench = (
    <UnifiedDesignerWorkbench
      key={workbenchKey}
      initialDocument={document}
      initialSavedDocument={studioRecoveredBaseDocument ?? undefined}
      modelFieldsByModel={modelFieldsByModel}
      returnHref={
        handoff
          ? newResource
            ? undefined
            : authoringReturnHref(handoff.returnTo, handoff.sessionPid, handoff.blockId)
          : source.type === 'page'
            ? '/p/page_schema'
            : undefined
      }
      onSave={handoff ? handleContextualStudioSave : handleSave}
      onDocumentChange={handoff ? handleContextualStudioDocumentChange : undefined}
      pageId={!handoff && source.type === 'page' ? source.pid : undefined}
      initialPublished={source.type === 'page' ? published : false}
      onPublish={source.type === 'page' && !handoff ? handlePublish : undefined}
      onUnpublish={source.type === 'page' && !handoff ? handleUnpublish : undefined}
      onReloadDocument={source.type === 'page' && !handoff ? handleReloadDocument : undefined}
      initialSelectedBlockId={
        handoff
          ? (handoff.blockId
              ? findBlockById(document.blocks, handoff.blockId)?.block.id
              : undefined) ?? document.blocks[0]?.id
          : undefined
      }
      contextualReadOnly={contextualReadOnly}
      contextualEditablePropertyPaths={contextualEditablePropertyPaths}
      contextualReorderableBlockTypes={contextualReorderableBlockTypes}
      contextualCreatableBlockTypes={contextualCreatableBlockTypes}
      contextualRemovableBlockTypes={contextualRemovableBlockTypes}
      contextualRelocatableBlockTypes={contextualRelocatableBlockTypes}
      governedAiCopilot={
        handoff &&
        !contextualReadOnly &&
        canWriteContextualStudio &&
        authoringSession &&
        authoringCapabilities
          ? {
              sessionPid: authoringSession.sessionPid,
              revision: authoringSession.revision,
              capabilities: authoringCapabilities,
              onApplied: handleAiProposalApplied,
            }
          : undefined
      }
      roleStructurePreviewSessionPid={
        handoff && canManageDesigner ? authoringSession?.sessionPid : undefined
      }
      identitySimulationAllowed={Boolean(
        handoff && canManageDesigner && canAuditIdentitySimulation && authoringSession?.sessionPid,
      )}
      embedded={Boolean(handoff)}
    />
  );

  if (!handoff) return workbench;

  return (
    <div
      className="relative flex h-[calc(100dvh-4rem)] min-h-[40rem] flex-col overflow-hidden bg-slate-100"
      data-testid="studio-handoff-context"
    >
      <header className="flex min-h-14 flex-wrap items-center gap-3 border-b border-slate-200 bg-white px-3 py-2 sm:px-4">
        <ShieldCheck className="h-5 w-5 shrink-0 text-blue-700" aria-hidden="true" />
        <div className="mr-auto min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <strong className="text-sm text-slate-950">应用设计中心</strong>
            <span className="rounded bg-blue-50 px-1.5 py-0.5 text-xs font-medium text-blue-700">
              隔离 ChangeSet
            </span>
            <span className="rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700">
              修订 r{authoringSession?.revision ?? handoff.revision}
            </span>
            {authoringSession?.ownership?.tenantOverride ? (
              <span className="rounded bg-indigo-50 px-1.5 py-0.5 text-xs text-indigo-700">
                租户派生层
              </span>
            ) : null}
          </div>
          {contextualReadOnly ? (
            <p
              className="mt-0.5 truncate text-xs text-slate-600"
              data-testid="studio-handoff-read-only-reason"
            >
              {studioReadOnlyReason(
                authoringSession,
                canWriteContextualStudio,
                Boolean(studioConflict),
                reviewWorkspaceMode,
              )}，当前仅可查看隔离草稿。
            </p>
          ) : (
            <p
              className="mt-0.5 truncate text-xs text-slate-600"
              data-testid="studio-handoff-editable-reason"
            >
              已从现场配置安全移交；所有修改写回同一隔离草稿，评审与发布继续沿用当前版本。
            </p>
          )}
        </div>
        {newResource ? (
          <a
            href={safeReturnTo(handoff.returnTo)}
            className="inline-flex min-h-9 items-center rounded-md border border-slate-300 px-3 text-sm font-medium text-slate-700 hover:bg-slate-50"
            data-testid="studio-return-source"
          >
            返回来源页
          </a>
        ) : null}
        <button
          type="button"
          onClick={() => setGovernanceOpen(true)}
          aria-expanded={governanceOpen}
          className="inline-flex min-h-9 items-center gap-2 rounded-md bg-blue-700 px-3 text-sm font-semibold text-white hover:bg-blue-800"
          data-testid="studio-governance-open"
        >
          <Settings2 className="h-4 w-4" aria-hidden="true" />
          治理与发布
        </button>
      </header>

      <div className="grid gap-2 border-b border-slate-200 bg-slate-50 px-3 py-2 xl:grid-cols-[minmax(0,1fr)_minmax(22rem,auto)]">
        <AuthoringRiskSummary session={authoringSession!} compact />
        {!reviewWorkspaceMode &&
        ['DRAFT', 'REJECTED'].includes(authoringSession?.changeSetStatus ?? '') ? (
          <StudioSubmissionNotice
            session={authoringSession!}
            pending={submissionPending}
            error={submissionError}
            enabled={Boolean(
              canManageDesigner &&
                authoringSession?.state === 'ACTIVE' &&
                hasOwnedWriterLease(authoringSession) &&
                !studioConflict
            )}
            onSubmit={handlePrepareOrSubmit}
          />
        ) : (
          <button
            type="button"
            onClick={() => setGovernanceOpen(true)}
            className="flex min-h-10 items-center justify-between rounded-md border border-slate-200 bg-white px-3 text-left text-sm text-slate-700 hover:border-blue-300"
          >
            <span>当前状态：{authoringSession?.changeSetStatus}</span>
            <span className="font-semibold text-blue-700">查看治理动作 →</span>
          </button>
        )}
      </div>

      {authoringSession?.validationState === 'INVALID' ? (
        <div className="px-3 pt-2">
          <AuthoringValidationNotice session={authoringSession} maxVisibleIssues={4} />
        </div>
      ) : null}
      {authoringSession?.impactState !== 'KNOWN' && (authoringSession?.revision ?? 0) > 1 ? (
        <div className="px-3 pt-2">
          <AuthoringImpactNotice session={authoringSession!} />
        </div>
      ) : null}
      {!reviewWorkspaceMode &&
      authoringSession?.writerLease &&
      authoringSession.writerLease.status !== 'OWNED' ? (
        <div className="px-3 pt-2">
          <AuthoringWriterLeaseNotice
            lease={authoringSession.writerLease}
            canTakeover={canWriteContextualStudio}
            pending={leaseTakeoverPending}
            onTakeover={handleWriterLeaseTakeover}
          />
        </div>
      ) : null}
      {leaseTakeoverError ? (
        <div
          className="mx-3 mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800"
          role="alert"
        >
          {leaseTakeoverError}
        </div>
      ) : null}
      {leaseTakeoverFeedback ? (
        <div
          className={`mx-3 mt-2 rounded-md border px-3 py-2 text-sm ${
            leaseTakeoverFeedback.tone === 'success'
              ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
              : 'border-amber-300 bg-amber-50 text-amber-900'
          }`}
          data-tone={leaseTakeoverFeedback.tone}
          data-testid="writer-lease-takeover-feedback"
          role="status"
        >
          {leaseTakeoverFeedback.message}
        </div>
      ) : null}
      {studioSaveReconciliationFeedback ? (
        <div
          className={`mx-3 mt-2 rounded-md border px-3 py-2 text-sm ${
            studioSaveReconciliationFeedback.tone === 'success'
              ? 'border-emerald-300 bg-emerald-50 text-emerald-900'
              : 'border-amber-300 bg-amber-50 text-amber-900'
          }`}
          data-testid="studio-save-reconciliation-feedback"
          data-tone={studioSaveReconciliationFeedback.tone}
          role="status"
        >
          {studioSaveReconciliationFeedback.message}
        </div>
      ) : null}
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

      {governanceOpen ? (
        <button
          type="button"
          aria-label="关闭治理与发布"
          className="absolute inset-0 z-40 bg-slate-950/25"
          onClick={() => setGovernanceOpen(false)}
        />
      ) : null}
      <aside
        aria-label="治理与发布"
        hidden={!governanceOpen}
        className={`absolute inset-y-0 right-0 z-50 flex w-full max-w-xl flex-col border-l border-slate-200 bg-white shadow-2xl transition-transform duration-200 ${
          governanceOpen ? 'translate-x-0' : 'invisible translate-x-full'
        }`}
        data-testid="studio-governance-drawer"
      >
        <div className="flex min-h-14 items-center justify-between border-b border-slate-200 px-4">
          <div>
            <h2 className="text-sm font-semibold text-slate-950">治理与发布</h2>
            <p className="mt-0.5 text-xs text-slate-500">
              风险、评审、发布历史、回滚与变更集拆分
            </p>
          </div>
          <button
            type="button"
            onClick={() => setGovernanceOpen(false)}
            className="rounded-md p-2 text-slate-500 hover:bg-slate-100"
            aria-label="关闭治理与发布"
            data-testid="studio-governance-close"
          >
            <X className="h-4 w-4" aria-hidden="true" />
          </button>
        </div>
        <div className="min-h-0 flex-1 space-y-2 overflow-auto p-3">
          <AuthoringOwnershipNotice ownership={authoringSession?.ownership} />
          <AuthoringRiskSummary session={authoringSession!} />
          <AuthoringGovernanceNotice
            session={authoringSession!}
            currentUserId={user?.id}
            canManage={!reviewWorkspaceMode && canManageDesigner}
            canReview={reviewWorkspaceMode && canReviewAuthoring}
            canPublish={!reviewWorkspaceMode && canPublishAuthoring}
            pendingAction={governancePending}
            error={governanceError}
            onAction={handleGovernanceAction}
          />
          {!reviewWorkspaceMode && canReadAuthoringReleases && authoringSession ? (
            <AuthoringReleaseHistoryPanel
              changeSetPid={authoringSession.changeSetPid}
              canRollback={canPublishAuthoring}
              refreshKey={`${authoringSession.publishState}:${authoringSession.revision}`}
              onRolledBack={handleReleaseRolledBack}
            />
          ) : null}
          <AuthoringChangeSetSplitPanel
            session={authoringSession!}
            enabled={Boolean(
              !reviewWorkspaceMode &&
                canWriteContextualStudio &&
                user?.id != null &&
                String(user.id) === String(authoringSession?.ownerUserId) &&
                authoringSession?.state === 'ACTIVE' &&
                hasOwnedWriterLease(authoringSession) &&
                !studioConflict
            )}
            onSplit={handleChangeSetSplit}
          />
        </div>
      </aside>
    </div>
  );
}

function StudioSubmissionNotice({
  session,
  pending,
  error,
  enabled,
  onSubmit,
}: {
  session: AuthoringSession;
  pending: boolean;
  error: string | null;
  enabled: boolean;
  onSubmit: () => Promise<void>;
}) {
  const prepared = session.validationState === 'VALID' && session.impactState === 'KNOWN';
  const validationErrors = session.validation?.errorCount ?? 0;
  const label = prepared ? '提交评审' : '校验与影响分析';
  const disabled =
    !enabled ||
    pending ||
    validationErrors > 0 ||
    session.impactState === 'STALE' ||
    session.revision <= 1;

  return (
    <section
      className="rounded-md border border-slate-200 bg-white px-3 py-3 text-sm text-slate-800"
      aria-label="Studio 提交治理"
      data-testid="studio-submission-notice"
    >
      <div className="flex flex-wrap items-center gap-3">
        <div className="mr-auto min-w-0">
          <div className="font-semibold">3. 校验、评审、发布</div>
          <p className="mt-1 text-xs leading-5 text-slate-600">
            {prepared
              ? '服务端已确认当前 revision 为 VALID + KNOWN，可冻结并提交独立评审。'
              : '先由服务端校验结构并计算影响；分析完成前不能提交评审。'}
          </p>
        </div>
        <button
          type="button"
          disabled={disabled}
          onClick={() => void onSubmit()}
          className="inline-flex min-h-9 items-center rounded-md bg-blue-700 px-3 font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:bg-slate-300"
          data-testid="studio-prepare-submit"
        >
          {pending ? (prepared ? '提交中…' : '分析中…') : label}
        </button>
      </div>
      {error ? (
        <div
          className="mt-2 rounded border border-red-200 bg-red-50 px-2 py-2 text-xs text-red-800"
          role="alert"
        >
          {error}
        </div>
      ) : null}
    </section>
  );
}

function NewPageWorkspaceWizard({
  options,
  pending,
  error,
  canCreate,
  returnHref,
  onCreate,
}: {
  options: NewPageWorkspaceOptions | null;
  pending: boolean;
  error: string | null;
  canCreate: boolean;
  returnHref: string;
  onCreate: (input: CreateNewPageWorkspaceInput) => Promise<void>;
}) {
  const [title, setTitle] = useState('');
  const [pageKey, setPageKey] = useState('');
  const [kind, setKind] = useState<CreateNewPageWorkspaceInput['kind']>('list');
  const [modelCode, setModelCode] = useState('');
  const [parentMenuCode, setParentMenuCode] = useState('');
  const [permissionCode, setPermissionCode] = useState('');
  const [description, setDescription] = useState('');
  const [menuIcon, setMenuIcon] = useState('');
  const [menuCode, setMenuCode] = useState('');
  const [menuPath, setMenuPath] = useState('');

  const derivedMenuCode = menuCode || pageKey;
  const derivedMenuPath = menuPath || (pageKey ? `/${pageKey.replaceAll('_', '-')}` : '');
  const valid = Boolean(
    canCreate &&
      options &&
      title.trim() &&
      /^[a-zA-Z][a-zA-Z0-9_-]{1,99}$/.test(pageKey) &&
      modelCode &&
      parentMenuCode &&
      permissionCode &&
      derivedMenuCode &&
      derivedMenuPath,
  );

  const submit = (event: React.FormEvent) => {
    event.preventDefault();
    if (!valid) return;
    void onCreate({
      pageKey,
      name: pageKey,
      title: title.trim(),
      description: description.trim() || undefined,
      kind,
      modelCode,
      parentMenuCode,
      menuCode: derivedMenuCode,
      menuName: title.trim(),
      menuPath: derivedMenuPath,
      menuIcon: menuIcon.trim() || undefined,
      permissionCode,
    });
  };

  return (
    <main
      className="min-h-[calc(100vh-4rem)] bg-slate-100 px-4 py-8 sm:px-6 lg:px-8"
      data-testid="new-page-workspace-wizard"
    >
      <div className="mx-auto max-w-6xl">
        <div className="mb-6 flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="text-sm font-semibold text-blue-700">应用设计中心 · 新资源</div>
            <h1 className="mt-1 text-2xl font-semibold tracking-tight text-slate-950">
              创建页面并挂载菜单
            </h1>
            <p className="mt-2 max-w-2xl text-sm text-slate-600">
              先确定资源身份和访问边界；页面结构随后在隔离草稿中设计，评审发布前不会出现在运行态。
            </p>
          </div>
          <a
            href={returnHref}
            className="inline-flex min-h-10 items-center rounded-md border border-slate-300 bg-white px-4 text-sm font-medium text-slate-700 hover:bg-slate-50"
          >
            返回现场
          </a>
        </div>

        <div className="mb-4 grid gap-2 text-xs sm:grid-cols-3">
          <div className="rounded-md border border-blue-300 bg-blue-50 px-3 py-2 font-semibold text-blue-800">
            1. 定义页面与入口
          </div>
          <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-slate-500">
            2. 设计页面结构
          </div>
          <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-slate-500">
            3. 校验、评审、发布
          </div>
        </div>

        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_19rem]">
          <form onSubmit={submit} className="rounded-xl border border-slate-200 bg-white shadow-sm">
            <div className="border-b border-slate-200 px-5 py-4">
              <h2 className="font-semibold text-slate-900">页面身份</h2>
              <p className="mt-1 text-xs text-slate-500">标识发布后用于稳定路由，请使用英文、数字、下划线或短横线。</p>
            </div>
            <div className="grid gap-5 p-5 sm:grid-cols-2">
              <label className="grid gap-1.5 text-sm font-medium text-slate-700 sm:col-span-2">
                页面标题
                <input
                  required
                  value={title}
                  onChange={(event) => setTitle(event.target.value)}
                  placeholder="例如：生产异常看板"
                  className="min-h-11 rounded-md border border-slate-300 px-3 font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                />
              </label>
              <label className="grid gap-1.5 text-sm font-medium text-slate-700">
                页面标识
                <input
                  required
                  value={pageKey}
                  onChange={(event) => setPageKey(event.target.value.trim())}
                  placeholder="production_exception"
                  aria-describedby="page-key-hint"
                  className="min-h-11 rounded-md border border-slate-300 px-3 font-mono text-sm font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                />
                <span id="page-key-hint" className="text-xs font-normal text-slate-500">
                  2–100 位，以英文字母开头
                </span>
              </label>
              <label className="grid gap-1.5 text-sm font-medium text-slate-700">
                页面类型
                <select
                  value={kind}
                  onChange={(event) => setKind(event.target.value as CreateNewPageWorkspaceInput['kind'])}
                  className="min-h-11 rounded-md border border-slate-300 bg-white px-3 font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100"
                >
                  <option value="list">列表</option>
                  <option value="form">表单</option>
                  <option value="detail">详情</option>
                </select>
                <span className="text-xs font-normal leading-5 text-slate-500">
                  仪表板是一等资源，不属于 PageSchema 页面类型；请使用
                  <a
                    href="/dashboard-designer"
                    className="ml-1 font-semibold text-blue-700 underline-offset-2 hover:underline"
                  >
                    仪表板设计器
                  </a>
                  。
                </span>
              </label>
              <label className="grid gap-1.5 text-sm font-medium text-slate-700 sm:col-span-2">
                业务模型
                <select
                  aria-label="业务模型"
                  required
                  value={modelCode}
                  onChange={(event) => setModelCode(event.target.value)}
                  disabled={!options || options.models.length === 0}
                  className="min-h-11 rounded-md border border-slate-300 bg-white px-3 font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                >
                  <option value="">
                    {!options
                      ? '正在加载…'
                      : options.models.length
                        ? '请选择已发布模型'
                        : '暂无已发布模型'}
                  </option>
                  {options?.models.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label} · {option.value}
                    </option>
                  ))}
                </select>
                <span className="text-xs font-normal leading-5 text-slate-500">
                  列表、表单和详情页必须绑定一个已发布模型。没有可选模型时，请先到
                  <a
                    href="/meta/models/new"
                    className="ml-1 font-semibold text-blue-700 underline-offset-2 hover:underline"
                  >
                    模型设计器
                  </a>
                  创建并发布模型。
                </span>
              </label>
            </div>

            <div className="border-y border-slate-200 bg-slate-50 px-5 py-4">
              <h2 className="font-semibold text-slate-900">菜单与访问边界</h2>
              <p className="mt-1 text-xs text-slate-500">只允许选择已有目录和已有权限事实，不在此处隐式创建公开权限。</p>
            </div>
            <div className="grid gap-5 p-5 sm:grid-cols-2">
              <label className="grid gap-1.5 text-sm font-medium text-slate-700">
                父菜单
                <select
                  required
                  value={parentMenuCode}
                  onChange={(event) => setParentMenuCode(event.target.value)}
                  disabled={!options}
                  className="min-h-11 rounded-md border border-slate-300 bg-white px-3 font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                >
                  <option value="">{options ? '请选择目录' : '正在加载…'}</option>
                  {options?.parentMenus.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label} · {option.value}
                    </option>
                  ))}
                </select>
              </label>
              <label className="grid gap-1.5 text-sm font-medium text-slate-700">
                访问权限
                <select
                  required
                  value={permissionCode}
                  onChange={(event) => setPermissionCode(event.target.value)}
                  disabled={!options}
                  className="min-h-11 rounded-md border border-slate-300 bg-white px-3 font-normal outline-none focus:border-blue-500 focus:ring-2 focus:ring-blue-100 disabled:bg-slate-100"
                >
                  <option value="">{options ? '请选择权限' : '正在加载…'}</option>
                  {options?.permissions.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label} · {option.value}
                    </option>
                  ))}
                </select>
              </label>

              <details className="rounded-md border border-slate-200 bg-slate-50 p-4 sm:col-span-2">
                <summary className="cursor-pointer text-sm font-medium text-slate-700">高级标识与说明</summary>
                <div className="mt-4 grid gap-4 sm:grid-cols-2">
                  <label className="grid gap-1 text-xs font-medium text-slate-600">
                    菜单编码
                    <input
                      value={menuCode}
                      onChange={(event) => setMenuCode(event.target.value.trim())}
                      placeholder={pageKey || '默认等于页面标识'}
                      className="min-h-10 rounded-md border border-slate-300 bg-white px-3 font-mono font-normal"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-medium text-slate-600">
                    路由路径
                    <input
                      value={menuPath}
                      onChange={(event) => setMenuPath(event.target.value.trim())}
                      placeholder={derivedMenuPath || '/production-exception'}
                      className="min-h-10 rounded-md border border-slate-300 bg-white px-3 font-mono font-normal"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-medium text-slate-600">
                    菜单图标
                    <input
                      value={menuIcon}
                      onChange={(event) => setMenuIcon(event.target.value)}
                      placeholder="可选，例如 LayoutDashboard"
                      className="min-h-10 rounded-md border border-slate-300 bg-white px-3 font-normal"
                    />
                  </label>
                  <label className="grid gap-1 text-xs font-medium text-slate-600">
                    页面说明
                    <input
                      value={description}
                      onChange={(event) => setDescription(event.target.value)}
                      placeholder="可选"
                      className="min-h-10 rounded-md border border-slate-300 bg-white px-3 font-normal"
                    />
                  </label>
                </div>
              </details>
            </div>

            {error ? (
              <div className="mx-5 mb-4 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
                {error}
              </div>
            ) : null}
            {!canCreate ? (
              <div className="mx-5 mb-4 rounded-md border border-amber-200 bg-amber-50 px-3 py-2 text-sm text-amber-800">
                当前会话没有应用设计中心管理员权限或 Writer lease，仅可返回现场。
              </div>
            ) : null}
            <div className="flex justify-end gap-3 border-t border-slate-200 px-5 py-4">
              <a href={returnHref} className="inline-flex min-h-10 items-center px-3 text-sm text-slate-600 hover:text-slate-900">
                取消
              </a>
              <button
                type="submit"
                disabled={!valid || pending}
                className="min-h-11 rounded-md bg-blue-700 px-5 text-sm font-semibold text-white hover:bg-blue-800 disabled:cursor-not-allowed disabled:bg-slate-300"
              >
                {pending ? '正在建立隔离草稿…' : '创建并进入页面设计'}
              </button>
            </div>
          </form>

          <aside className="h-fit rounded-xl border border-slate-200 bg-white p-5 shadow-sm">
            <h2 className="font-semibold text-slate-900">本次会发生什么</h2>
            <ol className="mt-4 space-y-4 text-sm text-slate-600">
              <li><strong className="text-slate-900">1. 预留身份</strong><br />页面和菜单分别记录为 ChangeItem。</li>
              <li><strong className="text-slate-900">2. 隔离设计</strong><br />运行态数据库暂不创建页面或菜单。</li>
              <li><strong className="text-slate-900">3. 强制评审</strong><br />L3 / Studio Approval，创建者不能自审。</li>
              <li><strong className="text-slate-900">4. 原子发布</strong><br />页面、菜单与 release 同事务生效，失败不留半成品。</li>
            </ol>
            <div className="mt-5 rounded-md border border-blue-200 bg-blue-50 p-3 text-xs text-blue-900">
              菜单只在页面已发布的环境显示；页面晋升到目标环境后，入口才会随之开放。
            </div>
            <div className="mt-5 rounded-md border border-amber-200 bg-amber-50 p-3 text-xs text-amber-900">
              新资源创建属于 Forward-only 变更；发布前可放弃，发布后不承诺一键回滚删除。
            </div>
          </aside>
        </div>
      </div>
    </main>
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

function resumeHandoffFromSession(
  session: AuthoringSession,
  resumeIntent?: string | null,
): HandoffContext {
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
    intent: resumeIntent === 'NEW_PAGE' ? 'NEW_PAGE' : 'PAGE_STRUCTURE',
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
  studioIntent?: string | null,
): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.delete(parameter);
  url.searchParams.delete('reviewSession');
  url.searchParams.set('authoringSession', sessionPid);
  if (studioIntent === 'NEW_PAGE') {
    url.searchParams.set('studioIntent', 'NEW_PAGE');
  } else {
    url.searchParams.delete('studioIntent');
  }
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

function studioSaveAuthorityLabel(session: AuthoringSession): string {
  if (session.state !== 'ACTIVE') return `ChangeSet 已进入 ${session.state} 状态`;
  if (session.writerLease?.status === 'EXPIRED') return 'Writer lease 已过期';
  return '编辑权已转移到其他会话';
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

function isNewAuthoringResource(session: AuthoringSession | null): boolean {
  const resource = session?.snapshot?._authoringResource;
  return Boolean(
    resource &&
      typeof resource === 'object' &&
      !Array.isArray(resource) &&
      (resource as Record<string, unknown>).lifecycle === 'NEW',
  );
}

async function submitAndReloadAuthoringSession(
  session: AuthoringSession,
): Promise<AuthoringSession> {
  await submitAuthoringSession(session.sessionPid, session.revision);
  return loadAuthoringSession(session.sessionPid);
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
