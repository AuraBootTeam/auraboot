/**
 * RecordPreviewDrawer - Generic record preview in a slide-over drawer
 *
 * Loads record data and renders key fields in a right-side drawer panel.
 * Provides quick preview without navigating away from the list page.
 *
 * Features:
 * - Auto-loads record data from dynamic API
 * - Renders fields using DSL field metadata
 * - Shows field labels with i18n
 * - "Open Full Detail" link to navigate to detail page
 * - Keyboard support (Escape to close)
 */

import { useState, useEffect, useRef } from 'react';
import { useNavigate } from 'react-router';
import dayjs from 'dayjs';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { useI18n } from '~/contexts/I18nContext';
import { buildApiEndpoint } from '~/routes/_shared/dynamic-route-utils';

// ============================================================================
// Types
// ============================================================================

export interface RecordPreviewDrawerProps {
  /** Whether the drawer is open */
  open: boolean;
  /** Model code (e.g., "crm_opportunity") */
  modelCode: string;
  /** Record PID to preview */
  recordId: string;
  /** Optional page key for the detail page (for "Open Detail" link) */
  detailPageKey?: string;
  /** Fields to display in preview. If not provided, shows all non-system fields */
  fields?: PreviewField[];
  /** Optional custom API endpoint for fetching the record (overrides dynamic API) */
  apiEndpoint?: string;
  /** Callback when drawer closes */
  onClose: () => void;
  /** Optional: callback after record is loaded */
  onRecordLoaded?: (record: Record<string, unknown>) => void;
}

export interface PreviewField {
  field: string;
  label?: string;
  type?: 'text' | 'number' | 'date' | 'datetime' | 'enum' | 'boolean' | 'reference';
  /** Dict code for enum fields */
  dictCode?: string;
}

interface RecordData {
  [key: string]: unknown;
  pid?: string;
  id?: number;
}

// System fields to exclude from auto-display
const SYSTEM_FIELDS = new Set([
  'id',
  'pid',
  'tenant_id',
  'created_at',
  'updated_at',
  'created_by',
  'updated_by',
  'deleted_flag',
  'sort_order',
]);

// ============================================================================
// Component
// ============================================================================

export function RecordPreviewDrawer({
  open,
  modelCode,
  recordId,
  detailPageKey,
  fields,
  apiEndpoint,
  onClose,
  onRecordLoaded,
}: RecordPreviewDrawerProps) {
  const navigate = useNavigate();
  const { t, locale } = useI18n();
  const drawerRef = useRef<HTMLDivElement>(null);

  const [record, setRecord] = useState<RecordData | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Load record data
  useEffect(() => {
    if (!open || !recordId || !modelCode) return;

    setLoading(true);
    setError(null);

    const endpoint = apiEndpoint
      ? `${apiEndpoint}/${recordId}`
      : `${buildApiEndpoint(modelCode)}/${recordId}`;
    fetchResult<RecordData>(endpoint, { method: 'get' })
      .then((result) => {
        if (ResultHelper.isSuccess(result) && result.data) {
          setRecord(result.data);
          onRecordLoaded?.(result.data);
        } else {
          setError(t('common.loadError') || 'Failed to load record');
        }
      })
      .catch(() => setError(t('common.loadError') || 'Failed to load record'))
      .finally(() => setLoading(false));
  }, [open, recordId, modelCode, apiEndpoint, t, onRecordLoaded]);

  // Keyboard handler
  useEffect(() => {
    if (!open) return;
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [open, onClose]);

  // Focus trap
  useEffect(() => {
    if (open && drawerRef.current) {
      drawerRef.current.focus();
    }
  }, [open]);

  // Derive display fields
  const displayFields = fields || deriveFieldsFromRecord(record);

  const detailUrl = detailPageKey
    ? `/p/${detailPageKey}/view/${recordId}`
    : `/p/${modelCode}/view/${recordId}`;

  if (!open) return null;

  return (
    <>
      {/* Backdrop */}
      <div
        className="fixed inset-0 z-40 bg-black/30 transition-opacity"
        onClick={onClose}
        data-testid="drawer-backdrop"
      />

      {/* Drawer panel */}
      <div
        ref={drawerRef}
        className="animate-slide-in-right fixed inset-y-0 right-0 z-50 flex w-[480px] max-w-full flex-col bg-white shadow-xl"
        tabIndex={-1}
        role="dialog"
        aria-modal="true"
        data-testid="record-preview-drawer"
      >
        {/* Header */}
        <div className="flex items-center justify-between border-b border-gray-200 px-6 py-4">
          <div className="flex min-w-0 items-center gap-3">
            <h2 className="truncate text-lg font-semibold text-gray-900">
              {record ? getRecordTitle(record, modelCode) : t('common.loading') || 'Loading...'}
            </h2>
          </div>
          <div className="flex items-center gap-2">
            <button
              onClick={() => navigate(detailUrl)}
              className="text-sm font-medium text-blue-600 hover:text-blue-800"
              data-testid="open-detail-link"
            >
              {t('action.view') || 'Open Detail'}
            </button>
            <button
              onClick={onClose}
              className="rounded-md p-1.5 text-gray-400 hover:bg-gray-100 hover:text-gray-600"
              data-testid="drawer-close-btn"
            >
              <svg className="h-5 w-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M6 18L18 6M6 6l12 12"
                />
              </svg>
            </button>
          </div>
        </div>

        {/* Content */}
        <div className="flex-1 overflow-y-auto px-6 py-4">
          {loading && (
            <div className="flex items-center justify-center py-12">
              <div className="h-8 w-8 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
            </div>
          )}

          {error && (
            <div className="rounded-lg bg-red-50 px-4 py-3 text-sm text-red-700">{error}</div>
          )}

          {!loading && !error && record && (
            <div className="space-y-4">
              {displayFields.map((field) => (
                <FieldRow
                  key={field.field}
                  field={field}
                  value={record[field.field]}
                  modelCode={modelCode}
                  locale={locale}
                  t={t}
                />
              ))}
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="border-t border-gray-200 bg-gray-50 px-6 py-3">
          <div className="flex items-center justify-between text-xs text-gray-500">
            {typeof record?.created_at === 'string' && (
              <span>
                {t('field.created_at.label') || 'Created'}: {formatDate(record.created_at)}
              </span>
            )}
            {typeof record?.updated_at === 'string' && (
              <span>
                {t('field.updated_at.label') || 'Updated'}: {formatDate(record.updated_at)}
              </span>
            )}
          </div>
        </div>
      </div>
    </>
  );
}

// ============================================================================
// Sub-components
// ============================================================================

function FieldRow({
  field,
  value,
  modelCode,
  t,
}: {
  field: PreviewField;
  value: unknown;
  modelCode: string;
  t: (key: string) => string;
}) {
  const label = field.label || resolveFieldLabel(field.field, modelCode, t);
  const displayValue = formatFieldValue(value, field.type);

  return (
    <div className="flex items-start gap-4" data-testid={`preview-field-${field.field}`}>
      <dt className="w-36 flex-shrink-0 pt-0.5 text-sm text-gray-500">{label}</dt>
      <dd className="flex-1 text-sm break-words text-gray-900">
        {displayValue || <span className="text-gray-300">—</span>}
      </dd>
    </div>
  );
}

// ============================================================================
// Helpers
// ============================================================================

function getRecordTitle(record: RecordData, modelCode: string): string {
  // Try common title fields
  const titleFields = [
    `${modelCode.replace(/^[a-z]+_/, '')}_name`,
    'name',
    `${modelCode}_name`,
    'title',
    'code',
    `${modelCode.replace(/^[a-z]+_/, '')}_code`,
    `${modelCode}_code`,
  ];

  for (const key of Object.keys(record)) {
    if (key.endsWith('_name') || key.endsWith('_title')) {
      const val = record[key];
      if (val && typeof val === 'string') return val;
    }
  }

  for (const field of titleFields) {
    const val = record[field];
    if (val && typeof val === 'string') return val;
  }

  return record.pid ? String(record.pid).slice(0, 8) + '...' : 'Record';
}

function resolveFieldLabel(
  fieldCode: string,
  modelCode: string,
  t: (key: string) => string,
): string {
  // Try model-scoped i18n key first
  const modelKey = `model.${modelCode}.${fieldCode}.label`;
  const resolved = t(modelKey);
  if (resolved !== modelKey) return resolved;

  // Try field-level key
  const fieldKey = `field.${fieldCode}.label`;
  const fieldResolved = t(fieldKey);
  if (fieldResolved !== fieldKey) return fieldResolved;

  // Fallback: humanize field code
  return fieldCode
    .replace(/^[a-z]+_[a-z]+_/, '') // strip prefix like crm_opp_
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}

function formatFieldValue(value: unknown, type?: string): string {
  if (value === null || value === undefined) return '';

  if (type === 'boolean') {
    return value ? 'Yes' : 'No';
  }

  if (type === 'date' || type === 'datetime') {
    return formatDate(String(value));
  }

  if (type === 'number') {
    const num = Number(value);
    if (!isNaN(num)) {
      return num.toLocaleString();
    }
  }

  return String(value);
}

function formatDate(dateStr: string): string {
  const d = dayjs(dateStr);
  if (!d.isValid()) return dateStr;
  return d.format('YYYY-MM-DD');
}

function deriveFieldsFromRecord(record: RecordData | null): PreviewField[] {
  if (!record) return [];
  return Object.keys(record)
    .filter((key) => !SYSTEM_FIELDS.has(key))
    .filter((key) => record[key] !== null && record[key] !== undefined)
    .slice(0, 15) // limit to 15 fields for preview
    .map((key) => ({
      field: key,
      type: inferFieldType(key, record[key]),
    }));
}

function inferFieldType(key: string, value: unknown): PreviewField['type'] {
  if (typeof value === 'boolean') return 'boolean';
  if (typeof value === 'number') return 'number';
  if (typeof value === 'string') {
    if (/^\d{4}-\d{2}-\d{2}T/.test(value) || /^\d{4}-\d{2}-\d{2}$/.test(value)) return 'date';
  }
  return 'text';
}
