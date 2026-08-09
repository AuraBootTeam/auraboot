import React, { useEffect, useState } from 'react';
import { useSearchParams } from 'react-router';
import { usePermission } from '~/contexts/AuthContext';
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
  loadAuthoringSession,
  moveAuthoringStudioBlock,
  observeAuthoringChangeSet,
  takeoverAuthoringWriterLease,
} from '~/framework/meta/authoring/authoringService';
import { AuthoringWriterLeaseNotice } from '~/framework/meta/authoring/AuthoringWriterLeaseNotice';
import type {
  AuthoringSession,
  CapabilityRegistry,
  HandoffContext,
} from '~/framework/meta/authoring/types';
import {
  authoringSnapshotToPageSchemaV3,
  planStudioAuthoringPatches,
  studioEditablePropertyPaths,
  studioReorderableBlockTypes,
} from '../components/unified-designer/persistence/contextualAuthoringAdapter';

const LOCAL_STORAGE_KEY = 'auraboot.unified-designer.sample';

export default function UnifiedDesignerPage() {
  const canAdministerDesigner = usePermission('meta.designer.admin');
  const [searchParams] = useSearchParams();
  const requestedPageId = searchParams.get('pageId') || searchParams.get('pid');
  const pageKey = searchParams.get('pageKey');
  const contextId = searchParams.get('contextId');
  const resumeSessionPid = searchParams.get('authoringSession');
  const observedChangeSetPid = searchParams.get('changeSetId');
  const hasAuthoringContext = Boolean(contextId || resumeSessionPid || observedChangeSetPid);
  const [handoff, setHandoff] = useState<HandoffContext | null>(null);
  const [authoringSession, setAuthoringSession] = useState<AuthoringSession | null>(null);
  const [authoringCapabilities, setAuthoringCapabilities] = useState<CapabilityRegistry | null>(null);
  const [handoffError, setHandoffError] = useState<string | null>(null);
  const [leaseTakeoverPending, setLeaseTakeoverPending] = useState(false);
  const [leaseTakeoverError, setLeaseTakeoverError] = useState<string | null>(null);
  const [document, setDocument] = useState<PageSchemaV3 | null>(null);
  const [source, setSource] = useState<PageSchemaV3Source>({ type: 'local' });
  const [published, setPublished] = useState(false);
  const [modelFieldsByModel, setModelFieldsByModel] = useState<ModelFieldsByModel>({});
  const [error, setError] = useState<string | null>(null);
  const modelCodeKey = document ? collectModelCodesFromDocument(document).join('|') : '';
  const documentId = document?.id ?? null;
  const resolvingHandoff = Boolean(
    hasAuthoringContext &&
      !handoffError &&
      (!handoff || !authoringSession || !authoringCapabilities || !document),
  );
  const activeAuthoringSessionPid = authoringSession?.sessionPid;

  useEffect(() => {
    if (!hasAuthoringContext) {
      setHandoff(null);
      setAuthoringSession(null);
      setAuthoringCapabilities(null);
      setHandoffError(null);
      setLeaseTakeoverError(null);
      return;
    }
    let cancelled = false;
    setDocument(null);
    setHandoff(null);
    setAuthoringSession(null);
    setAuthoringCapabilities(null);
    setHandoffError(null);
    setLeaseTakeoverError(null);
    const resolveContext = contextId
      ? consumeAuthoringHandoff(contextId).then(async (consumed) => {
          const [session, capabilities] = await Promise.all([
            loadAuthoringSession(consumed.sessionPid),
            loadAuthoringCapabilities(),
          ]);
          assertHandoffMatchesSession(consumed, session);
          replaceAuthoringContextUrl('contextId', consumed.sessionPid);
          return { handoff: consumed, session, capabilities };
        })
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
            };
          })
        : Promise.all([
            loadAuthoringSession(resumeSessionPid!),
            loadAuthoringCapabilities(),
          ]).then(([session, capabilities]) => ({
            handoff: resumeHandoffFromSession(session),
            session,
            capabilities,
          }));

    void resolveContext
      .then(({ handoff: resolvedHandoff, session, capabilities }) => {
        if (!cancelled) {
          const isolatedDocument = authoringSnapshotToPageSchemaV3(session.snapshot);
          setHandoff(resolvedHandoff);
          setAuthoringSession(session);
          setAuthoringCapabilities(capabilities);
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
  }, [contextId, hasAuthoringContext, observedChangeSetPid, resumeSessionPid]);

  useEffect(() => {
    if (!activeAuthoringSessionPid) return;
    let cancelled = false;
    const interval = window.setInterval(() => {
      void loadAuthoringSession(activeAuthoringSessionPid)
        .then((latest) => {
          if (!cancelled) setAuthoringSession(latest);
        })
        .catch(() => {
          // Keep the current isolated document and foreground error state on poll failure.
        });
    }, 15_000);
    return () => {
      cancelled = true;
      window.clearInterval(interval);
    };
  }, [activeAuthoringSessionPid]);

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

  const handleContextualStudioSave = async (
    nextDocument: PageSchemaV3,
  ): Promise<PageSchemaV3> => {
    if (!handoff || !authoringSession || !authoringCapabilities) {
      throw new Error('现场配置会话尚未就绪');
    }
    if (!canAdministerDesigner) {
      throw new Error('缺少应用设计中心高级配置权限');
    }
    if (authoringSession.state !== 'ACTIVE') {
      throw new Error(`当前 ChangeSet 会话状态为 ${authoringSession.state}，不能继续编辑`);
    }

    const baseline = authoringSnapshotToPageSchemaV3(authoringSession.snapshot);
    const plan = planStudioAuthoringPatches(baseline, nextDocument, authoringCapabilities);
    if (plan.unsupported.length > 0) {
      throw new Error(plan.unsupported.join('；'));
    }

    let workingSession = authoringSession;
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

    const canonicalDocument = authoringSnapshotToPageSchemaV3(workingSession.snapshot);
    setDocument(canonicalDocument);
    return canonicalDocument;
  };

  const handleWriterLeaseTakeover = async (reason: string) => {
    if (!handoff || !authoringSession || leaseTakeoverPending || !canAdministerDesigner) return;
    setLeaseTakeoverPending(true);
    setLeaseTakeoverError(null);
    try {
      const taken = await takeoverAuthoringWriterLease(
        authoringSession.sessionPid,
        authoringSession.revision,
        reason,
      );
      const canonicalDocument = authoringSnapshotToPageSchemaV3(taken.snapshot);
      setAuthoringSession(taken);
      setDocument(canonicalDocument);
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
    ? `authoring:${handoff.sessionPid}`
    : getWorkbenchKey(document, source);
  const contextualEditablePropertyPaths =
    handoff
      ? canAdministerDesigner && authoringCapabilities
        ? studioEditablePropertyPaths(authoringCapabilities)
        : {}
      : undefined;
  const contextualReorderableBlockTypes =
    handoff && canAdministerDesigner && authoringCapabilities
      ? studioReorderableBlockTypes(authoringCapabilities)
      : undefined;
  const contextualReadOnly = Boolean(
    handoff &&
      (!canAdministerDesigner ||
        authoringSession?.state !== 'ACTIVE' ||
        !hasOwnedWriterLease(authoringSession)),
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
            {studioReadOnlyReason(authoringSession, canAdministerDesigner)}，当前仅可查看隔离草稿。
          </span>
        ) : (
          <span className="ml-2" data-testid="studio-handoff-editable-reason">
            ChangeSet {handoff.changeSetPid} · 修订 r{authoringSession?.revision ?? handoff.revision} ·
            高级属性和已声明的同级顺序调整将写回同一隔离草稿；跨父级、增删区块等治理操作仍不开放。
          </span>
        )}
      </div>
      <div className="px-4 pt-3">
        <AuthoringWriterLeaseNotice
          lease={authoringSession?.writerLease}
          canTakeover={canAdministerDesigner}
          pending={leaseTakeoverPending}
          onTakeover={handleWriterLeaseTakeover}
        />
        {leaseTakeoverError ? (
          <div className="mt-2 rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-800" role="alert">
            {leaseTakeoverError}
          </div>
        ) : null}
      </div>
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

function replaceAuthoringContextUrl(parameter: 'contextId' | 'changeSetId', sessionPid: string): void {
  if (typeof window === 'undefined') return;
  const url = new URL(window.location.href);
  url.searchParams.delete(parameter);
  url.searchParams.set('authoringSession', sessionPid);
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
): string {
  if (!canAdministerDesigner) return '缺少高级设计权限';
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
