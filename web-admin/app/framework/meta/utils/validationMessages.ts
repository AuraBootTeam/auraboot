import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';

export function buildRequiredFieldMessage(
  // Accept a raw label which may be a LocalizedText object ({ "zh-CN": "..." }),
  // an $i18n: key, or a plain string. Callers pass rawField.label / displayName
  // untouched, so we resolve here to avoid rendering "[object Object]".
  label: unknown,
  options?: {
    dataType?: string;
    component?: string;
    locale?: string;
    t?: (key: string) => string;
  },
): string {
  const normalizedLabel =
    String(getLocalizedText(label as any, options?.locale, options?.t) || '').trim() ||
    'This field';
  const normalizedDataType = String(options?.dataType || '').toLowerCase();
  const normalizedComponent = String(options?.component || '').toLowerCase();
  const locale = String(options?.locale || 'en-US');
  const t = options?.t;

  if (!locale.toLowerCase().startsWith('zh')) {
    const requiredMsg = t?.('common.validation.required');
    return `${normalizedLabel} ${
      requiredMsg && requiredMsg !== 'common.validation.required' ? requiredMsg : 'is required'
    }`;
  }

  const selectLikeComponents = new Set([
    'smartselect',
    'select',
    'memberpicker',
    'userselect',
    'organizationselect',
    'treeselect',
    'cascadeselect',
    'radio',
    'checkbox',
    'smartdatepicker',
    'datepicker',
    'date',
    'daterange',
  ]);

  if (normalizedDataType === 'file' || normalizedComponent === 'upload' || normalizedComponent === 'smartupload') {
    return `请上传${normalizedLabel}`;
  }

  if (
    selectLikeComponents.has(normalizedComponent) ||
    ['enum', 'reference', 'date', 'datetime', 'boolean'].includes(normalizedDataType)
  ) {
    return `请选择${normalizedLabel}`;
  }

  return `请填写${normalizedLabel}`;
}

/**
 * Localized range/format constraint message for DSL schema rules
 * (minLength / maxLength / minValue / maxValue / pattern). Mirrors
 * buildRequiredFieldMessage: zh-CN gets 请填写/请选择-style phrasing, other
 * locales get English.
 */
export function buildConstraintFieldMessage(
  label: unknown,
  rule: { type?: string; minLength?: number; maxLength?: number; minValue?: number; maxValue?: number; pattern?: string },
  options?: {
    locale?: string;
    t?: (key: string) => string;
  },
): string {
  const normalizedLabel =
    String(getLocalizedText(label as any, options?.locale, options?.t) || '').trim() || 'This field';
  const locale = String(options?.locale || 'en-US');
  const zh = locale.toLowerCase().startsWith('zh');
  switch (rule.type) {
    case 'minLength':
      return zh
        ? `${normalizedLabel}至少 ${rule.minLength} 个字符`
        : `${normalizedLabel} must be at least ${rule.minLength} characters`;
    case 'maxLength':
      return zh
        ? `${normalizedLabel}最多 ${rule.maxLength} 个字符`
        : `${normalizedLabel} must be at most ${rule.maxLength} characters`;
    case 'minValue':
      return zh
        ? `${normalizedLabel}不能小于 ${rule.minValue}`
        : `${normalizedLabel} must be at least ${rule.minValue}`;
    case 'maxValue':
      return zh
        ? `${normalizedLabel}不能大于 ${rule.maxValue}`
        : `${normalizedLabel} must be at most ${rule.maxValue}`;
    case 'pattern':
      return zh ? `${normalizedLabel}格式不正确` : `${normalizedLabel} format is invalid`;
    default:
      return zh ? `${normalizedLabel}不符合约束` : `${normalizedLabel} violates a validation rule`;
  }
}
