/**
 * Shared utilities for dynamic routes
 * Eliminates code duplication across list/new/edit/view pages
 */

import React from 'react';
import { sanitizeHtml } from '~/framework/meta/utils/sanitizeHtml';
import { MemberPicker } from '~/ui/smart/picker/MemberPicker';

// 导入并重新导出统一的 i18n 实现
import {
  getLocalizedText,
  type LocalizedText,
  type TranslatableText,
  type TranslateFunction,
} from '~/framework/meta/runtime/expression/i18n-renderer';

// 重新导出供外部使用
export { getLocalizedText, type LocalizedText, type TranslatableText, type TranslateFunction };

function parseMemberPickerValue(value: unknown, multiple: boolean): string | string[] | undefined {
  if (value == null || value === '') return undefined;

  if (Array.isArray(value)) {
    const ids = value
      .map((item) => String(item ?? '').trim())
      .filter(Boolean);
    return multiple ? ids : ids[0];
  }

  if (typeof value === 'string') {
    const trimmed = value.trim();
    if (!trimmed) return undefined;

    if (trimmed.startsWith('[')) {
      try {
        const parsed = JSON.parse(trimmed);
        if (Array.isArray(parsed)) {
          const ids = parsed
            .map((item) => String(item ?? '').trim())
            .filter(Boolean);
          return multiple ? ids : ids[0];
        }
      } catch {
        // Ignore malformed persisted payloads and fall back to raw string display.
      }
    }

    return multiple ? [trimmed] : trimmed;
  }

  const normalized = String(value).trim();
  if (!normalized) return undefined;
  return multiple ? [normalized] : normalized;
}

/**
 * Build API endpoint with table name
 */
export function buildApiEndpoint(tableName: string, recordId?: string): string {
  const base = `/api/dynamic/${tableName}`;
  return recordId ? `${base}/${recordId}` : base;
}

// ============================================
// DynamicField Component for legacy pages
// ============================================

interface FieldConfig {
  field: string;
  label?: string | LocalizedText;
  component?: string;
  dictCode?: string;
  props?: Record<string, any>;
  validation?: ValidationRule[];
  span?: number;
  layout?: { colSpan?: number };
}

interface ValidationRule {
  type: string;
  message: string | LocalizedText;
  pattern?: string;
  min?: number;
  max?: number;
}

interface DynamicFieldProps {
  field: FieldConfig;
  value: any;
  onChange: (value: any) => void;
  readOnly?: boolean;
  locale?: string;
  getDictItems?: (
    code: string,
  ) => Array<{ value: string; label: string; extension?: Record<string, any> }>;
}

/**
 * DynamicField - Legacy field renderer for dynamic pages
 * Supports common field types with read-only mode
 */
export const DynamicField: React.FC<DynamicFieldProps> = ({
  field,
  value,
  onChange,
  readOnly = false,
  locale = 'zh-CN',
  getDictItems,
}) => {
  const label =
    typeof field.label === 'string'
      ? field.label
      : getLocalizedText(field.label, locale) || field.field;

  const isRequired = field.validation?.some((v) => v.type === 'required');
  const componentType = field.component?.toLowerCase() || 'smartinput';

  // Common input classes
  const inputClasses = `w-full px-3 py-2 border border-gray-300 rounded-md shadow-sm focus:outline-none focus:ring-1 focus:ring-blue-500 focus:border-blue-500 ${
    readOnly ? 'bg-gray-50 cursor-not-allowed' : ''
  }`;

  const renderField = () => {
    // Read-only display
    if (readOnly) {
      if (componentType === 'memberpicker') {
        const memberValue = parseMemberPickerValue(value, Boolean(field.props?.multiple));
        return (
          <MemberPicker
            value={memberValue}
            multiple={Boolean(field.props?.multiple)}
            readOnly
            className="py-1"
          />
        );
      }

      // 1. Dict field with color tag
      if (field.dictCode && getDictItems) {
        const items = getDictItems(field.dictCode);
        const item = items.find((i) => String(i.value) === String(value));
        if (item) {
          const color = item.extension?.color || 'gray';
          const TAG_COLORS: Record<string, string> = {
            gray: 'bg-gray-100 text-gray-800',
            red: 'bg-red-100 text-red-800',
            orange: 'bg-orange-100 text-orange-800',
            yellow: 'bg-yellow-100 text-yellow-800',
            green: 'bg-green-100 text-green-800',
            blue: 'bg-blue-100 text-blue-800',
            indigo: 'bg-indigo-100 text-indigo-800',
            purple: 'bg-purple-100 text-purple-800',
            pink: 'bg-pink-100 text-pink-800',
            cyan: 'bg-cyan-100 text-cyan-800',
          };
          const cls = TAG_COLORS[color] || TAG_COLORS.gray;
          return (
            <span
              className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${cls}`}
            >
              {item.label}
            </span>
          );
        }
      }

      // 2. Boolean as visual toggle
      if (['smartswitch', 'switch', 'smartcheckbox', 'checkbox'].includes(componentType)) {
        return (
          <div
            className={`relative inline-flex h-6 w-11 items-center rounded-full ${value ? 'bg-blue-600' : 'bg-gray-200'}`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${value ? 'translate-x-6' : 'translate-x-1'}`}
            />
          </div>
        );
      }

      // 3. Progress bar
      if (['progress', 'progressfield'].includes(componentType)) {
        const pct = Number(value) || 0;
        return (
          <div className="flex items-center gap-2">
            <div className="h-2 flex-1 rounded-full bg-gray-200">
              <div
                className="h-2 rounded-full bg-blue-600"
                style={{ width: `${Math.min(pct, 100)}%` }}
              />
            </div>
            <span className="text-sm text-gray-600">{pct}%</span>
          </div>
        );
      }

      // 4. Rating stars
      if (['rating', 'ratingfield'].includes(componentType)) {
        const stars = Number(value) || 0;
        return (
          <div className="flex gap-0.5">
            {[1, 2, 3, 4, 5].map((i) => (
              <svg
                key={i}
                className={`h-5 w-5 ${i <= stars ? 'text-yellow-400' : 'text-gray-300'}`}
                fill="currentColor"
                viewBox="0 0 20 20"
              >
                <path d="M9.049 2.927c.3-.921 1.603-.921 1.902 0l1.07 3.292a1 1 0 00.95.69h3.462c.969 0 1.371 1.24.588 1.81l-2.8 2.034a1 1 0 00-.364 1.118l1.07 3.292c.3.921-.755 1.688-1.54 1.118l-2.8-2.034a1 1 0 00-1.175 0l-2.8 2.034c-.784.57-1.838-.197-1.539-1.118l1.07-3.292a1 1 0 00-.364-1.118L2.98 8.72c-.783-.57-.38-1.81.588-1.81h3.461a1 1 0 00.951-.69l1.07-3.292z" />
              </svg>
            ))}
          </div>
        );
      }

      // 5. Color picker - show color swatch
      if (['colorpicker', 'color_picker', 'color'].includes(componentType) && value) {
        return (
          <div className="flex items-center gap-2 py-1">
            <div
              className="h-6 w-6 rounded border border-gray-300"
              style={{ backgroundColor: String(value) }}
            />
            <span className="text-sm text-gray-700">{String(value)}</span>
          </div>
        );
      }

      // 6. Rich text - render HTML content
      if (['richtext', 'richtexteditor', 'rich_text'].includes(componentType) && value) {
        return (
          <div
            className="prose prose-sm max-w-none rounded-md border border-gray-200 bg-gray-50 p-3 text-gray-900"
            dangerouslySetInnerHTML={{ __html: sanitizeHtml(String(value)) }}
          />
        );
      }

      // 7. File attachment - render download links
      if (['fileattachment', 'file_attachment', 'attachment'].includes(componentType) && value) {
        let files: Array<{ name: string; url: string; size?: number }> = [];
        try {
          files = typeof value === 'string' ? JSON.parse(value) : Array.isArray(value) ? value : [];
        } catch { /* ignore parse errors */ }
        if (files.length === 0) {
          return <span className="py-1 text-sm text-gray-400">&mdash;</span>;
        }
        return (
          <div className="space-y-1 py-1">
            {files.map((f, i) => (
              <a
                key={i}
                href={f.url}
                target="_blank"
                rel="noopener noreferrer"
                className="flex items-center gap-2 text-sm text-blue-600 hover:underline"
              >
                <svg className="h-4 w-4 flex-shrink-0" fill="none" stroke="currentColor" viewBox="0 0 24 24">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M15.172 7l-6.586 6.586a2 2 0 102.828 2.828l6.414-6.586a4 4 0 00-5.656-5.656l-6.415 6.585a6 6 0 108.486 8.486L20.5 13" />
                </svg>
                {f.name}{f.size ? ` (${f.size < 1024 ? f.size + ' B' : f.size < 1048576 ? (f.size / 1024).toFixed(1) + ' KB' : (f.size / 1048576).toFixed(1) + ' MB'})` : ''}
              </a>
            ))}
          </div>
        );
      }

      // 8. Date/time formatting, options lookup, null handling
      let displayValue = value;
      const options = field.props?.options || [];

      if (['smartdate', 'date'].includes(componentType) && value) {
        displayValue = new Date(value).toLocaleDateString(locale);
      } else if (['smartdatetime', 'datetime'].includes(componentType) && value) {
        displayValue = new Date(value).toLocaleString(locale);
      } else if (options.length > 0) {
        const matchedOption = options.find((opt: any) => String(opt?.value) === String(value));
        if (matchedOption) {
          displayValue =
            typeof matchedOption.label === 'string'
              ? matchedOption.label
              : getLocalizedText(matchedOption.label, locale) || matchedOption.value;
        }
      } else if (value === null || value === undefined) {
        displayValue = '-';
      }

      // 9. URL detection - render as clickable link
      if (displayValue && typeof displayValue === 'string' && /^https?:\/\/.+/i.test(displayValue)) {
        return (
          <div className="py-1 text-sm">
            <a href={displayValue} target="_blank" rel="noopener noreferrer" className="text-blue-600 hover:text-blue-800 hover:underline">
              {displayValue}
            </a>
          </div>
        );
      }

      // 10. Email detection - render as mailto link
      if (displayValue && typeof displayValue === 'string' && /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(displayValue)) {
        return (
          <div className="py-1 text-sm">
            <a href={`mailto:${displayValue}`} className="text-blue-600 hover:text-blue-800 hover:underline">
              {displayValue}
            </a>
          </div>
        );
      }

      return (
        <div className="py-1 text-sm text-gray-900">
          {displayValue === '-' ? (
            <span className="text-gray-400">&mdash;</span>
          ) : (
            String(displayValue)
          )}
        </div>
      );
    }

    // Editable fields
    switch (componentType) {
      case 'smarttextarea':
      case 'textarea':
        return (
          <textarea
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            rows={field.props?.rows || 3}
            className={inputClasses}
            placeholder={field.props?.placeholder}
          />
        );

      case 'smartnumber':
      case 'number':
        return (
          <input
            type="number"
            value={value ?? ''}
            onChange={(e) => onChange(e.target.value ? Number(e.target.value) : null)}
            className={inputClasses}
            min={field.props?.min}
            max={field.props?.max}
            step={field.props?.step}
            placeholder={field.props?.placeholder}
          />
        );

      case 'smartdate':
      case 'date':
        return (
          <input
            type="date"
            value={value ? value.substring(0, 10) : ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          />
        );

      case 'smartdatetime':
      case 'datetime':
        return (
          <input
            type="datetime-local"
            value={value ? value.substring(0, 16) : ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          />
        );

      case 'smartcheckbox':
      case 'checkbox':
        return (
          <input
            type="checkbox"
            checked={!!value}
            onChange={(e) => onChange(e.target.checked)}
            className="h-4 w-4 rounded border-gray-300 text-blue-600 focus:ring-blue-500"
          />
        );

      case 'smartswitch':
      case 'switch':
        return (
          <button
            type="button"
            onClick={() => onChange(!value)}
            className={`relative inline-flex h-6 w-11 items-center rounded-full transition-colors ${
              value ? 'bg-blue-600' : 'bg-gray-200'
            }`}
          >
            <span
              className={`inline-block h-4 w-4 transform rounded-full bg-white transition-transform ${
                value ? 'translate-x-6' : 'translate-x-1'
              }`}
            />
          </button>
        );

      case 'smartselect':
      case 'select':
        const options = field.props?.options || [];
        return (
          <select
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
          >
            <option value="">{field.props?.placeholder || 'Please select'}</option>
            {options.map((opt: any) => (
              <option key={opt.value} value={opt.value}>
                {typeof opt.label === 'string' ? opt.label : getLocalizedText(opt.label, locale)}
              </option>
            ))}
          </select>
        );

      case 'smartinput':
      case 'text':
      default:
        return (
          <input
            type="text"
            value={value || ''}
            onChange={(e) => onChange(e.target.value)}
            className={inputClasses}
            placeholder={field.props?.placeholder}
            maxLength={field.props?.maxLength}
          />
        );
    }
  };

  return (
    <div className="mb-4">
      <label className="mb-0.5 block text-xs font-medium tracking-wide text-gray-500 uppercase">
        {label}
        {isRequired && <span className="ml-1 text-red-500">*</span>}
      </label>
      {renderField()}
    </div>
  );
};

// ============================================
// Form validation utilities
// ============================================

interface ValidationResult {
  isValid: boolean;
  errors: Record<string, string>;
}

/**
 * Validate form data against field definitions
 */
export function validateForm(
  formData: Record<string, any>,
  fields: FieldConfig[],
  locale: string = 'zh-CN',
): ValidationResult {
  const errors: Record<string, string> = {};

  for (const field of fields) {
    const value = formData[field.field];
    const rules = field.validation || [];

    for (const rule of rules) {
      const message =
        typeof rule.message === 'string'
          ? rule.message
          : getLocalizedText(rule.message, locale) || `${field.field} validation failed`;

      switch (rule.type) {
        case 'required':
          if (value === undefined || value === null || value === '') {
            errors[field.field] = message;
          }
          break;

        case 'min':
          if (typeof value === 'number' && rule.min !== undefined && value < rule.min) {
            errors[field.field] = message;
          }
          if (typeof value === 'string' && rule.min !== undefined && value.length < rule.min) {
            errors[field.field] = message;
          }
          break;

        case 'max':
          if (typeof value === 'number' && rule.max !== undefined && value > rule.max) {
            errors[field.field] = message;
          }
          if (typeof value === 'string' && rule.max !== undefined && value.length > rule.max) {
            errors[field.field] = message;
          }
          break;

        case 'pattern':
          if (rule.pattern && typeof value === 'string') {
            const regex = new RegExp(rule.pattern);
            if (!regex.test(value)) {
              errors[field.field] = message;
            }
          }
          break;

        case 'email':
          if (value && typeof value === 'string') {
            const emailRegex = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
            if (!emailRegex.test(value)) {
              errors[field.field] = message;
            }
          }
          break;
      }

      // Stop at first error for this field
      if (errors[field.field]) break;
    }
  }

  return {
    isValid: Object.keys(errors).length === 0,
    errors,
  };
}

/**
 * Extract form fields from a UnifiedSchema
 */
export function getFormFields(schema: any): FieldConfig[] {
  const fields: FieldConfig[] = [];

  if (!schema?.blocks) return fields;

  // Iterate through all blocks to find fields
  for (const block of schema.blocks) {
    // Check various block types that contain fields
    if (block.fields && Array.isArray(block.fields)) {
      fields.push(...block.fields);
    }
  }

  return fields;
}
