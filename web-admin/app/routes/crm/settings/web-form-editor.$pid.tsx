/**
 * CRM Web Form Editor
 *
 * Full-featured form designer:
 * - Left panel: field list with up/down reorder, add/remove fields
 * - Right panel: style settings (color, button text, success message, redirect, CORS)
 * - Bottom bar: Save + Copy Embed Code
 * - Uses params.pid to load/save form via API
 */

import { useState, useEffect, useCallback } from 'react';
import { useParams, useNavigate } from 'react-router';
import {
  ArrowUpIcon,
  ArrowDownIcon,
  PlusIcon,
  TrashIcon,
  ArrowLeftIcon,
  ClipboardDocumentIcon,
  CheckIcon,
} from '@heroicons/react/24/outline';
import { useToastContext } from '~/contexts/ToastContext';

// ─── Types ────────────────────────────────────────────────────────────────────

type FieldType = 'text' | 'email' | 'phone' | 'select' | 'textarea';

interface FormField {
  id: string;
  name: string;
  label: string;
  fieldType: FieldType;
  required: boolean;
  placeholder: string;
  options?: string; // comma-separated for select
}

interface WebFormData {
  pid: string;
  name: string;
  channelPid: string;
  channelName?: string;
  enabled: boolean;
  fields: FormField[];
  style: {
    primaryColor: string;
    buttonText: string;
    successMessage: string;
    redirectUrl: string;
    corsOrigins: string;
  };
  createdAt: string;
}

function parseJsonValue<T>(value: unknown, fallback: T): T {
  if (value == null || value === '') return fallback;
  if (typeof value !== 'string') return value as T;
  try {
    return JSON.parse(value) as T;
  } catch {
    return fallback;
  }
}

const FIELD_TYPES: { value: FieldType; label: string }[] = [
  { value: 'text', label: 'Text' },
  { value: 'email', label: 'Email' },
  { value: 'phone', label: 'Phone' },
  { value: 'select', label: 'Select (Dropdown)' },
  { value: 'textarea', label: 'Text Area' },
];

const defaultStyle = {
  primaryColor: '#2563EB',
  buttonText: 'Submit',
  successMessage: 'Thank you! We will be in touch shortly.',
  redirectUrl: '',
  corsOrigins: '',
};

// ─── API helpers ──────────────────────────────────────────────────────────────

async function apiFetch<T = unknown>(path: string, opts?: RequestInit): Promise<T> {
  const jwt = localStorage.getItem('jwt');
  const res = await fetch(path, {
    headers: {
      'Content-Type': 'application/json',
      ...(jwt ? { Authorization: `Bearer ${jwt}` } : {}),
    },
    ...opts,
  });
  if (!res.ok) throw new Error(`HTTP ${res.status}`);
  const json = await res.json();
  if (json.code !== undefined && json.code != 0 && json.code != 200) {
    throw new Error(json.message ?? 'API error');
  }
  return json.data as T;
}

function generateId() {
  return `field_${Date.now()}_${Math.random().toString(36).slice(2, 7)}`;
}

// ─── Main Page ────────────────────────────────────────────────────────────────

export default function WebFormEditorPage() {
  const { pid } = useParams<{ pid: string }>();
  const navigate = useNavigate();
  const { showToast } = useToastContext();

  const [form, setForm] = useState<WebFormData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [copied, setCopied] = useState(false);

  const load = useCallback(async () => {
    if (!pid) return;
    setLoading(true);
    try {
      const data = await apiFetch<Record<string, unknown>>(`/api/crm/web-forms/${pid}`);
      const formSchema = parseJsonValue<FormField[]>(data.formSchema, []);
      const styleConfig = parseJsonValue<Record<string, unknown>>(data.styleConfig, {});
      const corsOrigins = parseJsonValue<string[]>(data.corsOrigins, []);
      setForm({
        pid: String(data.pid ?? pid),
        name: String(data.name ?? ''),
        channelPid: String(data.channelPid ?? ''),
        channelName: typeof data.channelName === 'string' ? data.channelName : undefined,
        enabled: Boolean(data.enabled ?? true),
        fields: formSchema,
        style: {
          primaryColor:
            typeof styleConfig.primaryColor === 'string'
              ? styleConfig.primaryColor
              : defaultStyle.primaryColor,
          buttonText:
            typeof styleConfig.buttonText === 'string'
              ? styleConfig.buttonText
              : defaultStyle.buttonText,
          successMessage:
            typeof data.successMessage === 'string'
              ? data.successMessage
              : typeof styleConfig.successMessage === 'string'
                ? styleConfig.successMessage
                : defaultStyle.successMessage,
          redirectUrl:
            typeof data.redirectUrl === 'string'
              ? data.redirectUrl
              : typeof styleConfig.redirectUrl === 'string'
                ? styleConfig.redirectUrl
                : defaultStyle.redirectUrl,
          corsOrigins: Array.isArray(corsOrigins) ? corsOrigins.join(', ') : defaultStyle.corsOrigins,
        },
        createdAt: typeof data.createdAt === 'string' ? data.createdAt : '',
      });
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to load form', 'error');
    } finally {
      setLoading(false);
    }
  }, [pid, showToast]);

  useEffect(() => {
    load();
  }, [load]);

  const patchStyle = (partial: Partial<WebFormData['style']>) => {
    if (!form) return;
    setForm((prev) => prev && { ...prev, style: { ...prev.style, ...partial } });
  };

  const addField = () => {
    if (!form) return;
    const newField: FormField = {
      id: generateId(),
      name: `field_${form.fields.length + 1}`,
      label: `Field ${form.fields.length + 1}`,
      fieldType: 'text',
      required: false,
      placeholder: '',
    };
    setForm((prev) => prev && { ...prev, fields: [...prev.fields, newField] });
  };

  const removeField = (id: string) => {
    if (!form) return;
    setForm((prev) => prev && { ...prev, fields: prev.fields.filter((f) => f.id !== id) });
  };

  const updateField = (id: string, partial: Partial<FormField>) => {
    if (!form) return;
    setForm(
      (prev) =>
        prev && {
          ...prev,
          fields: prev.fields.map((f) => (f.id === id ? { ...f, ...partial } : f)),
        },
    );
  };

  const moveField = (idx: number, direction: 'up' | 'down') => {
    if (!form) return;
    const newFields = [...form.fields];
    const targetIdx = direction === 'up' ? idx - 1 : idx + 1;
    if (targetIdx < 0 || targetIdx >= newFields.length) return;
    [newFields[idx], newFields[targetIdx]] = [newFields[targetIdx], newFields[idx]];
    setForm((prev) => prev && { ...prev, fields: newFields });
  };

  const handleSave = async () => {
    if (!form || !pid) return;
    setSaving(true);
    try {
      const updated = await apiFetch<WebFormData>(`/api/crm/web-forms/${pid}`, {
        method: 'put',
        body: JSON.stringify({
          name: form.name,
          channelPid: form.channelPid,
          formSchema: form.fields,
          styleConfig: {
            primaryColor: form.style.primaryColor,
            buttonText: form.style.buttonText,
          },
          successMessage: form.style.successMessage,
          redirectUrl: form.style.redirectUrl || null,
          corsOrigins: form.style.corsOrigins
            .split(',')
            .map((item) => item.trim())
            .filter(Boolean),
        }),
      });
      setForm((prev) => prev && { ...prev, ...updated });
      showToast('Form saved', 'success');
    } catch (e: unknown) {
      showToast((e instanceof Error ? e.message : null) ?? 'Failed to save form', 'error');
    } finally {
      setSaving(false);
    }
  };

  const handleCopyEmbed = () => {
    const embedCode = `<script src="${window.location.origin}/sdk/web-form.js" data-form-id="${pid}" async></script>`;
    navigator.clipboard.writeText(embedCode).then(() => {
      setCopied(true);
      showToast('Embed code copied to clipboard', 'success');
      setTimeout(() => setCopied(false), 2000);
    });
  };

  if (loading) {
    return (
      <div className="flex h-full items-center justify-center py-24 text-gray-400">
        Loading form editor...
      </div>
    );
  }

  if (!form) {
    return (
      <div className="flex h-full items-center justify-center py-24 text-gray-400">
        Form not found.
      </div>
    );
  }

  return (
    <div className="flex h-full flex-col overflow-hidden bg-gray-50 dark:bg-gray-900">
      {/* Top Bar */}
      <div className="flex items-center gap-4 border-b border-gray-200 bg-white px-6 py-3 dark:border-gray-700 dark:bg-gray-800">
        <button
          onClick={() => navigate('/crm/settings/web-forms')}
          className="rounded-lg p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-700 dark:hover:bg-gray-700"
        >
          <ArrowLeftIcon className="h-4 w-4" />
        </button>
        <div className="min-w-0 flex-1">
          <h1 className="text-base font-semibold text-gray-900 dark:text-white">{form.name}</h1>
          {form.channelName && (
            <p className="text-xs text-gray-400">Channel: {form.channelName}</p>
          )}
        </div>
        <div className="flex items-center gap-3">
          <button
            onClick={handleCopyEmbed}
            className="flex items-center gap-2 rounded-lg border border-gray-300 bg-white px-4 py-2 text-sm font-medium text-gray-700 transition-colors hover:bg-gray-50 dark:border-gray-600 dark:bg-gray-700 dark:text-gray-300"
            data-testid="webform-copy-embed-btn"
          >
            {copied ? (
              <CheckIcon className="h-4 w-4 text-green-500" />
            ) : (
              <ClipboardDocumentIcon className="h-4 w-4" />
            )}
            {copied ? 'Copied!' : 'Copy Embed Code'}
          </button>
          <button
            onClick={handleSave}
            disabled={saving}
            className="flex items-center gap-2 rounded-lg bg-blue-600 px-5 py-2 text-sm font-medium text-white transition-colors hover:bg-blue-700 disabled:opacity-50"
            data-testid="webform-save-btn"
          >
            {saving ? 'Saving...' : 'Save Form'}
          </button>
        </div>
      </div>

      {/* Main editor area */}
      <div className="flex flex-1 gap-0 overflow-hidden">
        {/* Left Panel: Fields */}
        <div className="flex w-96 flex-shrink-0 flex-col overflow-hidden border-r border-gray-200 bg-white dark:border-gray-700 dark:bg-gray-800">
          <div className="flex items-center justify-between border-b border-gray-100 px-4 py-3 dark:border-gray-700">
            <h2 className="text-sm font-semibold text-gray-700 dark:text-gray-300">
              Form Fields ({form.fields.length})
            </h2>
            <button
              onClick={addField}
              className="flex items-center gap-1 rounded-lg bg-blue-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-blue-700"
              data-testid="webform-add-field-btn"
            >
              <PlusIcon className="h-3.5 w-3.5" />
              Add Field
            </button>
          </div>

          <div className="flex-1 overflow-y-auto p-3">
            {form.fields.length === 0 ? (
              <div className="py-12 text-center text-sm text-gray-400">
                <p className="mb-2">No fields yet.</p>
                <p>Click "Add Field" to start building your form.</p>
              </div>
            ) : (
              <div className="space-y-2" data-testid="webform-fields">
                {form.fields.map((field, idx) => (
                  <FieldEditor
                    key={field.id}
                    field={field}
                    index={idx}
                    total={form.fields.length}
                    onChange={(partial) => updateField(field.id, partial)}
                    onRemove={() => removeField(field.id)}
                    onMove={(dir) => moveField(idx, dir)}
                  />
                ))}
              </div>
            )}
          </div>
        </div>

        {/* Right Panel: Style Settings + Preview */}
        <div className="flex flex-1 flex-col overflow-hidden">
          <div className="flex flex-1 gap-0 overflow-hidden">
            {/* Style Settings */}
            <div className="w-72 flex-shrink-0 overflow-y-auto border-r border-gray-200 bg-white p-4 dark:border-gray-700 dark:bg-gray-800">
              <h2 className="mb-4 text-sm font-semibold text-gray-700 dark:text-gray-300">
                Style & Settings
              </h2>
              <div className="space-y-4">
                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-700 dark:text-gray-300">
                    Primary Color
                  </label>
                  <div className="flex items-center gap-2">
                    <input
                      type="color"
                      value={form.style.primaryColor}
                      onChange={(e) => patchStyle({ primaryColor: e.target.value })}
                      className="h-8 w-10 cursor-pointer rounded border border-gray-300"
                    />
                    <input
                      type="text"
                      value={form.style.primaryColor}
                      onChange={(e) => patchStyle({ primaryColor: e.target.value })}
                      className="flex-1 rounded border border-gray-300 px-2 py-1.5 font-mono text-xs dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    />
                  </div>
                </div>

                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-700 dark:text-gray-300">
                    Button Text
                  </label>
                  <input
                    type="text"
                    value={form.style.buttonText}
                    onChange={(e) => patchStyle({ buttonText: e.target.value })}
                    className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="Submit"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-700 dark:text-gray-300">
                    Success Message
                  </label>
                  <textarea
                    value={form.style.successMessage}
                    onChange={(e) => patchStyle({ successMessage: e.target.value })}
                    rows={3}
                    className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="Thank you for your submission!"
                  />
                </div>

                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-700 dark:text-gray-300">
                    Redirect URL
                  </label>
                  <input
                    type="url"
                    value={form.style.redirectUrl}
                    onChange={(e) => patchStyle({ redirectUrl: e.target.value })}
                    className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="https://example.com/thank-you"
                  />
                  <p className="mt-1 text-xs text-gray-400">
                    If set, redirect after successful submission instead of showing success message.
                  </p>
                </div>

                <div>
                  <label className="mb-1 block text-xs font-medium text-gray-700 dark:text-gray-300">
                    Allowed Origins (CORS)
                  </label>
                  <input
                    type="text"
                    value={form.style.corsOrigins}
                    onChange={(e) => patchStyle({ corsOrigins: e.target.value })}
                    className="w-full rounded border border-gray-300 px-3 py-1.5 text-sm dark:border-gray-600 dark:bg-gray-700 dark:text-white"
                    placeholder="https://mysite.com, https://www.mysite.com"
                  />
                  <p className="mt-1 text-xs text-gray-400">Comma-separated origins.</p>
                </div>
              </div>
            </div>

            {/* Preview Panel */}
            <div className="flex flex-1 flex-col overflow-hidden bg-gray-100 dark:bg-gray-900">
              <div className="border-b border-gray-200 px-4 py-2 dark:border-gray-700">
                <span className="text-xs font-semibold tracking-wide text-gray-500 uppercase dark:text-gray-400">
                  Live Preview
                </span>
              </div>
              <div className="flex-1 overflow-y-auto p-6">
                <FormPreview form={form} />
              </div>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}

// ─── Field Editor Card ────────────────────────────────────────────────────────

function FieldEditor({
  field,
  index,
  total,
  onChange,
  onRemove,
  onMove,
}: {
  field: FormField;
  index: number;
  total: number;
  onChange: (partial: Partial<FormField>) => void;
  onRemove: () => void;
  onMove: (dir: 'up' | 'down') => void;
}) {
  const [expanded, setExpanded] = useState(false);

  return (
    <div
      className="overflow-hidden rounded-lg border border-gray-200 bg-white dark:border-gray-600 dark:bg-gray-700"
      data-testid="webform-field-card"
    >
      <div
        className="flex cursor-pointer items-center gap-2 px-3 py-2"
        onClick={() => setExpanded((p) => !p)}
      >
        <div className="flex flex-col gap-0.5">
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onMove('up');
            }}
            disabled={index === 0}
            className="text-gray-300 hover:text-gray-500 disabled:opacity-20"
          >
            <ArrowUpIcon className="h-3 w-3" />
          </button>
          <button
            type="button"
            onClick={(e) => {
              e.stopPropagation();
              onMove('down');
            }}
            disabled={index === total - 1}
            className="text-gray-300 hover:text-gray-500 disabled:opacity-20"
          >
            <ArrowDownIcon className="h-3 w-3" />
          </button>
        </div>
        <div className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-gray-800 dark:text-gray-200">
            {field.label || field.name}
          </span>
          <span className="text-xs text-gray-400">
            {FIELD_TYPES.find((t) => t.value === field.fieldType)?.label ?? field.fieldType}
            {field.required && ' · Required'}
          </span>
        </div>
        <button
          type="button"
          onClick={(e) => {
            e.stopPropagation();
            onRemove();
          }}
          className="rounded p-1 text-gray-300 hover:bg-red-50 hover:text-red-500 dark:hover:bg-red-900/20"
          data-testid="webform-remove-field-btn"
        >
          <TrashIcon className="h-3.5 w-3.5" />
        </button>
      </div>

      {expanded && (
        <div className="space-y-2 border-t border-gray-100 p-3 dark:border-gray-600">
          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="mb-0.5 block text-xs text-gray-500 dark:text-gray-400">
                Field Name
              </label>
              <input
                type="text"
                value={field.name}
                onChange={(e) => onChange({ name: e.target.value })}
                className="w-full rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-600 dark:text-white"
                placeholder="field_name"
              />
            </div>
            <div>
              <label className="mb-0.5 block text-xs text-gray-500 dark:text-gray-400">
                Label
              </label>
              <input
                type="text"
                value={field.label}
                onChange={(e) => onChange({ label: e.target.value })}
                className="w-full rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-600 dark:text-white"
                placeholder="Display Label"
              />
            </div>
          </div>

          <div className="grid grid-cols-2 gap-2">
            <div>
              <label className="mb-0.5 block text-xs text-gray-500 dark:text-gray-400">Type</label>
              <select
                value={field.fieldType}
                onChange={(e) => onChange({ fieldType: e.target.value as FieldType })}
                className="w-full rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-600 dark:text-white"
              >
                {FIELD_TYPES.map((t) => (
                  <option key={t.value} value={t.value}>
                    {t.label}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="mb-0.5 block text-xs text-gray-500 dark:text-gray-400">
                Placeholder
              </label>
              <input
                type="text"
                value={field.placeholder}
                onChange={(e) => onChange({ placeholder: e.target.value })}
                className="w-full rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-600 dark:text-white"
                placeholder="Hint text..."
              />
            </div>
          </div>

          {field.fieldType === 'select' && (
            <div>
              <label className="mb-0.5 block text-xs text-gray-500 dark:text-gray-400">
                Options (comma-separated)
              </label>
              <input
                type="text"
                value={field.options ?? ''}
                onChange={(e) => onChange({ options: e.target.value })}
                className="w-full rounded border border-gray-200 px-2 py-1 text-xs dark:border-gray-600 dark:bg-gray-600 dark:text-white"
                placeholder="Option 1, Option 2, Option 3"
              />
            </div>
          )}

          <label className="flex cursor-pointer items-center gap-2 text-xs select-none">
            <input
              type="checkbox"
              checked={field.required}
              onChange={(e) => onChange({ required: e.target.checked })}
              className="rounded border-gray-300"
            />
            <span className="text-gray-600 dark:text-gray-400">Required field</span>
          </label>
        </div>
      )}
    </div>
  );
}

// ─── Form Preview ─────────────────────────────────────────────────────────────

function FormPreview({ form }: { form: WebFormData }) {
  const { style, fields } = form;

  return (
    <div className="mx-auto max-w-md rounded-xl border border-gray-200 bg-white p-6 shadow-sm dark:border-gray-700 dark:bg-gray-800">
      <h3 className="mb-5 text-lg font-semibold text-gray-900 dark:text-white">{form.name}</h3>

      {fields.length === 0 ? (
        <p className="text-sm text-gray-400 italic">Add fields to preview your form.</p>
      ) : (
        <div className="space-y-4">
          {fields.map((field) => (
            <div key={field.id}>
              <label className="mb-1 block text-sm font-medium text-gray-700 dark:text-gray-300">
                {field.label || field.name}
                {field.required && <span className="ml-0.5 text-red-500">*</span>}
              </label>
              {field.fieldType === 'textarea' ? (
                <textarea
                  rows={3}
                  disabled
                  placeholder={field.placeholder}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-400 dark:border-gray-600 dark:bg-gray-700"
                />
              ) : field.fieldType === 'select' ? (
                <select
                  disabled
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-400 dark:border-gray-600 dark:bg-gray-700"
                >
                  <option value="">Select an option...</option>
                  {(field.options ?? '')
                    .split(',')
                    .filter(Boolean)
                    .map((opt) => (
                      <option key={opt.trim()} value={opt.trim()}>
                        {opt.trim()}
                      </option>
                    ))}
                </select>
              ) : (
                <input
                  type={field.fieldType}
                  disabled
                  placeholder={field.placeholder}
                  className="w-full rounded-lg border border-gray-300 px-3 py-2 text-sm text-gray-400 dark:border-gray-600 dark:bg-gray-700"
                />
              )}
            </div>
          ))}

          <button
            disabled
            className="mt-2 w-full rounded-lg px-4 py-2.5 text-sm font-medium text-white transition-opacity"
            style={{ backgroundColor: style.primaryColor }}
          >
            {style.buttonText || 'Submit'}
          </button>
        </div>
      )}
    </div>
  );
}
