/**
 * Shared WYSIWYG table preview for the unified designer's list-page `table` block.
 *
 * Mirrors the field bridge (`platformFieldPreview.tsx`): resolve each designer `column`
 * child against the page's model metadata into a platform `ColumnConfig`, then render the
 * real data table. Preview mode uses `RecordListView` (fetches a small page of real rows
 * with dict chips / reference display / typed cells — true WYSIWYG); the edit canvas uses
 * a representative skeleton table (see CanvasHost) so it stays deterministic and keeps the
 * per-column editing affordances.
 */
import React from 'react';
import RecordListView from '~/framework/meta/rendering/blocks/RecordListView';
import type { ColumnConfig } from '~/framework/meta/schemas/types';
import type { DslBlockV3, ModelFieldDefinition } from '../types';

/** Page primary model code, shared by canvas + preview so a table can fetch/label rows. */
export const DesignerPageModelCodeContext = React.createContext<string | undefined>(undefined);

/** Map a resolved model field to a platform cell `valueType` (dict chips handled via dictCode). */
export function inferColumnValueType(modelField: ModelFieldDefinition): string | undefined {
  const component = String(modelField.component ?? '').toLowerCase();
  if (component === 'colorpicker' || component === 'color') return 'color';
  if (component === 'progress' || component === 'progressfield') return 'progress';
  if (component === 'moneyinput' || component === 'money') return 'currency';
  const type = String(modelField.type ?? '').toLowerCase();
  if (type === 'reference') return 'reference';
  if (type === 'boolean') return 'boolean';
  if (type === 'date') return 'date';
  if (type === 'datetime') return 'datetime';
  if (type === 'decimal' || type === 'integer') return 'number';
  // enum/dictCode → let RecordListView render the dict color chip; string → default text.
  return undefined;
}

/**
 * Build platform `ColumnConfig`s from a designer `table` block's `column` children,
 * resolving each column's `field` against the page's model metadata for the display
 * label, dictCode and value type (the designer column block only carries field + label).
 */
export function buildPreviewColumnConfigs(
  tableBlock: DslBlockV3,
  modelFields: ModelFieldDefinition[],
): ColumnConfig[] {
  const columns = (tableBlock.blocks ?? []).filter((child) => child.blockType === 'column');
  return columns
    .map((col) => {
      const field = col.field ?? (col.props?.field as string | undefined);
      if (!field) return null;
      const modelField = modelFields.find((candidate) => candidate.code === field);
      const label = (col.props?.label ??
        col.title ??
        modelField?.label ??
        field) as ColumnConfig['label'];
      const width = col.layout?.width;
      const config: ColumnConfig = {
        field,
        label,
        ...(modelField?.dictCode ? { dictCode: modelField.dictCode } : {}),
        ...(modelField ? { valueType: inferColumnValueType(modelField) as ColumnConfig['valueType'] } : {}),
        ...(typeof width === 'number' ? { width } : {}),
      };
      return config;
    })
    .filter((config): config is ColumnConfig => config != null);
}

/**
 * Real data-table preview for a designer `table` block (preview mode). Renders the live
 * `RecordListView` (fetches a small page, resolves dict/reference/typed cells) so the
 * designer preview matches the published `/p/` list. Returns null when it cannot resolve a
 * model or columns, so the caller falls back to the schematic table.
 */
export function PreviewListTable({
  modelCode,
  tableBlock,
  modelFields,
  locale,
}: {
  modelCode: string | undefined;
  tableBlock: DslBlockV3;
  modelFields: ModelFieldDefinition[];
  locale?: string;
}) {
  const columns = React.useMemo(
    () => buildPreviewColumnConfigs(tableBlock, modelFields),
    [tableBlock, modelFields],
  );
  if (!modelCode || columns.length === 0) return null;
  return (
    <div data-testid={`preview-table-${tableBlock.id}`} data-wysiwyg="platform">
      <RecordListView
        modelCode={modelCode}
        columns={columns}
        pageSize={5}
        searchable={false}
        filterable={false}
        locale={locale}
        testIdPrefix={`preview-table-${tableBlock.id}`}
      />
    </div>
  );
}
