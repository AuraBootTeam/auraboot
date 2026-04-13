/**
 * Tailwind Template System Types
 *
 * Defines the input/output contracts for batch page generation.
 * Templates accept model field metadata and produce UnifiedSchema pages.
 *
 * @since 3.8.0
 */

import type { UnifiedSchema } from '~/framework/meta/schemas/types';

/** Field metadata input for template generation */
export interface TemplateFieldMeta {
  /** Field code (maps to DB column) */
  field: string;
  /** Display label */
  label: string;
  /** Field data type */
  type: FieldDataType;
  /** Whether this field is required */
  required?: boolean;
  /** Whether to show in list columns */
  listVisible?: boolean;
  /** Whether to show in form */
  formVisible?: boolean;
  /** Whether to show in detail view */
  detailVisible?: boolean;
  /** Whether this field is searchable (filter) */
  searchable?: boolean;
  /** Whether this field is sortable */
  sortable?: boolean;
  /** Fixed width for table column */
  width?: number;
  /** Placeholder text */
  placeholder?: string;
  /** Select/enum options */
  options?: Array<{ label: string; value: string }>;
  /** Data source ID for dynamic options */
  dataSourceId?: string;
  /** Default value */
  defaultValue?: any;
  /** Max length for text fields */
  maxLength?: number;
  /** Value type for display formatting */
  valueType?: 'text' | 'date' | 'datetime' | 'currency' | 'tag' | 'image';
}

export type FieldDataType =
  | 'string'
  | 'number'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  | 'text'
  | 'image'
  | 'file'
  | 'relation';

/** Model metadata input for template generation */
export interface TemplateModelMeta {
  /** Model code (unique identifier) */
  modelCode: string;
  /** Display name */
  displayName: string;
  /** Model description */
  description?: string;
  /** Primary key field (default: 'id') */
  primaryKey?: string;
  /** Field definitions */
  fields: TemplateFieldMeta[];
  /** API base path (default: '/api/dynamic/{modelCode}') */
  apiBasePath?: string;
}

/** Template generation options */
export interface TemplateOptions {
  /** Template variant (layout style) */
  variant?: TemplateVariant;
  /** Number of form columns (default: 2) */
  formColumns?: number;
  /** Page size for list pagination */
  pageSize?: number;
  /** Whether to include action column in list */
  includeActions?: boolean;
  /** Whether to include row selection */
  includeSelection?: boolean;
  /** Whether to include export button */
  includeExport?: boolean;
  /** Custom Tailwind class overrides */
  classOverrides?: TemplateClassOverrides;
  /** Whether form is dialog-based or page-based */
  formMode?: 'dialog' | 'page' | 'drawer';
  /** Custom action buttons */
  customActions?: TemplateAction[];
}

export type TemplateVariant = 'default' | 'compact' | 'spacious';

/** Tailwind class overrides for template sections */
export interface TemplateClassOverrides {
  /** Page container class */
  container?: string;
  /** Card/section wrapper class */
  card?: string;
  /** Table header class */
  tableHeader?: string;
  /** Form section class */
  formSection?: string;
  /** Button group class */
  buttonGroup?: string;
}

/** Custom action button definition */
export interface TemplateAction {
  code: string;
  label: string;
  variant?: 'default' | 'primary' | 'danger';
  icon?: string;
  position?: 'toolbar' | 'row';
  handler?: string;
  confirm?: { title: string; message: string };
}

/** Template type identifier */
export type TemplateType = 'list' | 'form' | 'detail' | 'list+form';

/** Template generator interface */
export interface TemplateGenerator {
  type: TemplateType;
  generate(model: TemplateModelMeta, options?: TemplateOptions): UnifiedSchema;
}

/** Batch generation request */
export interface BatchGenerateRequest {
  model: TemplateModelMeta;
  pages: Array<{
    type: TemplateType;
    options?: TemplateOptions;
  }>;
}

/** Batch generation result */
export interface BatchGenerateResult {
  modelCode: string;
  pages: Array<{
    type: TemplateType;
    schema: UnifiedSchema;
  }>;
}

/** Style token set for a template variant */
export interface TemplateStyleSet {
  container: string;
  card: string;
  cardHeader: string;
  cardBody: string;
  toolbar: string;
  filterBar: string;
  table: string;
  formGrid: string;
  formSection: string;
  formSectionTitle: string;
  buttonGroup: string;
  detailGrid: string;
  detailLabel: string;
  detailValue: string;
}

/** Predefined Tailwind style tokens for templates */
export const TEMPLATE_STYLES: Record<TemplateVariant, TemplateStyleSet> = {
  default: {
    container: 'p-6 space-y-4',
    card: 'bg-white dark:bg-gray-900 rounded-lg border border-gray-200 dark:border-gray-700 shadow-sm',
    cardHeader: 'px-4 py-3 border-b border-gray-200 dark:border-gray-700',
    cardBody: 'p-4',
    toolbar: 'flex items-center justify-between gap-3 px-4 py-3',
    filterBar: 'grid grid-cols-4 gap-4 px-4 py-3 bg-gray-50 dark:bg-gray-800/50 rounded-t-lg',
    table: 'w-full text-sm',
    formGrid: 'grid gap-4',
    formSection: 'space-y-4',
    formSectionTitle:
      'text-base font-medium text-gray-900 dark:text-gray-100 pb-2 border-b border-gray-200 dark:border-gray-700',
    buttonGroup: 'flex items-center gap-2',
    detailGrid: 'grid grid-cols-2 gap-x-8 gap-y-4',
    detailLabel: 'text-sm text-gray-500 dark:text-gray-400',
    detailValue: 'text-sm text-gray-900 dark:text-gray-100',
  },
  compact: {
    container: 'p-4 space-y-3',
    card: 'bg-white dark:bg-gray-900 rounded border border-gray-200 dark:border-gray-700',
    cardHeader: 'px-3 py-2 border-b border-gray-200 dark:border-gray-700',
    cardBody: 'p-3',
    toolbar: 'flex items-center justify-between gap-2 px-3 py-2',
    filterBar: 'grid grid-cols-5 gap-3 px-3 py-2 bg-gray-50 dark:bg-gray-800/50',
    table: 'w-full text-xs',
    formGrid: 'grid gap-3',
    formSection: 'space-y-3',
    formSectionTitle:
      'text-sm font-medium text-gray-900 dark:text-gray-100 pb-1.5 border-b border-gray-100 dark:border-gray-700',
    buttonGroup: 'flex items-center gap-1.5',
    detailGrid: 'grid grid-cols-3 gap-x-6 gap-y-3',
    detailLabel: 'text-xs text-gray-500 dark:text-gray-400',
    detailValue: 'text-xs text-gray-900 dark:text-gray-100',
  },
  spacious: {
    container: 'p-8 space-y-6',
    card: 'bg-white dark:bg-gray-900 rounded-xl border border-gray-200 dark:border-gray-700 shadow-md',
    cardHeader: 'px-6 py-4 border-b border-gray-200 dark:border-gray-700',
    cardBody: 'p-6',
    toolbar: 'flex items-center justify-between gap-4 px-6 py-4',
    filterBar: 'grid grid-cols-3 gap-6 px-6 py-4 bg-gray-50 dark:bg-gray-800/50 rounded-t-xl',
    table: 'w-full text-sm',
    formGrid: 'grid gap-6',
    formSection: 'space-y-6',
    formSectionTitle:
      'text-lg font-semibold text-gray-900 dark:text-gray-100 pb-3 border-b border-gray-200 dark:border-gray-700',
    buttonGroup: 'flex items-center gap-3',
    detailGrid: 'grid grid-cols-2 gap-x-12 gap-y-6',
    detailLabel: 'text-sm text-gray-500 dark:text-gray-400',
    detailValue: 'text-base text-gray-900 dark:text-gray-100',
  },
};
