/**
 * RecordListView — generic, embeddable model-bound record list.
 *
 * The reusable core behind the `embedded-list` block: a filter toolbar
 * (keyword search + filter chips) over a sortable / resizable column table
 * with pagination, scoped by `fixedFilters` (e.g. the surrounding detail
 * record's id). Unlike a full list *page* it intentionally omits SavedViews,
 * SmartView, tabs, dashboards and bulk-edit — an embedded list wants a focused
 * subset, not the whole list-page chrome.
 *
 * Composed from the already-extracted pieces (useListData / ListTable /
 * FilterChipBar / FilterFieldPicker / FilterValuePopover / cellRendererRegistry)
 * so `ListPageContent` is never touched — zero regression to existing list pages.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router';
import type { ColumnConfig, ButtonConfig, UnifiedSchema } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { ViewFilterConfig, SortConfig } from '~/framework/smart/types/savedView';
import { useListData } from '~/framework/meta/rendering/pages/hooks/useListData';
import { useDictCache } from '~/framework/meta/rendering/pages/hooks/useDictCache';
import { ListTable } from '~/framework/meta/rendering/pages/list/ListTable';
import { resolveStatusTone, StatusDot } from '~/framework/meta/runtime/renderers/statusTone';
import { Pagination } from '~/ui/Pagination';
import { FilterChipBar } from '~/framework/smart/components/view/FilterChipBar';
import { FilterFieldPicker } from '~/framework/smart/components/view/FilterFieldPicker';
import { FilterValuePopover } from '~/framework/smart/components/view/FilterValuePopover';
import { cellRendererRegistry } from '~/framework/meta/runtime/renderers/CellRendererRegistry';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useI18n } from '~/contexts/I18nContext';
import { useDebouncedValue } from '~/hooks/useDebouncedValue';
import { ErrorAlert } from '~/ui/ErrorAlert';

export interface RecordListViewProps {
  /** Model bound to this list (used as the dynamic list `tableName`). */
  modelCode: string;
  /** Column definitions (same shape as a table block's columns). */
  columns: ColumnConfig[];
  /**
   * Parent-scope filters AND-merged into every request and not user-clearable
   * (e.g. `{ bom_std_task_id: <taskId> }`). Always win over toolbar filters.
   */
  fixedFilters?: Record<string, any>;
  token?: string;
  /** Runtime, when available, enables `column.render` template expressions. */
  runtime?: SchemaRuntime;
  pageSize?: number;
  /** Show the keyword search box (default true). */
  searchable?: boolean;
  /** Show the filter chip bar (default true). */
  filterable?: boolean;
  locale?: string;
  /** Row click target. Defaults to navigating to the model's detail route. */
  onRowClick?: (record: Record<string, any>) => void;
  /** testid prefix for the rendered container. */
  testIdPrefix?: string;
}

function inferValueType(
  column: ColumnConfig,
  value: any,
  record?: Record<string, any>,
): string | undefined {
  if (column.valueType) return column.valueType;
  const field = column.field || '';
  if (field.endsWith('_id') || (record && record[`${field}_display`] !== undefined))
    return 'reference';
  if (field.endsWith('_at')) return 'datetime';
  if (field.endsWith('_date')) return 'date';
  if (field.endsWith('_time')) return 'time';
  if (typeof value === 'string' && /\d{4}-\d{2}-\d{2}T\d{2}:\d{2}/.test(value)) return 'datetime';
  if (typeof value === 'boolean' || value === 'true' || value === 'false') return 'boolean';
  return undefined;
}

export function RecordListView({
  modelCode,
  columns,
  fixedFilters,
  token,
  runtime,
  pageSize = 10,
  searchable = true,
  filterable = true,
  locale,
  onRowClick,
  testIdPrefix = 'embedded-list',
}: RecordListViewProps) {
  const { t, locale: ctxLocale } = useI18n();
  const effectiveLocale = locale ?? ctxLocale;
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();

  // Minimal synthetic schema so useListData treats this as a standard dynamic
  // table and applies the DSL pageSize.
  const schema = useMemo<UnifiedSchema>(
    () =>
      ({
        kind: 'list',
        blocks: [
          {
            id: `${modelCode}_embedded_table`,
            blockType: 'table',
            table: { columns, pagination: { pageSize } },
          },
        ],
      }) as unknown as UnifiedSchema,
    [modelCode, columns, pageSize],
  );

  const { data, loading, error, pagination, setPagination, loadData } = useListData({
    schema,
    tableName: modelCode,
    token,
    initialPageSize: pageSize,
    fixedFilters,
  });

  // Toolbar state
  const [keywordInput, setKeywordInput] = useState('');
  const debouncedKeyword = useDebouncedValue(keywordInput, 300);
  const [localChips, setLocalChips] = useState<ViewFilterConfig[]>([]);
  const [sorts, setSorts] = useState<SortConfig[]>([]);
  const [columnWidths, setColumnWidths] = useState<Record<string, number>>({});

  // URL `filter_<field>=<value>` params drive drill-down filters (e.g. a summary
  // chip click sets filter_bom_std_reason_code). These are derived — never stored
  // in state — so clearing the URL param removes the chip cleanly. User-added
  // chips (localChips) cover other fields; URL wins on field collisions.
  const urlChips = useMemo<ViewFilterConfig[]>(() => {
    const out: ViewFilterConfig[] = [];
    searchParams.forEach((value, key) => {
      if (key.startsWith('filter_') && value) {
        out.push({ fieldCode: key.slice('filter_'.length), operator: 'eq', value });
      }
    });
    return out;
  }, [searchParams]);
  const urlFields = useMemo(() => new Set(urlChips.map((c) => c.fieldCode)), [urlChips]);
  const chipFilters = useMemo(
    () => [...urlChips, ...localChips.filter((c) => !urlFields.has(c.fieldCode))],
    [urlChips, urlFields, localChips],
  );

  const handleChipFiltersChange = useCallback(
    (next: ViewFilterConfig[]) => {
      // URL-managed fields: a removed chip clears its param; a changed value rewrites it.
      const nextByField = new Map(next.map((c) => [c.fieldCode, c]));
      const sp = new URLSearchParams(searchParams);
      let spChanged = false;
      for (const c of urlChips) {
        const n = nextByField.get(c.fieldCode);
        if (!n) {
          sp.delete('filter_' + c.fieldCode);
          spChanged = true;
        } else if (String(n.value) !== String(c.value)) {
          sp.set('filter_' + c.fieldCode, String(n.value));
          spChanged = true;
        }
      }
      if (spChanged) setSearchParams(sp, { replace: true });
      setLocalChips(next.filter((c) => !urlFields.has(c.fieldCode)));
    },
    [searchParams, setSearchParams, urlChips, urlFields],
  );

  // Filter popovers
  const [fieldPickerOpen, setFieldPickerOpen] = useState(false);
  const [fieldPickerAnchor, setFieldPickerAnchor] = useState<
    { x: number; y: number } | undefined
  >();
  const [editingChipIdx, setEditingChipIdx] = useState<number | null>(null);
  const [valuePopoverAnchor, setValuePopoverAnchor] = useState<
    { x: number; y: number } | undefined
  >();

  // Dict cache for dict-backed columns
  const dictCodes = useMemo(
    () => columns.map((c) => (c as any).dictCode).filter(Boolean) as string[],
    [columns],
  );
  const { getDictItems, cache: dictCache } = useDictCache({ dictCodes, token });

  // Keep a stable reference to loadData so the reload effect does not retrigger
  // on every pagination mutation (which would loop).
  const loadDataRef = useRef(loadData);
  loadDataRef.current = loadData;

  const fixedFiltersKey = JSON.stringify(fixedFilters ?? {});
  const chipFiltersKey = JSON.stringify(chipFilters);
  const sortsKey = JSON.stringify(sorts);

  // Reload (back to page 1) whenever the query inputs change, plus initial mount.
  useEffect(() => {
    setPagination((prev) => ({ ...prev, current: 1 }));
    void loadDataRef.current({ page: 0, chipFilters, keyword: debouncedKeyword, sorts });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [modelCode, fixedFiltersKey, chipFiltersKey, sortsKey, debouncedKeyword, setPagination]);

  const handlePageChange = useCallback(
    (page: number) => {
      setPagination((prev) => ({ ...prev, current: page }));
      void loadDataRef.current({ page: page - 1, chipFilters, keyword: debouncedKeyword, sorts });
    },
    [chipFilters, debouncedKeyword, sorts, setPagination],
  );

  const resolveColumnLabel = useCallback(
    (column: ColumnConfig): string => {
      if (column.label) return getLocalizedText(column.label, effectiveLocale, t);
      const key = `model.${modelCode}.${column.field}.label`;
      const resolved = t(key);
      return resolved && resolved !== key ? resolved : column.field;
    },
    [effectiveLocale, modelCode, t],
  );

  const renderCellContent = useCallback(
    (record: Record<string, any>, column: ColumnConfig, rowIndex: number): React.ReactNode => {
      const value = record[column.field];
      const effectiveValueType = inferValueType(column, value, record);

      if (
        !((column as any).allowNullRenderer === true) &&
        (value === null || value === undefined)
      ) {
        return <span className="text-text-3">-</span>;
      }

      const dictCode = (column as any).dictCode as string | undefined;
      // A custom cellRenderer takes precedence over the built-in dict tag.
      if (dictCode && !(column as any).cellRenderer) {
        const items = getDictItems(dictCode);
        const item = items.find((i) => String(i.value) === String(value));
        if (item) {
          // §3 / §1.3: dict-coded status renders as 色点 + 文字, not a filled pill.
          return <StatusDot tone={resolveStatusTone(item.extension?.color)} label={item.label} />;
        }
        return String(value);
      }

      const rendererType = (column as any).cellRenderer || effectiveValueType;
      return cellRendererRegistry.render(rendererType, {
        value,
        record,
        column: {
          field: column.field,
          label: typeof column.label === 'string' ? column.label : undefined,
          valueType: effectiveValueType,
          cellRenderer: (column as any).cellRenderer,
          format: column.format,
          render: (column as any).render,
          ellipsis: column.ellipsis,
          width: column.width,
        },
        expressionContext: runtime?.getContext?.() as any,
        locale: effectiveLocale,
        t,
        rowIndex,
      });
    },
    [getDictItems, runtime, effectiveLocale, t],
  );

  const toggleSort = useCallback((field: string) => {
    setSorts((prev) => {
      const existing = prev.find((s) => s.fieldCode === field);
      if (!existing) return [{ fieldCode: field, direction: 'asc' }];
      if (existing.direction === 'asc') return [{ fieldCode: field, direction: 'desc' }];
      return [];
    });
  }, []);

  const fieldMetadata = useMemo(
    () =>
      columns
        .filter((c) => !(c as any).isActionColumn && c.field)
        .map((c) => ({
          fieldCode: c.field,
          label: resolveColumnLabel(c),
          fieldType: (c.valueType as string) || 'text',
          dictCode: (c as any).dictCode as string | undefined,
        })),
    [columns, resolveColumnLabel],
  );

  const columnOrder = useMemo(() => columns.map((c) => c.field), [columns]);
  const noop = useCallback(() => {}, []);

  // Resolve dict-backed filter values to their localized label so enum chips
  // (e.g. the reason drill-down) show "缺关键字段" instead of the raw code.
  const resolveChipValueLabel = useCallback(
    (filter: ViewFilterConfig): string | undefined => {
      const col = columns.find((c) => c.field === filter.fieldCode) as any;
      const dictCode = col?.dictCode as string | undefined;
      if (!dictCode) return undefined;
      const item = getDictItems(dictCode).find((i) => String(i.value) === String(filter.value));
      return item?.label;
    },
    [columns, getDictItems],
  );

  return (
    <div className="record-list-view" data-testid={testIdPrefix}>
      {(searchable || filterable) && (
        <div className="mb-3 flex flex-col gap-2">
          {searchable && (
            <input
              type="search"
              value={keywordInput}
              onChange={(e) => setKeywordInput(e.target.value)}
              placeholder={t('common.search') || 'Search'}
              data-testid={`${testIdPrefix}-search`}
              className="rounded-control border-border-strong focus-visible:shadow-focus h-9 w-64 border px-3 text-sm focus:outline-none"
            />
          )}
          {filterable && (
            <FilterChipBar
              filters={chipFilters}
              sorts={sorts}
              fieldMetadata={fieldMetadata}
              onFiltersChange={handleChipFiltersChange}
              onSortsChange={setSorts}
              onAddFilter={(e?: React.MouseEvent) => {
                const rect = (e?.currentTarget as HTMLElement)?.getBoundingClientRect?.();
                setFieldPickerAnchor(
                  rect ? { x: rect.left, y: rect.bottom + 4 } : { x: 300, y: 200 },
                );
                setFieldPickerOpen(true);
              }}
              onChipClick={(idx, e) => {
                const rect = (e.currentTarget as HTMLElement).getBoundingClientRect();
                setValuePopoverAnchor({ x: rect.left, y: rect.bottom + 4 });
                setEditingChipIdx(idx);
              }}
              onClearAll={() => {
                handleChipFiltersChange([]);
                setSorts([]);
              }}
              resolveValueLabel={resolveChipValueLabel}
              locale={effectiveLocale}
              t={t}
            />
          )}
        </div>
      )}

      {error ? (
        <ErrorAlert error={error} />
      ) : (
        <>
          <ListTable
            columns={columns}
            data={data}
            loading={loading}
            activeSorts={sorts}
            selectedIds={new Set<string>()}
            modelCode={modelCode}
            columnOrder={columnOrder}
            onColumnReorder={noop}
            onColumnResize={(field, width) =>
              setColumnWidths((prev) => ({ ...prev, [field]: width }))
            }
            onToggleSort={(field) => toggleSort(field)}
            onSelectRow={noop}
            onSelectAll={noop}
            onRowClick={(record) =>
              onRowClick
                ? onRowClick(record)
                : navigate(
        `/p/${modelCode}/view/${encodeURIComponent(String(record.pid ?? ''))}`,
                  )
            }
            onContextMenu={noop}
            renderCellContent={renderCellContent}
            evaluateVisibleWhen={() => true}
            resolveButtonLabel={(b: ButtonConfig) =>
              resolveColumnLabel(b as unknown as ColumnConfig)
            }
            handleAction={noop}
            resolveColumnLabel={resolveColumnLabel}
            columnWidths={columnWidths}
            collapsedGroups={new Set<string>()}
            onToggleGroupCollapse={noop}
            t={t}
            dictDataCache={dictCache}
            enableSelection={false}
          />
          <div className="mt-3">
            <Pagination
              current={pagination.current}
              pageSize={pagination.pageSize}
              total={pagination.total}
              onChange={handlePageChange}
              t={t}
            />
          </div>
        </>
      )}

      {filterable && (
        <>
          <FilterFieldPicker
            open={fieldPickerOpen}
            anchorEl={fieldPickerAnchor}
            fields={fieldMetadata}
            activeFieldCodes={chipFilters.map((f) => f.fieldCode)}
            onSelect={(fieldCode) => {
              setFieldPickerOpen(false);
              const meta = fieldMetadata.find((f) => f.fieldCode === fieldCode);
              const newChip: ViewFilterConfig = {
                fieldCode,
                operator: meta?.dictCode ? 'eq' : 'like',
                value: '',
              };
              const nextIdx = chipFilters.length;
              setLocalChips((prev) => [...prev, newChip]);
              // Immediately open the value editor for the new chip.
              setValuePopoverAnchor(fieldPickerAnchor);
              setEditingChipIdx(nextIdx);
            }}
            onClose={() => setFieldPickerOpen(false)}
          />
          {editingChipIdx !== null && chipFilters[editingChipIdx] && (
            <FilterValuePopover
              open={editingChipIdx !== null}
              anchorEl={valuePopoverAnchor}
              fieldCode={chipFilters[editingChipIdx].fieldCode}
              fieldLabel={
                fieldMetadata.find((f) => f.fieldCode === chipFilters[editingChipIdx]!.fieldCode)
                  ?.label ?? chipFilters[editingChipIdx]!.fieldCode
              }
              fieldType={
                fieldMetadata.find((f) => f.fieldCode === chipFilters[editingChipIdx]!.fieldCode)
                  ?.fieldType ?? 'text'
              }
              dictCode={
                fieldMetadata.find((f) => f.fieldCode === chipFilters[editingChipIdx]!.fieldCode)
                  ?.dictCode
              }
              token={token}
              operator={chipFilters[editingChipIdx].operator}
              value={chipFilters[editingChipIdx].value}
              onApply={(operator, value) => {
                // Route through handleChipFiltersChange so URL-backed chips write
                // to searchParams and local chips update localChips.
                handleChipFiltersChange(
                  chipFilters.map((c, i) =>
                    i === editingChipIdx
                      ? { ...c, operator: operator as ViewFilterConfig['operator'], value }
                      : c,
                  ),
                );
                setEditingChipIdx(null);
              }}
              onCancel={() => {
                // Drop an empty chip that was never given a value.
                handleChipFiltersChange(
                  chipFilters.filter(
                    (c, i) => !(i === editingChipIdx && (c.value == null || c.value === '')),
                  ),
                );
                setEditingChipIdx(null);
              }}
            />
          )}
        </>
      )}
    </div>
  );
}

export default RecordListView;
