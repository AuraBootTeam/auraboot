import type { DslBlockV3, ModelFieldDefinition } from '../../types';
import type { RuntimeExecutionContext, RuntimeExecutionServices } from '../runtimeExecution';
import { resolveDesignerText } from '~/shared/designer';
import React from 'react';





export const RuntimeLocaleContext = React.createContext<string>('zh-CN');

/** Resolve a runtime preview i18n string for the current locale. */
export function useRuntimeText() {
  const locale = React.useContext(RuntimeLocaleContext);
  return (entry: Record<string, string>) => resolveDesignerText(entry, locale);
}

export type RuntimePageContext = Pick<
  RuntimeExecutionContext,
  'source' | 'pageId' | 'pageKind' | 'schemaVersion' | 'routeQuery'
>;

export interface RuntimeFormContextValue {
  values: Record<string, unknown>;
  errors: Record<string, string>;
  setValue: (field: string, value: unknown) => void;
  validate: () => boolean;
}

export const RuntimeFormValueContext = React.createContext<RuntimeFormContextValue | null>(null);

export interface RuntimeSelectionContextValue {
  selectedRows: Array<Record<string, unknown>>;
  selectedRowIds: string[];
  filterBlocks: DslBlockV3[];
  filterValues: Record<string, unknown>;
  setFilterValue: (field: string, value: unknown) => void;
  toggleRow: (rowId: string, row: Record<string, unknown>) => void;
  isSelected: (rowId: string) => boolean;
}

export const RuntimeListSelectionContext = React.createContext<RuntimeSelectionContextValue | null>(null);
export const RuntimePermissionContext = React.createContext<(permissionCode: string) => boolean>(
  () => false,
);
export const EMPTY_PREVIEW_FORM_VALUES: Record<string, unknown> = {};
export const RuntimeInitialFormValuesContext = React.createContext<Record<string, unknown>>(
  EMPTY_PREVIEW_FORM_VALUES,
);

/** Stable empty reference so the default context value never triggers re-renders. */
export const EMPTY_MODEL_FIELDS: ModelFieldDefinition[] = [];
/**
 * Model field metadata for the page's primary model, provided by the workbench preview.
 * Consumed by {@link RuntimeField} to render the real platform control for `field` blocks.
 */
export const RuntimeModelFieldsContext =
  React.createContext<ModelFieldDefinition[]>(EMPTY_MODEL_FIELDS);

export interface RuntimeBlockProps {
  block: DslBlockV3;
  runtimeServices?: RuntimeExecutionServices;
  pageContext: RuntimePageContext;
  blockPath: string[];
}

export type RuntimeFieldComponent =
  | 'input'
  | 'textarea'
  | 'select'
  | 'date'
  | 'number'
  | 'checkbox'
  | 'switch'
  | 'radio'
  | 'upload'
  | 'picker'
  | 'rich-text';

export interface RuntimeSelectOption {
  label: string;
  value: string;
}

export interface RuntimeValidationRule {
  type: string;
  value?: unknown;
  message?: string;
}

export interface RuntimeActionTestIds {
  wrapper: string;
  button: string;
  permission: string;
  confirm: string;
  status: string;
  error: string;
  errorHint: string;
  overlay: string;
}

export interface RuntimeActionProps extends RuntimeBlockProps {
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
  testIds?: Partial<RuntimeActionTestIds>;
}

export interface RuntimeBlockContextOptions {
  formValues?: Record<string, unknown>;
  selectedRows?: Array<Record<string, unknown>>;
  selectedRowIds?: string[];
  currentRow?: Record<string, unknown>;
  currentRowId?: string;
  permissionCode?: string;
  pickerSearch?: string;
}
