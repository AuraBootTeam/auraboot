/**
 * I18nTextInput - Multi-locale tab input for i18n-enabled fields.
 *
 * Activated when a DSL field has `feature.i18nEnabled: true`.
 * Renders a tab strip for each supported locale (zh-CN / en-US / ja-JP / ko-KR).
 * Each tab contains a text area for the corresponding locale value.
 *
 * The component expects the parent form to manage a locale→value map.
 * On change, it calls `onChange` with the updated map so the parent can
 * build the flat payload patch via `buildI18nPatch`.
 *
 * Props:
 * - `value`    — Record<string, string>: current locale→value map, e.g.
 *                { 'zh-CN': '产品名称', 'en-US': 'Product Name' }
 * - `onChange` — (values: Record<string, string>) => void
 *
 * Alternatively, for simple integration where each locale field is handled
 * separately, pass `fieldCode` + `record` + `onRecordChange` to auto-split:
 * - `fieldCode`      — primary field code
 * - `record`         — current form data
 * - `onRecordChange` — (patch: Record<string, string | null>) => void
 *
 * @since 7.0.0
 */

import React, { useState, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';
import {
  I18N_LOCALE_LABELS,
  I18N_SUPPORTED_LOCALES,
  extractI18nValues,
  buildI18nPatch,
} from '~/meta/utils/i18nFieldHelper';

// ─── Types ────────────────────────────────────────────────────────────────────

/** Locale→value controlled mode */
interface ControlledModeProps {
  /** Current values keyed by locale, e.g. { 'zh-CN': '...', 'en-US': '...' } */
  value?: Record<string, string>;
  onChange?: (values: Record<string, string>) => void;
  fieldCode?: never;
  record?: never;
  onRecordChange?: never;
}

/** Record patch mode — reads/writes to a flat form record */
interface RecordModeProps {
  /** Primary field code (e.g. "product_name") */
  fieldCode: string;
  /** Full form record containing companion fields */
  record: Record<string, unknown>;
  /** Called with the flat patch to merge into the record */
  onRecordChange: (patch: Record<string, string | null>) => void;
  value?: never;
  onChange?: never;
}

type ValueProps = ControlledModeProps | RecordModeProps;

interface SharedProps {
  label?: string;
  placeholder?: string;
  required?: boolean;
  disabled?: boolean;
  readOnly?: boolean;
  rows?: number;
  className?: string;
}

export type I18nTextInputProps = SharedProps & ValueProps;

// ─── Component ────────────────────────────────────────────────────────────────

const ALL_LOCALES = ['zh-CN', ...I18N_SUPPORTED_LOCALES] as const;

const I18nTextInput: React.FC<I18nTextInputProps> = (props) => {
  const {
    label,
    placeholder,
    required = false,
    disabled = false,
    readOnly = false,
    rows = 3,
    className = '',
  } = props;

  const { locale: contextLocale } = useI18n();

  // ── Derive initial values ─────────────────────────────────────────────────
  let initialValues: Record<string, string>;
  if ('fieldCode' in props && props.fieldCode) {
    initialValues = extractI18nValues(props.record, props.fieldCode);
  } else {
    initialValues = (props as ControlledModeProps).value ?? {};
  }

  // ── Active tab state (default to context locale, fallback to zh-CN) ───────
  const defaultTab = ALL_LOCALES.includes(contextLocale as (typeof ALL_LOCALES)[number])
    ? contextLocale
    : 'zh-CN';
  const [activeLocale, setActiveLocale] = useState<string>(defaultTab);

  // ── Handle text change in one locale ─────────────────────────────────────
  const handleChange = useCallback(
    (locale: string, text: string) => {
      if ('fieldCode' in props && props.fieldCode) {
        const newValues = { ...initialValues, [locale]: text };
        const patch = buildI18nPatch(props.fieldCode, newValues);
        props.onRecordChange(patch);
      } else {
        const newValues = { ...(props.value ?? {}), [locale]: text };
        props.onChange?.(newValues);
      }
    },
    [props, initialValues],
  );

  // ─── Render ───────────────────────────────────────────────────────────────
  return (
    <div className={`smart-i18n-text-input ${className}`}>
      {/* Field label */}
      {label && (
        <label className="block text-sm font-medium text-gray-700 mb-1">
          {label}
          {required && <span className="ml-1 text-red-500">*</span>}
        </label>
      )}

      {/* Locale tab strip */}
      <div className="border border-gray-200 rounded-md overflow-hidden">
        {/* Tabs */}
        <div className="flex border-b border-gray-200 bg-gray-50">
          {ALL_LOCALES.map((locale) => (
            <button
              key={locale}
              type="button"
              onClick={() => setActiveLocale(locale)}
              className={[
                'px-3 py-2 text-xs font-medium transition-colors',
                activeLocale === locale
                  ? 'bg-white text-blue-600 border-b-2 border-blue-500 -mb-px'
                  : 'text-gray-500 hover:text-gray-700 hover:bg-gray-100',
              ]
                .filter(Boolean)
                .join(' ')}
            >
              {I18N_LOCALE_LABELS[locale] ?? locale}
            </button>
          ))}
        </div>

        {/* Text area for active locale */}
        <div className="p-2">
          <textarea
            rows={rows}
            disabled={disabled}
            readOnly={readOnly}
            placeholder={placeholder ?? (activeLocale === 'zh-CN' ? '请输入（默认语言）' : `Enter ${I18N_LOCALE_LABELS[activeLocale] ?? activeLocale} translation`)}
            value={initialValues[activeLocale] ?? ''}
            onChange={(e) => handleChange(activeLocale, e.target.value)}
            className={[
              'w-full resize-none text-sm outline-none',
              'placeholder:text-gray-400',
              disabled || readOnly ? 'bg-gray-50 text-gray-500 cursor-not-allowed' : 'bg-white text-gray-900',
            ].join(' ')}
          />
        </div>
      </div>

      {/* Missing translations hint */}
      {!readOnly && !disabled && (
        <MissingTranslationsHint values={initialValues} />
      )}
    </div>
  );
};

// ── Helper: show a compact chip for locales that have no translation yet ────

interface MissingTranslationsHintProps {
  values: Record<string, string>;
}

const MissingTranslationsHint: React.FC<MissingTranslationsHintProps> = ({ values }) => {
  const missing = ALL_LOCALES.filter(
    (locale) => locale !== 'zh-CN' && (!values[locale] || values[locale].trim() === ''),
  );

  if (missing.length === 0) return null;

  return (
    <p className="mt-1 text-xs text-amber-600">
      缺少翻译:{' '}
      {missing.map((locale) => I18N_LOCALE_LABELS[locale] ?? locale).join('、')}
    </p>
  );
};

export default I18nTextInput;
