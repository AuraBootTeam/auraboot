import type { ColumnConfig, SubTableConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';

export interface SubTableViewerProps {
  config: SubTableConfig;
  parentRecordPid: string;
  parentRecordData?: Record<string, any>;
  token?: string;
  locale?: string;
  t?: (key: string) => string;
  isEditable?: boolean;
  onDataChange?: () => void;
  runtime?: SchemaRuntime | null;
}

export interface ChildFieldMeta {
  code: string;
  displayName?: string;
  dataType?: string;
  dictCode?: string;
  referenceModelCode?: string;
  refTarget?: Record<string, any>;
  required?: boolean;
  feature?: { validation?: { maxLength?: number } };
  extension?: Record<string, any>;
}

export type EnrichedColumnConfig = ColumnConfig & {
  maxLength?: number;
  dataType?: string;
  refTarget?: Record<string, any>;
  referenceModelCode?: string;
  extension?: Record<string, any>;
};

export interface SubTableReferenceDisplayConfig {
  field: string;
  modelCode: string;
  valueField: string;
  displayField: string;
  displayKey: string;
}

export interface PaginationResult<T> {
  records?: T[];
}

export type ResolvedSubTableViewerProps = Omit<SubTableViewerProps, 'locale' | 't' | 'isEditable'> &
  Required<Pick<SubTableViewerProps, 'locale' | 't' | 'isEditable'>>;
