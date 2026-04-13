/**
 * Report Template Management — List Page
 *
 * Platform management page for managing JasperReports templates.
 * Supports search, filter by category/status, CRUD, publish/archive.
 */

import { useState, useEffect, useCallback } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import {
  PlusIcon,
  MagnifyingGlassIcon,
  PencilIcon,
  TrashIcon,
  ArrowUpTrayIcon,
  DocumentArrowDownIcon,
  EyeIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import { useConfirmDialog } from '~/contexts/ConfirmDialogContext';
import { reportTemplateService, type ReportTemplateDTO } from '~/shared/services/reportTemplateService';
import { ResultHelper } from '~/utils/type';
import { cn } from '~/utils/cn';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const STATUS_COLORS: Record<string, string> = {
  draft: 'bg-yellow-100 text-yellow-800',
  published: 'bg-green-100 text-green-800',
  archived: 'bg-gray-100 text-gray-600',
};

const FORMAT_LABELS: Record<string, string> = {
  PDF: 'pdf',
  XLSX: 'Excel',
  DOCX: 'Word',
  HTML: 'html',
  CSV: 'csv',
};

const DS_TYPE_LABELS: Record<string, string> = {
  MODEL: 'Model',
  NAMED_QUERY: 'Named Query',
  CUSTOM_SQL: 'Custom SQL',
};

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ReportTemplateListPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { showSuccessToast, showErrorToast } = useToastContext();
  const { confirm } = useConfirmDialog();

  // State
  const [templates, setTemplates] = useState<ReportTemplateDTO[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(false);
  const [categories, setCategories] = useState<string[]>([]);

  // Filters from URL
  const keyword = searchParams.get('keyword') || '';
  const category = searchParams.get('category') || '';
  const status = searchParams.get('status') || '';
  const page = parseInt(searchParams.get('page') || '1', 10);
  const pageSize = 20;

  // Load data
  const loadTemplates = useCallback(async () => {
    setLoading(true);
    try {
      const resp = await reportTemplateService.list({
        keyword: keyword || undefined,
        category: category || undefined,
        status: status || undefined,
        page,
        size: pageSize,
      });
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setTemplates(resp.data.records || []);
        setTotal(resp.data.total || 0);
      }
    } catch {
      showErrorToast('Failed to load templates');
    } finally {
      setLoading(false);
    }
  }, [keyword, category, status, page, showErrorToast]);

  const loadCategories = useCallback(async () => {
    try {
      const resp = await reportTemplateService.getCategories();
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setCategories(resp.data);
      }
    } catch {
      // ignore
    }
  }, []);

  useEffect(() => {
    loadTemplates();
  }, [loadTemplates]);

  useEffect(() => {
    loadCategories();
  }, [loadCategories]);

  // Handlers
  const updateFilter = useCallback(
    (key: string, value: string) => {
      const next = new URLSearchParams(searchParams);
      if (value) {
        next.set(key, value);
      } else {
        next.delete(key);
      }
      if (key !== 'page') next.set('page', '1');
      setSearchParams(next);
    },
    [searchParams, setSearchParams],
  );

  const handleDelete = useCallback(
    async (tpl: ReportTemplateDTO) => {
      const ok = await confirm({
        title: 'Delete Template',
        content: `Delete "${tpl.name}"? This cannot be undone.`,
      });
      if (!ok) return;
      try {
        const resp = await reportTemplateService.remove(tpl.pid);
        if (ResultHelper.isSuccess(resp)) {
          showSuccessToast('Template deleted');
          loadTemplates();
        } else {
          showErrorToast('Delete failed');
        }
      } catch {
        showErrorToast('Delete failed');
      }
    },
    [confirm, showSuccessToast, showErrorToast, loadTemplates],
  );

  const handlePublish = useCallback(
    async (tpl: ReportTemplateDTO) => {
      try {
        const resp = await reportTemplateService.publish(tpl.pid);
        if (ResultHelper.isSuccess(resp)) {
          showSuccessToast('Template published');
          loadTemplates();
        } else {
          showErrorToast('Publish failed');
        }
      } catch {
        showErrorToast('Publish failed');
      }
    },
    [showSuccessToast, showErrorToast, loadTemplates],
  );

  const handleArchive = useCallback(
    async (tpl: ReportTemplateDTO) => {
      try {
        const resp = await reportTemplateService.archive(tpl.pid);
        if (ResultHelper.isSuccess(resp)) {
          showSuccessToast('Template archived');
          loadTemplates();
        } else {
          showErrorToast('Archive failed');
        }
      } catch {
        showErrorToast('Archive failed');
      }
    },
    [showSuccessToast, showErrorToast, loadTemplates],
  );

  const totalPages = total > 0 ? Math.ceil(total / pageSize) : 0;

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-bold text-gray-900 dark:text-white">Report Templates</h1>
          <p className="mt-1 text-sm text-gray-500">
            Manage JasperReports templates for document generation
          </p>
        </div>
        <button
          onClick={() => navigate('/report-templates/new')}
          className="inline-flex items-center gap-2 rounded-lg bg-blue-600 px-4 py-2 text-white transition-colors hover:bg-blue-700"
        >
          <PlusIcon className="h-5 w-5" />
          {'New Template'}
        </button>
      </div>

      {/* Filters */}
      <div className="flex flex-wrap items-center gap-3">
        <div className="relative max-w-md min-w-[240px] flex-1">
          <MagnifyingGlassIcon className="absolute top-1/2 left-3 h-4 w-4 -translate-y-1/2 text-gray-400" />
          <input
            type="text"
            placeholder={'Search by name or code...'}
            defaultValue={keyword}
            onKeyDown={(e) => {
              if (e.key === 'Enter') {
                updateFilter('keyword', (e.target as HTMLInputElement).value);
              }
            }}
            className="w-full rounded-lg border border-gray-300 py-2 pr-4 pl-10 focus:border-blue-500 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
          />
        </div>

        <select
          value={category}
          onChange={(e) => updateFilter('category', e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
        >
          <option value="">{'All Categories'}</option>
          {categories.map((cat) => (
            <option key={cat} value={cat}>
              {cat}
            </option>
          ))}
        </select>

        <select
          value={status}
          onChange={(e) => updateFilter('status', e.target.value)}
          className="rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-800 dark:text-white"
        >
          <option value="">{'All Statuses'}</option>
          <option value="draft">Draft</option>
          <option value="published">Published</option>
          <option value="archived">Archived</option>
        </select>
      </div>

      {/* Table */}
      <div className="overflow-hidden rounded-lg bg-white shadow dark:bg-gray-800">
        <table className="min-w-full divide-y divide-gray-200 dark:divide-gray-700">
          <thead className="bg-gray-50 dark:bg-gray-900">
            <tr>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Name'}
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Code'}
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Category'}
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Format'}
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Data Source'}
              </th>
              <th className="px-4 py-3 text-left text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Status'}
              </th>
              <th className="px-4 py-3 text-right text-xs font-medium tracking-wider text-gray-500 uppercase">
                {'Actions'}
              </th>
            </tr>
          </thead>
          <tbody className="divide-y divide-gray-200 dark:divide-gray-700">
            {loading && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-500">
                  Loading...
                </td>
              </tr>
            )}
            {!loading && templates.length === 0 && (
              <tr>
                <td colSpan={7} className="px-4 py-12 text-center text-gray-500">
                  {'No templates found'}
                </td>
              </tr>
            )}
            {templates.map((tpl) => (
              <tr
                key={tpl.pid}
                className="dark:hover:bg-gray-750 cursor-pointer transition-colors hover:bg-gray-50"
                onClick={() => navigate(`/report-templates/${tpl.pid}`)}
              >
                <td className="px-4 py-3">
                  <div className="font-medium text-gray-900 dark:text-white">{tpl.name}</div>
                  {tpl.description && (
                    <div className="max-w-[300px] truncate text-xs text-gray-500">
                      {tpl.description}
                    </div>
                  )}
                </td>
                <td className="px-4 py-3 font-mono text-sm text-gray-600 dark:text-gray-300">
                  {tpl.code}
                </td>
                <td className="px-4 py-3 text-sm text-gray-600 dark:text-gray-300">
                  {tpl.category || '—'}
                </td>
                <td className="px-4 py-3">
                  <span className="inline-flex items-center rounded bg-blue-50 px-2 py-0.5 text-xs font-medium text-blue-700">
                    {FORMAT_LABELS[tpl.outputFormat] || tpl.outputFormat}
                  </span>
                </td>
                <td className="px-4 py-3 text-sm text-gray-600 dark:text-gray-300">
                  {DS_TYPE_LABELS[tpl.dataSourceType || ''] || '—'}
                </td>
                <td className="px-4 py-3">
                  <span
                    className={cn(
                      'inline-flex items-center rounded px-2 py-0.5 text-xs font-medium',
                      STATUS_COLORS[tpl.status],
                    )}
                  >
                    {tpl.status}
                  </span>
                </td>
                <td className="px-4 py-3 text-right" onClick={(e) => e.stopPropagation()}>
                  <div className="flex items-center justify-end gap-1">
                    {tpl.status === 'draft' && (
                      <button
                        onClick={() => handlePublish(tpl)}
                        className="rounded-md p-1.5 text-green-600 hover:bg-green-50"
                        title="Publish"
                      >
                        <ArrowUpTrayIcon className="h-4 w-4" />
                      </button>
                    )}
                    {tpl.status === 'published' && (
                      <button
                        onClick={() => handleArchive(tpl)}
                        className="rounded-md p-1.5 text-gray-500 hover:bg-gray-100"
                        title="Archive"
                      >
                        <DocumentArrowDownIcon className="h-4 w-4" />
                      </button>
                    )}
                    <button
                      onClick={() => navigate(`/report-templates/${tpl.pid}`)}
                      className="rounded-md p-1.5 text-blue-600 hover:bg-blue-50"
                      title="Edit"
                    >
                      <PencilIcon className="h-4 w-4" />
                    </button>
                    <button
                      onClick={() => handleDelete(tpl)}
                      className="rounded-md p-1.5 text-red-600 hover:bg-red-50"
                      title="Delete"
                    >
                      <TrashIcon className="h-4 w-4" />
                    </button>
                  </div>
                </td>
              </tr>
            ))}
          </tbody>
        </table>

        {/* Pagination */}
        {totalPages > 1 && (
          <div className="flex items-center justify-between border-t border-gray-200 px-4 py-3 dark:border-gray-700">
            <span className="text-sm text-gray-500">
              {total} {'items'}
            </span>
            <div className="flex items-center gap-2">
              <button
                disabled={page <= 1}
                onClick={() => updateFilter('page', String(page - 1))}
                className="rounded-md border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50"
              >
                Prev
              </button>
              <span className="text-sm text-gray-600">
                {page} / {totalPages}
              </span>
              <button
                disabled={page >= totalPages}
                onClick={() => updateFilter('page', String(page + 1))}
                className="rounded-md border px-3 py-1 text-sm hover:bg-gray-50 disabled:opacity-50"
              >
                Next
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
