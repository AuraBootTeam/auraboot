/**
 * AuraBot — Knowledge Base Detail Page
 *
 * 3 tabs: Documents (upload + status), Chunks (preview), Retrieval Test.
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowLeftIcon,
  CloudArrowUpIcon,
  DocumentTextIcon,
  MagnifyingGlassIcon,
  TrashIcon,
  ChevronDownIcon,
  ChevronRightIcon,
} from '@heroicons/react/24/outline';
import { get, post, del } from '~/shared/services/http-client';
import { useToastContext } from '~/contexts/ToastContext';

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
  chunkPid: string;
  docName: string;
  kbName: string;
  chunkIndex: number;
  content: string;
  distance: number;
  similarity: number;
}

const STATUS_STYLES: Record<string, string> = {
  pending: 'bg-yellow-100 text-yellow-700 dark:bg-yellow-900/30 dark:text-yellow-400',
  processing: 'bg-blue-100 text-blue-700 dark:bg-blue-900/30 dark:text-blue-400',
  completed: 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400',
  failed: 'bg-red-100 text-red-700 dark:bg-red-900/30 dark:text-red-400',
};

const TABS = ['Documents', 'Chunks', 'Retrieval Test'] as const;
type Tab = (typeof TABS)[number];

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function KnowledgeBaseDetailPage() {
  const { kbPid } = useParams<{ kbPid: string }>();
  const navigate = useNavigate();
  const toast = useToastContext();

  const [kb, setKb] = useState<KnowledgeBase | null>(null);
  const [activeTab, setActiveTab] = useState<Tab>('Documents');
  const [loading, setLoading] = useState(true);

  const fetchKb = useCallback(async () => {
    try {
      const res = await get<KnowledgeBase>(`/api/ai/knowledge/${kbPid}`);
      setKb(res?.data ?? null);
    } catch {
      toast.showErrorToast('Failed to load knowledge base');
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
    return <div className="p-6 text-gray-500">Knowledge base not found</div>;
  }

  return (
    <div className="flex h-full flex-col overflow-hidden">
      {/* Header */}
      <div className="flex items-center gap-4 border-b border-gray-200 px-6 py-4 dark:border-gray-700">
        <button
          onClick={() => navigate('/aurabot/knowledge')}
          className="text-gray-400 hover:text-gray-600"
        >
          <ArrowLeftIcon className="h-5 w-5" />
        </button>
        <div>
          <h1 className="text-xl font-bold text-gray-900 dark:text-white">{kb.name}</h1>
          <div className="flex items-center gap-3 text-sm text-gray-500 dark:text-gray-400">
            <span>{kb.docCount} documents</span>
            <span>{kb.chunkCount} chunks</span>
            <span>
              {kb.embeddingProvider}/{kb.embeddingModel}
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
            {tab}
          </button>
        ))}
      </div>

      {/* Tab content */}
      <div className="flex-1 overflow-auto p-6">
        {activeTab === 'Documents' && <DocumentsTab kbPid={kbPid!} onUpdate={fetchKb} />}
        {activeTab === 'Chunks' && <ChunksTab kbPid={kbPid!} />}
        {activeTab === 'Retrieval Test' && <RetrievalTestTab kbPid={kbPid!} />}
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Documents Tab
// ---------------------------------------------------------------------------

function DocumentsTab({ kbPid, onUpdate }: { kbPid: string; onUpdate: () => void }) {
  const toast = useToastContext();
  const [docs, setDocs] = useState<KbDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const fetchDocs = useCallback(async () => {
    try {
      const res = await get<KbDocument[]>(`/api/ai/knowledge/${kbPid}/documents`);
      setDocs(res?.data ?? []);
    } catch {
      toast.showErrorToast('Failed to load documents');
    } finally {
      setLoading(false);
    }
  }, [kbPid, toast]);

  useEffect(() => {
    fetchDocs();
  }, [fetchDocs]);

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
      toast.showSuccessToast(`${files.length} file(s) uploaded — processing started`);
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast('Upload failed');
    } finally {
      setUploading(false);
      if (fileInputRef.current) fileInputRef.current.value = '';
    }
  };

  const handleDelete = async (doc: KbDocument) => {
    if (!confirm(`Delete "${doc.docName}"?`)) return;
    try {
      await del(`/api/ai/knowledge/${kbPid}/documents/${doc.pid}`);
      toast.showSuccessToast('Document deleted');
      fetchDocs();
      onUpdate();
    } catch {
      toast.showErrorToast('Failed to delete document');
    }
  };

  return (
    <div>
      <div className="mb-4 flex items-center justify-between">
        <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Documents</h2>
        <label
          className={`flex cursor-pointer items-center gap-2 rounded-lg px-4 py-2 transition-colors ${
            uploading ? 'cursor-not-allowed bg-gray-400' : 'bg-blue-600 hover:bg-blue-700'
          } text-white`}
        >
          <CloudArrowUpIcon className="h-5 w-5" />
          {uploading ? 'Uploading...' : 'Upload Files'}
          <input
            ref={fileInputRef}
            type="file"
            multiple
            accept=".pdf,.docx,.md,.txt,.csv,.html"
            onChange={handleUpload}
            className="hidden"
            disabled={uploading}
          />
        </label>
      </div>

      {loading ? (
        <div className="py-8 text-center text-gray-400">Loading...</div>
      ) : docs.length === 0 ? (
        <div className="flex flex-col items-center justify-center py-16 text-gray-400">
          <DocumentTextIcon className="mb-3 h-12 w-12" />
          <p>No documents yet. Upload PDF, DOCX, MD, TXT, or CSV files.</p>
        </div>
      ) : (
        <div className="overflow-hidden rounded-lg border border-gray-200 dark:border-gray-700">
          <table className="w-full text-sm">
            <thead className="bg-gray-50 dark:bg-gray-800">
              <tr>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                  Name
                </th>
                <th className="px-4 py-3 text-left font-medium text-gray-500 dark:text-gray-400">
                  Type
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                  Chunks
                </th>
                <th className="px-4 py-3 text-right font-medium text-gray-500 dark:text-gray-400">
                  Chars
                </th>
                <th className="px-4 py-3 text-center font-medium text-gray-500 dark:text-gray-400">
                  Status
                </th>
                <th className="px-4 py-3 text-center font-medium text-gray-500 dark:text-gray-400">
                  Actions
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
                  </td>
                  <td className="px-4 py-3 text-gray-500">{doc.docType}</td>
                  <td className="px-4 py-3 text-right text-gray-500">{doc.chunkCount}</td>
                  <td className="px-4 py-3 text-right text-gray-500">
                    {doc.charCount?.toLocaleString()}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <span
                      className={`rounded-full px-2 py-1 text-xs ${STATUS_STYLES[doc.status] || ''}`}
                    >
                      {doc.status}
                    </span>
                    {doc.status === 'processing' && (
                      <span className="ml-1 inline-block h-3 w-3 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                    )}
                  </td>
                  <td className="px-4 py-3 text-center">
                    <button
                      onClick={() => handleDelete(doc)}
                      className="text-gray-400 hover:text-red-500"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
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

// ---------------------------------------------------------------------------
// Chunks Tab
// ---------------------------------------------------------------------------

function ChunksTab({ kbPid }: { kbPid: string }) {
  const toast = useToastContext();
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
        toast.showErrorToast('Failed to load documents');
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
        toast.showErrorToast('Failed to load chunks');
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
          Select Document
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
        <div className="py-8 text-center text-gray-400">Loading chunks...</div>
      ) : chunks.length === 0 ? (
        <div className="py-8 text-center text-gray-400">No chunks found</div>
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
                  Chunk #{chunk.chunkIndex}
                  <span className="ml-2 text-xs text-gray-400">
                    {chunk.charCount} chars · {chunk.tokenCount} tokens ·
                    <span
                      className={
                        chunk.embeddingStatus === 'completed' ? 'text-green-500' : 'text-yellow-500'
                      }
                    >
                      {' '}
                      {chunk.embeddingStatus}
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
  const [query, setQuery] = useState('');
  const [results, setResults] = useState<RetrievalResult[]>([]);
  const [searching, setSearching] = useState(false);

  const handleSearch = async () => {
    if (!query.trim()) return;
    setSearching(true);
    try {
      const res = await post<RetrievalResult[]>('/api/ai/knowledge/retrieve', {
        query,
        knowledgeBaseIds: [kbPid],
        topK: 5,
      });
      setResults(res?.data ?? []);
    } catch {
      toast.showErrorToast('Retrieval failed');
    } finally {
      setSearching(false);
    }
  };

  return (
    <div>
      <div className="mb-6 flex gap-3">
        <input
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          onKeyDown={(e) => e.key === 'Enter' && handleSearch()}
          placeholder="Ask a question to test retrieval..."
          className="flex-1 rounded-lg border border-gray-300 bg-white px-4 py-2.5 text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
        />
        <button
          onClick={handleSearch}
          disabled={searching || !query.trim()}
          className="flex items-center gap-2 rounded-lg bg-blue-600 px-6 py-2.5 text-white transition-colors hover:bg-blue-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          <MagnifyingGlassIcon className="h-5 w-5" />
          {searching ? 'Searching...' : 'Search'}
        </button>
      </div>

      {results.length > 0 && (
        <div className="space-y-3">
          <h3 className="text-sm font-medium text-gray-500 dark:text-gray-400">
            {results.length} result(s) found
          </h3>
          {results.map((r, i) => (
            <div
              key={r.chunkPid || i}
              className="rounded-lg border border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800"
            >
              <div className="mb-2 flex items-center justify-between">
                <span className="text-sm font-medium text-gray-700 dark:text-gray-300">
                  {r.docName} — Chunk #{r.chunkIndex}
                </span>
                <span className="rounded-full bg-blue-100 px-2 py-1 text-xs text-blue-700 dark:bg-blue-900/30 dark:text-blue-400">
                  {(r.similarity * 100).toFixed(1)}% match
                </span>
              </div>
              <p className="line-clamp-6 text-sm whitespace-pre-wrap text-gray-600 dark:text-gray-400">
                {r.content}
              </p>
            </div>
          ))}
        </div>
      )}

      {!searching && results.length === 0 && query && (
        <div className="py-8 text-center text-gray-400">
          No results. Try a different query or ensure documents are processed.
        </div>
      )}
    </div>
  );
}
