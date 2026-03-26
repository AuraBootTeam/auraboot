/**
 * ListPageContent — Extracted rendering logic from dynamic.$tableName.tsx
 *
 * Receives { schema, tableName, token } from DynamicPageRenderer and uses
 * usePageRuntime for runtime setup (replacing useDynamicPageSetup).
 *
 * Contains ALL rendering logic: filter-form, toolbar, data-table, pagination,
 * list-tabs, SavedView integration, dashboard rendering, dict cache, etc.
 */

import React, { useState, useCallback, useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import { useSearchParams } from 'react-router';
import type { PageContentProps } from '~/meta/profiles/types';
import { usePageRuntime } from '~/meta/rendering/pages/hooks/usePageRuntime';
import { buildApiEndpoint, getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { fetchResult } from '~/services/http-client';
import { ResultHelper } from '~/utils/type';
import { createExpressionContext } from '~/meta/runtime/expression/context';
import type { BlockConfig, ColumnConfig, FieldConfig, ButtonConfig } from '~/meta/schemas/types';
import { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';
import { sanitizeHtml } from '~/meta/utils/sanitizeHtml';
import { InlineEditCell } from '~/meta/rendering/components/InlineEditCell';
import { cellRendererRegistry } from '~/meta/runtime/renderers/CellRendererRegistry';
import { useActionHandler } from '~/meta/hooks/useActionHandler';
import { useToastContext } from '~/contexts/ToastContext';
import { DataSourceProvider } from '~/meta/contexts/DataSourceContext';
import { createFieldRenderer } from '~/meta/utils/createFieldRenderer';
import { ErrorAlert } from '~/components/ErrorAlert';
import { useAuth } from '~/contexts/AuthContext';
import { Pagination } from '~/components/Pagination';
import { ImportModal } from '~/smart/components/data-tools/ImportModal';
import { ToolbarMoreMenu } from '~/meta/rendering/components/ToolbarMoreMenu';
import FormDialog from '~/meta/runtime/actions/FormDialog';
import { useSavedViews } from '~/smart/hooks/useSavedViews';
import { useAutoSaveView } from '~/smart/hooks/useAutoSaveView';
import { ViewSelector } from '~/smart/components/view/ViewSelector';
import { ViewManagePanel } from '~/smart/components/view/ViewManagePanel';
import { ColumnSettingsPanel } from '~/smart/components/view/ColumnSettingsPanel';
import { RowHeightSelector } from '~/smart/components/view/RowHeightSelector';
import { ColumnResizeHandle } from '~/smart/components/view/ColumnResizeHandle';
import { FilterChipBar } from '~/smart/components/view/FilterChipBar';
import { FilterFieldPicker } from '~/smart/components/view/FilterFieldPicker';
import { FilterValuePopover } from '~/smart/components/view/FilterValuePopover';
import type { ViewFilterConfig } from '~/smart/types/savedView';
import type { RowHeight } from '~/smart/types/savedView';
import { ROW_HEIGHT_CONFIG, DEFAULT_ROW_HEIGHT } from '~/smart/types/savedView';
import { evaluateConditionalFormats, buildConditionalStyle } from '~/smart/utils/conditionalFormatEvaluator';
import { SmartViewRenderer } from '~/smart/components/view/SmartViewRenderer';
import { BulkActionToolbar } from '~/smart/components/bulk/BulkActionToolbar';
import { BulkEditModal } from '~/smart/components/bulk/BulkEditModal';
import { RecordPreviewDrawer } from '~/smart/components/preview/RecordPreviewDrawer';
import type {
  SavedViewCreateRequest,
  ColumnConfig as ViewColumnConfig,
  ViewType,
  SortConfig,
} from '~/smart/types/savedView';
import { modelService } from '~/services/modelService';
import { useTimezone } from '~/contexts/TimezoneContext';
import { deriveTestId } from '~/meta/rendering/utils/deriveTestId';

// Dict data item type
interface DictItem {
  value: string;
  label: string;
  extension?: Record<string, any>;
}

interface DynamicEntity {
  [key: string]: any;
  id?: string;
  pid?: string;
}

interface PaginationResult<T> {
  records: T[];
  total: number;
  page: number;
  pageSize: number;
  totalPages: number;
}

interface InviteCodeData {
  code: string;
  expiredAt?: string;
  createdAt?: string;
}

// Dashboard chart block — renders Smart chart components for chart-type blocks
const DashboardChartBlock = React.lazy(() => import('./DashboardChartBlock'));

// Dashboard stat card — renders stat cards from API datasource
function DashboardStatCard({ block, token, locale, t }: { block: any; token: string | null | undefined; locale: string; t?: (key: string) => string }) {
  const [stats, setStats] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const dsUrl = block.dataSource?.url || block.dataSource?.endpoint;
    if (!dsUrl) {
      setLoading(false);
      return;
    }
    const params = new URLSearchParams();
    if (block.dataSource?.datasourceId) params.set('datasourceId', block.dataSource.datasourceId);
    if (block.dataSource?.maxItems) params.set('maxItems', String(block.dataSource.maxItems || 10));
    if (block.dataSource?.params) {
      Object.entries(block.dataSource.params).forEach(([k, v]) => params.set(k, String(v)));
    }
    const qs = params.toString();
    const url = dsUrl + (qs ? (dsUrl.includes('?') ? '&' : '?') + qs : '');

    fetchResult<any>(url, { method: 'get', token: token || undefined })
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data) {
          const records = res.data.records || res.data || [];
          setStats(Array.isArray(records) ? records : [records]);
        }
      })
      .catch(() => {})
      .finally(() => setLoading(false));
  }, [block, token]);

  const cards = block.cards || [];
  if (loading) {
    return (
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        {(cards.length > 0 ? cards : [1, 2, 3, 4]).map((_: any, i: number) => (
          <div key={i} className="h-24 animate-pulse rounded-lg bg-gray-100" />
        ))}
      </div>
    );
  }

  // If block has explicit card definitions, use them
  if (cards.length > 0) {
    return (
      <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
        {cards.map((card: any, i: number) => {
          const record = stats[0] || {};
          const value = card.field ? record[card.field] : card.valueField ? record[card.valueField] : card.value || 0;
          return (
            <div key={i} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
              <p className="text-sm font-medium text-gray-500">{getLocalizedText(card.label || card.title, locale, t) || `Stat ${i + 1}`}</p>
              <p className="mt-1 text-2xl font-semibold text-gray-900">{value ?? '—'}</p>
              {card.description && <p className="mt-1 text-xs text-gray-400">{getLocalizedText(card.description, locale, t)}</p>}
            </div>
          );
        })}
      </div>
    );
  }

  // Fallback: render each record as a stat card
  return (
    <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
      {stats.slice(0, 8).map((stat: any, i: number) => {
        const entries = Object.entries(stat).filter(([k]) => !['id', 'pid', 'tenant_id'].includes(k));
        const label = entries[0]?.[1];
        const value = entries[1]?.[1];
        return (
          <div key={i} className="rounded-lg border border-gray-200 bg-white p-4 shadow-sm">
            <p className="text-sm font-medium text-gray-500">{String(label || `Item ${i + 1}`)}</p>
            <p className="mt-1 text-2xl font-semibold text-gray-900">{String(value ?? '—')}</p>
          </div>
        );
      })}
    </div>
  );
}

// Dashboard block — independently fetches data for its modelCode
function DashboardBlockTable({ block, token }: { block: any; token: string | null | undefined }) {
  const [rows, setRows] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const dsUrl = block.dataSource?.url || block.dataSource?.endpoint;
    if (!block.modelCode && !dsUrl) {
      setLoading(false);
      return;
    }

    let fetchUrl: string;
    if (dsUrl) {
      // API datasource: use the configured URL directly
      const params = new URLSearchParams();
      if (block.dataSource?.datasourceId) params.set('datasourceId', block.dataSource.datasourceId);
      if (block.dataSource?.maxItems) params.set('maxItems', String(block.dataSource.maxItems));
      if (block.dataSource?.params) {
        Object.entries(block.dataSource.params).forEach(([k, v]) => params.set(k, String(v)));
      }
      const qs = params.toString();
      fetchUrl = dsUrl + (qs ? (dsUrl.includes('?') ? '&' : '?') + qs : '');
    } else {
      const slug = block.modelCode.replace(/_/g, '-');
      const params: Record<string, any> = { page: 0, size: 10 };
      // Support both defaultFilters (array, canonical) and defaultFilter (singular, deprecated)
      const blockFilters =
        block.defaultFilters || (block.defaultFilter ? [block.defaultFilter] : undefined);
      if (blockFilters && blockFilters.length > 0) {
        const normalizedFilters = blockFilters.map((f: any) => {
          if (f.fieldName || f.field) return f;
          const entries = Object.entries(f);
          if (entries.length > 0) {
            const [key, val] = entries[0];
            return { fieldName: key, operator: 'EQ', value: val };
          }
          return f;
        });
        params.filters = JSON.stringify(normalizedFilters);
      }
      const qs = new URLSearchParams();
      Object.entries(params).forEach(([k, v]) => qs.set(k, String(v)));
      fetchUrl = `/api/dynamic/${slug}/list?${qs.toString()}`;
    }

    fetchResult<any>(fetchUrl, {
      method: 'get',
      token: token || undefined,
    })
      .then((res) => {
        if (ResultHelper.isSuccess(res) && res.data) {
          setRows(res.data.records || res.data || []);
        } else {
          setErr(res.desc || 'Failed to load');
        }
      })
      .catch((e) => setErr(e.message))
      .finally(() => setLoading(false));
  }, [block, token]);

  const columns = block.columns || block.table?.columns || [];

  if (loading) {
    return <div className="px-4 py-6 text-center text-sm text-gray-400">Loading...</div>;
  }
  if (err) {
    return <div className="px-4 py-6 text-center text-sm text-red-500">{err}</div>;
  }
  return (
    <table className="min-w-full divide-y divide-gray-200">
      <thead className="bg-gray-50">
        <tr>
          {columns.map((col: any) => (
            <th
              key={col.field}
              className={`px-4 py-2 text-xs font-medium text-gray-500 uppercase ${
                col.align === 'right' ? 'text-right' : 'text-left'
              }`}
              style={col.width ? { width: `${col.width}px` } : undefined}
            >
              {col.label
                ? typeof col.label === 'string'
                  ? col.label
                  : col.label['zh-CN'] || col.label['en'] || col.field
                : col.field}
            </th>
          ))}
        </tr>
      </thead>
      <tbody className="divide-y divide-gray-200">
        {rows.length === 0 ? (
          <tr>
            <td
              colSpan={columns.length || 1}
              className="px-4 py-4 text-center text-sm text-gray-400"
            >
              No data
            </td>
          </tr>
        ) : (
          rows.map((row: any, i: number) => (
            <tr key={row.pid || row.id || i}>
              {columns.map((col: any) => (
                <td
                  key={col.field}
                  className={`px-4 py-2 text-sm text-gray-900 ${col.align === 'right' ? 'text-right' : ''}`}
                >
                  {row[col.field] ?? '-'}
                </td>
              ))}
            </tr>
          ))
        )}
      </tbody>
    </table>
  );
}

/**
 * DropdownMenu — Portal-rendered dropdown for row actions.
 * Positioned absolutely relative to the trigger button to avoid
 * being clipped by table overflow-x-auto containers.
 */
function DropdownMenu({
  menuRef,
  moreButtons,
  record,
  resolveButtonLabel,
  handleAction,
  setOpen,
}: {
  menuRef: React.RefObject<HTMLDivElement | null>;
  moreButtons: ButtonConfig[];
  record: any;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: any) => void;
  setOpen: (v: boolean) => void;
}) {
  const [pos, setPos] = useState<{ top: number; left: number }>({ top: 0, left: 0 });

  useEffect(() => {
    if (!menuRef.current) return;
    const rect = menuRef.current.getBoundingClientRect();
    setPos({
      top: rect.bottom + 4,
      left: rect.right - 120, // min-w-[120px] aligned to right edge
    });
  }, [menuRef]);

  return (
    <div
      className="fixed z-[9999] min-w-[120px] rounded-md border border-gray-200 bg-white py-1 shadow-lg"
      style={{ top: pos.top, left: Math.max(0, pos.left) }}
      data-testid="row-action-dropdown"
    >
      {moreButtons.map((btn) => (
        <button
          type="button"
          key={btn.code}
          data-testid={`row-action-${btn.code}`}
          onClick={(e) => {
            e.stopPropagation();
            setOpen(false);
            handleAction(btn, record);
          }}
          className={`block w-full px-3 py-1.5 text-left text-sm transition-colors ${
            btn.danger
              ? 'text-red-600 hover:bg-red-50'
              : 'text-gray-700 hover:bg-gray-50'
          }`}
        >
          {resolveButtonLabel(btn)}
        </button>
      ))}
    </div>
  );
}

/**
 * RowActionButtons — Renders primary action as a link and remaining actions
 * in a compact "..." dropdown menu. Improves UX over flat text buttons.
 */
function RowActionButtons({
  buttons,
  record,
  evaluateVisibleWhen,
  resolveButtonLabel,
  handleAction,
}: {
  buttons: ButtonConfig[];
  record: any;
  evaluateVisibleWhen: (expr: string | undefined, record: any) => boolean;
  resolveButtonLabel: (button: ButtonConfig) => string;
  handleAction: (button: ButtonConfig, record?: any) => void;
}) {
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  // Close dropdown on outside click (check both trigger and portaled dropdown)
  useEffect(() => {
    if (!open) return;
    const handler = (e: MouseEvent) => {
      const target = e.target as Node;
      if (menuRef.current && menuRef.current.contains(target)) return;
      // Check if click is inside the portaled dropdown
      const dropdown = document.querySelector('[data-testid="row-action-dropdown"]');
      if (dropdown && dropdown.contains(target)) return;
      setOpen(false);
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [open]);

  const visibleButtons = buttons.filter((button) =>
    evaluateVisibleWhen(button.visibleWhen, record),
  );

  if (visibleButtons.length === 0) return null;

  // If only 1 button, render it directly
  if (visibleButtons.length === 1) {
    const btn = visibleButtons[0];
    return (
      <div className="inline-flex items-center justify-start">
        <button
          type="button"
          data-testid={`row-action-${btn.code}`}
          onClick={(e) => { e.stopPropagation(); handleAction(btn, record); }}
          className={`rounded-md px-2 py-1 text-sm font-medium transition-colors ${
            btn.danger
              ? 'text-red-600 hover:bg-red-50 hover:text-red-800'
              : 'text-blue-600 hover:bg-blue-50 hover:text-blue-800'
          }`}
        >
          {resolveButtonLabel(btn)}
        </button>
      </div>
    );
  }

  // Primary = first non-danger button; rest go into dropdown
  const primaryBtn = visibleButtons[0];
  const moreButtons = visibleButtons.slice(1);

  return (
    <div className="inline-flex items-center justify-start gap-1">
      {/* Primary action as link */}
      <button
        type="button"
        data-testid={`row-action-${primaryBtn.code}`}
        onClick={(e) => { e.stopPropagation(); handleAction(primaryBtn, record); }}
        className="rounded-md px-2 py-1 text-sm font-medium text-blue-600 transition-colors hover:bg-blue-50 hover:text-blue-800"
      >
        {resolveButtonLabel(primaryBtn)}
      </button>

      {/* More actions dropdown — rendered via Portal to avoid overflow clipping */}
      {moreButtons.length > 0 && (
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            data-testid="row-action-more"
            onClick={(e) => { e.stopPropagation(); setOpen(!open); }}
            className="rounded-md p-1 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
            aria-label="More actions"
          >
            <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="1" />
              <circle cx="12" cy="5" r="1" />
              <circle cx="12" cy="19" r="1" />
            </svg>
          </button>
          {open && createPortal(
            <DropdownMenu menuRef={menuRef} moreButtons={moreButtons} record={record} resolveButtonLabel={resolveButtonLabel} handleAction={handleAction} setOpen={setOpen} />,
            document.body,
          )}
        </div>
      )}
    </div>
  );
}

// Column context menu component — appears on right-click or ⋮ click on column headers
function ColumnContextMenu({
  x,
  y,
  column,
  currentSortDir,
  onSort,
  onFreeze,
  onHide,
  onFilterByColumn,
  onGroupBy,
  onClose,
}: {
  x: number;
  y: number;
  column: ColumnConfig;
  currentSortDir?: 'asc' | 'desc';
  onSort: (dir: 'asc' | 'desc' | 'clear') => void;
  onFreeze: (pos: 'left' | 'right' | 'none') => void;
  onHide: () => void;
  onFilterByColumn: () => void;
  onGroupBy: () => void;
  onClose: () => void;
}) {
  const menuRef = useRef<HTMLDivElement>(null);
  useEffect(() => {
    const handler = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) onClose();
    };
    document.addEventListener('mousedown', handler);
    return () => document.removeEventListener('mousedown', handler);
  }, [onClose]);

  const frozenPos = column.fixed || (column as any).frozenPosition;
  const menuItems = [
    { icon: '↑', label: 'Sort Ascending', active: currentSortDir === 'asc', onClick: () => onSort('asc') },
    { icon: '↓', label: 'Sort Descending', active: currentSortDir === 'desc', onClick: () => onSort('desc') },
    currentSortDir ? { icon: '✕', label: 'Clear Sort', onClick: () => onSort('clear') } : null,
    'divider' as const,
    { icon: '📌', label: 'Freeze Left', active: frozenPos === 'left', onClick: () => onFreeze(frozenPos === 'left' ? 'none' : 'left') },
    { icon: '📌', label: 'Freeze Right', active: frozenPos === 'right', onClick: () => onFreeze(frozenPos === 'right' ? 'none' : 'right') },
    'divider' as const,
    { icon: '👁', label: 'Hide Column', onClick: onHide },
    { icon: '🔍', label: 'Filter by Column', onClick: onFilterByColumn },
    'divider' as const,
    { icon: '☰', label: 'Group by Column', onClick: onGroupBy },
  ].filter(Boolean);

  return createPortal(
    <div
      ref={menuRef}
      className="fixed z-[1000] min-w-[180px] rounded-lg border border-gray-200 bg-white py-1 shadow-lg"
      style={{ left: Math.min(x, window.innerWidth - 200), top: Math.min(y, window.innerHeight - 300) }}
    >
      {menuItems.map((item, idx) => {
        if (item === 'divider') return <div key={idx} className="my-1 h-px bg-gray-100" />;
        if (!item || typeof item === 'string') return null;
        return (
          <button
            key={idx}
            type="button"
            className={`flex w-full items-center gap-2 px-3 py-1.5 text-left text-sm hover:bg-gray-50 ${item.active ? 'text-blue-600 font-medium' : 'text-gray-700'}`}
            onClick={() => { item.onClick(); onClose(); }}
          >
            <span className="w-4 text-center text-xs">{item.icon}</span>
            <span className="flex-1">{item.label}</span>
            {item.active && <span className="text-xs text-blue-500">✓</span>}
          </button>
        );
      })}
    </div>,
    document.body,
  );
}

// Quick filter chip definitions
type QuickFilterKey = 'my_records' | 'created_today' | 'modified_this_week';

// List Page Content Component
export function ListPageContent(props: PageContentProps) {
  const { schema, tableName, token } = props;
  const { user } = useAuth();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  // Parse filter_* params from URL for drill-down navigation
  const [searchParams, setSearchParams] = useSearchParams();
  const urlFilters = useMemo(() => {
    const filters: Record<string, any> = {};
    searchParams.forEach((value, key) => {
      if (key.startsWith('filter_')) {
        const fieldName = key.replace('filter_', '');
        filters[fieldName] = value;
      }
    });
    return filters;
  }, [searchParams]);
  const urlPageNum = useMemo(() => {
    const raw = searchParams.get('pageNum');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);
  const urlPageSize = useMemo(() => {
    const raw = searchParams.get('pageSize');
    if (!raw) return null;
    const parsed = Number(raw);
    return Number.isFinite(parsed) && parsed >= 1 ? Math.floor(parsed) : null;
  }, [searchParams]);

  // State management - P2-1 fix: merged into single state
  const [data, setData] = useState<DynamicEntity[]>([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [selectedIds, setSelectedIds] = useState<Set<string>>(new Set());
  const [bulkEditOpen, setBulkEditOpen] = useState(false);
  const [pageState, setPageState] = useState(() => ({
    filters: { ...urlFilters } as Record<string, any>,
    pagination: {
      current: urlPageNum ?? 1,
      pageSize: urlPageSize ?? 20,
      total: 0,
    },
  }));

  // P2-1 fix: destructure state for convenience
  const { filters, pagination } = pageState;

  // Active sort state — initialized from SavedView or DSL defaultSort
  const [activeSorts, setActiveSorts] = useState<SortConfig[]>([]);
  // Active filter chips — user-added filters via chip bar (separate from filter-form)
  const [chipFilters, setChipFilters] = useState<ViewFilterConfig[]>([]);
  // FilterFieldPicker state
  const [fieldPickerOpen, setFieldPickerOpen] = useState(false);
  const [fieldPickerAnchor, setFieldPickerAnchor] = useState<{ x: number; y: number } | undefined>();
  // FilterValuePopover state — for editing a chip's operator + value
  const [editingChipIdx, setEditingChipIdx] = useState<number | null>(null);
  const [valuePopoverAnchor, setValuePopoverAnchor] = useState<{ x: number; y: number } | undefined>();

  // Client-side grouping state
  const [groupByField, setGroupByField] = useState<string | null>(null);
  const [collapsedGroups, setCollapsedGroups] = useState<Set<string>>(new Set());

  const groupedData = useMemo(() => {
    if (!groupByField || data.length === 0) return null;
    const groups = new Map<string, DynamicEntity[]>();
    for (const row of data) {
      const key = String(row[groupByField] ?? '(empty)');
      if (!groups.has(key)) groups.set(key, []);
      groups.get(key)!.push(row);
    }
    return Array.from(groups.entries()).map(([key, rows]) => ({ key, rows, count: rows.length }));
  }, [data, groupByField]);

  const toggleGroupCollapse = useCallback((key: string) => {
    setCollapsedGroups((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key); else next.add(key);
      return next;
    });
  }, []);

  // P2-1 fix: helper functions for updating filters and pagination
  const setFilters = useCallback((filters: Record<string, any>) => {
    setPageState((prev) => ({ ...prev, filters }));
  }, []);

  const setPagination = useCallback((pagination: any) => {
    setPageState((prev) => ({
      ...prev,
      pagination: typeof pagination === 'function' ? pagination(prev.pagination) : pagination,
    }));
  }, []);

  const shouldPersistPaginationToUrlRef = useRef(urlPageNum != null || urlPageSize != null);
  const tableBlock = useMemo(() => {
    if (!schema?.areas) return null;
    return (
      Object.values(schema.areas)
        .flatMap((area: any) => area.blocks)
        .find((block) => block.blockType === 'data-table') || null
    );
  }, [schema]);

  // Sync URL pagination params -> local state (supports refresh and browser back/forward).
  useEffect(() => {
    if (urlPageNum == null && urlPageSize == null) return;
    shouldPersistPaginationToUrlRef.current = true;
    setPageState((prev) => {
      const nextCurrent = urlPageNum ?? prev.pagination.current;
      const nextPageSize = urlPageSize ?? prev.pagination.pageSize;
      if (nextCurrent === prev.pagination.current && nextPageSize === prev.pagination.pageSize) {
        return prev;
      }
      return {
        ...prev,
        pagination: {
          ...prev.pagination,
          current: nextCurrent,
          pageSize: nextPageSize,
        },
      };
    });
  }, [urlPageNum, urlPageSize]);

  // Sync local pagination state -> URL query params (preserve existing filter_* params).
  useEffect(() => {
    if (!shouldPersistPaginationToUrlRef.current) return;
    const currentUrlPageNum = Number(searchParams.get('pageNum'));
    const currentUrlPageSize = Number(searchParams.get('pageSize'));
    const needsUpdate =
      currentUrlPageNum !== pagination.current || currentUrlPageSize !== pagination.pageSize;
    if (!needsUpdate) return;
    const nextParams = new URLSearchParams(searchParams);
    nextParams.set('pageNum', String(pagination.current));
    nextParams.set('pageSize', String(pagination.pageSize));
    setSearchParams(nextParams, { replace: true });
  }, [pagination.current, pagination.pageSize, searchParams, setSearchParams]);

  // Use usePageRuntime instead of useDynamicPageSetup
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: {
      filters,
    },
  });

  const navigateToRecordView = useCallback(
    (recordId: string | number | null | undefined) => {
      if (recordId == null || recordId === '') {
        return;
      }
      navigate(`/dynamic/${tableName}/view/${String(recordId)}`);
    },
    [navigate, tableName],
  );

  // Tab state for list-tabs
  const [activeTab, setActiveTab] = useState('all');
  const [importOpen, setImportOpen] = useState(false);
  const [viewManageOpen, setViewManageOpen] = useState(false);
  const [columnSettingsOpen, setColumnSettingsOpen] = useState(false);
  const [contextMenu, setContextMenu] = useState<{ x: number; y: number; column: ColumnConfig } | null>(null);
  const [activeQuickFilter, setActiveQuickFilter] = useState<QuickFilterKey | null>(null);
  const [filtersExpanded, setFiltersExpanded] = useState(false);
  const [activeViewType, setActiveViewType] = useState<ViewType>('table');
  const [startCreateViewMode, setStartCreateViewMode] = useState(false);
  const [inviteDialogOpen, setInviteDialogOpen] = useState(false);
  const [inviteLoading, setInviteLoading] = useState(false);
  const [inviteCodeData, setInviteCodeData] = useState<InviteCodeData | null>(null);
  const { formats: dateTimeFormats } = useTimezone();
  const loadDataRef = useRef<
    | ((params?: { page?: number; size?: number; filters?: Record<string, any> }) => Promise<void>)
    | null
  >(null);

  // Record preview drawer state
  const [previewRecordId, setPreviewRecordId] = useState<string | null>(null);

  // SavedView integration
  const modelCode = schema?.modelCode || tableName.replace(/-/g, '_');
  const pageKey = tableName;
  const isTenantMemberPage = modelCode === 'tenant_member' || pageKey === 'tenant-member';
  const {
    views: savedViews,
    currentView,
    selectView,
    createView,
    updateViewConfig,
    deleteView: deleteSavedView,
    setDefaultView,
    duplicateView,
    loading: viewsLoading,
  } = useSavedViews({ modelCode, pageKey, autoLoad: !!schema });

  // Resolve modelPid from modelCode for ViewManagePanel field operations
  const [modelPid, setModelPid] = useState<string | undefined>();
  useEffect(() => {
    if (!modelCode) return;
    modelService
      .findByCode(modelCode)
      .then((model) => setModelPid(model.pid))
      .catch(() => setModelPid(undefined));
  }, [modelCode]);

  // Apply SavedView viewConfig (pagination + filters) when view changes
  useEffect(() => {
    if (!currentView?.viewConfig) return;
    const vc = currentView.viewConfig;
    if (vc.pagination?.pageSize && vc.pagination.pageSize > 0) {
      setPagination((prev: typeof pagination) => ({
        ...prev,
        pageSize: vc.pagination!.pageSize!,
      }));
    }
    // Apply saved filters
    if (vc.filters && vc.filters.length > 0) {
      const restoredFilters: Record<string, any> = {};
      vc.filters.forEach((f) => {
        restoredFilters[f.fieldCode] = f.value;
      });
      setFilters(restoredFilters);
    }
    // Apply saved sorts
    if (vc.sorts && vc.sorts.length > 0) {
      setActiveSorts(vc.sorts);
    }
  }, [currentView, setPagination, setFilters]);


  // Dict data cache
  const dictDataCache = useRef<Map<string, DictItem[]>>(new Map());

  // Load dict data
  useEffect(() => {
    if (!schema) return;

    // Get columns from tableBlock (safely handle missing areas)
    const tableBlock = schema.areas
      ? Object.values(schema.areas)
          .flatMap((area: any) => area.blocks)
          .find((block) => block.blockType === 'data-table')
      : undefined;

    const rawColumns = tableBlock?.table?.columns || tableBlock?.columns;
    const columns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];
    const dictCodes = columns
      .filter((col: ColumnConfig) => col.dictCode)
      .map((col: ColumnConfig) => col.dictCode!);

    if (dictCodes.length === 0) {
      return;
    }

    // Get codes not yet loaded
    const unloadedCodes = dictCodes.filter((code: string) => !dictDataCache.current.has(code));

    if (unloadedCodes.length === 0) {
      return;
    }

    // Load all dict data in parallel
    const loadDictData = async () => {
      const promises = unloadedCodes.map(async (code: string) => {
        try {
          const result = await fetchResult(`/api/meta/dict/by-code/${code}/data`, {
            method: 'get',
            token: token || undefined,
          });
          if (ResultHelper.isSuccess(result) && result.data) {
            // Adapt dict data format
            const data = result.data as { items?: DictItem[] } | DictItem[];
            const items: DictItem[] = Array.isArray(data) ? data : data.items || [];
            dictDataCache.current.set(code, items);
          }
        } catch (error) {
          console.error(`[ListPageContent] Failed to load dict: ${code}`, error);
        }
      });

      await Promise.all(promises);
    };

    loadDictData();
  }, [schema, token]);

  // Apply DSL pagination.pageSize when schema loads
  useEffect(() => {
    const dslPageSize = tableBlock?.table?.pagination?.pageSize;
    if (dslPageSize && dslPageSize > 0) {
      setPagination((prev: typeof pagination) => ({ ...prev, pageSize: dslPageSize }));
    }
  }, [tableBlock, setPagination]);

  // Create full ExpressionContext for field rendering
  const pageContext = useMemo(() => {
    return createExpressionContext({
      global: {
        locale,
        theme: 'light',
        user: undefined,
        tenant: undefined,
      },
      state: {
        filters,
      },
      locale,
      t: (key: string) => t(key),
      __dataSourceManager: dataSourceManager,
    });
  }, [locale, filters, t, dataSourceManager]);

  // Get current tab filter as QueryCondition (if list-tabs block exists)
  const getTabFilter = useCallback((): {
    fieldName: string;
    operator: string;
    value: string;
  } | null => {
    if (!schema?.areas) return null;
    const tabsBlock = Object.values(schema.areas)
      .flatMap((area: any) => area.blocks)
      .find((block) => block.blockType === 'list-tabs');
    if (!tabsBlock?.tabs) return null;
    const currentTab = (tabsBlock.tabs as any[]).find((tab: any) => tab.key === activeTab);
    if (!currentTab?.filter) return null;
    const { field, fieldName, value, operator } = currentTab.filter;
    return { fieldName: fieldName || field, operator: operator || 'EQ', value };
  }, [schema, activeTab]);

  // Build filters JSON array from tab filter + user filters
  const buildFiltersParam = useCallback(
    (
      tabCondition: { fieldName: string; operator: string; value: string } | null,
      userFilters?: Record<string, any>,
      chipFiltersList?: ViewFilterConfig[],
    ) => {
      const conditions: Array<{ fieldName: string; operator: string; value: string }> = [];
      if (tabCondition) {
        conditions.push(tabCondition);
      }
      // Convert user filters (key-value from filter-form) to QueryCondition format
      if (userFilters) {
        for (const [key, value] of Object.entries(userFilters)) {
          if (value == null || value === '') continue;
          // Handle date range objects { start, end }
          if (typeof value === 'object' && ('start' in value || 'end' in value)) {
            if (value.start) {
              conditions.push({ fieldName: key, operator: 'gte', value: String(value.start) });
            }
            if (value.end) {
              conditions.push({ fieldName: key, operator: 'lte', value: String(value.end) });
            }
          } else {
            const fieldConfig = ((filterBlock?.fields || []) as FieldConfig[]).find(
              (field) => field.field === key,
            );
            const explicitOperator = (fieldConfig as any)?.operator || fieldConfig?.props?.operator;
            const operator =
              typeof explicitOperator === 'string' && explicitOperator.trim()
                ? explicitOperator.trim().toUpperCase()
                : fieldConfig?.component === 'SmartInput'
                  ? 'LIKE'
                  : 'EQ';
            const textValue = String(value);
            conditions.push({
              fieldName: key,
              operator,
              value: operator === 'LIKE' ? `%${textValue}%` : textValue,
            });
          }
        }
      }
      // Merge chip filters (from FilterChipBar) into conditions
      if (chipFiltersList) {
        for (const cf of chipFiltersList) {
          if (!cf.fieldCode) continue;
          // Skip unary operators (isNull/isNotNull) that don't need a value
          if (cf.operator === 'isNull' || cf.operator === 'isNotNull') {
            conditions.push({ fieldName: cf.fieldCode, operator: cf.operator.toUpperCase(), value: '' });
            continue;
          }
          if (cf.value == null || cf.value === '') continue;
          const op = cf.operator?.toUpperCase() || 'EQ';
          const val = String(cf.value);
          conditions.push({
            fieldName: cf.fieldCode,
            operator: op,
            value: op === 'LIKE' ? `%${val}%` : val,
          });
        }
      }
      return conditions.length > 0 ? JSON.stringify(conditions) : undefined;
    },
    [],
  );

  // Detect namedQuery data source from page DSL
  const namedQueryCode = useMemo(() => {
    if (schema?.dataSource?.type === 'namedQuery' && schema.dataSource.queryCode) {
      return schema.dataSource.queryCode;
    }
    // Also support top-level queryCode (e.g., NQ-backed list pages like "My Tasks")
    if (schema?.queryCode) {
      console.warn(
        '[DSL Deprecation] Top-level queryCode is deprecated. Use dataSource: { type: "namedQuery", queryCode: "..." }',
      );
      return schema.queryCode;
    }
    return null;
  }, [schema]);

  // Load data from API - P2-1 fix: use destructured pagination
  const loadData = useCallback(
    async (params?: { page?: number; size?: number; filters?: Record<string, any> }) => {
      if (!schema) return;

      try {
        setLoading(true);
        setError(null);

        // Resolve endpoint: use API datasource endpoint if configured, else default dynamic table
        let endpoint: string;
        let method: 'get' | 'post' | 'put' | 'delete' | 'patch' | 'options' = 'get';
        const isApiDatasource = schema.dataSource?.type === 'api' && schema.dataSource.endpoint;
        if (isApiDatasource) {
          endpoint = schema.dataSource!.endpoint!;
          method = (schema.dataSource!.method as typeof method) || 'get';
        } else {
          // Use schema.modelCode for API endpoint when available (supports NQ-backed pages
          // where URL tableName differs from actual model, e.g., /dynamic/crm-my-tasks → crm_activity)
          const apiTableName = schema.modelCode ? schema.modelCode.replace(/-/g, '_') : tableName;
          endpoint = `${buildApiEndpoint(apiTableName)}/list`;
        }

        const requestedPageNum = (params?.page ?? pagination.current - 1) + 1;
        const requestedPageSize = params?.size ?? pagination.pageSize;
        const requestedPageZeroBased = Math.max(requestedPageNum - 1, 0);
        const queryParams: Record<string, any> = {};

        if (isApiDatasource) {
          queryParams.page = requestedPageZeroBased;
          queryParams.size = requestedPageSize;
        } else {
          queryParams.pageNum = requestedPageNum;
          queryParams.pageSize = requestedPageSize;
        }

        if (isApiDatasource) {
          // For API datasources, pass filter values as individual query params
          if (params?.filters) {
            for (const [key, value] of Object.entries(params.filters)) {
              if (value == null || value === '') continue;
              // Handle date range objects { start, end }
              if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                if (value.start) queryParams[`${key}_start`] = String(value.start);
                if (value.end) queryParams[`${key}_end`] = String(value.end);
              } else {
                queryParams[key] = String(value);
              }
            }
          }
        } else {
          // For standard dynamic tables, use JSON filters array
          const tabCondition = getTabFilter();
          const filtersParam = buildFiltersParam(tabCondition, params?.filters, chipFilters);
          if (filtersParam) {
            queryParams.filters = filtersParam;
          }
          // Use active sorts (user-driven) > SavedView sorts > DSL defaultSort
          if (activeSorts.length > 0) {
            queryParams.sortField = activeSorts[0].fieldCode;
            queryParams.sortOrder = activeSorts[0].direction;
          } else if (tableBlock?.defaultSort?.field) {
            queryParams.sortField = tableBlock.defaultSort.field;
            queryParams.sortOrder = String(tableBlock.defaultSort.order || 'desc').toLowerCase();
          }
        }

        // When page uses namedQuery data source, pass queryCode to backend
        if (namedQueryCode) {
          queryParams.queryCode = namedQueryCode;
        }

        const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
          method,
          params: queryParams,
          token: token || undefined,
        });

        if (ResultHelper.isSuccess(result) && result.data) {
          // Handle both paginated ({ records, total, current }) and flat array responses
          const responseData = result.data;
          if (Array.isArray(responseData)) {
            // API returned flat array — client-side pagination
            const start = requestedPageZeroBased * requestedPageSize;
            const sliced = responseData.slice(start, start + requestedPageSize);
            setData(sliced as DynamicEntity[]);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total: responseData.length,
                current: requestedPageNum,
              },
            }));
          } else {
            const records = responseData.records ?? [];
            const currentPage = Number(responseData.page ?? requestedPageNum) || requestedPageNum;
            const total = Number(responseData.total ?? 0);
            setData(records);
            setPageState((prev) => ({
              ...prev,
              pagination: {
                ...prev.pagination,
                total,
                current: currentPage,
              },
            }));
          }
        } else {
          setError(result.desc || t('common.loadDataError') || 'Failed to load data');
        }
      } catch (err) {
        setError(
          err instanceof Error ? err.message : t('common.loadDataError') || 'Failed to load data',
        );
      } finally {
        setLoading(false);
      }
    },
    [
      schema,
      tableName,
      token,
      pagination.current,
      pagination.pageSize,
      getTabFilter,
      buildFiltersParam,
      namedQueryCode,
      tableBlock,
      activeSorts,
      chipFilters,
    ],
  );

  useEffect(() => {
    loadDataRef.current = loadData;
  }, [loadData]);

  // Use unified action handler hook
  // IMPORTANT: Must be declared before any useEffect that references handleAction
  // to avoid temporal dead zone ("Cannot access 'handleAction' before initialization").
  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
    onError: (err) => setError(err.message),
  });

  // Listen for cell-button-click events from Button field renderer (GAP-131)
  useEffect(() => {
    const handler = (e: Event) => {
      const detail = (e as CustomEvent).detail;
      if (detail?.commandCode && detail?.record) {
        handleAction({ code: detail.commandCode, action: 'executeCommand' } as any, detail.record);
      }
    };
    window.addEventListener('cell-button-click', handler);
    return () => window.removeEventListener('cell-button-click', handler);
  }, [handleAction]);

  // Dashboard detection
  const isDashboard = schema?.kind === 'Dashboard';

  // Initial data load - only execute once when schema is loaded (skip for Dashboard pages)
  // Pass current filters (which may include URL filter_* params) for the first load
  useEffect(() => {
    if (schema && !isDashboard) {
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    }
    // Intentionally only react to schema/dashboard changes.
    // Pagination or filter updates are handled by explicit user actions.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [schema, isDashboard]);

  // Handle tab change - reload data with tab filter
  const handleTabChange = useCallback(
    (tabKey: string) => {
      setActiveTab(tabKey);
      // Compute tab filter directly (don't rely on getTabFilter since activeTab is stale in closure)
      let tabCondition: { fieldName: string; operator: string; value: string } | null = null;
      if (schema?.areas) {
        const tabsBlock = Object.values(schema.areas)
          .flatMap((area: any) => area.blocks)
          .find((block) => block.blockType === 'list-tabs');
        const tabDef = (tabsBlock?.tabs as any[])?.find((t: any) => t.key === tabKey);
        if (tabDef?.filter) {
          tabCondition = {
            fieldName: tabDef.filter.fieldName || tabDef.filter.field,
            operator: tabDef.filter.operator || 'EQ',
            value: tabDef.filter.value,
          };
        }
      }

      // Call loadData with pre-built params (bypass getTabFilter to avoid stale closure)
      (async () => {
        try {
          setLoading(true);
          setError(null);

          // Resolve endpoint: API datasource vs standard dynamic table
          let endpoint: string;
          let method: 'get' | 'post' = 'get';
          const isApiDatasource = schema?.dataSource?.type === 'api' && schema.dataSource.endpoint;
          if (isApiDatasource) {
            endpoint = schema!.dataSource!.endpoint!;
            method = (schema!.dataSource!.method as 'get' | 'post') || 'get';
          } else {
            const apiTableName = schema?.modelCode
              ? schema.modelCode.replace(/-/g, '_')
              : tableName;
            endpoint = `${buildApiEndpoint(apiTableName)}/list`;
          }

          // Build query params
          const queryParams: Record<string, any> = {};
          if (isApiDatasource) {
            queryParams.page = 0;
            queryParams.size = pagination.pageSize;
          } else {
            queryParams.pageNum = 1;
            queryParams.pageSize = pagination.pageSize;
          }
          if (isApiDatasource) {
            // API datasource: tab filter as individual query param
            if (tabCondition) {
              queryParams[tabCondition.fieldName] = tabCondition.value;
            }
            if (filters) {
              for (const [key, value] of Object.entries(filters)) {
                if (value == null || value === '') continue;
                if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                  if (value.start) queryParams[`${key}_start`] = String(value.start);
                  if (value.end) queryParams[`${key}_end`] = String(value.end);
                } else {
                  queryParams[key] = String(value);
                }
              }
            }
          } else {
            const filtersParam = buildFiltersParam(tabCondition, filters, chipFilters);
            if (filtersParam) {
              queryParams.filters = filtersParam;
            }
          }

          const result = await fetchResult<PaginationResult<DynamicEntity>>(endpoint, {
            method,
            params: queryParams,
            token: token || undefined,
          });
          if (ResultHelper.isSuccess(result) && result.data) {
            const responseData = result.data;
            if (Array.isArray(responseData)) {
              // API returned flat array — client-side pagination
              const sliced = (responseData as any[]).slice(0, pagination.pageSize);
              setData(sliced as DynamicEntity[]);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total: (responseData as any[]).length,
                  current: 1,
                },
              }));
            } else {
              const records = responseData.records ?? [];
              const currentPage = Number(responseData.page ?? 1) || 1;
              const total = Number(responseData.total ?? 0);
              setData(records);
              setPageState((prev) => ({
                ...prev,
                pagination: {
                  ...prev.pagination,
                  total,
                  current: currentPage,
                },
              }));
            }
          } else {
            setError(result.desc || t('common.loadDataError') || 'Failed to load data');
          }
        } catch (err) {
          setError(
            err instanceof Error ? err.message : t('common.loadDataError') || 'Failed to load data',
          );
        } finally {
          setLoading(false);
        }
      })();
    },
    [schema, buildFiltersParam, filters, pagination.pageSize, tableName, token, t],
  );

  // Evaluate visibleWhen expression against a row record
  const evaluateVisibleWhen = useCallback(
    (visibleWhen: string | undefined, record?: Record<string, any>): boolean => {
      if (!visibleWhen) return true;
      try {
        // Support expressions like "row.field === 'value'" or "['v1','v2'].includes(row.field)"
        const row = record || {};
        const form = record || {};
        // eslint-disable-next-line no-new-func
        const fn = new Function('row', 'form', 'record', `return (${visibleWhen})`);
        return !!fn(row, form, record);
      } catch {
        return true; // Show button if expression evaluation fails
      }
    },
    [],
  );

  // Resolve button display label
  const resolveButtonLabel = useCallback(
    (button: ButtonConfig): string => {
      if (button.content) {
        return getLocalizedText(button.content, locale, t);
      }
      if (button.label) {
        return getLocalizedText(button.label, locale, t);
      }
      // Try plugin-namespaced key from commandCode (e.g., "crm:contact_lead" → "crm.action.contact_lead")
      if (button.commandCode && button.commandCode.includes(':')) {
        const [ns, actionCode] = button.commandCode.split(':');
        const nsKey = `${ns}.action.${actionCode}`;
        const nsResolved = t(nsKey);
        if (nsResolved && nsResolved !== nsKey) return nsResolved;
      }
      if (button.label) {
        const labelStr = typeof button.label === 'string' ? button.label : undefined;
        if (labelStr) {
          const labelResolved = t(labelStr);
          if (labelResolved && labelResolved !== labelStr) return labelResolved;
          return labelStr;
        }
      }
      if (button.action && typeof button.action === 'string') {
        // Map action values to i18n keys
        const actionKeyMap: Record<string, string> = {
          edit: 'update',
          navigate: 'view',
          detail: 'view',
        };
        const i18nAction = actionKeyMap[button.action] || button.action;
        const i18nKey = `action.${i18nAction}`;
        const translated = t(i18nKey);
        if (translated && translated !== i18nKey) {
          return translated;
        }
      }
      // Try button code as i18n key
      const codeKey = `action.${button.code}`;
      const codeResolved = t(codeKey);
      if (codeResolved && codeResolved !== codeKey) return codeResolved;
      return button.code;
    },
    [locale, t],
  );

  // Handle search - using ActionRegistry
  const handleSearch = useCallback(() => {
    actionRegistry.execute('search', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [filters, loadData, navigate, tableName, dataSourceManager, locale, t, token, pagination]);

  // Handle reset - using ActionRegistry
  const handleReset = useCallback(() => {
    actionRegistry.execute('reset', {
      args: {},
      navigate,
      tableName,
      loadData,
      filters,
      setFilters,
      pagination,
      setPagination,
      dataSourceManager,
      locale,
      t,
      token: token || undefined,
      fetchResult,
      buildApiEndpoint,
    });
  }, [loadData, navigate, tableName, dataSourceManager, locale, t, token, filters, pagination]);

  // Handle pagination - P2-1 fix: use destructured pagination
  const handlePageChange = useCallback(
    (page: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: page }));
      loadData({ page: page - 1, filters });
    },
    [filters, loadData, setPagination],
  );

  const handlePageSizeChange = useCallback(
    (pageSize: number) => {
      shouldPersistPaginationToUrlRef.current = true;
      setPagination((prev: typeof pagination) => ({ ...prev, current: 1, pageSize }));
      loadData({ page: 0, size: pageSize, filters });
    },
    [filters, loadData, setPagination],
  );

  // Column header sort toggle: none → asc → desc → none
  // Shift+click appends to multi-sort, regular click replaces
  const toggleSort = useCallback(
    (fieldCode: string, multiSort = false) => {
      setActiveSorts((prev) => {
        const existing = prev.find((s) => s.fieldCode === fieldCode);
        let next: SortConfig[];
        if (!existing) {
          // Add new sort
          const newSort: SortConfig = { fieldCode, direction: 'asc', priority: prev.length };
          next = multiSort ? [...prev, newSort] : [newSort];
        } else if (existing.direction === 'asc') {
          // asc → desc
          next = multiSort
            ? prev.map((s) => (s.fieldCode === fieldCode ? { ...s, direction: 'desc' as const } : s))
            : [{ fieldCode, direction: 'desc', priority: 0 }];
        } else {
          // desc → clear
          next = multiSort ? prev.filter((s) => s.fieldCode !== fieldCode) : [];
        }
        return next;
      });
    },
    [],
  );

  // Re-fetch data when sorts or chip filters change
  useEffect(() => {
    if (!schema) return;
    loadData({ page: 0, size: pagination.pageSize, filters });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSorts, chipFilters]);

  // Auto-save sorts to SavedView (debounced)
  useEffect(() => {
    if (!schema) return;
    autoSave({ sorts: activeSorts });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeSorts]);

  const handleImportComplete = useCallback(() => {
    setImportOpen(false);
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Bulk selection handlers
  const toggleRowSelection = useCallback((recordId: string) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(recordId)) next.delete(recordId);
      else next.add(recordId);
      return next;
    });
  }, []);

  const toggleSelectAll = useCallback(() => {
    setSelectedIds((prev) => {
      if (prev.size === data.length && data.length > 0) return new Set();
      return new Set(data.map((r) => r.pid || r.id || '').filter(Boolean));
    });
  }, [data]);

  const handleBulkDelete = useCallback(
    async (ids: string[]) => {
      const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
      const resp = await fetchResult(`/api/dynamic/${mc}/batch`, {
        method: 'delete',
        params: ids,
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(resp)) {
        setSelectedIds(new Set());
        loadData({ page: 0, size: pagination.pageSize, filters });
      }
    },
    [schema?.modelCode, tableName, token, loadData, pagination.pageSize, filters],
  );

  const handleBulkEditComplete = useCallback(() => {
    setBulkEditOpen(false);
    setSelectedIds(new Set());
    loadData({ page: 0, size: pagination.pageSize, filters });
  }, [loadData, pagination.pageSize, filters]);

  // Render filter field using utility (P1-5 fix: extract repeated logic)
  const renderSmartField = useMemo(
    () => createFieldRenderer(filters, setFilters, pageContext),
    [filters, pageContext],
  );

  const inferValueType = useCallback(
    (column: ColumnConfig, value: any): ColumnConfig['valueType'] => {
      if (column.valueType) {
        return column.valueType;
      }
      const field = column.field || '';
      // REFERENCE field: ends with _id and has _display suffix in record
      if (field.endsWith('_id')) {
        return 'reference';
      }
      if (field.endsWith('_at')) {
        return 'datetime';
      }
      if (field.endsWith('_date')) {
        return 'date';
      }
      if (field.endsWith('_time')) {
        return 'time';
      }
      if (typeof value === 'string' && /\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}/.test(value)) {
        return 'datetime';
      }
      // Detect boolean values (native boolean or string "true"/"false")
      if (typeof value === 'boolean' || value === 'true' || value === 'false') {
        return 'boolean' as any;
      }
      return undefined;
    },
    [],
  );

  // Render cell content using CellRendererRegistry
  const renderCellContent = useCallback(
    (column: ColumnConfig, record: DynamicEntity, rowIndex: number) => {
      const value = record[column.field];
      const effectiveValueType = inferValueType(column, value);

      // Null/undefined handling
      if (value === null || value === undefined) {
        return <span className="text-gray-400">-</span>;
      }

      // If dictCode exists, try to translate value to label
      if (column.dictCode) {
        const dictItems = dictDataCache.current.get(column.dictCode);
        if (dictItems) {
          const item = dictItems.find((i) => String(i.value) === String(value));
          if (item) {
            // Display dict label with tag style, color from extension.color
            const tagColor = item.extension?.color || 'blue';
            const colorMap: Record<string, string> = {
              gray: 'bg-gray-100 text-gray-800',
              red: 'bg-red-100 text-red-800',
              orange: 'bg-orange-100 text-orange-800',
              yellow: 'bg-yellow-100 text-yellow-800',
              green: 'bg-green-100 text-green-800',
              blue: 'bg-blue-100 text-blue-800',
              indigo: 'bg-indigo-100 text-indigo-800',
              purple: 'bg-purple-100 text-purple-800',
              pink: 'bg-pink-100 text-pink-800',
              cyan: 'bg-cyan-100 text-cyan-800',
            };
            const colorCls = colorMap[tagColor] || colorMap.blue;
            return (
              <span className={`inline-flex rounded-full px-2 py-1 text-xs font-medium ${colorCls}`}>
                {item.label}
              </span>
            );
          }
        }
        // Dict not loaded or no match — show raw value
        return String(value);
      }

      // Handle custom render expressions first (if runtime is available)
      if (typeof column.render === 'string' && runtime) {
        try {
          const context = runtime.getContext();
          const evaluator = runtime.getEvaluator();
          const rendered = evaluator.evaluateTemplate(column.render, {
            ...context,
            row: record,
          });
          return <span dangerouslySetInnerHTML={{ __html: sanitizeHtml(rendered) }} />;
        } catch (err) {
          console.error('Render expression failed:', err);
        }
      }

      // Use CellRendererRegistry for valueType-based rendering
      return cellRendererRegistry.render(effectiveValueType, {
        value,
        record,
        column: {
          field: column.field,
          label: typeof column.label === 'string' ? column.label : undefined,
          valueType: effectiveValueType,
          format: column.format,
          dateTimeFormats,
          render: column.render,
          tagMap: (column as any).tagMap,
        },
        expressionContext: pageContext,
        locale,
        t,
        rowIndex,
      });
    },
    [runtime, pageContext, locale, t, inferValueType, dateTimeFormats],
  );

  // Inline edit: save a single field value via dynamic data PUT API
  const handleInlineSave = useCallback(
    async (field: string, value: any, record: Record<string, any>) => {
      const pid = record.pid || record.id;
      if (!pid) throw new Error('Record has no pid/id');
      const slug = (schema?.modelCode || tableName.replace(/-/g, '_')).replace(/_/g, '-');
      const result = await fetchResult<any>(`/api/dynamic/${slug}/${pid}`, {
        method: 'put',
        token: token || undefined,
        params: { [field]: value },
      });
      if (!ResultHelper.isSuccess(result)) {
        throw new Error(result.desc || 'Save failed');
      }
      // Refresh list data
      loadDataRef.current?.({ page: pagination.current - 1, size: pagination.pageSize, filters });
    },
    [schema?.modelCode, tableName, token, pagination, filters],
  );

  // Fetch namedQuery filter schema when page uses namedQuery data source
  const [nqFilterFields, setNqFilterFields] = useState<any[] | null>(null);
  useEffect(() => {
    if (!namedQueryCode || !token) return;
    const fetchFilterSchema = async () => {
      try {
        const result = await fetchResult<any[]>(
          `${buildApiEndpoint(schema?.modelCode?.replace(/-/g, '_') || tableName)}/filter-schema?queryCode=${encodeURIComponent(namedQueryCode)}`,
          { method: 'get', token: token || undefined },
        );
        if (ResultHelper.isSuccess(result) && result.data) {
          setNqFilterFields(result.data);
        }
      } catch (err) {
        console.warn('[ListPageContent] Failed to fetch namedQuery filter schema:', err);
      }
    };
    fetchFilterSchema();
  }, [namedQueryCode, token, tableName]);

  // Extract regions from schema (UnifiedSchema areas.blocks)
  // NOTE: These must be computed before early returns to keep hook count stable
  const allBlocks = useMemo(() => {
    if (schema?.areas) {
      return Object.values(schema.areas).flatMap((area: any) => area.blocks);
    }
    return [];
  }, [schema]);

  // Map NamedQuery uiComponent to SmartField component name
  const mapNqComponent = useCallback((uiComponent?: string): string => {
    switch (uiComponent) {
      case 'number':
      case 'numberRange':
        return 'SmartNumber';
      case 'select':
        return 'SmartSelect';
      case 'dateRange':
      case 'date':
        return 'SmartDate';
      case 'switch':
        return 'SmartSwitch';
      case 'search':
      case 'cascader':
      case 'userPicker':
        return 'SmartInput';
      default:
        return 'SmartInput';
    }
  }, []);

  const resolveModelFieldLabel = useCallback(
    (fieldCode: string) => {
      const mc = schema?.modelCode || tableName.replace(/-/g, '_');
      const modelKey = `model.${mc}.${fieldCode}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel && modelLabel !== modelKey) {
        return modelLabel;
      }
      const fieldKey = `field.${fieldCode}.label`;
      const fieldLabel = t(fieldKey);
      if (fieldLabel && fieldLabel !== fieldKey) {
        return fieldLabel;
      }
      return fieldCode;
    },
    [schema?.modelCode, tableName, t],
  );

  const filterBlock = useMemo(() => {
    const tableBlock = allBlocks.find((b: any) => b.blockType === 'data-table');
    const rawColumns = (tableBlock as any)?.table?.columns || (tableBlock as any)?.columns;
    const columns: ColumnConfig[] = Array.isArray(rawColumns) ? rawColumns : [];
    const columnLabelMap = new Map<string, any>();
    columns.forEach((col) => {
      if (!col.field || col.isActionColumn) return;
      if (col.label) {
        columnLabelMap.set(col.field, col.label);
      }
    });

    const patchFilterFields = (fields: FieldConfig[]): FieldConfig[] =>
      fields.map((field) => {
        if (field.label) return field;
        const columnLabel = columnLabelMap.get(field.field);
        return {
          ...field,
          label: columnLabel || resolveModelFieldLabel(field.field),
        };
      });

    const found = allBlocks.find((block) => block.blockType === 'filter-form');
    if (found) {
      const foundFields = Array.isArray((found as any).fields) ? (found as any).fields : [];
      return {
        ...found,
        fields: patchFilterFields(foundFields),
      };
    }

    // NamedQuery data source: synthesize filter-form from param-schema
    if (namedQueryCode && nqFilterFields && nqFilterFields.length > 0) {
      return {
        blockType: 'filter-form' as const,
        fields: nqFilterFields.map((f: any) => ({
          field: f.fieldCode,
          label:
            f.displayName || columnLabelMap.get(f.fieldCode) || resolveModelFieldLabel(f.fieldCode),
          component: mapNqComponent(f.uiComponent),
          props: {
            ...(f.placeholder ? { placeholder: f.placeholder } : {}),
            ...(f.defaultValue ? { defaultValue: f.defaultValue } : {}),
          },
        })),
        buttons: [],
      };
    }

    // Auto-synthesize from data-table searchFields
    if (
      tableBlock &&
      (tableBlock as any).searchFields &&
      Array.isArray((tableBlock as any).searchFields)
    ) {
      const searchFields = (tableBlock as any).searchFields as string[];
      return {
        blockType: 'filter-form' as const,
        fields: searchFields.map((fieldCode: string) => ({
          field: fieldCode,
          label: columnLabelMap.get(fieldCode) || resolveModelFieldLabel(fieldCode),
          component: 'SmartInput',
          props: {},
        })),
        buttons: [],
      };
    }

    return undefined;
  }, [allBlocks, namedQueryCode, nqFilterFields, mapNqComponent, resolveModelFieldLabel]);

  const actionBlock = useMemo(() => {
    const found = allBlocks.find(
      (block) => block.blockType === 'form-buttons' || block.blockType === 'toolbar',
    );
    return found;
  }, [allBlocks]);

  // System fields on every dynamic entity — not in DSL but available in API response
  const SYSTEM_FIELD_DEFS: ColumnConfig[] = [
    { field: 'created_at', label: t('common.field.created_at') !== 'common.field.created_at' ? t('common.field.created_at') : 'Created At', valueType: 'datetime' as any },
    { field: 'updated_at', label: t('common.field.updated_at') !== 'common.field.updated_at' ? t('common.field.updated_at') : 'Updated At', valueType: 'datetime' as any },
    { field: 'created_by', label: t('common.field.created_by') !== 'common.field.created_by' ? t('common.field.created_by') : 'Created By' },
    { field: 'updated_by', label: t('common.field.updated_by') !== 'common.field.updated_by' ? t('common.field.updated_by') : 'Updated By' },
  ];

  // Get columns: prefer table.columns, fallback to block-level columns
  // Then apply SavedView column visibility/order overrides
  const tableColumns: ColumnConfig[] = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];

    // If the block has rowActions defined at block level, synthesize an action column
    const rowActions = tableBlock.rowActions;
    let baseCols = cols as ColumnConfig[];
    if (rowActions && rowActions.length > 0) {
      const hasActionCol = cols.some((c: any) => c.isActionColumn);
      if (!hasActionCol) {
        baseCols = [
          ...cols,
          { field: '_actions', isActionColumn: true, buttons: rowActions },
        ] as ColumnConfig[];
      }
    }

    // Apply SavedView column config (visibility + order + width)
    const viewColumns = currentView?.viewConfig?.columns;
    if (!viewColumns || viewColumns.length === 0) return baseCols;

    const viewColMap = new Map(viewColumns.map((vc) => [vc.fieldCode, vc]));
    // Filter visible columns and apply order
    // Include system fields that are explicitly enabled in viewConfig
    const baseFields = new Set(baseCols.map((c) => c.field));
    const sysCols = SYSTEM_FIELD_DEFS.filter((sf) => {
      const vc = viewColMap.get(sf.field);
      return vc && vc.visible !== false && !baseFields.has(sf.field);
    });
    const allCols = [...baseCols, ...sysCols];
    const visibleCols = allCols
      .map((col) => {
        const vc = viewColMap.get(col.field);
        if (vc && vc.visible === false) return null;
        // Apply width override
        if (vc?.width) {
          return { ...col, width: vc.width };
        }
        return col;
      })
      .filter((col): col is ColumnConfig => col !== null);

    // Sort by SavedView order if specified
    const hasOrder = viewColumns.some((vc) => vc.order !== undefined && vc.order !== null);
    if (hasOrder) {
      visibleCols.sort((a, b) => {
        const orderA = viewColMap.get(a.field)?.order ?? 999;
        const orderB = viewColMap.get(b.field)?.order ?? 999;
        return orderA - orderB;
      });
    }

    return visibleCols;
  }, [tableBlock, currentView]);

  // Row click → navigate to detail page or open preview drawer
  const handleRowClick = useCallback((record: Record<string, unknown>) => {
    const pid = record.pid as string | undefined;
    if (!pid) return;

    // Check DSL config for navigation preference (on schema or table block)
    const detailNav = (schema as any)?.options?.detailNavigation
      || (tableBlock as any)?.onRowClick;
    if (detailNav === 'navigate' || detailNav === 'page') {
      // Support custom URL template with {field} placeholders
      const detailUrl = (schema as any)?.options?.detailUrl
        || (tableBlock as any)?.detailUrl;
      if (detailUrl) {
        const resolved = detailUrl.replace(/\{(\w+)\}/g, (_: string, key: string) =>
          String(record[key] ?? ''));
        navigate(resolved);
        return;
      }
      const detailPageKey = (schema as any)?.options?.detailPageKey;
      if (detailPageKey) {
        navigate(`/dynamic/${detailPageKey.replace(/_/g, '-')}/view/${pid}`);
      } else {
        navigate(`/dynamic/${tableName.replace(/_/g, '-')}/view/${pid}`);
      }
      return;
    }

    setPreviewRecordId(pid);
  }, [schema, tableBlock, tableName, navigate]);

  // All column definitions for ColumnSettingsPanel (with labels)
  const allColumnDefs = useMemo(() => {
    if (!tableBlock) return [];
    const cols = (tableBlock as BlockConfig).table?.columns || tableBlock.columns;
    if (!Array.isArray(cols)) return [];
    const dslCols = (cols as ColumnConfig[])
      .filter((c) => !c.isActionColumn)
      .map((col) => ({
        field: col.field,
        label: col.label
          ? typeof col.label === 'string'
            ? col.label
            : getLocalizedText(col.label, locale, t)
          : (() => {
              const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
              const modelKey = `model.${mc}.${col.field}.label`;
              const modelLabel = t(modelKey);
              if (modelLabel !== modelKey) return modelLabel;
              const fieldKey = `field.${col.field}.label`;
              const fieldLabel = t(fieldKey);
              if (fieldLabel !== fieldKey) return fieldLabel;
              const commonKey = `common.field.${col.field}`;
              const commonLabel = t(commonKey);
              return commonLabel !== commonKey ? commonLabel : col.field;
            })(),
      }));
    // Append system fields (hidden by default in ColumnSettingsPanel)
    const sysDefs = SYSTEM_FIELD_DEFS.map((sf) => ({
      field: sf.field,
      label: typeof sf.label === 'string' ? sf.label : sf.field,
    }));
    return [...dslCols, ...sysDefs];
  }, [tableBlock, locale, t, tableName, schema?.modelCode]);

  // Auto-create a personal SavedView if none exists, then update config
  const ensureViewAndUpdateConfig = useCallback(
    async (config: Partial<import('~/smart/types/savedView').ViewConfig>) => {
      try {
        if (currentView) {
          await updateViewConfig(config);
        } else {
          // Auto-create a personal SavedView with the config
          await createView({
            name: 'My View',
            modelCode,
            pageKey,
            scope: 'personal',
            viewType: 'table',
            viewConfig: config,
            isDefault: true,
          });
        }
      } catch (err) {
        console.error('[ListPageContent] Failed to save view config:', err);
      }
    },
    [currentView, updateViewConfig, createView, modelCode, pageKey],
  );

  const { autoSave } = useAutoSaveView({
    currentView,
    updateViewConfig: async (config) => {
      await ensureViewAndUpdateConfig(config);
      return currentView!;
    },
  });

  // Handle column settings save -> update SavedView
  const handleColumnSettingsSave = useCallback(
    async (columns: ViewColumnConfig[]) => {
      await ensureViewAndUpdateConfig({ columns });
    },
    [ensureViewAndUpdateConfig],
  );

  // Row height from current view config (with fallback)
  const effectiveRowHeight: RowHeight = currentView?.viewConfig?.rowHeight || DEFAULT_ROW_HEIGHT;
  const rowHeightCfg = ROW_HEIGHT_CONFIG[effectiveRowHeight];

  // Handle row height change -> update SavedView
  const handleRowHeightChange = useCallback(
    async (height: RowHeight) => {
      await ensureViewAndUpdateConfig({ rowHeight: height });
    },
    [ensureViewAndUpdateConfig],
  );

  // Quick filter handler — toggle on/off, apply filter and reload
  const handleQuickFilter = useCallback(
    (key: QuickFilterKey) => {
      if (activeQuickFilter === key) {
        // Toggle off — clear filter and reload
        setActiveQuickFilter(null);
        setFilters({});
        loadData({ page: 0, size: pagination.pageSize, filters: {} });
        return;
      }
      setActiveQuickFilter(key);
      const today = new Date().toISOString().split('T')[0];
      const weekAgo = new Date(Date.now() - 7 * 86400000).toISOString().split('T')[0];
      let qf: Record<string, any> = {};
      switch (key) {
        case 'my_records':
          if (user?.id) {
            const createdBy = Number.parseInt(String(user.id), 10);
            if (!Number.isNaN(createdBy)) {
              qf = { created_by: createdBy };
            }
          }
          break;
        case 'created_today':
          qf = { created_at: { start: today, end: today + 'T23:59:59' } };
          break;
        case 'modified_this_week':
          qf = { updated_at: { start: weekAgo, end: today + 'T23:59:59' } };
          break;
      }
      setFilters(qf);
      loadData({ page: 0, size: pagination.pageSize, filters: qf });
    },
    [activeQuickFilter, user, setFilters, loadData, pagination.pageSize],
  );

  // Save current filters to SavedView
  const handleSaveFilters = useCallback(async () => {
    const viewFilters = Object.entries(filters)
      .filter(([, v]) => v != null && v !== '')
      .map(([field, value]) => ({
        fieldCode: field,
        operator: 'eq' as const,
        value: String(value),
      }));
    await ensureViewAndUpdateConfig({ filters: viewFilters });
  }, [filters, ensureViewAndUpdateConfig]);

  const loadCurrentInviteCode = useCallback(async () => {
    if (!isTenantMemberPage) return;
    try {
      const result = await fetchResult<InviteCodeData | null>('/api/tenant/invite-code/current', {
        method: 'get',
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(result)) {
        setInviteCodeData(result.data ?? null);
      }
    } catch {
      setInviteCodeData(null);
    }
  }, [isTenantMemberPage, token]);

  useEffect(() => {
    void loadCurrentInviteCode();
  }, [loadCurrentInviteCode]);

  const handleGenerateInviteCode = useCallback(async () => {
    setInviteLoading(true);
    try {
      const result = await fetchResult<string>('/api/tenant/invite-code/generate?expiryDays=7', {
        method: 'post',
        token: token || undefined,
      });
      if (!ResultHelper.isSuccess(result) || !result.data) {
        throw new Error(result.desc || result.message || 'Failed to generate invite code');
      }
      const current = await fetchResult<InviteCodeData | null>('/api/tenant/invite-code/current', {
        method: 'get',
        token: token || undefined,
      });
      if (ResultHelper.isSuccess(current) && current.data) {
        setInviteCodeData(current.data);
      } else {
        setInviteCodeData({ code: result.data });
      }
      showSuccessToast('Invite code generated');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to generate invite code';
      showErrorToast(message);
    } finally {
      setInviteLoading(false);
    }
  }, [showErrorToast, showSuccessToast, token]);

  const handleRevokeInviteCode = useCallback(async () => {
    if (!inviteCodeData?.code) return;
    setInviteLoading(true);
    try {
      const result = await fetchResult<boolean>(
        `/api/tenant/invite-code/revoke?code=${encodeURIComponent(inviteCodeData.code)}`,
        {
          method: 'post',
          token: token || undefined,
        },
      );
      if (!ResultHelper.isSuccess(result) || result.data !== true) {
        throw new Error(result.desc || result.message || 'Failed to revoke invite code');
      }
      setInviteCodeData(null);
      showSuccessToast('Invite code revoked');
    } catch (err) {
      const message = err instanceof Error ? err.message : 'Failed to revoke invite code';
      showErrorToast(message);
    } finally {
      setInviteLoading(false);
    }
  }, [inviteCodeData?.code, showErrorToast, showSuccessToast, token]);

  const listTabsBlock = useMemo(() => {
    const found = allBlocks.find((block) => block.blockType === 'list-tabs');
    return found;
  }, [allBlocks]);

  // Error handling — local errors only (schema loading errors handled by DynamicPageRenderer)
  if (error) {
    return (
      <ErrorAlert
        error={error}
        onRetry={() => {
          setError(null);
          window.location.reload();
        }}
      />
    );
  }

  // Dashboard rendering — each block independently fetches its own data
  if (isDashboard) {
    const dashBlocks = schema.areas
      ? Object.values(schema.areas).flatMap((area: any) => area.blocks || [])
      : [];
    return (
      <DataSourceProvider manager={dataSourceManager}>
        <div className="mx-auto w-full px-6 py-8" data-testid={deriveTestId('dashboard', modelCode, 'container')}>
          <h2 className="mb-6 text-lg font-medium text-gray-900">
            {schema.title ? getLocalizedText(schema.title, locale, t) : tableName}
          </h2>
          <div className="grid grid-cols-12 gap-4">
            {dashBlocks.map((block: any) => {
              const isChartBlock = block.blockType === 'chart';
              const isStatCard = block.blockType === 'stat-card';
              return (
                <div
                  key={block.id}
                  className={isChartBlock || isStatCard ? '' : 'overflow-hidden rounded-lg bg-white shadow-sm'}
                  style={{ gridColumn: `span ${block.layout?.colSpan || 12}` }}
                  data-testid={`dashboard-block-${block.id}`}
                  data-ab-testid={deriveTestId('dashboard', modelCode, 'block', block.id)}
                  data-block-id={block.id}
                >
                  {isChartBlock ? (
                    <React.Suspense
                      fallback={
                        <div className="flex h-64 items-center justify-center rounded-lg border border-gray-200 bg-white p-4">
                          <div className="h-5 w-5 animate-spin rounded-full border-2 border-blue-500 border-t-transparent" />
                        </div>
                      }
                    >
                      <DashboardChartBlock
                        block={block}
                        locale={locale}
                        onDrillDown={(config) => {
                          if (config.action === 'navigate' && config.targetPage) {
                            const params = new URLSearchParams();
                            if (config.paramMapping) {
                              Object.entries(config.paramMapping).forEach(([k, v]) => {
                                params.set(`filter_${k}`, String(v));
                              });
                            }
                            const qs = params.toString();
                            navigate(`/dynamic/${config.targetPage}${qs ? `?${qs}` : ''}`);
                          }
                        }}
                      />
                    </React.Suspense>
                  ) : isStatCard ? (
                    <DashboardStatCard block={block} token={token} locale={locale} t={t} />
                  ) : (
                    <>
                      <div className="border-b border-gray-200 bg-gray-50 px-4 py-3">
                        <h3 className="text-sm font-medium text-gray-700">
                          {block.title ? getLocalizedText(block.title, locale, t) : block.id}
                        </h3>
                      </div>
                      <DashboardBlockTable block={block} token={token} />
                    </>
                  )}
                </div>
              );
            })}
          </div>
        </div>
      </DataSourceProvider>
    );
  }

  return (
    <DataSourceProvider manager={dataSourceManager}>
      <div className="mx-auto w-full px-6 py-8" data-testid="dynamic-list" data-ab-testid={deriveTestId('list', modelCode, 'container')}>
        <div className="rounded-lg bg-white shadow-sm">
          {/* Page title, view selector, and action buttons */}
          <div className="border-b border-gray-200 px-6 py-4">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-3">
                <h2 className="text-lg font-medium text-gray-900">
                  {schema.title ? getLocalizedText(schema.title, locale, t) : tableName}
                </h2>
                {schema.enableMultiView && (
                  <ViewSelector
                    views={savedViews}
                    currentView={currentView}
                    onSelectView={(pid) => {
                      selectView(pid);
                      const view = savedViews.find((v) => v.pid === pid);
                      if (view?.viewType && view.viewType !== 'table') {
                        setActiveViewType(view.viewType as ViewType);
                      }
                    }}
                    onCreateView={(viewType) => {
                      if (viewType) {
                        setActiveViewType(viewType);
                      }
                      setStartCreateViewMode(true);
                      setViewManageOpen(true);
                    }}
                    onManageViews={() => {
                      setStartCreateViewMode(false);
                      setViewManageOpen(true);
                    }}
                    loading={viewsLoading}
                    activeViewType={activeViewType}
                    onViewTypeChange={(vt) => {
                      setActiveViewType(vt);
                      // Auto-select first saved view of this type if none currently selected
                      if (vt !== 'table' && (!currentView || currentView.viewType !== vt)) {
                        const match = savedViews.find((v) => v.viewType === vt);
                        if (match) selectView(match.pid);
                      }
                    }}
                  />
                )}
              </div>
              <div className="print-hide flex items-center gap-2" data-print="hide" data-testid={deriveTestId('list', modelCode, 'toolbar')}>
                {isTenantMemberPage && (
                  <button
                    type="button"
                    data-testid="invite-section"
                    onClick={() => setInviteDialogOpen(true)}
                    className="inline-flex items-center gap-1.5 rounded-md bg-emerald-600 px-3 py-1.5 text-sm font-medium text-white shadow-sm transition-colors duration-150 hover:bg-emerald-700"
                  >
                    Invite
                  </button>
                )}
                {(actionBlock?.buttons || []).map((button: ButtonConfig) => (
                  <button
                    type="button"
                    key={button.code}
                    data-testid={`toolbar-btn-${button.code}`}
                    onClick={() => handleAction(button)}
                    className={`rounded-md px-4 py-2 text-sm font-medium ${
                      button.primary || button.variant === 'primary'
                        ? 'bg-blue-600 text-white hover:bg-blue-700'
                        : button.variant === 'danger' || button.danger
                          ? 'bg-red-600 text-white hover:bg-red-700'
                          : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
                    }`}
                  >
                    {resolveButtonLabel(button)}
                  </button>
                ))}
                <ToolbarMoreMenu
                  onImport={() => setImportOpen(true)}
                  modelCode={modelCode}
                  filters={(() => {
                    const conditions: Array<{ field: string; operator: string; value: unknown }> =
                      [];
                    const tabCondition = getTabFilter();
                    if (tabCondition) {
                      conditions.push({
                        field: tabCondition.fieldName,
                        operator: tabCondition.operator,
                        value: tabCondition.value,
                      });
                    }
                    if (filters) {
                      for (const [key, value] of Object.entries(filters)) {
                        if (value == null || value === '') continue;
                        if (typeof value === 'object' && ('start' in value || 'end' in value)) {
                          if (value.start)
                            conditions.push({
                              field: key,
                              operator: 'gte',
                              value: String(value.start),
                            });
                          if (value.end)
                            conditions.push({
                              field: key,
                              operator: 'lte',
                              value: String(value.end),
                            });
                        } else {
                          conditions.push({ field: key, operator: 'EQ', value: String(value) });
                        }
                      }
                    }
                    return conditions.length > 0 ? conditions : undefined;
                  })()}
                />
              </div>
            </div>
          </div>

          {isTenantMemberPage && inviteDialogOpen && (
            <div
              role="dialog"
              aria-modal="true"
              data-testid="invite-dialog"
              className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4"
            >
              <div className="w-full max-w-md rounded-lg bg-white p-6 shadow-xl">
                <div className="mb-4 flex items-center justify-between">
                  <h3 className="text-lg font-semibold text-gray-900">Member Invite</h3>
                  <button
                    type="button"
                    onClick={() => setInviteDialogOpen(false)}
                    className="text-sm text-gray-500 hover:text-gray-700"
                  >
                    Close
                  </button>
                </div>
                <div className="space-y-4">
                  {inviteCodeData?.code ? (
                    <>
                      <div className="rounded-md bg-gray-50 p-3">
                        <div className="mb-1 text-xs font-medium text-gray-500">
                          Current Invite Code
                        </div>
                        <div className="font-mono text-lg tracking-wider text-gray-900">
                          {inviteCodeData.code}
                        </div>
                        {inviteCodeData.expiredAt && (
                          <div className="mt-1 text-xs text-gray-500">
                            Expires at {new Date(inviteCodeData.expiredAt).toLocaleString()}
                          </div>
                        )}
                      </div>
                      <div className="flex gap-2">
                        <button
                          type="button"
                          onClick={handleGenerateInviteCode}
                          disabled={inviteLoading}
                          className="flex-1 rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
                        >
                          Refresh
                        </button>
                        <button
                          type="button"
                          onClick={handleRevokeInviteCode}
                          disabled={inviteLoading}
                          className="flex-1 rounded-md bg-red-600 px-3 py-2 text-sm font-medium text-white hover:bg-red-700 disabled:opacity-60"
                        >
                          Revoke
                        </button>
                      </div>
                    </>
                  ) : (
                    <div className="space-y-3">
                      <div className="rounded-md bg-amber-50 p-3 text-sm text-amber-800">
                        No active invite code. Generate one to invite members into this tenant.
                      </div>
                      <button
                        type="button"
                        onClick={handleGenerateInviteCode}
                        disabled={inviteLoading}
                        className="w-full rounded-md bg-blue-600 px-3 py-2 text-sm font-medium text-white hover:bg-blue-700 disabled:opacity-60"
                      >
                        Generate Invite Code
                      </button>
                    </div>
                  )}
                </div>
              </div>
            </div>
          )}

          {/* List Tabs */}
          {listTabsBlock?.tabs && (listTabsBlock.tabs as any[]).length > 0 && (
            <div className="border-b border-gray-200 px-6">
              <nav className="-mb-px flex space-x-6" aria-label="Tabs">
                {(listTabsBlock.tabs as any[]).map((tab: any) => (
                  <button
                    key={tab.key}
                    data-testid={`tab-${tab.key}`}
                    onClick={() => handleTabChange(tab.key)}
                    className={`border-b-2 px-1 py-3 text-sm font-medium whitespace-nowrap ${
                      activeTab === tab.key
                        ? 'border-blue-500 text-blue-600'
                        : 'border-transparent text-gray-500 hover:border-gray-300 hover:text-gray-700'
                    }`}
                  >
                    {typeof tab.label === 'string'
                      ? tab.label
                      : getLocalizedText(tab.label, locale, t)}
                  </button>
                ))}
              </nav>
            </div>
          )}

          {/* Filter area - Using Smart Components with collapse/expand (hidden in print) */}
          {filterBlock && filterBlock.fields && filterBlock.fields.length > 0 && (
            <div
              data-testid="search-area"
              data-ab-testid={deriveTestId('list', modelCode, 'filters')}
              className="print-hide border-b border-gray-200 bg-gray-50 px-6 py-4"
              data-print="hide"
              onKeyDown={(e) => {
                if (e.key === 'Enter') {
                  e.preventDefault();
                  handleSearch();
                }
              }}
            >
              <div
                className={`grid grid-cols-1 gap-4 md:grid-cols-12 ${
                  !filtersExpanded && filterBlock.fields.length > 4
                    ? 'max-h-[72px] overflow-hidden'
                    : ''
                }`}
              >
                {filterBlock.fields.map((field: FieldConfig) => (
                  <div
                    key={field.field}
                    className="min-w-0"
                    style={{
                      gridColumn: `span ${Math.min(Math.max(field.layout?.colSpan || 4, 1), 12)}`,
                    }}
                  >
                    {renderSmartField(
                      field.component === 'SmartSelect' && field.props?.inline === undefined
                        ? { ...field, props: { ...(field.props || {}), inline: true } }
                        : field,
                    )}
                  </div>
                ))}
              </div>
              <div className="mt-4 flex items-center justify-between">
                {/* Expand/collapse toggle */}
                <div>
                  {filterBlock.fields.length > 4 && (
                    <button
                      type="button"
                      data-testid="filter-toggle"
                      onClick={() => setFiltersExpanded((prev) => !prev)}
                      className="flex items-center gap-1 text-sm text-blue-600 hover:text-blue-800"
                    >
                      {filtersExpanded ? (
                        <>
                          <svg
                            className="h-4 w-4"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M5 15l7-7 7 7"
                            />
                          </svg>
                          {t('action.collapse') !== 'action.collapse'
                            ? t('action.collapse')
                            : 'Collapse'}
                        </>
                      ) : (
                        <>
                          <svg
                            className="h-4 w-4"
                            fill="none"
                            stroke="currentColor"
                            viewBox="0 0 24 24"
                          >
                            <path
                              strokeLinecap="round"
                              strokeLinejoin="round"
                              strokeWidth={2}
                              d="M19 9l-7 7-7-7"
                            />
                          </svg>
                          {t('action.expand') !== 'action.expand' ? t('action.expand') : 'Expand'}
                        </>
                      )}
                    </button>
                  )}
                </div>
                <div className="flex items-center space-x-2">
                  {/* Save current filters to view */}
                  {currentView && (
                    <button
                      type="button"
                      data-testid="filter-save"
                      onClick={handleSaveFilters}
                      className="rounded-md px-3 py-2 text-sm text-gray-500 transition-colors hover:bg-gray-100 hover:text-gray-700"
                      title="Save filters to current view"
                    >
                      <svg
                        className="mr-1 inline h-4 w-4"
                        fill="none"
                        stroke="currentColor"
                        viewBox="0 0 24 24"
                      >
                        <path
                          strokeLinecap="round"
                          strokeLinejoin="round"
                          strokeWidth={2}
                          d="M8 7H5a2 2 0 00-2 2v9a2 2 0 002 2h14a2 2 0 002-2V9a2 2 0 00-2-2h-3m-1 4l-3 3m0 0l-3-3m3 3V4"
                        />
                      </svg>
                    </button>
                  )}
                  {/* DSL-driven buttons if defined, otherwise fallback to defaults */}
                  {filterBlock.buttons && filterBlock.buttons.length > 0 ? (
                    filterBlock.buttons.map((button: ButtonConfig) => (
                      <button
                        type="button"
                        key={button.code}
                        data-testid={`filter-btn-${button.code}`}
                        onClick={() => handleAction(button)}
                        className={`rounded-md px-4 py-2 ${
                          button.primary || button.variant === 'primary'
                            ? 'bg-blue-600 text-white hover:bg-blue-700'
                            : button.variant === 'danger' || button.danger
                              ? 'bg-red-600 text-white hover:bg-red-700'
                              : 'border border-gray-300 bg-white text-gray-600 hover:bg-gray-50'
                        }`}
                      >
                        {resolveButtonLabel(button)}
                      </button>
                    ))
                  ) : (
                    <>
                      {/* Default buttons for backward compatibility */}
                      <button
                        type="button"
                        data-testid="filter-reset"
                        onClick={handleReset}
                        className="rounded-md border border-gray-300 bg-white px-4 py-2 text-gray-600 hover:bg-gray-50"
                      >
                        {t('action.reset')}
                      </button>
                      <button
                        type="button"
                        data-testid="filter-search"
                        onClick={handleSearch}
                        className="rounded-md bg-blue-600 px-4 py-2 text-white hover:bg-blue-700"
                      >
                        {t('action.search')}
                      </button>
                    </>
                  )}
                </div>
              </div>
            </div>
          )}

          {activeViewType === 'table' ? (
            <>
              {/* Table toolbar: quick filters (left) + row height + column settings (right) */}
              <div
                className="print-hide flex items-center justify-between border-b border-gray-100 px-6 py-2"
                data-print="hide"
              >
                {/* Quick filter chips */}
                <div className="flex gap-1.5" data-testid="quick-filters">
                  {([
                    { key: 'my_records' as QuickFilterKey, label: 'My Records', icon: '👤' },
                    { key: 'created_today' as QuickFilterKey, label: 'Created Today', icon: '📅' },
                    { key: 'modified_this_week' as QuickFilterKey, label: 'Modified This Week', icon: '🕐' },
                  ]).map((qf) => (
                    <button
                      key={qf.key}
                      type="button"
                      onClick={() => handleQuickFilter(qf.key)}
                      data-testid={`quick-filter-${qf.key}`}
                      className={`rounded-full px-3 py-1 text-xs font-medium transition-colors ${
                        activeQuickFilter === qf.key
                          ? 'bg-blue-100 text-blue-700 ring-1 ring-blue-400'
                          : 'bg-gray-100 text-gray-600 hover:bg-gray-200'
                      }`}
                    >
                      {qf.label}
                    </button>
                  ))}
                </div>
                <div className="flex gap-1">
                <RowHeightSelector
                  value={currentView?.viewConfig?.rowHeight}
                  onChange={handleRowHeightChange}
                />
                <button
                  type="button"
                  onClick={() => setColumnSettingsOpen(true)}
                  title="Column settings"
                  className="rounded-md p-1.5 text-gray-400 transition-colors hover:bg-gray-100 hover:text-gray-600"
                  data-testid="column-settings-btn"
                >
                  <svg className="h-4 w-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.065 2.572c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.572 1.065c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.065-2.572c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z"
                    />
                    <path
                      strokeLinecap="round"
                      strokeLinejoin="round"
                      strokeWidth={2}
                      d="M15 12a3 3 0 11-6 0 3 3 0 016 0z"
                    />
                  </svg>
                </button>
                </div>
              </div>

              {/* Filter Chip Bar — always visible for "Add Filter" access */}
              <FilterChipBar
                  filters={chipFilters}
                  sorts={activeSorts}
                  fieldMetadata={tableColumns
                    .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
                    .map((c: ColumnConfig) => ({
                      fieldCode: c.field,
                      label: c.label
                        ? (typeof c.label === 'string' ? c.label : (c.label as any)?.['zh-CN'] || c.field)
                        : (() => {
                            const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
                            const key = `model.${mc}.${c.field}.label`;
                            const resolved = t(key);
                            return resolved !== key ? resolved : c.field;
                          })(),
                      fieldType: c.valueType || c.sorter || 'text',
                      dictCode: c.dictCode,
                    }))}
                  onFiltersChange={setChipFilters}
                  onSortsChange={setActiveSorts}
                  onAddFilter={(e?: React.MouseEvent) => {
                    const rect = (e?.currentTarget as HTMLElement)?.getBoundingClientRect?.();
                    setFieldPickerAnchor(rect ? { x: rect.left, y: rect.bottom + 4 } : { x: 300, y: 200 });
                    setFieldPickerOpen(true);
                  }}
                  onChipClick={(idx, e) => {
                    const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
                    setValuePopoverAnchor({ x: rect.left, y: rect.bottom + 4 });
                    setEditingChipIdx(idx);
                  }}
                  onClearAll={() => { setChipFilters([]); setActiveSorts([]); }}
                />

              {/* Table area */}
              <div className="relative overflow-x-auto" data-testid={deriveTestId('list', modelCode, 'table')}>
                <table className="min-w-full divide-y divide-gray-200">
                  <thead className="bg-gray-50">
                    <tr>
                      <th className="print-hide w-10 px-3 py-3" data-print="hide">
                        <input
                          type="checkbox"
                          checked={selectedIds.size > 0 && selectedIds.size === data.length}
                          ref={(el) => {
                            if (el)
                              el.indeterminate =
                                selectedIds.size > 0 && selectedIds.size < data.length;
                          }}
                          onChange={toggleSelectAll}
                          className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                          data-testid="select-all-checkbox"
                        />
                      </th>
                      {tableColumns.map((column: ColumnConfig) => {
                        const sortInfo = activeSorts.find((s) => s.fieldCode === column.field);
                        const isSortable = !column.isActionColumn && column.sortable !== false;
                        const frozenPos = column.fixed || (column as any).frozenPosition;
                        const isFrozenLeft = frozenPos === 'left';
                        const isFrozenRight = frozenPos === 'right' || column.isActionColumn;
                        return (
                        <th
                          key={column.field}
                          className={`${
                            column.isActionColumn ? 'w-px px-2 py-3' : 'px-6 py-3'
                          } text-xs font-medium tracking-wider text-gray-500 uppercase ${
                            column.align === 'right'
                              ? 'text-right'
                              : column.align === 'center'
                                ? 'text-center'
                                : 'text-left'
                          } ${
                            isFrozenRight
                              ? 'sticky right-0 z-20 border-l border-gray-200 bg-gray-50 shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)]'
                              : isFrozenLeft
                                ? 'sticky left-0 z-20 border-r border-gray-200 bg-gray-50 shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)]'
                                : ''
                          } ${
                            isSortable ? 'cursor-pointer select-none hover:bg-gray-100' : ''
                          }`}
                          style={
                            column.width
                              ? {
                                  width:
                                    typeof column.width === 'number'
                                      ? `${column.width}px`
                                      : column.width,
                                }
                              : undefined
                          }
                          onClick={isSortable ? (e) => toggleSort(column.field, e.shiftKey) : undefined}
                          onContextMenu={!column.isActionColumn ? (e) => {
                            e.preventDefault();
                            e.stopPropagation();
                            setContextMenu({ x: e.clientX, y: e.clientY, column });
                          } : undefined}
                        >
                          <div className="group/th relative flex items-center gap-1">
                            <span>
                            {column.label
                              ? getLocalizedText(column.label, locale, t)
                              : column.isActionColumn
                                ? t('table.actions')
                                : (() => {
                                    const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
                                    const modelKey = `model.${mc}.${column.field}.label`;
                                    const modelLabel = t(modelKey);
                                    if (modelLabel !== modelKey) return modelLabel;
                                    const fieldKey = `field.${column.field}.label`;
                                    const fieldLabel = t(fieldKey);
                                    if (fieldLabel !== fieldKey) return fieldLabel;
                                    const commonKey = `common.field.${column.field}`;
                                    const commonLabel = t(commonKey);
                                    return commonLabel !== commonKey ? commonLabel : column.field;
                                  })()}
                            </span>
                            {isSortable && (
                              <span className={`text-[10px] ${sortInfo ? 'text-blue-600' : 'text-gray-300'}`}>
                                {sortInfo?.direction === 'asc' ? '↑' : sortInfo?.direction === 'desc' ? '↓' : '↕'}
                              </span>
                            )}
                            {!column.isActionColumn && (
                              <button
                                type="button"
                                className="absolute right-1 top-1/2 -translate-y-1/2 rounded p-0.5 text-gray-400 opacity-0 transition-opacity hover:bg-gray-200 hover:text-gray-600 group-hover/th:opacity-100"
                                onClick={(e) => {
                                  e.stopPropagation();
                                  setContextMenu({ x: e.clientX, y: e.clientY, column });
                                }}
                              >
                                <svg width="14" height="14" viewBox="0 0 24 24" fill="currentColor"><circle cx="12" cy="5" r="2"/><circle cx="12" cy="12" r="2"/><circle cx="12" cy="19" r="2"/></svg>
                              </button>
                            )}
                          </div>
                          {!column.isActionColumn && column.width && (
                            <ColumnResizeHandle
                              width={typeof column.width === 'number' ? column.width : parseInt(String(column.width), 10) || 100}
                              onResize={(newWidth) => {
                                if (!currentView) return;
                                const cols = [...(currentView.viewConfig?.columns || [])];
                                const idx = cols.findIndex((c) => c.fieldCode === column.field);
                                if (idx >= 0) {
                                  cols[idx] = { ...cols[idx], width: newWidth };
                                } else {
                                  cols.push({ fieldCode: column.field, width: newWidth });
                                }
                                autoSave({ columns: cols });
                              }}
                            />
                          )}
                        </th>
                        );
                      })}
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-200 bg-white">
                    {loading ? (
                      <tr>
                        <td
                          colSpan={(tableColumns.length || 1) + 1}
                          className="px-6 py-4 text-center"
                        >
                          <div className="flex items-center justify-center">
                            <span className="loading loading-spinner loading-md mr-2"></span>
                            {t('message.loading')}
                          </div>
                        </td>
                      </tr>
                    ) : data.length === 0 ? (
                      <tr>
                        <td
                          colSpan={(tableColumns.length || 1) + 1}
                          className="px-6 py-4 text-center text-gray-500"
                        >
                          {t('table.noData')}
                        </td>
                      </tr>
                    ) : (
                      (() => {
                        // Build rows: optionally grouped with collapsible headers
                        const visibleData = groupedData
                          ? data.filter((r) => !collapsedGroups.has(String(r[groupByField!] ?? '(empty)')))
                          : data;
                        const rowElements: React.ReactNode[] = [];

                        if (groupedData) {
                          let globalIdx = 0;
                          for (const group of groupedData) {
                            const isCollapsed = collapsedGroups.has(group.key);
                            rowElements.push(
                              <tr
                                key={`group-${group.key}`}
                                className="cursor-pointer bg-gray-50 hover:bg-gray-100"
                                onClick={() => toggleGroupCollapse(group.key)}
                              >
                                <td
                                  colSpan={(tableColumns.length || 1) + 1}
                                  className="px-6 py-2 text-sm font-medium text-gray-700"
                                >
                                  <span className="mr-2 text-xs text-gray-400">{isCollapsed ? '▶' : '▼'}</span>
                                  <span className="font-semibold">{groupByField}</span>
                                  <span className="mx-1">:</span>
                                  <span>{group.key}</span>
                                  <span className="ml-2 text-xs text-gray-400">({group.count})</span>
                                </td>
                              </tr>,
                            );
                            if (!isCollapsed) {
                              // Group's data rows rendered below via shared renderRow path
                              globalIdx += group.count;
                            }
                          }
                        }
                        return rowElements;
                      })()
                    )}
                    {/* Data rows */}
                    {!loading && data.length > 0 && (groupedData
                      ? data.filter((r) => !collapsedGroups.has(String(r[groupByField!] ?? '(empty)')))
                      : data
                    ).map((record, index) => {
                        const rowId = record.pid || record.id || '';
                        const cfStyle = evaluateConditionalFormats(currentView?.viewConfig?.conditionalFormats, record);
                        const cfInline = buildConditionalStyle(cfStyle);
                        return (
                          <tr
                            key={rowId || index}
                            data-testid={`table-row-${index}`}
                            className={`group hover:bg-gray-50 cursor-pointer${selectedIds.has(rowId) ? 'bg-blue-50' : ''}${previewRecordId === rowId ? 'bg-blue-50/50' : ''}`}
                            style={{ height: `${rowHeightCfg.px}px`, ...cfInline }}
                            onClick={() => handleRowClick(record)}
                          >
                            <td
                              className={`px-3 ${rowHeightCfg.pyClass} print-hide w-10`}
                              data-print="hide"
                              onClick={(e) => e.stopPropagation()}
                            >
                              <input
                                type="checkbox"
                                checked={selectedIds.has(rowId)}
                                onChange={() => toggleRowSelection(rowId)}
                                className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
                                data-testid={`row-checkbox-${index}`}
                              />
                            </td>
                            {tableColumns.map((column: ColumnConfig) => {
                              const tdFrozenPos = column.fixed || (column as any).frozenPosition;
                              const tdFrozenLeft = tdFrozenPos === 'left';
                              const tdFrozenRight = tdFrozenPos === 'right' || column.isActionColumn;
                              return (
                              <td
                                key={column.field}
                                data-testid={`table-cell-${index}-${column.field}`}
                                className={`${
                                  column.isActionColumn
                                    ? `px-2 ${rowHeightCfg.pyClass} w-px`
                                    : `px-6 ${rowHeightCfg.pyClass}`
                                } text-sm whitespace-nowrap text-gray-900 ${
                                  column.align === 'right'
                                    ? 'text-right'
                                    : column.align === 'center'
                                      ? 'text-center'
                                      : ''
                                } ${
                                  tdFrozenRight
                                    ? 'sticky right-0 z-10 border-l border-gray-200 bg-white shadow-[-8px_0_8px_-8px_rgba(0,0,0,0.2)] group-hover:bg-gray-50'
                                    : tdFrozenLeft
                                      ? 'sticky left-0 z-10 border-r border-gray-200 bg-white shadow-[2px_0_4px_-2px_rgba(0,0,0,0.1)] group-hover:bg-gray-50'
                                      : ''
                                }`}
                              >
                                {column.isActionColumn ? (
                                  <div className="opacity-0 transition-opacity duration-150 group-hover:opacity-100">
                                  <RowActionButtons
                                    buttons={column.buttons || []}
                                    record={record}
                                    evaluateVisibleWhen={evaluateVisibleWhen}
                                    resolveButtonLabel={resolveButtonLabel}
                                    handleAction={handleAction}
                                  />
                                  </div>
                                ) : column.editable ? (
                                  <InlineEditCell
                                    column={column}
                                    value={record[column.field]}
                                    record={record}
                                    onSave={handleInlineSave}
                                    editable
                                    dictItems={
                                      column.dictCode
                                        ? (dictDataCache.current.get(column.dictCode) ?? [])
                                        : undefined
                                    }
                                  >
                                    {renderCellContent(column, record, index)}
                                  </InlineEditCell>
                                ) : (
                                  renderCellContent(column, record, index)
                                )}
                              </td>
                              );
                            })}
                          </tr>
                        );
                      })}
                  </tbody>
                </table>
              </div>

              {/* Pagination (hidden in print) */}
              <div className="print-hide" data-print="hide">
                <Pagination
                  current={pagination.current}
                  pageSize={pagination.pageSize}
                  total={pagination.total}
                  onChange={handlePageChange}
                  onPageSizeChange={handlePageSizeChange}
                  t={t}
                />
              </div>

              {/* Bulk Action Toolbar (hidden in print) */}
              <BulkActionToolbar
                selectedCount={selectedIds.size}
                selectedIds={Array.from(selectedIds)}
                modelCode={modelCode}
                onBulkEdit={() => setBulkEditOpen(true)}
                onBulkDelete={handleBulkDelete}
                onClearSelection={() => setSelectedIds(new Set())}
              />

              {/* Bulk Edit Modal */}
              {bulkEditOpen && (
                <BulkEditModal
                  open={bulkEditOpen}
                  onClose={() => setBulkEditOpen(false)}
                  selectedIds={Array.from(selectedIds)}
                  modelCode={modelCode}
                  fields={tableColumns
                    .filter((c) => !c.isActionColumn && c.field)
                    .map((c) => ({
                      code: c.field,
                      name: c.field,
                      dataType: c.valueType || 'string',
                    }))}
                  onUpdateComplete={handleBulkEditComplete}
                />
              )}
            </>
          ) : (
            <SmartViewRenderer
              view={
                {
                  ...(currentView || {}),
                  modelCode,
                  viewType: activeViewType,
                  viewConfig: currentView?.viewConfig || {},
                } as any
              }
              onGanttTaskClick={navigateToRecordView}
              onOpenViewConfig={() => setViewManageOpen(true)}
              onSwitchToTableView={() => setActiveViewType('table')}
              onCardClick={(card) => navigateToRecordView((card as any).id || (card as any).pid)}
              onEventClick={navigateToRecordView}
              onGalleryCardClick={navigateToRecordView}
              onTreeNodeClick={navigateToRecordView}
              onDataRefresh={() => loadData({ page: 0, size: pagination.pageSize })}
              linkageFilters={[]}
            />
          )}

          <ImportModal
            open={importOpen}
            onClose={() => setImportOpen(false)}
            modelCode={modelCode}
            onImportComplete={handleImportComplete}
          />

          <FormDialog />

          <ViewManagePanel
            open={viewManageOpen}
            onClose={() => {
              setViewManageOpen(false);
              setStartCreateViewMode(false);
            }}
            views={savedViews}
            currentView={currentView}
            onCreateView={async (req: SavedViewCreateRequest) => createView(req)}
            onCreateViewSuccess={(view) => {
              const newType = (view.viewType as ViewType) || 'table';
              setActiveViewType(newType);
              setStartCreateViewMode(false);
            }}
            onDeleteView={async (pid: string) => {
              await deleteSavedView(pid);
            }}
            onDuplicateView={async (pid: string, name: string) => {
              await duplicateView(pid, name);
            }}
            onSetDefaultView={async (pid: string) => {
              await setDefaultView(pid);
            }}
            onSelectView={selectView}
            modelCode={modelCode}
            pageKey={pageKey}
            activeViewType={activeViewType}
            startInCreateMode={startCreateViewMode}
            modelPid={modelPid}
            onFieldsCreated={() => {
              loadData({ page: 0, size: pagination.pageSize, filters });
            }}
          />

          <ColumnSettingsPanel
            open={columnSettingsOpen}
            onClose={() => setColumnSettingsOpen(false)}
            allColumns={allColumnDefs}
            viewColumns={currentView?.viewConfig?.columns}
            onSave={handleColumnSettingsSave}
            t={t}
          />

          {/* Record Preview Drawer */}
          {/* Filter Field Picker */}
          <FilterFieldPicker
            open={fieldPickerOpen}
            anchorEl={fieldPickerAnchor}
            fields={tableColumns
              .filter((c: ColumnConfig) => !c.isActionColumn && c.field)
              .map((c: ColumnConfig) => ({
                fieldCode: c.field,
                label: c.label
                  ? (typeof c.label === 'string' ? c.label : (c.label as any)?.['zh-CN'] || c.field)
                  : (() => {
                      const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
                      const key = `model.${mc}.${c.field}.label`;
                      const resolved = t(key);
                      return resolved !== key ? resolved : c.field;
                    })(),
                fieldType: c.valueType || c.sorter || 'text',
                dictCode: c.dictCode,
              }))}
            activeFieldCodes={chipFilters.map((f) => f.fieldCode)}
            onSelect={(fieldCode) => {
              // Add a new empty filter chip and immediately open the value popover
              const newFilter: ViewFilterConfig = {
                fieldCode,
                operator: 'eq',
                value: '',
              };
              setChipFilters((prev) => {
                const next = [...prev, newFilter];
                // Open value popover for the newly added chip
                setTimeout(() => {
                  setEditingChipIdx(next.length - 1);
                  // Position near the "Add Filter" button
                  setValuePopoverAnchor(fieldPickerAnchor || { x: 300, y: 200 });
                }, 50);
                return next;
              });
              setFieldPickerOpen(false);
            }}
            onClose={() => setFieldPickerOpen(false)}
          />

          {/* Filter Value Popover — edit operator + value of a chip */}
          {editingChipIdx !== null && chipFilters[editingChipIdx] && (() => {
            const cf = chipFilters[editingChipIdx];
            const fieldMeta = tableColumns.find((c: ColumnConfig) => c.field === cf.fieldCode) as any;
            return (
              <FilterValuePopover
                open
                anchorEl={valuePopoverAnchor}
                fieldCode={cf.fieldCode}
                fieldLabel={fieldMeta?.label
                  ? (typeof fieldMeta.label === 'string' ? fieldMeta.label : fieldMeta.label?.['zh-CN'] || cf.fieldCode)
                  : (() => {
                      const mc = (schema?.modelCode || tableName).replace(/-/g, '_');
                      const key = `model.${mc}.${cf.fieldCode}.label`;
                      const resolved = t(key);
                      return resolved !== key ? resolved : cf.fieldCode;
                    })()}
                fieldType={fieldMeta?.valueType || fieldMeta?.sorter || 'text'}
                dictCode={fieldMeta?.dictCode}
                operator={cf.operator}
                value={cf.value}
                onApply={(operator, value) => {
                  setChipFilters((prev) =>
                    prev.map((f, i) => (i === editingChipIdx ? { ...f, operator: operator as any, value } : f)),
                  );
                  setEditingChipIdx(null);
                }}
                onCancel={() => setEditingChipIdx(null)}
              />
            );
          })()}

          {/* Column Context Menu */}
          {contextMenu && (
            <ColumnContextMenu
              x={contextMenu.x}
              y={contextMenu.y}
              column={contextMenu.column}
              currentSortDir={activeSorts.find((s) => s.fieldCode === contextMenu.column.field)?.direction}
              onSort={(dir) => {
                if (dir === 'clear') {
                  setActiveSorts((prev) => prev.filter((s) => s.fieldCode !== contextMenu.column.field));
                } else {
                  setActiveSorts([{ fieldCode: contextMenu.column.field, direction: dir, priority: 0 }]);
                }
              }}
              onFreeze={(_pos) => {
                // Column freeze is handled via DSL config; for runtime, update SavedView
                // This would require extending ViewColumnConfig with frozen support
              }}
              onHide={() => {
                if (!currentView) return;
                const cols = (currentView.viewConfig?.columns || []).map((c) =>
                  c.fieldCode === contextMenu.column.field ? { ...c, visible: false } : c,
                );
                // If column not in saved config yet, add it as hidden
                if (!cols.find((c) => c.fieldCode === contextMenu.column.field)) {
                  cols.push({ fieldCode: contextMenu.column.field, visible: false });
                }
                updateViewConfig({ columns: cols });
              }}
              onFilterByColumn={() => {
                // Placeholder — will be connected to FilterChipBar in integration step
              }}
              onGroupBy={() => {
                setGroupByField((prev) =>
                  prev === contextMenu.column.field ? null : contextMenu.column.field,
                );
              }}
              onClose={() => setContextMenu(null)}
            />
          )}

          <RecordPreviewDrawer
            open={!!previewRecordId}
            modelCode={modelCode}
            recordId={previewRecordId || ''}
            apiEndpoint={
              schema?.dataSource?.type === 'api'
                ? schema.dataSource.endpoint || (schema.dataSource as any).url
                : undefined
            }
            onClose={() => setPreviewRecordId(null)}
          />
        </div>
      </div>
    </DataSourceProvider>
  );
}
