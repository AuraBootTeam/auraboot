/**
 * useI18nResolver - React Hook for DSL Schema i18n Resolution
 *
 * Provides i18n resolution capabilities for DSL schema rendering.
 * Automatically injects the translate function and locale from I18nContext.
 *
 * @author AuraBoot
 */

import { useMemo } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  createI18nResolver,
  resolveButtonLabel,
  resolveFieldLabel,
  resolveFieldPlaceholder,
  resolveColumnLabel,
  resolveMessage,
  resolveConfirmDialog,
  resolveContent,
  resolveValidationMessage,
  resolveSectionTitle,
  resolveHandlerStep,
  type DslButton,
  type DslField,
  type DslColumn,
  type DslHandlerStep,
  type I18nResolver,
} from '~/framework/meta/utils/i18nResolver';

/**
 * Hook for i18n resolution in DSL schema
 *
 * @param modelCode - Model code for field/column resolution
 * @returns I18nResolver instance with all resolution methods
 *
 * @example
 * function ListPage({ schema }) {
 *   const resolver = useI18nResolver(schema.modelCode);
 *
 *   return (
 *     <Table>
 *       {schema.columns.map(col => (
 *         <Column key={col.field} title={resolver.resolveColumnLabel(col)}>
 *           ...
 *         </Column>
 *       ))}
 *     </Table>
 *   );
 * }
 */
export function useI18nResolver(modelCode: string): I18nResolver {
  const { t, locale } = useI18n();

  return useMemo(() => createI18nResolver(modelCode, t, locale), [modelCode, t, locale]);
}

/**
 * Hook for resolving a single button label
 *
 * @param button - Button definition
 * @returns Resolved button label
 *
 * @example
 * function ActionButton({ button }) {
 *   const label = useButtonLabel(button);
 *   return <Button>{label}</Button>;
 * }
 */
export function useButtonLabel(button: DslButton): string {
  const { t } = useI18n();

  return useMemo(
    () => resolveButtonLabel(button, t),
    [button.action, button.content, button.code, t],
  );
}

/**
 * Hook for resolving a field label
 *
 * @param field - Field definition or field code
 * @param modelCode - Model code
 * @param fieldMeta - Optional field metadata
 * @returns Resolved field label
 */
export function useFieldLabel(
  field: DslField | string,
  modelCode: string,
  fieldMeta?: { displayName?: string },
): string {
  const { t } = useI18n();
  const fieldCode = typeof field === 'string' ? field : field.field;

  return useMemo(
    () => resolveFieldLabel(field, modelCode, t, fieldMeta),
    [fieldCode, modelCode, fieldMeta?.displayName, t],
  );
}

/**
 * Hook for resolving a column label
 *
 * @param column - Column definition
 * @param modelCode - Model code
 * @returns Resolved column label
 */
export function useColumnLabel(column: DslColumn, modelCode: string): string {
  const { t } = useI18n();

  return useMemo(
    () => resolveColumnLabel(column, modelCode, t),
    [column.field, column.isActionColumn, column.label, modelCode, t],
  );
}

/**
 * Hook for resolving a message
 *
 * @param messageKey - Message key
 * @param vars - Optional interpolation variables
 * @returns Resolved message
 */
export function useMessage(messageKey: string, vars?: Record<string, any>): string {
  const { t } = useI18n();

  return useMemo(() => resolveMessage(messageKey, t, vars), [messageKey, vars, t]);
}

/**
 * Hook for resolving a confirmation dialog
 *
 * @param messageKey - Message key
 * @returns Resolved title and content
 */
export function useConfirmDialog(messageKey: string): {
  title: string;
  content: string;
} {
  const { t } = useI18n();

  return useMemo(() => resolveConfirmDialog(messageKey, t), [messageKey, t]);
}

/**
 * Hook for resolving a section title
 *
 * @param pageType - Page type ("form" or "detail")
 * @param modelCode - Model code
 * @param sectionKey - Optional section key
 * @returns Resolved section title
 */
export function useSectionTitle(
  pageType: 'form' | 'detail',
  modelCode: string,
  sectionKey?: string,
): string {
  const { t, locale } = useI18n();

  return useMemo(
    () => resolveSectionTitle(pageType, modelCode, sectionKey, t, locale),
    [pageType, modelCode, sectionKey, t, locale],
  );
}

/**
 * Hook for resolving field metadata (label, placeholder, validation messages)
 *
 * @param field - Field definition
 * @param modelCode - Model code
 * @param fieldMeta - Optional field metadata
 * @returns Resolved field properties
 */
export function useFieldMeta(
  field: DslField,
  modelCode: string,
  fieldMeta?: { displayName?: string },
): {
  label: string;
  placeholder: string;
  getValidationMessage: (type: string) => string;
} {
  const { t } = useI18n();

  return useMemo(() => {
    const label = resolveFieldLabel(field, modelCode, t, fieldMeta);
    const placeholder = resolveFieldPlaceholder(field, modelCode, t, label);

    return {
      label,
      placeholder,
      getValidationMessage: (type: string) => resolveValidationMessage(type, t, label),
    };
  }, [field.field, modelCode, fieldMeta?.displayName, t]);
}

// Re-export types for convenience
export type { DslButton, DslField, DslColumn, DslHandlerStep, I18nResolver };
