/**
 * LocalizedTextInput — designer-side editor for LocalizedText DSL values.
 *
 * Value shapes supported:
 *   - `undefined` / `null` / `''`  → empty state
 *   - `"plain string"`             → single-locale (zh-CN) mode
 *   - `"$i18n:some.key"`           → i18n-key mode (pass-through, edited as string)
 *   - `{ "zh-CN": "...", "en-US": "..." }` → multi-locale object mode
 *
 * UX:
 *   - Collapsed (default): single input bound to zh-CN. "+ 多语言" button expands.
 *   - Expanded: zh-CN + en-US inputs stacked. "- 折叠" button hides en-US (keeps
 *     value as object if en-US has content, otherwise collapses to string).
 *   - `$i18n:` prefix auto-detected: the component stays in single-string mode and
 *     no expansion is offered (the key is the authoritative value).
 *
 * This is the ONLY sanctioned way to edit user-facing DSL labels in the designer.
 * String-only inputs are being deprecated from section title / field label /
 * button label editors.
 */

import React, { useState, useMemo, useCallback } from 'react';
import { useI18n } from '~/contexts/I18nContext';

export type LocalizedTextValue = string | { [locale: string]: string } | undefined | null;

export interface LocalizedTextInputProps {
  value: LocalizedTextValue;
  onChange: (next: LocalizedTextValue) => void;
  label?: string;
  placeholder?: string;
  disabled?: boolean;
  /** Optional test-id prefix. Inputs will be `${testId}-zh`, `${testId}-en`. */
  testId?: string;
  className?: string;
}

const LOCALES: Array<{ code: string; short: string }> = [
  { code: 'zh-CN', short: '中' },
  { code: 'en-US', short: 'EN' },
];

function isI18nKey(v: unknown): v is string {
  return typeof v === 'string' && v.startsWith('$i18n:');
}

function isObjectForm(v: unknown): v is Record<string, string> {
  return (
    !!v &&
    typeof v === 'object' &&
    !Array.isArray(v) &&
    Object.values(v as Record<string, unknown>).every((x) => typeof x === 'string')
  );
}

function readLocale(v: LocalizedTextValue, locale: string): string {
  if (!v) return '';
  if (typeof v === 'string') {
    // String form is treated as zh-CN content; other locales start empty.
    return locale === 'zh-CN' ? v : '';
  }
  if (isObjectForm(v)) {
    return v[locale] ?? '';
  }
  return '';
}

/**
 * Decide the output shape:
 *   - Both empty → undefined
 *   - Only zh-CN filled and component not expanded → plain string
 *   - Otherwise → object form with whichever locales have content
 */
function buildOutput(zh: string, en: string, expanded: boolean): LocalizedTextValue {
  const zhTrim = zh;
  const enTrim = en;
  if (!zhTrim && !enTrim) return undefined;
  if (!expanded) {
    // Collapsed mode: always string form (en is ignored).
    return zhTrim || undefined;
  }
  // Expanded: emit object form so i18n resolver can pick the right locale.
  const out: Record<string, string> = {};
  if (zhTrim) out['zh-CN'] = zhTrim;
  if (enTrim) out['en-US'] = enTrim;
  if (Object.keys(out).length === 0) return undefined;
  // If only zh-CN was filled, still emit object form in expanded mode to make
  // the user's intent explicit (they opened multi-lang on purpose).
  return out;
}

export const LocalizedTextInput: React.FC<LocalizedTextInputProps> = ({
  value,
  onChange,
  label,
  placeholder,
  disabled,
  testId,
  className,
}) => {
  const { locale } = useI18n();
  const l = useCallback((zh: string, en: string) => (locale === 'zh-CN' ? zh : en), [locale]);
  // Auto-expand when the incoming value is already an object-form with en-US
  // content, so reopening a page with multilingual data shows both inputs.
  const initialExpanded = useMemo(() => {
    if (isObjectForm(value)) {
      return Object.keys(value).some((k) => k !== 'zh-CN' && value[k]);
    }
    return false;
  }, [value]);

  const [expanded, setExpanded] = useState<boolean>(initialExpanded);

  // $i18n: pass-through — render a single input, no expansion.
  const i18nMode = isI18nKey(value);

  const zh = readLocale(value, 'zh-CN');
  const en = readLocale(value, 'en-US');

  const handleLocaleChange = useCallback(
    (locale: string, next: string) => {
      if (i18nMode) {
        onChange(next);
        return;
      }
      if (locale === 'zh-CN') {
        onChange(buildOutput(next, en, expanded));
      } else {
        onChange(buildOutput(zh, next, expanded));
      }
    },
    [onChange, zh, en, expanded, i18nMode],
  );

  const toggleExpanded = useCallback(() => {
    setExpanded((prev) => {
      const next = !prev;
      // On collapse: preserve string form if en was empty, otherwise keep obj.
      if (!next) {
        onChange(buildOutput(zh, en, false));
      } else {
        onChange(buildOutput(zh, en, true));
      }
      return next;
    });
  }, [onChange, zh, en]);

  return (
    <div className={className}>
      {/* Toggle row — always renders (label optional) so wrapping in
          <PropertyField label="..."> still surfaces the "+ 多语言" button. */}
      {(label || !i18nMode) && (
        <div className="mb-1 flex items-center justify-between">
          {label ? (
            <label className="block text-xs font-medium text-gray-600">{label}</label>
          ) : (
            <span />
          )}
          {!i18nMode && (
            <button
              type="button"
              onClick={toggleExpanded}
              disabled={disabled}
              data-testid={testId ? `${testId}-toggle` : undefined}
              className="text-[10px] text-blue-600 hover:underline disabled:text-gray-400"
            >
              {expanded ? l('- 折叠', '- Collapse') : l('+ 多语言', '+ Locales')}
            </button>
          )}
        </div>
      )}

      {/* zh-CN (always visible) */}
      <div className={expanded && !i18nMode ? 'mb-2' : ''}>
        {expanded && !i18nMode && (
          <span className="mr-2 inline-block w-6 text-[10px] text-gray-400">
            {LOCALES[0].short}
          </span>
        )}
        <input
          type="text"
          value={zh}
          onChange={(e) => handleLocaleChange('zh-CN', e.target.value)}
          placeholder={
            i18nMode
              ? '$i18n:your.key'
              : placeholder || (expanded ? l('中文', 'Chinese') : undefined)
          }
          disabled={disabled}
          data-testid={testId ? `${testId}-zh` : undefined}
          className={`${expanded && !i18nMode ? 'w-[calc(100%-2rem)]' : 'w-full'} rounded border border-gray-200 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none ${disabled ? 'bg-gray-50 text-gray-400' : 'bg-white'}`}
        />
      </div>

      {/* en-US (visible only when expanded) */}
      {expanded && !i18nMode && (
        <div>
          <span className="mr-2 inline-block w-6 text-[10px] text-gray-400">
            {LOCALES[1].short}
          </span>
          <input
            type="text"
            value={en}
            onChange={(e) => handleLocaleChange('en-US', e.target.value)}
            placeholder={l('英文', 'English')}
            disabled={disabled}
            data-testid={testId ? `${testId}-en` : undefined}
            className={`w-[calc(100%-2rem)] rounded border border-gray-200 px-2 py-1.5 text-sm focus:border-blue-500 focus:ring-1 focus:ring-blue-500 focus:outline-none ${disabled ? 'bg-gray-50 text-gray-400' : 'bg-white'}`}
          />
        </div>
      )}
    </div>
  );
};

export default LocalizedTextInput;
