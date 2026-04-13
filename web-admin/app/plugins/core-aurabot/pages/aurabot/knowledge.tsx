/**
 * AuraBot — RAG Knowledge Base Management
 *
 * Card-grid list of knowledge bases with create/edit/delete.
 * Follows the same layout pattern as providers.tsx.
 */

import { useState, useEffect, useCallback } from 'react';
import {
  PlusIcon,
  TrashIcon,
  PencilIcon,
  DocumentTextIcon,
  MagnifyingGlassIcon,
  XMarkIcon,
  CircleStackIcon,
} from '@heroicons/react/24/outline';
import { get, post, put, del } from '~/services/http-client';
import { useToastContext } from '~/contexts/ToastContext';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

interface KnowledgeBase {
  pid: string;
  name: string;
  description: string;
  status: 'active' | 'disabled';
  embeddingProvider: string;
  embeddingModel: string;
  embeddingDimension: number;
  chunkStrategy: string;
  chunkSize: number;
  chunkOverlap: number;
  docCount: number;
  chunkCount: number;
  createdAt: string;
}

interface CreateKbForm {
  name: string;
  description: string;
  embeddingProvider: string;
  embeddingModel: string;
  chunkSize: number;
  chunkOverlap: number;
}

const DEFAULT_FORM: CreateKbForm = {
  name: '',
  description: '',
  embeddingProvider: 'openai',
  embeddingModel: 'text-embedding-3-small',
  chunkSize: 500,
  chunkOverlap: 50,
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function KnowledgeBasePage() {
  const toast = useToastContext();
  const [kbs, setKbs] = useState<KnowledgeBase[]>([]);
  const [loading, setLoading] = useState(true);
  const [showCreate, setShowCreate] = useState(false);
  const [editingKb, setEditingKb] = useState<KnowledgeBase | null>(null);
  const [form, setForm] = useState<CreateKbForm>(DEFAULT_FORM);

  const fetchKbs = useCallback(async () => {
    try {
      const res = await get<KnowledgeBase[]>('/api/ai/knowledge');
      setKbs(res?.data ?? []);
    } catch {
      toast.showErrorToast('Failed to load knowledge bases');
    } finally {
      setLoading(false);
    }
  }, [toast]);

  useEffect(() => {
    fetchKbs();
  }, [fetchKbs]);

  const handleCreate = async () => {
    if (!form.name.trim()) {
      toast.showWarningToast('Name is required');
      return;
    }
    try {
      await post('/api/ai/knowledge', form);
      toast.showSuccessToast('Knowledge base created');
      setShowCreate(false);
      setForm(DEFAULT_FORM);
      fetchKbs();
    } catch {
      toast.showErrorToast('Failed to create knowledge base');
    }
  };

  const handleUpdate = async () => {
    if (!editingKb) return;
    try {
      await put(`/api/ai/knowledge/${editingKb.pid}`, form);
      toast.showSuccessToast('Knowledge base updated');
      setEditingKb(null);
      setForm(DEFAULT_FORM);
      fetchKbs();
    } catch {
      toast.showErrorToast('Failed to update knowledge base');
    }
  };

  const handleDelete = async (kb: KnowledgeBase) => {
    if (!confirm(`Delete "${kb.name}"? This will remove all documents and chunks.`)) return;
    try {
      await del(`/api/ai/knowledge/${kb.pid}`);
      toast.showSuccessToast('Knowledge base deleted');
      fetchKbs();
    } catch {
      toast.showErrorToast('Failed to delete knowledge base');
    }
  };

  const handleToggleStatus = async (kb: KnowledgeBase) => {
    try {
      await post(`/api/ai/knowledge/${kb.pid}/toggle-status`);
      fetchKbs();
    } catch {
      toast.showErrorToast('Failed to toggle status');
    }
  };

  const openEdit = (kb: KnowledgeBase) => {
    setForm({
      name: kb.name,
      description: kb.description || '',
      embeddingProvider: kb.embeddingProvider,
      embeddingModel: kb.embeddingModel,
      chunkSize: kb.chunkSize,
      chunkOverlap: kb.chunkOverlap,
    });
    setEditingKb(kb);
  };

  const isFormOpen = showCreate || editingKb !== null;

  return (
    <div className="flex h-full">
      {/* Main content */}
      <div className={`flex-1 overflow-auto p-6 ${isFormOpen ? 'mr-96' : ''}`}>
        <div className="mb-6 flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Knowledge Base</h1>
            <p className="mt-1 text-sm text-gray-500 dark:text-gray-400">
              Manage RAG knowledge bases for AI-augmented responses
            </p>
          </div>
          <button
            onClick={() => {
              setForm(DEFAULT_FORM);
              setShowCreate(true);
              setEditingKb(null);
            }}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
          >
            <PlusIcon className="h-5 w-5" />
            New Knowledge Base
          </button>
        </div>

        {loading ? (
          <div className="flex h-64 items-center justify-center">
            <div className="h-8 w-8 animate-spin rounded-full border-4 border-blue-500 border-t-transparent" />
          </div>
        ) : kbs.length === 0 ? (
          <div className="flex h-64 flex-col items-center justify-center text-gray-400">
            <CircleStackIcon className="mb-4 h-16 w-16" />
            <p className="text-lg">No knowledge bases yet</p>
            <p className="text-sm">Create one to start building your AI knowledge</p>
          </div>
        ) : (
          <div className="grid grid-cols-1 gap-4 md:grid-cols-2 lg:grid-cols-3">
            {kbs.map((kb) => (
              <KbCard
                key={kb.pid}
                kb={kb}
                onEdit={() => openEdit(kb)}
                onDelete={() => handleDelete(kb)}
                onToggleStatus={() => handleToggleStatus(kb)}
              />
            ))}
          </div>
        )}
      </div>

      {/* Side panel */}
      {isFormOpen && (
        <div className="fixed top-0 right-0 bottom-0 z-50 w-96 overflow-auto border-l border-gray-200 bg-white shadow-xl dark:border-gray-700 dark:bg-gray-800">
          <div className="p-6">
            <div className="mb-6 flex items-center justify-between">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
                {editingKb ? 'Edit Knowledge Base' : 'New Knowledge Base'}
              </h2>
              <button
                onClick={() => {
                  setShowCreate(false);
                  setEditingKb(null);
                }}
              >
                <XMarkIcon className="h-5 w-5 text-gray-400 hover:text-gray-600" />
              </button>
            </div>

            <KbForm
              form={form}
              onChange={setForm}
              onSubmit={editingKb ? handleUpdate : handleCreate}
              submitLabel={editingKb ? 'Update' : 'Create'}
            />
          </div>
        </div>
      )}
    </div>
  );
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

function KbCard({
  kb,
  onEdit,
  onDelete,
  onToggleStatus,
}: {
  kb: KnowledgeBase;
  onEdit: () => void;
  onDelete: () => void;
  onToggleStatus: () => void;
}) {
  const isActive = kb.status === 'active';

  return (
    <div
      className={`rounded-xl border p-5 transition-all hover:shadow-md ${
        isActive
          ? 'border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800'
          : 'border-gray-200 bg-gray-50 opacity-60 dark:border-gray-700 dark:bg-gray-900'
      }`}
    >
      <div className="mb-3 flex items-start justify-between">
        <div className="flex items-center gap-3">
          <div className="rounded-lg bg-blue-50 p-2 dark:bg-blue-900/30">
            <CircleStackIcon className="h-6 w-6 text-blue-600 dark:text-blue-400" />
          </div>
          <div>
            <h3 className="font-semibold text-gray-900 dark:text-white">{kb.name}</h3>
            <span
              className={`rounded-full px-2 py-0.5 text-xs ${
                isActive
                  ? 'bg-green-100 text-green-700 dark:bg-green-900/30 dark:text-green-400'
                  : 'bg-gray-100 text-gray-500 dark:bg-gray-700 dark:text-gray-400'
              }`}
            >
              {kb.status}
            </span>
          </div>
        </div>
      </div>

      {kb.description && (
        <p className="mb-3 line-clamp-2 text-sm text-gray-500 dark:text-gray-400">
          {kb.description}
        </p>
      )}

      <div className="mb-4 flex items-center gap-4 text-xs text-gray-500 dark:text-gray-400">
        <span className="flex items-center gap-1">
          <DocumentTextIcon className="h-4 w-4" />
          {kb.docCount} docs
        </span>
        <span className="flex items-center gap-1">
          <MagnifyingGlassIcon className="h-4 w-4" />
          {kb.chunkCount} chunks
        </span>
      </div>

      <div className="mb-4 text-xs text-gray-400 dark:text-gray-500">
        {kb.embeddingProvider} / {kb.embeddingModel}
      </div>

      <div className="flex items-center gap-2 border-t border-gray-100 pt-3 dark:border-gray-700">
        <a
          href={`/aurabot/knowledge/${kb.pid}`}
          className="flex-1 rounded-lg py-1.5 text-center text-sm font-medium text-blue-600 transition-colors hover:bg-blue-50 dark:text-blue-400 dark:hover:bg-blue-900/20"
        >
          Open
        </a>
        <button
          onClick={onEdit}
          className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300"
        >
          <PencilIcon className="h-4 w-4" />
        </button>
        <button
          onClick={onToggleStatus}
          className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600 dark:hover:bg-gray-700 dark:hover:text-gray-300"
        >
          {kb.status === 'active' ? '⏸' : '▶'}
        </button>
        <button
          onClick={onDelete}
          className="rounded-lg p-1.5 text-gray-400 hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
        >
          <TrashIcon className="h-4 w-4" />
        </button>
      </div>
    </div>
  );
}

function KbForm({
  form,
  onChange,
  onSubmit,
  submitLabel,
}: {
  form: CreateKbForm;
  onChange: (f: CreateKbForm) => void;
  onSubmit: () => void;
  submitLabel: string;
}) {
  const update = (key: keyof CreateKbForm, value: any) => onChange({ ...form, [key]: value });

  return (
    <div className="space-y-4">
      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
          Name *
        </label>
        <input
          type="text"
          value={form.name}
          onChange={(e) => update('name', e.target.value)}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          placeholder="e.g. Product Documentation"
        />
      </div>

      <div>
        <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
          Description
        </label>
        <textarea
          value={form.description}
          onChange={(e) => update('description', e.target.value)}
          rows={3}
          className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 focus:border-transparent focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          placeholder="What kind of knowledge will this base contain?"
        />
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
            Provider
          </label>
          <select
            value={form.embeddingProvider}
            onChange={(e) => update('embeddingProvider', e.target.value)}
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          >
            <option value="openai">OpenAI</option>
            <option value="zhipu">Zhipu</option>
          </select>
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
            Model
          </label>
          <input
            type="text"
            value={form.embeddingModel}
            onChange={(e) => update('embeddingModel', e.target.value)}
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-3">
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
            Chunk Size
          </label>
          <input
            type="number"
            value={form.chunkSize}
            onChange={(e) => update('chunkSize', parseInt(e.target.value) || 500)}
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>
        <div>
          <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
            Chunk Overlap
          </label>
          <input
            type="number"
            value={form.chunkOverlap}
            onChange={(e) => update('chunkOverlap', parseInt(e.target.value) || 50)}
            className="w-full rounded-lg border border-gray-300 bg-white px-3 py-2 text-gray-900 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
          />
        </div>
      </div>

      <button
        onClick={onSubmit}
        className="w-full rounded-lg bg-blue-600 py-2.5 font-medium text-white transition-colors hover:bg-blue-700"
      >
        {submitLabel}
      </button>
    </div>
  );
}
