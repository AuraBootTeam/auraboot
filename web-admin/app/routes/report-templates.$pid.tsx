/**
 * Report Template Editor — Detail/Edit Page
 *
 * Two modes:
 * - pid === "new" → create mode
 * - otherwise → edit existing template
 *
 * Left panel: form fields + parameter config
 * Right panel: preview (when template has content)
 */

import { useState, useEffect, useCallback, useRef } from 'react';
import { useNavigate, useParams } from 'react-router';
import {
  ArrowLeftIcon,
  ArrowUpTrayIcon,
  DocumentArrowDownIcon,
  EyeIcon,
  CloudArrowUpIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';
import {
  reportTemplateService,
  type ReportTemplateDTO,
  type ReportTemplateCreateRequest,
  type ReportParameter,
} from '~/shared/services/reportTemplateService';
import { ResultHelper } from '~/utils/type';
import { cn } from '~/utils/cn';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

const OUTPUT_FORMATS = ['pdf', 'xlsx', 'docx', 'html', 'csv'] as const;
const PAGE_SIZES = ['A4', 'A3', 'letter', 'legal', 'custom'] as const;
const ORIENTATIONS = ['portrait', 'landscape'] as const;
const DS_TYPES = ['model', 'named_query', 'custom_sql'] as const;
const PARAM_TYPES = ['string', 'integer', 'long', 'double', 'date', 'boolean'] as const;

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

export default function ReportTemplateEditorPage() {
  const { pid } = useParams<{ pid: string }>();
  const navigate = useNavigate();
  const { showSuccessToast, showErrorToast } = useToastContext();

  const isNew = pid === 'new';

  // Form state
  const [form, setForm] = useState<ReportTemplateCreateRequest>({
    code: '',
    name: '',
    description: '',
    category: '',
    templateType: 'jrxml',
    outputFormat: 'pdf',
    pageSize: 'A4',
    orientation: 'portrait',
    dataSourceType: 'model',
    dataSourceConfig: {},
    parameters: [],
  });

  const [template, setTemplate] = useState<ReportTemplateDTO | null>(null);
  const [loading, setLoading] = useState(!isNew);
  const [saving, setSaving] = useState(false);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  // Load existing template
  useEffect(() => {
    if (isNew) return;
    (async () => {
      setLoading(true);
      try {
        const resp = await reportTemplateService.getByPid(pid!);
        if (ResultHelper.isSuccess(resp) && resp.data) {
          const d = resp.data;
          setTemplate(d);
          setForm({
            code: d.code,
            name: d.name,
            description: d.description || '',
            category: d.category || '',
            templateType: d.templateType,
            outputFormat: d.outputFormat,
            pageSize: d.pageSize,
            orientation: d.orientation,
            dataSourceType: d.dataSourceType || 'model',
            dataSourceConfig: d.dataSourceConfig || {},
            parameters: d.parameters || [],
          });
        } else {
          showErrorToast('Template not found');
          navigate('/report-templates');
        }
      } catch {
        showErrorToast('Failed to load template');
      } finally {
        setLoading(false);
      }
    })();
  }, [pid, isNew, navigate, showErrorToast]);

  // Form field update helper
  const updateField = useCallback(
    <K extends keyof ReportTemplateCreateRequest>(
      key: K,
      value: ReportTemplateCreateRequest[K],
    ) => {
      setForm((prev) => ({ ...prev, [key]: value }));
    },
    [],
  );

  // Save
  const handleSave = useCallback(async () => {
    if (!form.code.trim() || !form.name.trim()) {
      showErrorToast('Code and Name are required');
      return;
    }
    setSaving(true);
    try {
      const resp = isNew
        ? await reportTemplateService.create(form)
        : await reportTemplateService.update(pid!, form);
      if (ResultHelper.isSuccess(resp) && resp.data) {
        showSuccessToast(isNew ? 'Template created' : 'Template saved');
        if (isNew) {
          navigate(`/report-templates/${resp.data.pid}`, { replace: true });
        } else {
          setTemplate(resp.data);
        }
      } else {
        showErrorToast('Save failed');
      }
    } catch {
      showErrorToast('Save failed');
    } finally {
      setSaving(false);
    }
  }, [form, isNew, pid, navigate, showSuccessToast, showErrorToast]);

  // Publish
  const handlePublish = useCallback(async () => {
    if (!template) return;
    try {
      const resp = await reportTemplateService.publish(template.pid);
      if (ResultHelper.isSuccess(resp) && resp.data) {
        setTemplate(resp.data);
        showSuccessToast('Template published');
      }
    } catch {
      showErrorToast('Publish failed');
    }
  }, [template, showSuccessToast, showErrorToast]);

  // Upload JRXML
  const handleUpload = useCallback(
    async (e: React.ChangeEvent<HTMLInputElement>) => {
      const file = e.target.files?.[0];
      if (!file || !template) return;
      if (!file.name.endsWith('.jrxml')) {
        showErrorToast('Only .jrxml files are accepted');
        return;
      }
      try {
        const resp = await reportTemplateService.uploadTemplate(template.pid, file);
        if (ResultHelper.isSuccess(resp) && resp.data) {
          setTemplate(resp.data);
          showSuccessToast('Template file uploaded');
        } else {
          showErrorToast('Upload failed');
        }
      } catch {
        showErrorToast('Upload failed');
      }
      // Reset input
      if (fileInputRef.current) fileInputRef.current.value = '';
    },
    [template, showSuccessToast, showErrorToast],
  );

  // Preview
  const handlePreview = useCallback(async () => {
    if (!template) return;
    try {
      const blob = await reportTemplateService.preview(template.code);
      const url = URL.createObjectURL(blob);
      setPreviewUrl(url);
    } catch {
      showErrorToast('Preview failed — ensure template has content and data source is configured');
    }
  }, [template, showErrorToast]);

  // Parameter CRUD
  const addParameter = useCallback(() => {
    updateField('parameters', [
      ...(form.parameters || []),
      { name: '', type: 'string' as const, required: false },
    ]);
  }, [form.parameters, updateField]);

  const updateParameter = useCallback(
    (index: number, param: Partial<ReportParameter>) => {
      const next = [...(form.parameters || [])];
      next[index] = { ...next[index], ...param };
      updateField('parameters', next);
    },
    [form.parameters, updateField],
  );

  const removeParameter = useCallback(
    (index: number) => {
      updateField(
        'parameters',
        (form.parameters || []).filter((_, i) => i !== index),
      );
    },
    [form.parameters, updateField],
  );

  if (loading) {
    return (
      <div className="flex h-64 items-center justify-center">
        <div className="h-8 w-8 animate-spin rounded-full border-b-2 border-blue-600" />
      </div>
    );
  }

  return (
    <div className="space-y-6 p-6">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-3">
          <button
            onClick={() => navigate('/report-templates')}
            className="rounded-lg p-2 hover:bg-gray-100 dark:hover:bg-gray-700"
          >
            <ArrowLeftIcon className="h-5 w-5" />
          </button>
          <div>
            <h1 className="text-xl font-bold text-gray-900 dark:text-white">
              {isNew ? 'New Template' : form.name || 'Edit Template'}
            </h1>
            {template && (
              <span
                className={cn(
                  'mt-1 inline-flex items-center rounded px-2 py-0.5 text-xs font-medium',
                  {
                    'bg-yellow-100 text-yellow-800': template.status === 'draft',
                    'bg-green-100 text-green-800': template.status === 'published',
                    'bg-gray-100 text-gray-600': template.status === 'archived',
                  },
                )}
              >
                {template.status}
              </span>
            )}
          </div>
        </div>
        <div className="flex items-center gap-2">
          {template && !isNew && (
            <>
              <button
                onClick={handlePreview}
                className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-2 text-sm hover:bg-gray-50"
              >
                <EyeIcon className="h-4 w-4" /> Preview
              </button>
              {template.status === 'draft' && (
                <button
                  onClick={handlePublish}
                  className="inline-flex items-center gap-1.5 rounded-lg bg-green-600 px-3 py-2 text-sm text-white hover:bg-green-700"
                >
                  <ArrowUpTrayIcon className="h-4 w-4" /> Publish
                </button>
              )}
            </>
          )}
          <button
            onClick={handleSave}
            disabled={saving}
            className="inline-flex items-center gap-1.5 rounded-lg bg-blue-600 px-4 py-2 text-sm text-white hover:bg-blue-700 disabled:opacity-50"
          >
            {saving ? 'Saving...' : 'Save'}
          </button>
        </div>
      </div>

      {/* Two-column layout */}
      <div className="grid grid-cols-1 gap-6 lg:grid-cols-3">
        {/* Left: Form */}
        <div className="space-y-6 lg:col-span-2">
          {/* Basic Info */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">
              Basic Information
            </h2>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Code *
                </label>
                <input
                  type="text"
                  value={form.code}
                  onChange={(e) => updateField('code', e.target.value)}
                  disabled={!isNew}
                  placeholder="e.g. order_detail_report"
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 disabled:bg-gray-100 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Name *
                </label>
                <input
                  type="text"
                  value={form.name}
                  onChange={(e) => updateField('name', e.target.value)}
                  placeholder="e.g. Order Detail Report"
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Category
                </label>
                <input
                  type="text"
                  value={form.category || ''}
                  onChange={(e) => updateField('category', e.target.value)}
                  placeholder="e.g. sales, finance"
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Output Format
                </label>
                <select
                  value={form.outputFormat}
                  onChange={(e) => updateField('outputFormat', e.target.value)}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                >
                  {OUTPUT_FORMATS.map((f) => (
                    <option key={f} value={f}>
                      {f}
                    </option>
                  ))}
                </select>
              </div>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Description
              </label>
              <textarea
                value={form.description || ''}
                onChange={(e) => updateField('description', e.target.value)}
                rows={2}
                className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              />
            </div>
          </section>

          {/* Page Settings */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Page Settings</h2>
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Page Size
                </label>
                <select
                  value={form.pageSize}
                  onChange={(e) => updateField('pageSize', e.target.value)}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                >
                  {PAGE_SIZES.map((s) => (
                    <option key={s} value={s}>
                      {s}
                    </option>
                  ))}
                </select>
              </div>
              <div>
                <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                  Orientation
                </label>
                <select
                  value={form.orientation}
                  onChange={(e) => updateField('orientation', e.target.value)}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                >
                  {ORIENTATIONS.map((o) => (
                    <option key={o} value={o}>
                      {o}
                    </option>
                  ))}
                </select>
              </div>
            </div>
          </section>

          {/* Data Source */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Data Source</h2>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Type
              </label>
              <select
                value={form.dataSourceType || 'model'}
                onChange={(e) => updateField('dataSourceType', e.target.value)}
                className="w-full rounded-lg border border-gray-300 px-3 py-2 focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              >
                {DS_TYPES.map((t) => (
                  <option key={t} value={t}>
                    {t}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                Config (JSON)
              </label>
              <textarea
                value={JSON.stringify(form.dataSourceConfig || {}, null, 2)}
                onChange={(e) => {
                  try {
                    updateField('dataSourceConfig', JSON.parse(e.target.value));
                  } catch {
                    // ignore parse errors during typing
                  }
                }}
                rows={4}
                className="w-full rounded-lg border border-gray-300 px-3 py-2 font-mono text-sm focus:ring-2 focus:ring-blue-500 dark:border-gray-600 dark:bg-gray-700 dark:text-white"
              />
            </div>
          </section>

          {/* Parameters */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Parameters</h2>
              <button onClick={addParameter} className="text-sm text-blue-600 hover:text-blue-700">
                + Add Parameter
              </button>
            </div>
            {(form.parameters || []).length === 0 && (
              <p className="text-sm text-gray-500">No parameters defined</p>
            )}
            {(form.parameters || []).map((param, idx) => (
              <div
                key={idx}
                className="dark:bg-gray-750 flex items-center gap-3 rounded-lg bg-gray-50 p-3"
              >
                <input
                  type="text"
                  value={param.name}
                  onChange={(e) => updateParameter(idx, { name: e.target.value })}
                  placeholder="Name"
                  className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
                <select
                  value={param.type}
                  onChange={(e) =>
                    updateParameter(idx, { type: e.target.value as ReportParameter['type'] })
                  }
                  className="w-28 rounded border border-gray-300 px-2 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                >
                  {PARAM_TYPES.map((t) => (
                    <option key={t} value={t}>
                      {t}
                    </option>
                  ))}
                </select>
                <label className="flex items-center gap-1 text-sm">
                  <input
                    type="checkbox"
                    checked={param.required}
                    onChange={(e) => updateParameter(idx, { required: e.target.checked })}
                  />
                  Required
                </label>
                <input
                  type="text"
                  value={param.description || ''}
                  onChange={(e) => updateParameter(idx, { description: e.target.value })}
                  placeholder="Description"
                  className="flex-1 rounded border border-gray-300 px-2 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                />
                <button
                  onClick={() => removeParameter(idx)}
                  className="text-sm text-red-500 hover:text-red-700"
                >
                  Remove
                </button>
              </div>
            ))}
          </section>
        </div>

        {/* Right: Template Upload + Preview */}
        <div className="space-y-6">
          {/* Template File */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Template File</h2>
            {template ? (
              <>
                <div className="space-y-1 text-sm text-gray-600 dark:text-gray-300">
                  <div>
                    Type: <span className="font-mono">{template.templateType}</span>
                  </div>
                  <div>
                    Content:{' '}
                    {template.hasInlineContent
                      ? 'Inline JRXML'
                      : template.hasFileContent
                        ? 'Uploaded file'
                        : 'None'}
                  </div>
                </div>
                <div>
                  <input
                    ref={fileInputRef}
                    type="file"
                    accept=".jrxml"
                    onChange={handleUpload}
                    className="hidden"
                  />
                  <button
                    onClick={() => fileInputRef.current?.click()}
                    className="inline-flex w-full items-center justify-center gap-2 rounded-lg border-2 border-dashed border-gray-300 px-4 py-3 text-sm text-gray-600 transition-colors hover:border-blue-500 hover:text-blue-600"
                  >
                    <CloudArrowUpIcon className="h-5 w-5" />
                    Upload JRXML File
                  </button>
                </div>
              </>
            ) : (
              <p className="text-sm text-gray-500">
                Save the template first, then upload a JRXML file.
              </p>
            )}
          </section>

          {/* Preview */}
          <section className="space-y-4 rounded-lg bg-white p-6 shadow dark:bg-gray-800">
            <h2 className="text-lg font-semibold text-gray-900 dark:text-white">Preview</h2>
            {previewUrl ? (
              <iframe
                src={previewUrl}
                className="h-[500px] w-full rounded-lg border"
                title="Report Preview"
              />
            ) : (
              <div className="flex h-48 flex-col items-center justify-center text-gray-400">
                <EyeIcon className="mb-2 h-10 w-10" />
                <p className="text-sm">Click "Preview" to generate a preview</p>
              </div>
            )}
          </section>
        </div>
      </div>
    </div>
  );
}
