/**
 * AuraBot — Knowledge Base Detail Page
 *
 * 3 tabs: Documents (upload + status), Chunks (preview), Retrieval Test.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  ArrowPathIcon,
  LinkIcon,
  CloudArrowUpIcon,
  CircleStackIcon,
  DocumentTextIcon,
  MagnifyingGlassIcon,
  TrashIcon,
  ChevronDownIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import { get, post, del } from '~/shared/services/http-client';
import { useToastContext } from '~/contexts/ToastContext';
import { useI18n } from '~/contexts/I18nContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface KnowledgeBase {
  pid: string;
  name: string;
  description: string;
  status: string;
  embeddingProvider: string;
  embeddingModel: string;
  activeIndexReleasePid?: string;
  docCount: number;
  chunkCount: number;
}

interface KbDocument {
  pid: string;
  docName: string;
  docType: string;
  fileSize: number;
  charCount: number;
  chunkCount: number;
  /** How many chunks carry a vector. Zero, with chunks present, means semantic search is dead. */
  embeddedChunkCount?: number;
  activeVersionPid?: string;
  versionNo?: number;
  status: 'pending' | 'processing' | 'completed' | 'failed';
  errorMessage?: string;
  createdAt: string;
}

interface KbChunk {
  pid: string;
  chunkIndex: number;
  content: string;
  charCount: number;
  tokenCount: number;
  embeddingStatus: string;
}

interface RetrievalResult {
  kbPid: string;
  documentPid: string;
  documentVersionPid: string;
  indexReleasePid: string;
  chunkPid: string;
  docName: string;
  kbName: string;
  chunkIndex: number;
  content: string;
  distance: number;
  similarity: number;
  vectorScore: number;
  bm25Score: number;
  hybridScore: number;
  rerankScore: number;
  citationLocator?: string;
}

interface IndexRelease {
  pid: string;
  release_no: number;
  release_type: 'full' | 'text' | 'vector';
  state: 'building' | 'ready' | 'active' | 'failed' | 'retired';
  embedding_provider?: string;
  embedding_model?: string;
  embedding_dimension?: number;
  error_message?: string;
  created_at: string;
  activated_at?: string;
}

interface AccessGrant {
  pid: string;
  subject_type: 'user' | 'member' | 'role' | 'digital_employee';
  subject_id: string;
  permission: 'read' | 'manage';
  expires_at?: string;
}

const STATUS_STYLES: Record<string, string> = {
  pending: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  processing: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  completed: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
  failed: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
};

const TABS = ['Documents', 'Chunks', 'Retrieval Test', 'Access'] as const;
type Tab = (typeof TABS)[number];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function KnowledgeBaseDetailPage() {
  const { kbPid } = useParams<{ kbPid: string }>();
  const navigate = useNavigate();
  const toast = useToastContext();
  const { t } = useI18n();

  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('Documents');
  const [loading, setLoading] = useState(true);

  const TAB_LABELS: Record<Tab, string> = {
    Documents: t('ai.knowledge.detail.tab.documents', undefined, 'Documents'),
    Chunks: t('ai.knowledge.detail.tab.chunks', undefined, 'Chunks'),
    'Retrieval Test': t('ai.knowledge.detail.tab.retrieval', undefined, 'Retrieval Test'),
    Access: t('ai.knowledge.detail.tab.access', undefined, 'Access'),
  };

  const fetchKb = useCallback(async () => {
    try {
      const res = await get<KnowledgeBase>(`/api/ai/knowledge/${kbPid}`);
      setKb(res?.data ?? null);
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.loadKbFailed', undefined, 'Failed to load knowledge base'));
    } finally {
      setLoading(false);
    }
  }, [kbPid, toast]);

  useEffect(() => {
    fetchKb();
  }, [fetchKb]);

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
      </div>
    );
  }

  if (!kb) {
    return <div className="p-6 text-gray-500">{t('ai.knowledge.detail.notFound', undefined, 'Knowledge base not found')}</div>;
  }

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-4 border-b border-gray-200 px-6 py-4 dark:border-gray-700">
        <button
          onClick={() => navigate('/aurabot/knowledge')}
          aria-label={t('ai.knowledge.detail.back', undefined, 'Back to knowledge bases')}
          title={t('ai.knowledge.detail.back', undefined, 'Back to knowledge bases')}
          className="rounded-lg p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-800"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div className="rounded-xl bg-gradient-to-br from-blue-500 to-indigo-600 p-2.5 shadow-sm">
          <CircleStackIcon className="h-6 w-6 text-white" />
        </div>
        <div>
          <h1 className="text-2xl font-bold tracking-tight text-gray-900 dark:text-white">{kb.name}</h1>
          <div className="mt-0.5 flex items-center gap-2 text-sm text-gray-500 dark:text-gray-400">
            <span>{kb.docCount} {t('ai.knowledge.detail.unit.documents', undefined, 'documents')}</span>
            <span className="text-gray-300 dark:text-gray-600">·</span>
            <span>{kb.chunkCount} {t('ai.knowledge.detail.unit.chunks', undefined, 'chunks')}</span>
            <span className="ml-1 rounded-md bg-gray-100 px-2 py-0.5 font-mono text-xs text-gray-600 dark:bg-gray-800 dark:text-gray-300">
              {kb.embeddingProvider}/
              {kb.embeddingModel || t('ai.knowledge.detail.modelUnconfigured', undefined, 'Model not configured')}
            </span>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="flex gap-1 border-b border-gray-200 px-6 pt-3 dark:border-gray-700">
        {TABS.map((tab) => (
          <button
            key={tab}
            onClick={() => setActiveTab(tab)}
            className={`rounded-t-lg px-4 py-2 text-sm font-medium transition-colors ${
              activeTab === tab
                ? 'border border-b-0 border-gray-200 bg-white text-blue-600 dark:border-gray-700 dark:bg-gray-800 dark:text-blue-400'
                : 'text-gray-500 hover:text-gray-700 dark:hover:text-gray-300'
            }`}
          >
            {TAB_LABELS[tab]}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto p-6">
        {activeTab === 'Documents' && <DocumentsTab kbPid={kbPid!} onUpdate={fetchKb} />}
        {activeTab === 'Chunks' && <ChunksTab kbPid={kbPid!} />}
        {activeTab === 'Retrieval Test' && <RetrievalTestTab kbPid={kbPid!} />}
        {activeTab === 'Access' && <AccessTab kbPid={kbPid!} />}
      </div>
    </div>
  );
}

function AccessTab({ kbPid }: { kbPid: string }) {
  const toast = useToastContext();
  const { t } = useI18n();
  const [grants, setGrants] = useState<AccessGrant[]>([]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [subjectType, setSubjectType] = useState<AccessGrant['subject_type']>('role');
  const [subjectId, setSubjectId] = useState('');
  const [permission, setPermission] = useState<AccessGrant['permission']>('read');
  const [expiresAt, setExpiresAt] = useState('');

  const load = useCallback(async () => {
    try {
      const res = await get<AccessGrant[]>(`/api/ai/knowledge/${kbPid}/access-grants`);
      setGrants(res?.data ?? []);
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.accessLoadFailed', undefined, 'Failed to load access grants'));
    } finally {
      setLoading(false);
    }
  }, [kbPid, toast, t]);

  useEffect(() => {
    load();
  }, [load]);

  const save = async () => {
    if (!subjectId.trim()) return;
    setSaving(true);
    try {
      await post(`/api/ai/knowledge/${kbPid}/access-grants`, {
        subjectType,
        subjectId: subjectId.trim(),
        permission,
        expiresAt: expiresAt ? new Date(expiresAt).toISOString() : null,
      });
      setSubjectId('');
      setExpiresAt('');
      await load();
      toast.showSuccessToast(t('ai.knowledge.detail.toast.accessSaved', undefined, 'Access grant saved'));
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.accessSaveFailed', undefined, 'Could not save access grant'));
    } finally {
      setSaving(false);
    }
  };

  const remove = async (grant: AccessGrant) => {
    try {
      await del(`/api/ai/knowledge/${kbPid}/access-grants/${grant.pid}`);
      await load();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.accessDeleteFailed', undefined, 'Could not remove access grant'));
    }
  };

  return (
    <div className="max-w-4xl space-y-5">
      <div>
        <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
          {t('ai.knowledge.detail.accessTitle', undefined, 'Explicit access grants')}
        </h2>
        <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
          {t('ai.knowledge.detail.accessHint', undefined, 'Restricted and private knowledge bases are readable only by their owner or these subjects. Runtime retrieval still intersects this list with the employee deployment binding.')}
        </p>
      </div>

      <div className="grid gap-3 rounded-xl border border-gray-200 bg-white p-4 sm:grid-cols-2 lg:grid-cols-5 dark:border-gray-700 dark:bg-gray-800">
        <select value={subjectType} onChange={(e) => setSubjectType(e.target.value as AccessGrant['subject_type'])} className="rounded-lg border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700">
          <option value="role">{t('ai.knowledge.detail.subject.role', undefined, 'Role')}</option>
          <option value="user">{t('ai.knowledge.detail.subject.user', undefined, 'User')}</option>
          <option value="member">{t('ai.knowledge.detail.subject.member', undefined, 'Member')}</option>
          <option value="digital_employee">{t('ai.knowledge.detail.subject.employee', undefined, 'Digital employee')}</option>
        </select>
        <input value={subjectId} onChange={(e) => setSubjectId(e.target.value)} placeholder={t('ai.knowledge.detail.subjectId', undefined, 'Subject ID')} className="rounded-lg border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700" />
        <select value={permission} onChange={(e) => setPermission(e.target.value as AccessGrant['permission'])} className="rounded-lg border border-gray-300 px-3 py-2 dark:border-gray-600 dark:bg-gray-700">
          <option value="read">{t('ai.knowledge.detail.permission.read', undefined, 'Read')}</option>
          <option value="manage">{t('ai.knowledge.detail.permission.manage', undefined, 'Manage')}</option>
        </select>
        <input
          type="datetime-local"
          aria-label={t('ai.knowledge.detail.expiresAt', undefined, 'Optional expiration time')}
          value={expiresAt}
          onChange={(e) => setExpiresAt(e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-2 text-sm dark:border-gray-600 dark:bg-gray-700"
        />
        <button onClick={save} disabled={saving || !subjectId.trim()} className="rounded-lg bg-blue-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50">
          {saving ? t('common.saving', undefined, 'Saving…') : t('common.add', undefined, 'Add')}
        </button>
      </div>

      {loading ? (
        <div className="py-8 text-center text-gray-400">{t('common.loading', undefined, 'Loading…')}</div>
      ) : grants.length === 0 ? (
        <div className="rounded-xl border border-dashed border-gray-300 py-10 text-center text-sm text-gray-500 dark:border-gray-700">
          {t('ai.knowledge.detail.accessEmpty', undefined, 'No explicit grants. Tenant-visible knowledge remains available tenant-wide; restricted/private knowledge remains owner-only.')}
        </div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-gray-200 dark:border-gray-700">
          {grants.map((grant) => (
            <div key={grant.pid} className="flex items-center justify-between border-b border-gray-100 bg-white px-4 py-3 last:border-b-0 dark:border-gray-700 dark:bg-gray-800">
              <div>
                <span className="font-medium text-gray-900 dark:text-white">
                  {t(
                    `ai.knowledge.detail.subject.${grant.subject_type === 'digital_employee' ? 'employee' : grant.subject_type}`,
                    undefined,
                    grant.subject_type,
                  )}
                  ：{grant.subject_id}
                </span>
                <span className="ml-2 rounded bg-blue-50 px-2 py-0.5 text-xs text-blue-700 dark:bg-blue-900/30 dark:text-blue-300">
                  {t(
                    `ai.knowledge.detail.permission.${grant.permission}`,
                    undefined,
                    grant.permission,
                  )}
                </span>
                {grant.expires_at && <p className="mt-1 text-xs text-gray-500">{t('ai.knowledge.detail.expires', undefined, 'Expires')} {new Date(grant.expires_at).toLocaleString()}</p>}
              </div>
              <button
                onClick={() => remove(grant)}
                aria-label={t('ai.knowledge.detail.removeGrant', undefined, 'Remove access grant')}
                title={t('ai.knowledge.detail.removeGrant', undefined, 'Remove access grant')}
                className="rounded-lg p-2 text-gray-400 hover:bg-red-50 hover:text-red-600 dark:hover:bg-red-900/20"
              >
                <TrashIcon className="h-4 w-4" />
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Documents Tab
// ---------------------------------------------------------------------------

function DocumentsTab({ kbPid, onUpdate }: { kbPid: string; onUpdate: () => void }) {
  const toast = useToastContext();
  const { t } = useI18n();
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [releases, setReleases] = useState<IndexRelease[]>([]);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchDocs = useCallback(async () => {
    try {
      const res = await get<KbDocument[]>(`/api/ai/knowledge/${kbPid}/documents`);
      setDocs(res?.data ?? []);
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.loadDocsFailed', undefined, 'Failed to load documents'));
    } finally {
      setLoading(false);
    }
  }, [kbPid, toast]);

  const fetchReleases = useCallback(async () => {
    try {
      const res = await get<IndexRelease[]>(`/api/ai/knowledge/${kbPid}/index-releases`);
      setReleases(res?.data ?? []);
    } catch {
      setReleases([]);
    }
  }, [kbPid]);

  useEffect(() => {
    fetchDocs();
    fetchReleases();
  }, [fetchDocs, fetchReleases]);

  // Poll for processing status
  useEffect(() => {
    const hasProcessing = docs.some((d) => d.status === 'pending' || d.status === 'processing');
    if (!hasProcessing) return;

    const timer = setInterval(() => {
      fetchDocs();
      onUpdate();
    }, 3000);
    return () => clearInterval(timer);
  }, [docs, fetchDocs, onUpdate]);

  const handleUpload = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = e.target.files;
    if (!files?.length) return;

    setUploading(true);
    try {
      for (const file of Array.from(files)) {
        const formData = new FormData();
        formData.append('file', file);
        const resp = await fetch(`/api/ai/knowledge/${kbPid}/documents/upload`, {
          method: 'post',
          body: formData,
        });
        if (!resp.ok) throw new Error('Upload failed');
      }
      toast.showSuccessToast(t('ai.knowledge.detail.toast.uploaded', { count: files.length }, `${files.length} file(s) uploaded — processing started`));
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.uploadFailed', undefined, 'Upload failed'));
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const [reindexing, setReindexing] = useState(false);
  const handleReindex = async () => {
    setReindexing(true);
    try {
      const res = await post<{ reindexedChunks: number }>(`/api/ai/knowledge/${kbPid}/reindex`, {});
      toast.showSuccessToast(t('ai.knowledge.detail.toast.reindexed', { count: res?.data?.reindexedChunks ?? 0 }, `Built a new text index release for ${res?.data?.reindexedChunks ?? 0} chunks`));
      fetchReleases();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.reindexFailed', undefined, 'Reindex failed'));
    } finally {
      setReindexing(false);
    }
  };

  const [rebuildingVector, setRebuildingVector] = useState(false);
  const handleVectorRebuild = async () => {
    if (!confirm(t(
      'ai.knowledge.detail.confirmVectorRebuild',
      undefined,
      'Re-embed every active chunk and activate a new vector index release? The current release remains available for rollback.',
    ))) return;
    setRebuildingVector(true);
    try {
      const res = await post<{ indexedChunks: number }>(
        `/api/ai/knowledge/${kbPid}/rebuild-vector-index`,
        {},
      );
      toast.showSuccessToast(t(
        'ai.knowledge.detail.toast.vectorRebuilt',
        { count: res?.data?.indexedChunks ?? 0 },
        `Built a new vector index release for ${res?.data?.indexedChunks ?? 0} chunks`,
      ));
      fetchReleases();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.vectorRebuildFailed', undefined, 'Vector index rebuild failed; the previous release is still active'));
    } finally {
      setRebuildingVector(false);
    }
  };

  const activateRelease = async (release: IndexRelease) => {
    if (!confirm(t(
      'ai.knowledge.detail.confirmReleaseActivate',
      { release: release.release_no },
      `Activate index release #${release.release_no}?`,
    ))) return;
    try {
      await post(
        `/api/ai/knowledge/${kbPid}/index-releases/${release.pid}/activate`,
        {},
      );
      toast.showSuccessToast(t('ai.knowledge.detail.toast.releaseActivated', undefined, 'Index release activated'));
      fetchReleases();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.releaseActivateFailed', undefined, 'Could not activate that release'));
    }
  };

  const handleDelete = async (doc: KbDocument) => {
    if (!confirm(t('ai.knowledge.detail.confirmDeleteDoc', { name: doc.docName }, `Delete "${doc.docName}"?`))) return;
    try {
      await del(`/api/ai/knowledge/${kbPid}/documents/${doc.pid}`);
      toast.showSuccessToast(t('ai.knowledge.detail.toast.docDeleted', undefined, 'Document deleted'));
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.docDeleteFailed', undefined, 'Failed to delete document'));
    }
  };

  const [url, setUrl] = useState('');
  const [addingUrl, setAddingUrl] = useState(false);

  const handleAddUrl = async () => {
    const target = url.trim();
    if (!target) return;

    setAddingUrl(true);
    try {
      const res = await post<KbDocument>(`/api/ai/knowledge/${kbPid}/documents/from-url`, {
        url: target,
      });
      // A refused URL (unsafe target, not an HTML page, no readable text) comes back as a normal
      // response with success=false — it does not throw. Show the server's reason: "failed" alone
      // leaves the user with no idea whether to fix the URL or give up.
      if (res?.success === false || !res?.data) {
        toast.showErrorToast(res?.message || t('ai.knowledge.detail.toast.urlFailed', undefined, 'Could not add that URL'));
        return;
      }
      toast.showSuccessToast(t('ai.knowledge.detail.toast.urlAdded', { name: res.data.docName }, `Added "${res.data.docName}"`));
      setUrl('');
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.urlFailed', undefined, 'Could not add that URL'));
    } finally {
      setAddingUrl(false);
    }
  };

  const handleReprocess = async (doc: KbDocument) => {
    try {
      await post(`/api/ai/knowledge/${kbPid}/documents/${doc.pid}/reprocess`, {});
      toast.showSuccessToast(t('ai.knowledge.detail.toast.reprocessing', { name: doc.docName }, `Reprocessing "${doc.docName}"`));
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.reprocessFailed', undefined, 'Failed to reprocess document'));
    }
  };

  return (
    <div>
      {releases.length > 0 && (
        <div className="mb-5 rounded-xl border border-slate-200 bg-slate-50 p-4 dark:border-slate-700 dark:bg-slate-900/40">
          <div className="mb-3 flex items-center justify-between">
            <div>
              <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
                {t('ai.knowledge.detail.indexReleases', undefined, 'Index releases')}
              </h3>
              <p className="text-xs text-slate-500">
                {t('ai.knowledge.detail.indexReleasesHint', undefined, 'Text and vector rebuilds create immutable releases; activation is an atomic pointer switch.')}
              </p>
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            {releases.slice(0, 6).map((release) => (
              <button
                key={release.pid}
                type="button"
                disabled={release.state === 'active' || release.state === 'failed'}
                onClick={() => activateRelease(release)}
                title={release.error_message || release.pid}
                className={`rounded-lg border px-3 py-2 text-left text-xs ${
                  release.state === 'active'
                    ? 'border-green-300 bg-green-50 text-green-700 dark:border-green-800 dark:bg-green-900/30 dark:text-green-300'
                    : release.state === 'failed'
                      ? 'cursor-not-allowed border-red-200 bg-red-50 text-red-600 dark:border-red-900 dark:bg-red-900/20'
                      : 'border-slate-300 bg-white text-slate-600 hover:border-blue-400 dark:border-slate-600 dark:bg-slate-800 dark:text-slate-300'
                }`}
              >
                <span className="font-semibold">
                  #{release.release_no} · {t(
                    `ai.knowledge.detail.releaseType.${release.release_type}`,
                    undefined,
                    release.release_type,
                  )}
                </span>
                <span className="ml-2">
                  · {t(
                    `ai.knowledge.detail.releaseState.${release.state}`,
                    undefined,
                    release.state,
                  )}
                </span>
              </button>
            ))}
          </div>
        </div>
      )}
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-white">{t('ai.knowledge.detail.tab.documents', undefined, 'Documents')}</h2>
        <div className="flex items-center gap-2">
        <button
          type="button"
          data-testid="kb-reindex-button"
          onClick={handleReindex}
          disabled={reindexing}
          className={`flex items-center gap-2 rounded-lg border border-gray-300 px-4 py-2 text-sm transition-colors dark:border-gray-600 ${
            reindexing ? 'cursor-not-allowed text-gray-400' : 'text-gray-700 hover:bg-gray-50 dark:text-gray-200 dark:hover:bg-gray-800'
          }`}
        >
          {reindexing ? t('ai.knowledge.detail.reindexing', undefined, 'Building text index…') : t('ai.knowledge.detail.reindex', undefined, 'Rebuild text index')}
        </button>
        <button
          type="button"
          data-testid="kb-vector-rebuild-button"
          onClick={handleVectorRebuild}
          disabled={rebuildingVector}
          className="flex items-center gap-2 rounded-lg border border-gray-300 px-4 py-2 text-sm text-gray-700 transition-colors hover:bg-gray-50 disabled:cursor-not-allowed disabled:text-gray-400 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-800"
        >
          {rebuildingVector
            ? t('ai.knowledge.detail.vectorRebuilding', undefined, 'Re-embedding…')
            : t('ai.knowledge.detail.vectorRebuild', undefined, 'Rebuild vector index')}
        </button>
        <label
          className={`flex cursor-pointer items-center gap-2 rounded-lg px-4 py-2 transition-colors ${
            uploading ? 'cursor-not-allowed bg-gray-400' : 'bg-blue-600 hover:bg-blue-700'
          } text-white`}
        >
          <CloudArrowUpIcon className="h-5 w-5" />
          {uploading ? t('ai.knowledge.detail.uploading', undefined, 'Uploading...') : t('ai.knowledge.detail.uploadFiles', undefined, 'Upload Files')}
          <input
            data-testid="kb-upload-input"
            ref={fileInputRef}
            type="file"
            multiple
            accept=".pdf,.docx,.pptx,.xlsx,.ppt,.xls,.md,.txt,.csv,.html,.png,.jpg,.jpeg,.gif,.webp"
            onChange={handleUpload}
            className="hidden"
            disabled={uploading}
          />
        </label>
        </div>
      </div>

      <div className="mb-4 flex items-center gap-2">
        <LinkIcon className="h-5 w-5 shrink-0 text-gray-400" />
        <input
          type="url"
          data-testid="kb-url-input"
          value={url}
          onChange={(e) => setUrl(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter') handleAddUrl();
          }}
          placeholder={t('ai.knowledge.detail.urlPlaceholder', undefined, 'Paste a page URL to add it to this knowledge base')}
          disabled={addingUrl}
          className="flex-1 rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-900 placeholder:text-gray-400 focus:border-blue-500 focus:outline-none focus:ring-1 focus:ring-blue-500 disabled:bg-gray-100 dark:border-gray-600 dark:bg-gray-800 dark:text-white dark:disabled:bg-gray-700"
        />
        <button
          type="button"
          data-testid="kb-url-add-button"
          onClick={handleAddUrl}
          disabled={addingUrl || !url.trim()}
          className={`rounded-lg px-4 py-2 text-sm text-white transition-colors ${
            addingUrl || !url.trim()
              ? 'cursor-not-allowed bg-gray-400'
              : 'bg-blue-600 hover:bg-blue-700'
          }`}
        >
          {addingUrl ? t('ai.knowledge.detail.fetching', undefined, 'Fetching...') : t('ai.knowledge.detail.addUrl', undefined, 'Add URL')}
        </button>
      </div>

      {loading ? (
        <div className="py-8 text-center text-gray-400">{t('ai.knowledge.detail.loading', undefined, 'Loading...')}</div>
      ) : docs.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <DocumentTextIcon className="mb-3 h-12 w-12" />
          <p>{t('ai.knowledge.detail.empty', undefined, 'No documents yet. Upload PDF, DOCX, PPTX, XLSX, MD, TXT, CSV, HTML — or a chart image.')}</p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 dark:bg-gray-800">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.name', undefined, 'Name')}
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.type', undefined, 'Type')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.chunks', undefined, 'Chunks')}
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.chars', undefined, 'Chars')}
                </th>
                <th className="px-4 py-3 text-center font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.status', undefined, 'Status')}
                </th>
                <th className="px-4 py-3 text-center font-medium text-gray-500 dark:text-gray-400">
                  {t('ai.knowledge.detail.col.actions', undefined, 'Actions')}
                </th>
              </tr>
            </thead>
            <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
              {docs.map((doc) => (
                <tr
                  key={doc.pid}
                  className="dark:hover:bg-gray-750 bg-white hover:bg-gray-50 dark:bg-gray-800"
                >
                  <td className="px-4 py-3 font-medium text-gray-900 dark:text-white">
                    {doc.docName}
                    {doc.activeVersionPid && (
                      <p className="mt-0.5 font-mono text-[11px] font-normal text-gray-400">
                        v{doc.versionNo ?? 1} · {doc.activeVersionPid}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3 text-gray-500">{doc.docType}</td>
                  <td className="px-4 py-3 text-right text-gray-500">
                    <EmbeddingState doc={doc} />
                  </td>
                  <td className="px-4 py-3 text-right text-gray-500">
                    {doc.charCount?.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <span
                      data-testid={`doc-status-${doc.pid}`}
                      className={`rounded-full px-2 py-1 text-xs ${STATUS_STYLES[doc.status] || ''}`}
                    >
                      {t(`ai.knowledge.detail.docStatus.${doc.status}`, undefined, doc.status)}
                    </span>
                    {doc.status === 'processing' && (
                      <span className="ml-1 inline-block h-3 w-3 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                    )}
                    {doc.status === 'failed' && doc.errorMessage && (
                      <p
                        data-testid={`doc-error-${doc.pid}`}
                        className="mt-1 text-xs text-red-600 dark:text-red-400"
                        title={doc.errorMessage}
                      >
                        {doc.errorMessage}
                      </p>
                    )}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <div className="flex items-center justify-center gap-2">
                      {doc.status === 'failed' && (
                        <button
                          type="button"
                          data-testid={`doc-reprocess-${doc.pid}`}
                          onClick={() => handleReprocess(doc)}
                          title={t('ai.knowledge.detail.reprocessTitle', undefined, 'Reprocess document')}
                          className="text-gray-400 hover:text-blue-600"
                        >
                          <ArrowPathIcon className="h-4 w-4" />
                        </button>
                      )}
                      <button
                        data-testid={`doc-delete-${doc.pid}`}
                        onClick={() => handleDelete(doc)}
                        aria-label={t('ai.knowledge.detail.deleteDocument', { name: doc.docName }, `Delete ${doc.docName}`)}
                        title={t('ai.knowledge.detail.deleteDocument', { name: doc.docName }, `Delete ${doc.docName}`)}
                        className="text-gray-400 hover:text-red-500"
                      >
                        <TrashIcon className="h-4 w-4" />
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </div>
  );
}

/**
 * What the chunk count leaves out.
 *
 * A document goes "completed" once its text is chunked and stored. Embedding is a separate remote
 * step, and it can fail on every single chunk while the document still shows green — leaving a
 * knowledge base that looks perfect and answers nothing, because retrieval silently drops to
 * keyword matching. The row said "3 chunks" and told you nothing about that.
 */
function EmbeddingState({ doc }: { doc: KbDocument }) {
  const { t } = useI18n();
  const total = doc.chunkCount ?? 0;
  const embedded = doc.embeddedChunkCount ?? 0;

  if (total === 0) {
    return <span>0</span>;
  }

  if (embedded === total) {
    return <span data-testid={`doc-chunks-${doc.pid}`}>{total}</span>;
  }

  const none = embedded === 0;
  return (
    <span
      data-testid={`doc-chunks-${doc.pid}`}
      title={
        none
          ? t('ai.knowledge.detail.embed.noneTitle', undefined, "Stored, but not embedded — this document cannot be found by meaning, only by keyword. Check the knowledge base's embedding provider.")
          : t('ai.knowledge.detail.embed.partialTitle', { embedded, total }, `Only ${embedded} of ${total} chunks were embedded; the rest are searchable by keyword only.`)
      }
      className={`rounded px-1.5 py-0.5 text-xs ${
        none
          ? 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400'
          : 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
      }`}
    >
      {embedded}/{total} {t('ai.knowledge.detail.embed.embedded', undefined, 'embedded')}
    </span>
  );
}

// ---------------------------------------------------------------------------
// Chunks Tab
// ---------------------------------------------------------------------------

function ChunksTab({ kbPid }: { kbPid: string }) {
  const toast = useToastContext();
  const { t } = useI18n();
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [selectedDoc, setSelectedDoc] = useState<string | null>(null);
  const [chunks, setChunks] = useState<KbChunk[]>([]);
  const [loadingChunks, setLoadingChunks] = useState(false);
  const [expandedChunks, setExpandedChunks] = useState<Set<string>>(new Set());

  useEffect(() => {
    (async () => {
      try {
        const res = await get<KbDocument[]>(`/api/ai/knowledge/${kbPid}/documents`);
        const completedDocs = (res?.data ?? []).filter((d: KbDocument) => d.status === 'completed');
        setDocs(completedDocs);
        if (completedDocs.length > 0) setSelectedDoc(completedDocs[0].pid);
      } catch {
        toast.showErrorToast(t('ai.knowledge.detail.toast.loadDocsFailed', undefined, 'Failed to load documents'));
      }
    })();
  }, [kbPid, toast]);

  useEffect(() => {
    if (!selectedDoc) return;
    setLoadingChunks(true);
    (async () => {
      try {
        const res = await get<KbChunk[]>(
          `/api/ai/knowledge/${kbPid}/documents/${selectedDoc}/chunks`,
          { limit: 100 },
        );
        setChunks(res?.data ?? []);
      } catch {
        toast.showErrorToast(t('ai.knowledge.detail.toast.loadChunksFailed', undefined, 'Failed to load chunks'));
      } finally {
        setLoadingChunks(false);
      }
    })();
  }, [kbPid, selectedDoc, toast]);

  const toggleChunk = (pid: string) => {
    setExpandedChunks((prev) => {
      const next = new Set(prev);
      next.has(pid) ? next.delete(pid) : next.add(pid);
      return next;
    });
  };

  return (
    <div>
      <div className="mb-4">
        <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
          {t('ai.knowledge.detail.selectDocument', undefined, 'Select Document')}
        </label>
        <select
          value={selectedDoc || ''}
          onChange={(e) => setSelectedDoc(e.target.value)}
          className="rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
        >
          {docs.map((d) => (
            <option key={d.pid} value={d.pid}>
              {d.docName} ({d.chunkCount} chunks)
            </option>
          ))}
        </select>
      </div>

      {loadingChunks ? (
        <div className="py-8 text-center text-gray-400">{t('ai.knowledge.detail.loadingChunks', undefined, 'Loading chunks...')}</div>
      ) : chunks.length === 0 ? (
        <div className="py-8 text-center text-gray-400">{t('ai.knowledge.detail.noChunks', undefined, 'No chunks found')}</div>
      ) : (
        <div className="space-y-2">
          {chunks.map((chunk) => (
            <div
              key={chunk.pid}
              className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700"
            >
              <button
                onClick={() => toggleChunk(chunk.pid)}
                className="dark:hover:bg-gray-750 flex w-full items-center justify-between bg-gray-50 px-4 py-3 text-left hover:bg-gray-100 dark:bg-gray-800"
              >
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  {t('ai.knowledge.detail.chunk', undefined, 'Chunk')} #{chunk.chunkIndex}
                  <span className="ml-2 text-xs text-gray-400">
                    {chunk.charCount} {t('ai.knowledge.detail.unit.chars', undefined, 'chars')} · {chunk.tokenCount} {t('ai.knowledge.detail.unit.tokens', undefined, 'tokens')} ·
                    <span
                      className={
                        chunk.embeddingStatus === 'completed' ? 'text-green-500' : 'text-yellow-500'
                      }
                    >
                      {' '}
                      {t(`ai.knowledge.detail.docStatus.${chunk.embeddingStatus}`, undefined, chunk.embeddingStatus)}
                    </span>
                  </span>
                </span>
                {expandedChunks.has(chunk.pid) ? (
                  <ChevronDownIcon className="h-4 w-4 text-gray-400" />
                ) : (
                  <ChevronRightIcon className="h-4 w-4 text-gray-400" />
                )}
              </button>
              {expandedChunks.has(chunk.pid) && (
                <div className="max-h-64 overflow-auto border-t border-gray-200 bg-white px-4 py-3 text-sm whitespace-pre-wrap text-gray-700 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-300">
                  {chunk.content}
                </div>
              )}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Retrieval Test Tab
// ---------------------------------------------------------------------------

function RetrievalTestTab({ kbPid }: { kbPid: string }) {
  const toast = useToastContext();
  const { t } = useI18n();
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<RetrievalResult[]>([]);
  const [path, setPath] = useState<string | null>(null);
  const [warnings, setWarnings] = useState<string[]>([]);
  const [searching, setSearching] = useState(false);
  const [hasSearched, setHasSearched] = useState(false);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setSearching(true);
    setHasSearched(true);
    try {
      const res = await post<{ results: RetrievalResult[]; warnings: string[]; path: string }>(
        '/api/ai/knowledge/retrieve',
        {
          query,
          knowledgeBaseIds: [kbPid],
          topK: 5,
        },
      );
      setResults(res?.data?.results ?? []);
      setPath(res?.data?.path ?? null);
      setWarnings(res?.data?.warnings ?? []);
      for (const w of res?.data?.warnings ?? []) {
        toast.showErrorToast(w);
      }
    } catch {
      toast.showErrorToast(t('ai.knowledge.detail.toast.retrievalFailed', undefined, 'Retrieval failed'));
    } finally {
      setSearching(false);
    }
  };

  return (
    <div>
      <div className="mb-6 flex gap-3">
        <input
          data-testid="kb-retrieval-query"
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          placeholder={t('ai.knowledge.detail.retrievalPlaceholder', undefined, 'Ask a question to test retrieval...')}
          className="flex-1 rounded-lg border border-gray-300 bg-white px-4 py-2.5 text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
        />
        <button
          data-testid="kb-retrieval-search"
          onClick={handleSearch}
          disabled={searching || !query.trim()}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-2.5 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <MagnifyingGlassIcon className="h-5 w-5" />
          {searching ? t('ai.knowledge.detail.searching', undefined, 'Searching...') : t('ai.knowledge.detail.search', undefined, 'Search')}
        </button>
      </div>

      {warnings.length > 0 && (
        <div role="status" className="mb-4 rounded-lg border border-amber-200 bg-amber-50 p-3 text-sm text-amber-800 dark:border-amber-900 dark:bg-amber-900/20 dark:text-amber-300">
          <ul className="list-disc space-y-1 pl-5">
            {warnings.map((warning) => <li key={warning}>{warning}</li>)}
          </ul>
        </div>
      )}

      {results.length > 0 && (
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <h3 className="text-sm font-medium text-gray-500 dark:text-gray-400">
              {results.length} {t('ai.knowledge.detail.resultsFound', undefined, 'result(s) found')}
            </h3>
            {path && (
              <span
                data-testid="retrieval-path"
                /* The raw path, so a check can assert the semantics rather than the
                   localized label — the badge text is translated and a test pinned to
                   English wording goes red under any other locale. */
                data-path={path}
                title={
                  path === 'hybrid'
                    ? t('ai.knowledge.detail.pathHybridTitle', undefined, 'Vector similarity combined with keyword matching')
                    : t('ai.knowledge.detail.pathKeywordTitle', undefined, 'Keyword matching only — semantic search was unavailable')
                }
                className={`rounded-full px-2 py-0.5 text-xs ${
                  path === 'hybrid'
                    ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                    : 'bg-amber-100 text-amber-700 dark:bg-amber-900/30 dark:text-amber-400'
                }`}
              >
                {path === 'hybrid'
                  ? t('ai.knowledge.detail.pathHybrid', undefined, 'hybrid (vector + keyword)')
                  : t('ai.knowledge.detail.pathKeywordBadge', undefined, 'keyword only')}
              </span>
            )}
          </div>
          {results.map((r, i) => (
            <div
              key={r.chunkPid || i}
              className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
            >
              <div className="mb-2 flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  {r.docName} — {t('ai.knowledge.detail.chunk', undefined, 'Chunk')} #{r.chunkIndex}
                </span>
                <span className="rounded-full bg-blue-100 px-2 py-1 text-xs text-blue-700 dark:bg-blue-900/30 dark:text-blue-400">
                  {(r.similarity * 100).toFixed(1)}% {t('ai.knowledge.detail.matchLabel', undefined, 'match')}
                </span>
              </div>
              <p className="line-clamp-6 text-sm whitespace-pre-wrap text-gray-600 dark:text-gray-400">
                {r.content}
              </p>
              <div className="mt-3 grid gap-2 text-[11px] text-gray-500 sm:grid-cols-2">
                <span className="font-mono">version: {r.documentVersionPid}</span>
                <span className="font-mono">release: {r.indexReleasePid}</span>
                <span>vector {r.vectorScore?.toFixed(3)} · keyword {r.bm25Score?.toFixed(3)}</span>
                <span>hybrid {r.hybridScore?.toFixed(3)} · rerank {r.rerankScore?.toFixed(3)}</span>
              </div>
              {r.citationLocator && (
                <a
                  href={`/${r.citationLocator}`}
                  className="mt-2 inline-flex text-xs font-medium text-blue-600 hover:underline dark:text-blue-400"
                >
                  {t('ai.knowledge.detail.openEvidence', undefined, 'Open source evidence')}
                </a>
              )}
            </div>
          ))}
        </div>
      )}

      {!searching && hasSearched && results.length === 0 && (
        <div className="py-8 text-center text-gray-400">
          {t('ai.knowledge.detail.noResults', undefined, 'No results. Try a different query or ensure documents are processed.')}
        </div>
      )}
    </div>
  );
}
