/**
 * Runtime table-view configuration.
 *
 * The panel edits the column and density sections of the active SavedView in
 * one transaction. It deliberately works from model metadata as well as the
 * page's default columns so a user can promote a less common business field
 * into their personal view without changing the DSL page.
 */

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ArrowPathIcon,
  Bars3Icon,
  MagnifyingGlassIcon,
  XMarkIcon,
} from '@heroicons/react/24/outline';
import {
  DEFAULT_ROW_HEIGHT,
  ROW_HEIGHT_CONFIG,
  type ColumnConfig as ViewColumnConfig,
  type RowHeight,
} from '~/framework/smart/types/savedView';
import { cn } from '~/utils/cn';

export interface ColumnSettingsDefinition {
  field: string;
  label: string;
  dataType?: string;
  group?: 'business' | 'system';
  defaultVisible?: boolean;
  defaultWidth?: number;
  defaultFrozenPosition?: 'left' | 'right';
}

export interface ColumnSettingsRow extends ViewColumnConfig {
  label: string;
  dataType: string;
  group: 'business' | 'system';
  defaultVisible: boolean;
  defaultWidth?: number;
}

export interface ColumnSettingsSavePayload {
  columns: ViewColumnConfig[];
  rowHeight: RowHeight;
}

export interface ColumnSettingsPanelProps {
  allColumns: ColumnSettingsDefinition[];
  viewColumns?: ViewColumnConfig[];
  rowHeight?: RowHeight;
  onSave: (payload: ColumnSettingsSavePayload) => void | Promise<void>;
  open: boolean;
  onClose: () => void;
  t?: (key: string) => string;
}

const ROW_HEIGHT_OPTIONS: RowHeight[] = ['short', 'medium', 'tall', 'extra-tall'];

const normalizeRowHeight = (value: unknown): RowHeight =>
  typeof value === 'string' && value in ROW_HEIGHT_CONFIG
    ? (value as RowHeight)
    : DEFAULT_ROW_HEIGHT;

export function buildColumnSettingsRows(
  allColumns: ColumnSettingsDefinition[],
  viewColumns?: ViewColumnConfig[],
): ColumnSettingsRow[] {
  const viewColumnMap = new Map((viewColumns ?? []).map((column) => [column.fieldCode, column]));
  return allColumns
    .map((definition, index) => {
      const saved = viewColumnMap.get(definition.field);
      return {
        fieldCode: definition.field,
        label: definition.label,
        dataType: definition.dataType || 'text',
        group: definition.group || 'business',
        defaultVisible: definition.defaultVisible !== false,
        defaultWidth: definition.defaultWidth,
        visible: saved?.visible ?? definition.defaultVisible !== false,
        width: saved?.width,
        order: saved?.order ?? index,
        frozen: saved?.frozen ?? Boolean(saved?.frozenPosition ?? definition.defaultFrozenPosition),
        frozenPosition: saved?.frozenPosition ?? definition.defaultFrozenPosition,
      } satisfies ColumnSettingsRow;
    })
    .sort((left, right) => (left.order ?? 0) - (right.order ?? 0));
}

export function serializeColumnSettings(rows: ColumnSettingsRow[]): ViewColumnConfig[] {
  return rows.map((row, order) => ({
    fieldCode: row.fieldCode,
    visible: row.visible !== false,
    ...(row.width ? { width: Math.min(600, Math.max(80, row.width)) } : {}),
    order,
    frozen: Boolean(row.frozen && row.frozenPosition),
    ...(row.frozen && row.frozenPosition
      ? { frozen: true, frozenPosition: row.frozenPosition }
      : {}),
  }));
}

export const ColumnSettingsPanel: React.FC<ColumnSettingsPanelProps> = ({
  allColumns,
  viewColumns,
  rowHeight,
  onSave,
  open,
  onClose,
  t = (key) => key,
}) => {
  const [columns, setColumns] = useState<ColumnSettingsRow[]>([]);
  const [selectedRowHeight, setSelectedRowHeight] = useState<RowHeight>(DEFAULT_ROW_HEIGHT);
  const [search, setSearch] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const dragField = useRef<string | null>(null);
  const initialSnapshot = useRef('');

  const l = useCallback(
    (key: string, fallback: string) => {
      const value = t(key);
      return value && value !== key ? value : fallback;
    },
    [t],
  );

  const resetState = useCallback(() => {
    const nextColumns = buildColumnSettingsRows(allColumns, viewColumns);
    const nextRowHeight = normalizeRowHeight(rowHeight);
    setColumns(nextColumns);
    setSelectedRowHeight(nextRowHeight);
    setSearch('');
    setError(null);
    initialSnapshot.current = JSON.stringify({
      columns: serializeColumnSettings(nextColumns),
      rowHeight: nextRowHeight,
    });
  }, [allColumns, rowHeight, viewColumns]);

  useEffect(() => {
    if (open) resetState();
  }, [open, resetState]);

  const visibleCount = columns.filter((column) => column.visible !== false).length;
  const currentSnapshot = JSON.stringify({
    columns: serializeColumnSettings(columns),
    rowHeight: selectedRowHeight,
  });
  const hasChanges = currentSnapshot !== initialSnapshot.current;

  const filteredColumns = useMemo(() => {
    const query = search.trim().toLocaleLowerCase();
    if (!query) return columns;
    return columns.filter(
      (column) =>
        column.label.toLocaleLowerCase().includes(query) ||
        column.fieldCode.toLocaleLowerCase().includes(query),
    );
  }, [columns, search]);

  const toggleVisibility = useCallback(
    (fieldCode: string) => {
      setColumns((previous) => {
        const target = previous.find((column) => column.fieldCode === fieldCode);
        if (!target) return previous;
        if (
          target.visible !== false &&
          previous.filter((column) => column.visible !== false).length <= 1
        ) {
          setError(l('common.column_settings_minimum', 'Keep at least one field visible.'));
          return previous;
        }
        setError(null);
        return previous.map((column) =>
          column.fieldCode === fieldCode
            ? { ...column, visible: column.visible === false }
            : column,
        );
      });
    },
    [l],
  );

  const updateWidth = useCallback((fieldCode: string, rawWidth: string) => {
    const parsed = rawWidth === '' ? undefined : Number.parseInt(rawWidth, 10);
    setColumns((previous) =>
      previous.map((column) =>
        column.fieldCode === fieldCode
          ? { ...column, width: Number.isFinite(parsed) ? parsed : undefined }
          : column,
      ),
    );
  }, []);

  const toggleFrozen = useCallback((fieldCode: string, position: 'left' | 'right') => {
    setColumns((previous) => {
      const target = previous.find((column) => column.fieldCode === fieldCode);
      const shouldClear = target?.frozen && target.frozenPosition === position;
      return previous.map((column) =>
        column.fieldCode === fieldCode
          ? shouldClear
            ? { ...column, frozen: false, frozenPosition: undefined }
            : { ...column, visible: true, frozen: true, frozenPosition: position }
          : column,
      );
    });
  }, []);

  const handleDragEnd = useCallback((targetField: string) => {
    const sourceField = dragField.current;
    dragField.current = null;
    if (!sourceField || sourceField === targetField) return;
    setColumns((previous) => {
      const from = previous.findIndex((column) => column.fieldCode === sourceField);
      const to = previous.findIndex((column) => column.fieldCode === targetField);
      if (from < 0 || to < 0) return previous;
      const next = [...previous];
      const [moved] = next.splice(from, 1);
      next.splice(to, 0, moved);
      return next;
    });
  }, []);

  const handleRestoreDefault = useCallback(() => {
    setColumns(buildColumnSettingsRows(allColumns));
    setSelectedRowHeight(DEFAULT_ROW_HEIGHT);
    setError(null);
  }, [allColumns]);

  const handleSelectAll = useCallback(() => {
    setColumns((previous) => previous.map((column) => ({ ...column, visible: true })));
    setError(null);
  }, []);

  const handleSave = useCallback(async () => {
    if (visibleCount === 0) {
      setError(l('common.column_settings_minimum', 'Keep at least one field visible.'));
      return;
    }
    setSaving(true);
    setError(null);
    try {
      await onSave({ columns: serializeColumnSettings(columns), rowHeight: selectedRowHeight });
      onClose();
    } catch (saveError) {
      console.error('Failed to save column settings', saveError);
      setError(l('common.column_settings_save_failed', 'Failed to save view settings.'));
    } finally {
      setSaving(false);
    }
  }, [columns, l, onClose, onSave, selectedRowHeight, visibleCount]);

  if (!open) return null;

  return (
    <>
      <button
        type="button"
        className="fixed inset-0 z-40 cursor-default bg-slate-950/30 backdrop-blur-[1px]"
        aria-label={l('common.close', 'Close')}
        onClick={onClose}
      />
      <section
        role="dialog"
        aria-modal="true"
        aria-labelledby="column-settings-title"
        data-testid="column-settings-panel"
        className="bg-panel fixed top-0 right-0 z-50 flex h-full w-[30rem] max-w-[calc(100vw-1rem)] flex-col border-l border-slate-200 shadow-2xl"
      >
        <header className="border-border bg-panel border-b px-5 py-4">
          <div className="flex items-start justify-between gap-4">
            <div>
              <h2 id="column-settings-title" className="text-text text-base font-semibold">
                {l('common.column_settings_title', 'Configure fields')}
              </h2>
              <p className="text-text-3 mt-1 text-xs leading-5">
                {l(
                  'common.column_settings_help',
                  'Choose the fields, order, width and density saved with this view.',
                )}
              </p>
            </div>
            <button
              type="button"
              onClick={onClose}
              className="text-text-3 hover:bg-hover hover:text-text rounded-control p-1.5"
              aria-label={l('common.close', 'Close')}
              data-testid="column-settings-close"
            >
              <XMarkIcon className="h-5 w-5" />
            </button>
          </div>

          <div className="mt-4 flex items-center gap-2">
            <label className="border-border bg-subtle focus-within:border-accent flex h-9 min-w-0 flex-1 items-center gap-2 rounded-lg border px-3">
              <MagnifyingGlassIcon className="text-text-3 h-4 w-4 flex-none" />
              <input
                value={search}
                onChange={(event) => setSearch(event.target.value)}
                placeholder={l('common.column_settings_search', 'Search fields')}
                className="text-text min-w-0 flex-1 bg-transparent text-sm outline-none"
                data-testid="column-settings-search"
              />
            </label>
            <button
              type="button"
              onClick={handleRestoreDefault}
              className="border-border text-text-2 hover:bg-hover rounded-control inline-flex h-9 items-center gap-1.5 border px-3 text-xs font-medium"
              data-testid="column-settings-restore"
            >
              <ArrowPathIcon className="h-3.5 w-3.5" />
              {l('common.column_settings_restore', 'Restore default')}
            </button>
          </div>
        </header>

        <div className="border-border bg-subtle/60 border-b px-5 py-3">
          <div className="flex items-center justify-between gap-3">
            <div>
              <div className="text-text text-xs font-semibold">
                {l('common.column_settings_density', 'Row density')}
              </div>
              <div className="text-text-3 mt-0.5 text-[11px]">
                {l('common.column_settings_density_help', 'Applied to this view only')}
              </div>
            </div>
            <div className="border-border bg-panel flex rounded-lg border p-0.5">
              {ROW_HEIGHT_OPTIONS.map((height) => (
                <button
                  key={height}
                  type="button"
                  onClick={() => setSelectedRowHeight(height)}
                  className={cn(
                    'rounded-md px-2 py-1 text-[11px] transition-colors',
                    selectedRowHeight === height
                      ? 'bg-accent text-white shadow-sm'
                      : 'text-text-3 hover:bg-hover hover:text-text-2',
                  )}
                  data-testid={`column-settings-density-${height}`}
                >
                  {l(
                    `common.row_height_${height.replace('-', '_')}`,
                    ROW_HEIGHT_CONFIG[height].label,
                  )}
                </button>
              ))}
            </div>
          </div>
        </div>

        <div className="border-border flex items-center justify-between border-b px-5 py-2.5">
          <div className="text-text-3 text-xs" data-testid="column-settings-visible-summary">
            {l('common.column_settings_visible_summary', '{visible} of {total} visible')
              .replace('{visible}', String(visibleCount))
              .replace('{total}', String(columns.length))}
          </div>
          <button
            type="button"
            onClick={handleSelectAll}
            className="text-accent hover:text-accent-hover text-xs font-medium"
          >
            {l('common.column_settings_show_all', 'Show all')}
          </button>
        </div>

        <div className="flex-1 overflow-y-auto px-3 py-2">
          {filteredColumns.length === 0 ? (
            <div className="text-text-3 flex h-40 items-center justify-center text-sm">
              {l('common.no_fields_found', 'No fields found')}
            </div>
          ) : (
            filteredColumns.map((column) => {
              const isOnlyVisible = column.visible !== false && visibleCount === 1;
              return (
                <div
                  key={column.fieldCode}
                  draggable={!search}
                  onDragStart={() => {
                    dragField.current = column.fieldCode;
                  }}
                  onDragOver={(event) => event.preventDefault()}
                  onDrop={() => handleDragEnd(column.fieldCode)}
                  data-testid={`column-settings-row-${column.fieldCode}`}
                  className="group border-border hover:border-accent/30 hover:bg-hover mb-1 grid grid-cols-[1.25rem_2rem_minmax(0,1fr)_4rem_4.75rem] items-center gap-2 rounded-lg border border-transparent px-2.5 py-2 transition-colors"
                >
                  <Bars3Icon
                    className={cn('text-text-3 h-4 w-4', search ? 'opacity-25' : 'cursor-grab')}
                    aria-hidden="true"
                  />
                  <input
                    type="checkbox"
                    checked={column.visible !== false}
                    disabled={isOnlyVisible}
                    onChange={() => toggleVisibility(column.fieldCode)}
                    className="border-border-strong text-accent focus-visible:shadow-focus h-4 w-4 rounded disabled:cursor-not-allowed disabled:opacity-50"
                    aria-label={column.label}
                    data-testid={`column-settings-visible-${column.fieldCode}`}
                  />
                  <div className="min-w-0">
                    <div className="flex items-center gap-1.5">
                      <span
                        className={cn(
                          'truncate text-sm font-medium',
                          column.visible === false ? 'text-text-3' : 'text-text',
                        )}
                      >
                        {column.label}
                      </span>
                      {column.group === 'system' && (
                        <span className="bg-subtle text-text-3 rounded px-1 py-0.5 text-[9px] font-medium uppercase">
                          {l('common.column_settings_system', 'System')}
                        </span>
                      )}
                    </div>
                  </div>
                  <div className="border-border bg-panel flex h-7 overflow-hidden rounded-md border">
                    {(['left', 'right'] as const).map((position) => (
                      <button
                        key={position}
                        type="button"
                        onClick={() => toggleFrozen(column.fieldCode, position)}
                        aria-pressed={column.frozen && column.frozenPosition === position}
                        title={l(
                          position === 'left'
                            ? 'common.column_settings_pin_left'
                            : 'common.column_settings_pin_right',
                          position === 'left' ? 'Pin left' : 'Pin right',
                        )}
                        className={cn(
                          'w-8 text-[10px] font-semibold',
                          column.frozen && column.frozenPosition === position
                            ? 'bg-accent-weak text-accent'
                            : 'text-text-3 hover:bg-hover',
                        )}
                        data-testid={`column-settings-pin-${position}-${column.fieldCode}`}
                      >
                        <span aria-hidden="true">{position === 'left' ? '←' : '→'}</span>
                        <span className="sr-only">
                          {l(
                            position === 'left'
                              ? 'common.column_settings_pin_left'
                              : 'common.column_settings_pin_right',
                            position === 'left' ? 'Pin left' : 'Pin right',
                          )}
                        </span>
                      </button>
                    ))}
                  </div>
                  <label className="border-border bg-panel flex h-7 items-center rounded-md border px-1.5">
                    <input
                      type="number"
                      min={80}
                      max={600}
                      value={column.width ?? ''}
                      placeholder={String(column.defaultWidth ?? 160)}
                      onChange={(event) => updateWidth(column.fieldCode, event.target.value)}
                      className="text-text min-w-0 flex-1 bg-transparent text-right text-[11px] outline-none"
                      aria-label={l('common.column_settings_width', 'Width')}
                      data-testid={`column-settings-width-${column.fieldCode}`}
                    />
                    <span className="text-text-3 ml-0.5 text-[9px]">px</span>
                  </label>
                </div>
              );
            })
          )}
        </div>

        <footer className="border-border bg-panel border-t px-5 py-4">
          {error && (
            <div
              role="alert"
              className="mb-3 rounded-lg border border-red-200 bg-red-50 px-3 py-2 text-xs text-red-700"
            >
              {error}
            </div>
          )}
          <div className="flex items-center justify-between gap-3">
            <span className="text-text-3 text-[11px]">
              {hasChanges
                ? l('common.column_settings_unsaved', 'Changes will be saved with this view')
                : l('common.column_settings_unchanged', 'No changes')}
            </span>
            <div className="flex gap-2">
              <button
                type="button"
                onClick={onClose}
                className="border-border-strong bg-panel text-text-2 hover:bg-hover rounded-control border px-4 py-2 text-sm"
                data-testid="column-settings-cancel"
              >
                {l('common.cancel', 'Cancel')}
              </button>
              <button
                type="button"
                onClick={handleSave}
                disabled={saving || !hasChanges}
                className="bg-accent hover:bg-accent-hover rounded-control px-4 py-2 text-sm font-medium text-white disabled:cursor-not-allowed disabled:opacity-50"
                data-testid="column-settings-save"
              >
                {saving
                  ? l('common.saving', 'Saving...')
                  : l('common.column_settings_apply', 'Apply to view')}
              </button>
            </div>
          </div>
        </footer>
      </section>
    </>
  );
};

export default ColumnSettingsPanel;
