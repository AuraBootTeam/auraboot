import type { LocalizedText, BlockLayoutConfig, LayoutConfig } from '~/framework/meta/schemas/types';

export interface BlockDataSource {
  type: 'model' | 'namedQuery' | 'api';
  modelCode?: string;
  queryCode?: string;
  endpoint?: string;
  params?: Record<string, unknown>;
  pagination?: boolean;
  autoFetch?: boolean;
}

export interface TableFeatures {
  search: boolean;
  filter: boolean;
  sort: boolean;
  pagination: { enabled: boolean; pageSize: number };
  create?: {
    enabled: boolean;
    commandCode?: string;
    openMode?: 'modal' | 'page' | 'inline';
  };
  batchActions?: boolean;
  export?: boolean;
  rowActions?: RowAction[];
}

export interface RowAction {
  label: string | LocalizedText;
  action: { type: 'command'; command: string } | { type: 'navigate'; to: string };
  confirm?: boolean;
  danger?: boolean;
}

export type FormSectionMode = 'display' | 'create' | 'edit';

export type AfterSubmitBehavior = 'toast' | 'refresh' | 'navigate' | 'clearForm';

export interface CanvasBlock {
  id: string;
  blockType: string;
  dataSource?: BlockDataSource;
  config: Record<string, unknown>;
  layout?: BlockLayoutConfig;
}

