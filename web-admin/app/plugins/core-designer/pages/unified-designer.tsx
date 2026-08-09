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
} from '~/framework/meta/authoring/authoringService';
import type {
  AuthoringSession,
  CapabilityRegistry,
  HandoffContext,
} from '~/framework/meta/authoring/types';
import {
  authoringSnapshotToPageSchemaV3,
  planStudioAuthoringPatches,
  studioEditablePropertyPaths,
} from '../components/unified-designer/persistence/contextualAuthoringAdapter';

const LOCAL_STORAGE_KEY = 'auraboot.unified-designer.sample';

export default function UnifiedDesignerPage() {
  const canAdministerDesigner = usePermission('meta.designer.admin');
  const [searchParams] = useSearchParams();
  const requestedPageId = searchParams.get('pageId') || searchParams.get('pid');
  const pageKey = searchParams.get('pageKey');
  const contextId = searchParams.get('contextId');
  const [handoff, setHandoff] = useState<HandoffContext | null>(null);
  const [authoringSession, setAuthoringSession] = useState<AuthoringSession | null>(null);
  const [authoringCapabilities, setAuthoringCapabilities] = useState<CapabilityRegistry | null>(null);
  const [handoffError, setHandoffError] = useState<string | null>(null);
  const [document, setDocument] = useState<PageSchemaV3 | null>(null);
  const [source, setSource] = useState<PageSchemaV3Source>({ type: 'local' });
  const [published, setPublished] = useState(false);
  const [modelFieldsByModel, setModelFieldsByModel] = useState<ModelFieldsByModel>({});
  const [error, setError] = useState<string | null>(null);
  const modelCodeKey = document ? collectModelCodesFromDocument(document).join('|') : '';
  const documentId = document?.id ?? null;
  const resolvingHandoff = Boolean(
    contextId &&
      !handoffError &&
      (!handoff || !authoringSession || !authoringCapabilities || !document),
  );

  useEffect(() => {
    if (!contextId) {
      setHandoff(null);
      setAuthoringSession(null);
      setAuthoringCapabilities(null);
      setHandoffError(null);
      return;
    }
    let cancelled = false;
    setDocument(null);
    setHandoff(null);
    setAuthoringSession(null);
    setAuthoringCapabilities(null);
    setHandoffError(null);
    void consumeAuthoringHandoff(contextId)
      .then(async (consumed) => {
        const [session, capabilities] = await Promise.all([
          loadAuthoringSession(consumed.sessionPid),
          loadAuthoringCapabilities(),
        ]);
        assertHandoffMatchesSession(consumed, session);
        if (!cancelled) {
          const isolatedDocument = authoringSnapshotToPageSchemaV3(session.snapshot);
          setHandoff(consumed);
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
  }, [contextId]);

  useEffect(() => {
    let cancelled = false;

    async function loadDocument() {
      if (contextId) return;
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
  }, [contextId, pageKey, requestedPageId]);

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
  const contextualReadOnly = Boolean(
    handoff && (!canAdministerDesigner || authoringSession?.state !== 'ACTIVE'),
  );

  const workbench = (
    <UnifiedDesignerWorkbench
      key={workbenchKey}
      initialDocument={document}
      modelFieldsByModel={modelFieldsByModel}
      returnHref={handoff?.returnTo || (source.type === 'page' ? '/p/page_schema' : undefined)}
      onSave={handoff ? handleContextualStudioSave : handleSave}
      pageId={!handoff && source.type === 'page' ? source.pid : undefined}
      initialPublished={source.type === 'page' ? published : false}
      onPublish={source.type === 'page' && !handoff ? handlePublish : undefined}
      onUnpublish={source.type === 'page' && !handoff ? handleUnpublish : undefined}
      onReloadDocument={source.type === 'page' && !handoff ? handleReloadDocument : undefined}
      initialSelectedBlockId={handoff?.blockId || undefined}
      contextualReadOnly={contextualReadOnly}
      contextualEditablePropertyPaths={contextualEditablePropertyPaths}
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
            缺少高级设计权限或会话不可编辑，当前仅可查看隔离草稿。
          </span>
        ) : (
          <span className="ml-2" data-testid="studio-handoff-editable-reason">
            ChangeSet {handoff.changeSetPid} · 修订 r{authoringSession?.revision ?? handoff.revision} ·
            高级属性将写回同一隔离草稿；结构操作需通过后续 typed patch，不会直接修改线上页面。
          </span>
        )}
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
