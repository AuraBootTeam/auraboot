// web-admin/app/components/field-adapter/SmartFieldAdapter.ts
import { useMemo } from 'react';
import {
  useSmartField,
  useExpressionValue,
  type ValidationRule,
} from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import {
  useSmartFieldContract,
  type SmartFieldExpressions,
} from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import type { SmartText } from '~/utils/i18n';
import type { FieldAdapter, FieldAdapterProps } from './types';

/**
 * SmartFieldAdapterProps - SmartForm 场景的 FieldAdapter Props
 *
 * Extends the base FieldAdapterProps with SmartForm-specific configurations
 * like validation rules, expressions, and runtime context.
 */
export interface SmartFieldAdapterProps<T = unknown> extends FieldAdapterProps<T> {
  /** Validation rules for the field */
  validationRules?: ValidationRule[];
  /** Expression configurations for dynamic behavior */
  expressions?: SmartFieldExpressions;
  /** Runtime context for expression evaluation */
  context?: Record<string, unknown>;
  /** Label text (supports i18n) */
  label?: SmartText;
  /** Placeholder text (supports i18n) */
  placeholder?: SmartText;
  /** Help text (supports i18n) */
  helpText?: SmartText;
  /** Visibility condition */
  visible?: boolean | string;
}

/**
 * SmartFieldAdapterResult - Extended result with SmartForm-specific data
 */
export interface SmartFieldAdapterResult<T = unknown> {
  /** The unified FieldAdapter interface */
  adapter: FieldAdapter<T>;
  /** Resolved label text */
  labelText?: string;
  /** Resolved placeholder text */
  placeholderText?: string;
  /** Resolved help text */
  helpText?: string;
  /** Whether the field is visible */
  visible: boolean;
  /** Whether to show error (touched && hasError) */
  showError: boolean;
  /** Field meta information */
  meta: {
    touched: boolean;
    error?: string;
    validating?: boolean;
  };
  /** Mark the field as touched */
  markTouched: () => void;
  /** Validate the field value */
  validate: (value?: T) => Promise<boolean>;
}

/**
 * useSmartFieldAdapter - Creates a FieldAdapter for SmartForm context
 *
 * This hook wraps the existing Smart hooks (useSmartField, useSmartFieldContract,
 * useSmartFieldMeta) and returns a unified FieldAdapter interface that can be
 * used with BaseField components.
 *
 * @example
 * ```tsx
 * const { adapter, labelText, visible, showError } = useSmartFieldAdapter({
 *   name: 'username',
 *   value: formData.username,
 *   onChange: (v) => setFormData({ ...formData, username: v }),
 *   required: true,
 *   label: { zh: '用户名', en: 'Username' },
 *   validationRules: [
 *     { type: 'minLength', value: 3, message: 'At least 3 characters' }
 *   ],
 *   context: { formData },
 * });
 *
 * if (!visible) return null;
 *
 * return (
 *   <BaseInput
 *     adapter={adapter}
 *     label={labelText}
 *     showError={showError}
 *   />
 * );
 * ```
 */
export function useSmartFieldAdapter<T = unknown>(
  props: SmartFieldAdapterProps<T>,
): SmartFieldAdapterResult<T> {
  const {
    name,
    value,
    defaultValue,
    required,
    disabled,
    readOnly,
    onChange,
    onBlur,
    validationRules = [],
    expressions,
    context = {},
    label,
    placeholder,
    helpText,
    visible,
  } = props;

  // Use SmartFieldContract for expression-based property resolution
  const contract = useSmartFieldContract({
    label,
    placeholder,
    helpText,
    required,
    disabled,
    visible,
    expressions,
    context,
  });

  // Use SmartField for value management and validation
  const field = useSmartField<T>({
    name,
    value,
    defaultValue,
    required: contract.required,
    validationRules,
    context,
    onChange,
    onBlur,
  });

  // Use SmartFieldMeta for meta state management
  const { meta, showError, markTouched } = useSmartFieldMeta({
    field: {
      meta: field.meta,
      error: field.error,
    },
  });

  // Resolve readOnly expression
  const resolvedReadOnly = useExpressionValue(readOnly, context);

  // Build the unified FieldAdapter interface
  const adapter: FieldAdapter<T> = useMemo(
    () => ({
      value: field.value,
      setValue: field.setValue,
      error: meta.error,
      disabled: contract.disabled,
      required: contract.required,
      readOnly: Boolean(resolvedReadOnly),
      onBlur: field.onBlur,
      onFocus: undefined,
    }),
    [
      field.value,
      field.setValue,
      field.onBlur,
      meta.error,
      contract.disabled,
      contract.required,
      resolvedReadOnly,
    ],
  );

  return {
    adapter,
    labelText: contract.labelText,
    placeholderText: contract.placeholderText,
    helpText: contract.helpText,
    visible: contract.visible,
    showError,
    meta,
    markTouched,
    validate: field.validate,
  };
}

/**
 * createSmartFieldAdapter - Factory function to create adapter from existing Smart hook results
 *
 * Use this when you've already called the Smart hooks separately and want to
 * create a FieldAdapter from their results.
 *
 * @param field - Result from useSmartField
 * @param contract - Result from useSmartFieldContract
 * @param metaResult - Result from useSmartFieldMeta
 * @param readOnly - Optional readOnly state
 */
export function createSmartFieldAdapter<T = unknown>(
  field: {
    value: T;
    setValue: (value: T) => void;
    onBlur: () => void;
  },
  contract: {
    disabled?: boolean;
    required?: boolean;
  },
  metaResult: {
    meta: { error?: string };
  },
  readOnly?: boolean,
): FieldAdapter<T> {
  return {
    value: field.value,
    setValue: field.setValue,
    error: metaResult.meta.error,
    disabled: contract.disabled,
    required: contract.required,
    readOnly,
    onBlur: field.onBlur,
  };
}
