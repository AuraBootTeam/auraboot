import React, { forwardRef, useEffect, useMemo, useState } from 'react';
import clsx from 'clsx';
import type { JsonEditorProps } from '~/plugins/core-designer/components/studio/domain/schema/smart-components';
import { useSmartField } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartComponent';
import { useSmartFieldContract } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldContract';
import { useSmartFieldMeta } from '~/plugins/core-designer/components/studio/hooks/runtime/useSmartFieldMeta';
import { useSmartText } from '~/utils/i18n';
import { FieldBase } from '~/ui/ui/field-base';
import { Textarea as BaseTextarea } from '~/ui/ui/textarea';
import {
  fieldErrorFocusStyles,
  fieldFocusStyles,
  fieldSizeStyles,
  fieldVariantStyles,
} from '~/ui/ui/field-styles';
import { sanitizeSmartDomProps } from './domProps';

type JsonValidationState =
  | { valid: true; parsed?: unknown }
  | { valid: false; reason: 'syntax' | 'objectOrArray' };

export function formatJsonEditorValue(value: unknown): string {
  if (value === undefined || value === null) return '';
  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return value;
    try {
      return JSON.stringify(JSON.parse(trimmed), null, 2);
    } catch {
      return value;
    }
  }
  try {
    return JSON.stringify(value, null, 2);
  } catch {
    return String(value);
  }
}

export function validateJsonEditorText(value: string): JsonValidationState {
  const trimmed = value.trim();
  if (!trimmed) return { valid: true };
  const looksLikeObjectOrArray =
    (trimmed.startsWith('{') && trimmed.endsWith('}')) ||
    (trimmed.startsWith('[') && trimmed.endsWith(']'));
  if (!looksLikeObjectOrArray) return { valid: false, reason: 'objectOrArray' };
  try {
    const parsed = JSON.parse(trimmed);
    if (!parsed || typeof parsed !== 'object') return { valid: false, reason: 'objectOrArray' };
    return { valid: true, parsed };
  } catch {
    return { valid: false, reason: 'syntax' };
  }
}

export const JsonEditor = forwardRef<HTMLTextAreaElement, JsonEditorProps>(
  (
    {
      name,
      label,
      placeholder,
      disabled = false,
      required = false,
      size = 'medium',
      variant = 'default',
      maxLength,
      minLength,
      rows = 8,
      autoResize = false,
      validationRules = [],
      context,
      value: propValue,
      defaultValue,
      onChange,
      onBlur,
      visible,
      className,
      readOnly = false,
      formatOnBlur = false,
      mode = 'json',
      invalidMessage,
      ...restProps
    },
    ref,
  ) => {
    const st = useSmartText();
    const {
      labelText,
      placeholderText,
      required: requiredValue,
      disabled: disabledValue,
      visible: isVisible,
    } = useSmartFieldContract({
      label,
      placeholder,
      required,
      disabled,
      context,
      visible,
    });

    const normalizedValue = useMemo(
      () => formatJsonEditorValue(propValue ?? defaultValue ?? ''),
      [defaultValue, propValue],
    );
    const [draft, setDraft] = useState(normalizedValue);
    const [jsonTouched, setJsonTouched] = useState(false);

    useEffect(() => {
      setDraft(normalizedValue);
    }, [normalizedValue]);

    const field = useSmartField<string>({
      name,
      value: draft,
      defaultValue: '',
      required: requiredValue,
      validationRules,
      context,
      onBlur,
    });
    const externalMeta = useSmartFieldMeta({ field });
    const validationState = useMemo(() => validateJsonEditorText(draft), [draft]);
    const jsonError =
      !validationState.valid && jsonTouched
        ? st(
            invalidMessage || '$i18n:common.json_editor.invalid',
            validationState.reason === 'syntax'
              ? 'Invalid JSON syntax'
              : 'JSON must be an object or array',
          )
        : undefined;
    const requiredError = externalMeta.showError
      ? st(externalMeta.meta.error || '$i18n:common.field_required', 'Required')
      : undefined;
    const errorText = jsonError || requiredError;
    const finalVariant = errorText ? 'error' : variant;
    const isDisabled = Boolean(disabledValue);
    const modeLabel = mode === 'schema' ? 'Schema' : 'JSON';

    const emitChange = (nextValue: string) => {
      setDraft(nextValue);
      field.setValue(nextValue);
      onChange?.(nextValue);
    };

    const formatCurrentValue = () => {
      const state = validateJsonEditorText(draft);
      setJsonTouched(true);
      if (!state.valid || state.parsed === undefined) return;
      const formatted = JSON.stringify(state.parsed, null, 2);
      emitChange(formatted);
    };

    const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
      setJsonTouched(true);
      emitChange(e.target.value);
    };

    const handleBlur = () => {
      setJsonTouched(true);
      if (formatOnBlur) {
        formatCurrentValue();
      }
      field.onBlur();
    };

    if (!isVisible) {
      return null;
    }

    return (
      <FieldBase
        id={name}
        label={labelText}
        required={requiredValue}
        error={errorText}
        className={clsx('space-y-1', className)}
      >
        <div className="rounded-control border-border-strong bg-panel shadow-card overflow-hidden border">
          <div className="border-border bg-subtle flex items-center justify-between border-b px-3 py-1.5">
            <div className="flex items-center gap-2">
              <span className="text-caption text-text-2 font-semibold tracking-normal">
                {modeLabel}
              </span>
              <span
                className={clsx(
                  'text-caption rounded-full px-2 py-0.5',
                  validationState.valid
                    ? 'bg-status-green-bg text-status-green'
                    : 'bg-status-red-bg text-status-red',
                )}
                data-testid={`json-editor-status-${name}`}
              >
                {validationState.valid
                  ? st('$i18n:common.json_editor.valid', 'Valid')
                  : st('$i18n:common.json_editor.invalid_short', 'Invalid')}
              </span>
            </div>
            <button
              type="button"
              className="text-caption text-accent hover:bg-accent-weak rounded px-2 py-1 disabled:cursor-not-allowed disabled:opacity-50"
              onClick={formatCurrentValue}
              disabled={isDisabled || readOnly || !validationState.valid}
            >
              {st('$i18n:common.json_editor.format', 'Format')}
            </button>
          </div>
          <BaseTextarea
            ref={ref}
            id={name}
            name={name}
            placeholder={placeholderText}
            disabled={isDisabled}
            readOnly={readOnly}
            required={requiredValue}
            maxLength={maxLength}
            minLength={minLength}
            rows={rows}
            value={draft}
            onChange={handleChange}
            onBlur={handleBlur}
            spellCheck={false}
            data-testid={`json-editor-${name}`}
            className={clsx(
              fieldSizeStyles[size],
              fieldVariantStyles[finalVariant],
              fieldFocusStyles,
              errorText && fieldErrorFocusStyles,
              'min-h-[180px] resize-y rounded-none border-0 font-mono text-sm leading-5 shadow-none',
              isDisabled && 'cursor-not-allowed bg-gray-100 opacity-60',
              autoResize && 'resize-none',
            )}
            {...sanitizeSmartDomProps(restProps as Record<string, unknown>)}
          />
        </div>
      </FieldBase>
    );
  },
);

JsonEditor.displayName = 'JsonEditor';

export default JsonEditor;
