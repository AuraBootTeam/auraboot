/**
 * Shared types for meta form components.
 *
 * @since 3.7.0
 */

// Re-export DocumentFlow types from their source module
export type { DocumentFlowStep, DocumentFlowStepperProps } from './DocumentFlowConfig';

export interface CascadeOption {
  value: string;
  label: string;
  children?: CascadeOption[];
  isLeaf?: boolean;
}

export interface FileItem {
  uid: string;
  name: string;
  size: number;
  type: string;
  status: 'uploading' | 'done' | 'error';
  progress?: number;
  url?: string;
  thumbUrl?: string;
  errorMessage?: string;
}

export interface FileUploadConfig {
  accept?: string;
  maxSize?: number; // bytes
  maxCount?: number;
  multiple?: boolean;
  uploadUrl?: string;
  headers?: Record<string, string>;
  listType?: 'text' | 'picture' | 'picture-card';
}

export interface SubTableColumn {
  field: string;
  label: string;
  type: 'text' | 'number' | 'select' | 'date' | 'boolean';
  width?: number;
  required?: boolean;
  options?: { label: string; value: string }[];
  placeholder?: string;
  editable?: boolean;
}

export interface SubTableSummaryField {
  field: string;
  aggregation: 'sum' | 'avg' | 'count' | 'min' | 'max';
  label?: string;
}

export interface SubTableSummaryConfig {
  fields: SubTableSummaryField[];
}

export interface SubTableConfig {
  columns: SubTableColumn[];
  maxRows?: number;
  minRows?: number;
  addLabel?: string;
  sortable?: boolean;
  sortField?: string;
  showIndex?: boolean;
  summary?: SubTableSummaryConfig;
  treeConfig?: {
    parentField: string;
    maxDepth?: number;
    defaultExpanded?: boolean;
  };
}
