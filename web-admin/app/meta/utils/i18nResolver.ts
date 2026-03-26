/**
 * I18n Resolver - DSL Schema i18n Key Resolution
 *
 * This module resolves semantic attributes in DSL schema to i18n keys.
 * Instead of storing hardcoded `$i18n:xxx` strings in DSL, we use semantic
 * attributes (action, field, messageKey) that are resolved at runtime.
 *
 * Resolution Rules:
 * 1. Button `action` → `action.{action}` (e.g., action.create → "新建")
 * 2. Field `field` + modelCode → `model.{modelCode}.{fieldCode}.label`
 * 3. `messageKey` → `message.{messageKey}` (e.g., message.delete.success)
 * 4. `contentKey` → special resolution (e.g., selectedInfo → table.selected)
 * 5. Table column `isActionColumn` → `table.actions`
 * 6. Form/Detail section → default "基本信息" / "详细信息"
 *
 * @author AuraBoot
 */

import type { TranslateFunction } from '~/meta/runtime/expression/i18n-renderer';

/**
 * Button definition from DSL
 */
export interface DslButton {
  code: string;
  action?: string;
  content?: string; // Legacy: may contain $i18n:xxx
  primary?: boolean;
  danger?: boolean;
  visibleWhen?: string;
  enableWhen?: string;
  events?: Record<string, any>;
}

/**
 * Field definition from DSL
 */
export interface DslField {
  field: string;
  label?: string; // Legacy: may contain $i18n:xxx
  component?: string;
  props?: Record<string, any>;
  layout?: Record<string, any>;
  validation?: Array<{ type: string; message?: string }>;
}

/**
 * Column definition from DSL
 */
export interface DslColumn {
  field: string;
  label?: string; // Legacy: may contain $i18n:xxx
  isActionColumn?: boolean;
  valueType?: string;
  sortable?: boolean;
  buttons?: DslButton[];
}

/**
 * Handler step definition from DSL
 */
export interface DslHandlerStep {
  action: string;
  args?: {
    messageKey?: string;
    title?: string;
    content?: string;
  };
  messageKey?: string;
  content?: string; // Legacy: may contain $i18n:xxx
  level?: string;
}

/**
 * Content key mapping for special content types
 */
const CONTENT_KEY_MAP: Record<string, string> = {
  selectedInfo: 'table.selected',
};

/**
 * Default fallback values for common UI elements
 */
const DEFAULT_FALLBACKS: Record<string, Record<string, string>> = {
  'zh-CN': {
    'form.section.default': '基本信息',
    'detail.section.default': '详细信息',
  },
  'en-US': {
    'form.section.default': 'Basic Information',
    'detail.section.default': 'Detail Information',
  },
};

/**
 * Resolve button label from action attribute
 *
 * @param button - Button definition
 * @param t - Translate function
 * @returns Resolved button label
 *
 * @example
 * resolveButtonLabel({ code: 'create', action: 'create' }, t) // => "新建"
 */
export function resolveButtonLabel(button: DslButton, t: TranslateFunction): string {
  // If action is defined, resolve from action.{action}
  if (button.action) {
    const key = `action.${button.action}`;
    const label = t(key);
    // If translation exists (not same as key), return it
    if (label !== key) {
      return label;
    }
  }

  // Legacy: if content starts with $i18n:, resolve it
  if (button.content?.startsWith('$i18n:')) {
    const key = button.content.slice(6);
    return t(key);
  }

  // Fallback to content or code
  return button.content || button.code;
}

/**
 * Resolve field label from field code and model code
 *
 * Resolution order:
 * 1. model.{modelCode}.{fieldCode}.label
 * 2. common.field.{fieldCode} (for common fields like id, name, code)
 * 3. Field displayName (from Field metadata if available)
 * 4. Field code as fallback
 *
 * @param field - Field definition or field code
 * @param modelCode - Model code
 * @param t - Translate function
 * @param fieldMeta - Optional field metadata with displayName
 * @returns Resolved field label
 */
export function resolveFieldLabel(
  field: DslField | string,
  modelCode: string,
  t: TranslateFunction,
  fieldMeta?: { displayName?: string },
): string {
  const fieldCode = typeof field === 'string' ? field : field.field;

  // Legacy: if label exists and starts with $i18n:, resolve it
  if (typeof field === 'object' && field.label?.startsWith('$i18n:')) {
    const key = field.label.slice(6);
    return t(key);
  }

  // Try model-specific key first
  const modelKey = `model.${modelCode}.${fieldCode}.label`;
  const modelLabel = t(modelKey);
  if (modelLabel !== modelKey) {
    return modelLabel;
  }

  // Try common field key
  const commonKey = `common.field.${fieldCode}`;
  const commonLabel = t(commonKey);
  if (commonLabel !== commonKey) {
    return commonLabel;
  }

  // Use field displayName if available
  if (fieldMeta?.displayName) {
    return fieldMeta.displayName;
  }

  // Fallback to field code
  return fieldCode;
}

/**
 * Resolve field placeholder
 *
 * @param field - Field definition or field code
 * @param modelCode - Model code
 * @param t - Translate function
 * @param fieldLabel - Resolved field label (for generating default placeholder)
 * @returns Resolved placeholder
 */
export function resolveFieldPlaceholder(
  field: DslField | string,
  modelCode: string,
  t: TranslateFunction,
  fieldLabel?: string,
): string {
  const fieldCode = typeof field === 'string' ? field : field.field;

  // Legacy: if placeholder exists in props and starts with $i18n:, resolve it
  if (typeof field === 'object' && field.props?.placeholder?.startsWith('$i18n:')) {
    const key = field.props.placeholder.slice(6);
    return t(key);
  }

  // Try model-specific placeholder key
  const modelKey = `model.${modelCode}.${fieldCode}.placeholder`;
  const modelPlaceholder = t(modelKey);
  if (modelPlaceholder !== modelKey) {
    return modelPlaceholder;
  }

  // Generate default placeholder using field label
  const label = fieldLabel || resolveFieldLabel(field, modelCode, t);
  const placeholderTemplate = t('message.placeholder.input');
  if (placeholderTemplate !== 'message.placeholder.input') {
    // Use ICU format if available
    return placeholderTemplate.replace('{field}', label);
  }

  // Simple fallback
  return `请输入${label}`;
}

/**
 * Resolve table column label
 *
 * @param column - Column definition
 * @param modelCode - Model code
 * @param t - Translate function
 * @returns Resolved column label
 */
export function resolveColumnLabel(
  column: DslColumn,
  modelCode: string,
  t: TranslateFunction,
): string {
  // Action column
  if (column.isActionColumn) {
    const key = 'table.actions';
    const label = t(key);
    return label !== key ? label : '操作';
  }

  // Legacy: if label starts with $i18n:, resolve it
  if (column.label?.startsWith('$i18n:')) {
    const key = column.label.slice(6);
    return t(key);
  }

  // Resolve as field label
  return resolveFieldLabel(column.field, modelCode, t);
}

/**
 * Resolve message from messageKey
 *
 * @param messageKey - Message key (e.g., "delete.success", "delete.confirm")
 * @param t - Translate function
 * @param vars - Optional interpolation variables
 * @returns Resolved message
 */
export function resolveMessage(
  messageKey: string,
  t: TranslateFunction,
  vars?: Record<string, any>,
): string {
  const key = `message.${messageKey}`;
  const message = t(key, vars);
  return message !== key ? message : messageKey;
}

/**
 * Resolve dialog confirmation (title + content)
 *
 * @param messageKey - Message key (e.g., "delete.confirm")
 * @param t - Translate function
 * @returns Resolved title and content
 */
const CONFIRM_DIALOG_FALLBACKS: Record<string, { title: string; content: string }> = {
  'confirm.delete': { title: '确认删除', content: '此操作不可撤销，确定要删除吗？' },
  'confirm.archive': { title: '确认归档', content: '归档后将无法编辑，确定要归档吗？' },
  'confirm.submit': { title: '确认提交', content: '提交后将进入审批流程，确定要提交吗？' },
};

export function resolveConfirmDialog(
  messageKey: string,
  t: TranslateFunction,
): { title: string; content: string } {
  const titleKey = `message.${messageKey}.title`;
  const contentKey = `message.${messageKey}.content`;

  const title = t(titleKey);
  const content = t(contentKey);

  const fallback = CONFIRM_DIALOG_FALLBACKS[messageKey];
  return {
    title: title !== titleKey ? title : (fallback?.title ?? '确认'),
    content: content !== contentKey ? content : (fallback?.content ?? ''),
  };
}

/**
 * Resolve content from contentKey
 *
 * @param contentKey - Content key (e.g., "selectedInfo")
 * @param t - Translate function
 * @param vars - Optional interpolation variables
 * @returns Resolved content
 */
export function resolveContent(
  contentKey: string,
  t: TranslateFunction,
  vars?: Record<string, any>,
): string {
  // Map to actual i18n key
  const mappedKey = CONTENT_KEY_MAP[contentKey] || contentKey;
  const content = t(mappedKey, vars);
  return content !== mappedKey ? content : contentKey;
}

/**
 * Resolve validation message
 *
 * @param validationType - Validation type (e.g., "required", "minLength")
 * @param t - Translate function
 * @param fieldLabel - Field label for interpolation
 * @returns Resolved validation message
 */
export function resolveValidationMessage(
  validationType: string,
  t: TranslateFunction,
  fieldLabel?: string,
): string {
  const key = `message.validation.${validationType}`;
  const message = t(key, { field: fieldLabel });

  if (message !== key) {
    return message;
  }

  // Default fallbacks
  switch (validationType) {
    case 'required':
      return fieldLabel ? `${fieldLabel}为必填项` : '此字段为必填项';
    case 'minLength':
      return '输入内容过短';
    case 'maxLength':
      return '输入内容过长';
    default:
      return '输入格式不正确';
  }
}

/**
 * Resolve section title
 *
 * @param pageType - Page type ("form" or "detail")
 * @param modelCode - Model code
 * @param sectionKey - Optional section key
 * @param t - Translate function
 * @param locale - Current locale
 * @returns Resolved section title
 */
export function resolveSectionTitle(
  pageType: 'form' | 'detail',
  modelCode: string,
  sectionKey: string | undefined,
  t: TranslateFunction,
  locale: string = 'zh-CN',
): string {
  // Try page-specific key
  if (sectionKey) {
    const key = `page.${pageType}.${modelCode}.${sectionKey}`;
    const title = t(key);
    if (title !== key) {
      return title;
    }
  }

  // Use default fallback
  const fallbackKey = `${pageType}.section.default`;
  return DEFAULT_FALLBACKS[locale]?.[fallbackKey] || DEFAULT_FALLBACKS['zh-CN'][fallbackKey];
}

/**
 * Resolve handler step messages (toast, dialog)
 *
 * @param step - Handler step definition
 * @param t - Translate function
 * @returns Step with resolved messages
 */
export function resolveHandlerStep(step: DslHandlerStep, t: TranslateFunction): DslHandlerStep {
  const resolved = { ...step };

  // Resolve messageKey for toast
  if (step.messageKey) {
    resolved.content = resolveMessage(step.messageKey, t);
    delete resolved.messageKey;
  }

  // Resolve dialog.confirm args
  if (step.action === 'dialog.confirm' && step.args?.messageKey) {
    const { title, content } = resolveConfirmDialog(step.args.messageKey, t);
    resolved.args = {
      ...step.args,
      title,
      content,
    };
    delete resolved.args.messageKey;
  }

  return resolved;
}

/**
 * Create an I18nResolver instance with bound modelCode
 *
 * @param modelCode - Model code
 * @param t - Translate function
 * @param locale - Current locale
 * @returns Resolver instance with bound methods
 */
export function createI18nResolver(
  modelCode: string,
  t: TranslateFunction,
  locale: string = 'zh-CN',
) {
  return {
    // Button
    resolveButtonLabel: (button: DslButton) => resolveButtonLabel(button, t),

    // Field
    resolveFieldLabel: (field: DslField | string, fieldMeta?: { displayName?: string }) =>
      resolveFieldLabel(field, modelCode, t, fieldMeta),

    resolveFieldPlaceholder: (field: DslField | string, fieldLabel?: string) =>
      resolveFieldPlaceholder(field, modelCode, t, fieldLabel),

    // Column
    resolveColumnLabel: (column: DslColumn) => resolveColumnLabel(column, modelCode, t),

    // Message
    resolveMessage: (messageKey: string, vars?: Record<string, any>) =>
      resolveMessage(messageKey, t, vars),

    resolveConfirmDialog: (messageKey: string) => resolveConfirmDialog(messageKey, t),

    // Content
    resolveContent: (contentKey: string, vars?: Record<string, any>) =>
      resolveContent(contentKey, t, vars),

    // Validation
    resolveValidationMessage: (validationType: string, fieldLabel?: string) =>
      resolveValidationMessage(validationType, t, fieldLabel),

    // Section
    resolveSectionTitle: (pageType: 'form' | 'detail', sectionKey?: string) =>
      resolveSectionTitle(pageType, modelCode, sectionKey, t, locale),

    // Handler
    resolveHandlerStep: (step: DslHandlerStep) => resolveHandlerStep(step, t),
  };
}

export type I18nResolver = ReturnType<typeof createI18nResolver>;

// ==================== Extension displayName helpers ====================

/**
 * Resolve displayName from field extension (non-i18n context, for useEffect).
 *
 * @param extension - Field extension object
 * @param code - Field code (fallback label)
 * @param locale - Current locale (default 'zh-CN')
 * @returns Resolved display name
 */
export function resolveExtensionDisplayName(
  extension: Record<string, any> | null | undefined,
  code: string,
  locale: string = 'zh-CN',
): string {
  if (extension) {
    const localized = extension[`displayName:${locale}`];
    if (typeof localized === 'string' && localized) return localized;
    const generic = extension.displayName;
    if (typeof generic === 'string' && generic) return generic;
  }
  return humanizeFieldCode(code);
}

/**
 * Convert field code to Title Case label (strip model prefix).
 *
 * @example humanizeFieldCode('pe_order_title') // => 'Title'
 * @example humanizeFieldCode('status') // => 'Status'
 */
export function humanizeFieldCode(code: string): string {
  return code
    .replace(/^[a-z0-9]+_[a-z0-9]+_/, '')
    .replace(/_/g, ' ')
    .replace(/\b\w/g, (c) => c.toUpperCase());
}
