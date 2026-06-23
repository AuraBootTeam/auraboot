/**
 * FormPageContent - Extracted form rendering logic for DSL-driven form pages
 *
 * This component receives a pre-loaded schema and handles all form rendering:
 * - Form sections with smart field components
 * - Sub-table blocks with inline editing
 * - Form buttons with action handling
 * - Edit mode record fetching
 * - Expression-driven visibility conditions
 *
 * Used by DynamicPageRenderer when the page kind is "form".
 */

import { useState, useCallback, useMemo, useEffect, useRef } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useUser, usePermissions } from '~/contexts/AuthContext';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useComputedFields } from '~/framework/meta/hooks/useComputedFields';
import { useToastContext } from '~/contexts/ToastContext';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { scrollToFormField } from '~/framework/meta/rendering/pages/form/scrollToFormField';
import { buildRequiredFieldMessage } from '~/framework/meta/utils/validationMessages';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { SubTable } from '~/framework/meta/components/SubTable';
import { SubTableViewer } from '~/framework/meta/rendering/blocks/SubTableViewer';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';
import { BlockRenderer, BlockErrorBoundary, type PageContentProps } from '@auraboot/runtime-kernel';
import type { SubTableColumn } from '~/framework/meta/components/types';
import { resolveExtensionDisplayName } from '~/framework/meta/utils/i18nResolver';
import { mergeRules as crossFieldMergeRules } from '~/framework/meta/validation/ruleMerger';
import { evaluateCondition as crossFieldEvalCondition } from '~/framework/meta/validation/conditionEvaluator';
import { evaluateAssert as crossFieldEvalAssert } from '~/framework/meta/validation/assertEvaluator';
import { deriveTestId, buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';
import { useModelCapabilities } from '~/shared/hooks/useModelCapabilities';
import { checkKindCompatibility } from '~/shared/utils/kindCapability';
import type { ComputedFieldDef } from '~/framework/meta/runtime/computed/types';
import { useFormDraft } from '~/framework/meta/rendering/pages/form/useFormDraft';
import { RestoreDraftBanner } from '~/framework/meta/rendering/pages/form/RestoreDraftBanner';
import { buildCommandTargetParams } from '~/framework/meta/utils/publicRecordId';

/**
 * Map field dataType to Smart component name.
 * Used to infer component when DSL field does not specify `component`.
 */
const DATA_TYPE_TO_COMPONENT: Record<string, string> = {
  string: 'SmartInput',
  text: 'SmartTextarea',
  decimal: 'SmartNumberInput',
  integer: 'SmartNumberInput',
  enum: 'SmartSelect',
  date: 'SmartDatePicker',
  datetime: 'SmartDatePicker',
  boolean: 'SmartSwitch',
  reference: 'SmartSelect',
  json: 'SmartTextarea',
  jsonb: 'SmartTextarea',
  file: 'SmartUpload',
  money: 'SmartMoneyInput',
};

interface FieldMetaInfo {
  dataType: string;
  displayName?: string;
  dictCode?: string;
  component?: string;
  refTarget?: Record<string, any>;
  referenceModelCode?: string;
  required?: boolean;
  feature?: Record<string, any>;
  ruleSchema?: Record<string, any>;
  extension?: Record<string, any>;
  extensionProps?: Record<string, any>;
}

function mergeRefTarget(
  primary?: Record<string, any> | null,
  secondary?: Record<string, any> | null,
): Record<string, any> | undefined {
  const merged = {
    ...(secondary || {}),
    ...(primary || {}),
  };
  return Object.keys(merged).length > 0 ? merged : undefined;
}

interface NormalizedValidationRule {
  type: string;
  message?: string;
  maxLength?: number;
  minLength?: number;
  minValue?: number;
  maxValue?: number;
  pattern?: string;
}

interface FormValidationResult {
  fieldErrors: Record<string, string>;
  summaryErrors: string[];
}

function pushUniqueMessage(target: string[], message?: string) {
  const normalized = String(message || '').trim();
  if (!normalized || target.includes(normalized)) return;
  target.push(normalized);
}

export function parseValidationSummaryMessages(message?: string | null): string[] {
  if (!message) return [];
  return message
    .split(';')
    .map((item) => item.trim())
    .filter(Boolean)
    .filter((item, index, arr) => arr.indexOf(item) === index);
}

export function resolveFormButtonContent(
  button: Record<string, any>,
  locale: string,
  t: (key: string) => string,
): string {
  return (
    getLocalizedText(button.content || button.label, locale, t) ||
    (typeof button.action === 'string' ? t(`action.${button.action}`) : undefined) ||
    button.code
  );
}

export function normalizeCommandPayloadValue(rawValue: any, dataType?: string): any {
  if (Array.isArray(rawValue) && String(dataType || '').toLowerCase() === 'string') {
    const path = rawValue.filter((item) => typeof item === 'string' && item !== '');
    return path[path.length - 1] ?? '';
  }
  const normalized = normalizePayloadValue(rawValue, dataType);
  if (isJsonLikeDataType(dataType) && normalized != null && typeof normalized === 'object') {
    return JSON.stringify(normalized);
  }
  return normalized;
}

function isEmptySubmittedValue(value: any): boolean {
  if (value === undefined || value === null) return true;
  if (Array.isArray(value) && value.length === 0) return true;
  return (
    value && typeof value === 'object' && !Array.isArray(value) && Object.keys(value).length === 0
  );
}

function collectSubmitPayloadFieldTypes(blocks: any[] | undefined): Record<string, string> {
  const fieldTypes: Record<string, string> = {};

  const visit = (items: any[] | undefined) => {
    for (const block of items ?? []) {
      if (block?.blockType === 'form-section' && Array.isArray(block.fields)) {
        for (const rawField of block.fields) {
          const fieldCode = typeof rawField?.field === 'string' ? rawField.field : '';
          if (!fieldCode || rawField.submitPayload !== true) continue;
          fieldTypes[fieldCode] = String(rawField.dataType || 'string');
        }
      }
      visit(block?.blocks);
      visit(block?.tabs?.flatMap((tab: any) => tab?.blocks ?? []));
    }
  };

  visit(blocks);
  return fieldTypes;
}

export function buildFormCommandPayload(
  actionRecord: Record<string, any>,
  modelFields: Record<string, Pick<FieldMetaInfo, 'dataType'>>,
  blocks?: any[],
): Record<string, any> {
  const submitPayloadFieldTypes = collectSubmitPayloadFieldTypes(blocks);
  const modelFieldEntries = Object.entries(modelFields);

  if (modelFieldEntries.length > 0) {
    return Object.fromEntries(
      Object.entries(actionRecord).flatMap(([key, rawValue]) => {
        const dataType = modelFields[key]?.dataType || submitPayloadFieldTypes[key];
        if (!dataType) return [];
        const value = normalizeCommandPayloadValue(rawValue, dataType);
        if (isEmptySubmittedValue(value)) return [];
        return [[key, value]];
      }),
    );
  }

  return Object.fromEntries(
    Object.entries(actionRecord).flatMap(([key, rawValue]) => {
      if (
        key === 'id' ||
        key === 'pid' ||
        key === 'tenant_id' ||
        key === 'created_at' ||
        key === 'created_by' ||
        key === 'updated_at' ||
        key === 'updated_by' ||
        key === 'deleted_flag' ||
        key === 'deleted_at' ||
        key === 'deleted_by' ||
        key.startsWith('_')
      ) {
        return [];
      }
      const dataType = submitPayloadFieldTypes[key];
      const value = dataType ? normalizeCommandPayloadValue(rawValue, dataType) : rawValue;
      if (isEmptySubmittedValue(value)) return [];
      return [[key, value]];
    }),
  );
}

export function resolveAsyncCommandDispatch(responseData: any): { taskCode: string } | null {
  const dispatch =
    responseData?.data && typeof responseData.data === 'object' ? responseData.data : responseData;
  const taskCode = dispatch?.taskCode;
  if (dispatch?.async === true && typeof taskCode === 'string' && taskCode.trim()) {
    return { taskCode: taskCode.trim() };
  }
  return null;
}

async function pollFormAsyncCommandTask(taskCode: string, token?: string): Promise<any> {
  const terminal = new Set(['completed', 'failed', 'cancelled']);
  for (let attempt = 0; attempt < 600; attempt++) {
    const result = await fetchResult(`/api/async-tasks/${encodeURIComponent(taskCode)}`, {
      method: 'get',
      token,
    });
    if (!ResultHelper.isSuccess(result)) {
      throw new Error(
        (result as any).desc || (result as any).message || 'Async task status unavailable',
      );
    }
    const task = (result as any).data || {};
    const status = String(task.status || '').toLowerCase();
    if (terminal.has(status)) {
      if (status === 'completed') return task.resultData ?? {};
      if (status === 'cancelled') throw new Error('Task cancelled');
      throw new Error(task.errorMessage || 'Async task failed');
    }
    await new Promise((resolve) => setTimeout(resolve, 1500));
  }
  throw new Error('Async task timed out');
}

export function mergeLoadedRecordWithDirtyFields(
  loadedRecord: Record<string, any>,
  currentData: Record<string, any>,
  dirtyFields: ReadonlySet<string>,
): Record<string, any> {
  if (dirtyFields.size === 0) return loadedRecord;
  const merged = { ...loadedRecord };
  for (const fieldCode of dirtyFields) {
    if (Object.prototype.hasOwnProperty.call(currentData, fieldCode)) {
      merged[fieldCode] = currentData[fieldCode];
    }
  }
  return merged;
}

function snakeToCamel(value: string): string {
  return value.replace(/_([a-zA-Z0-9])/g, (_, char: string) => char.toUpperCase());
}

function camelToSnake(value: string): string {
  return value.replace(/[A-Z]/g, (char) => `_${char.toLowerCase()}`);
}

export function getFormFieldValueWithAlias(
  formData: Record<string, any>,
  fieldCode: string,
): unknown {
  if (Object.prototype.hasOwnProperty.call(formData, fieldCode)) {
    return formData[fieldCode];
  }
  const alias = fieldCode.includes('_') ? snakeToCamel(fieldCode) : camelToSnake(fieldCode);
  if (alias !== fieldCode && Object.prototype.hasOwnProperty.call(formData, alias)) {
    return formData[alias];
  }
  return undefined;
}

function isJsonLikeDataType(dataType?: string): boolean {
  return ['json', 'jsonb'].includes(String(dataType || '').toLowerCase());
}

function tryParseJsonText(text: string): unknown {
  const trimmed = text.trim();
  if (!trimmed) return text;
  try {
    return JSON.parse(trimmed);
  } catch {
    return text;
  }
}

function isJsonEnvelope(value: unknown): value is { type?: unknown; value: unknown } {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return false;
  const record = value as Record<string, unknown>;
  const type = String(record.type || '').toLowerCase();
  return (
    (type === 'json' || type === 'jsonb') && Object.prototype.hasOwnProperty.call(record, 'value')
  );
}

export function unwrapJsonLikeValue(rawValue: any): any {
  let current = rawValue;
  for (let depth = 0; depth < 8; depth += 1) {
    if (isJsonEnvelope(current)) {
      const envelopeValue = current.value;
      current = typeof envelopeValue === 'string' ? tryParseJsonText(envelopeValue) : envelopeValue;
      continue;
    }

    if (typeof current === 'string') {
      const parsed = tryParseJsonText(current);
      if (parsed !== current && isJsonEnvelope(parsed)) {
        current = parsed;
        continue;
      }
    }

    break;
  }
  return current;
}

export function normalizeLoadedFormValue(rawValue: any, dataType?: string): any {
  const normalized = unwrapJsonLikeValue(rawValue);
  const shouldFormatJson = isJsonLikeDataType(dataType) || normalized !== rawValue;
  if (!shouldFormatJson || normalized == null || normalized === '') {
    return normalized;
  }

  if (typeof normalized === 'string') {
    const parsed = tryParseJsonText(normalized);
    return parsed === normalized ? normalized : JSON.stringify(parsed, null, 2);
  }

  try {
    return JSON.stringify(normalized, null, 2);
  } catch {
    return String(normalized);
  }
}

export function normalizeLoadedRecordForForm(
  loadedRecord: Record<string, any>,
  fieldDataTypes: Record<string, string> = {},
): Record<string, any> {
  return Object.fromEntries(
    Object.entries(loadedRecord).map(([key, value]) => [
      key,
      normalizeLoadedFormValue(value, fieldDataTypes[key]),
    ]),
  );
}

function resolveComponentByFieldMeta(
  dataType?: string,
  extension?: Record<string, any>,
): string | undefined {
  // Plugin import may store under nested extension.extension (legacy shape),
  // while runtime may also have the properties hoisted at the top level.
  const nested = (extension as any)?.extension as Record<string, any> | undefined;
  const preferred = String(
    extension?.renderComponent ??
      extension?.component ??
      extension?.uiComponent ??
      nested?.renderComponent ??
      nested?.component ??
      nested?.uiComponent ??
      '',
  )
    .trim()
    .toLowerCase();

  if (preferred) {
    if (
      preferred === 'richtext' ||
      preferred === 'rich_text' ||
      preferred === 'smartrichtexteditor'
    ) {
      return 'richtext';
    }
    return preferred;
  }

  if (!dataType) return undefined;
  return DATA_TYPE_TO_COMPONENT[String(dataType).toLowerCase()];
}

export function normalizePayloadValue(rawValue: any, dataType?: string) {
  if (String(dataType || '').toLowerCase() === 'file') {
    if (rawValue == null || rawValue === '') {
      return null;
    }
    if (typeof rawValue === 'string') {
      return rawValue;
    }
    if (Array.isArray(rawValue)) {
      const normalizedFiles = rawValue
        .filter((item) => item && typeof item === 'object')
        .filter((item) => !('status' in item) || item.status === 'done')
        .map((item) => {
          const response = item.response && typeof item.response === 'object' ? item.response : {};
          const url =
            item.url ||
            item.thumbUrl ||
            (response as any).url ||
            (response as any).downloadUrl ||
            ((response as any).fileId || (response as any).pid
              ? `/api/file/download/${(response as any).fileId || (response as any).pid}`
              : undefined);
          return {
            name: item.name,
            url,
            size: item.size,
            type: item.type,
            fileId: (response as any).fileId || (response as any).pid,
          };
        })
        .filter((item) => item.name && item.url);

      return normalizedFiles.length > 0 ? JSON.stringify(normalizedFiles) : null;
    }
  }
  if (
    rawValue === '' &&
    ['date', 'datetime', 'decimal', 'integer'].includes(String(dataType || '').toLowerCase())
  ) {
    return null;
  }
  if (isJsonLikeDataType(dataType)) {
    const unwrapped = unwrapJsonLikeValue(rawValue);
    if (typeof unwrapped !== 'string') {
      return unwrapped;
    }
    const trimmed = unwrapped.trim();
    if (
      (trimmed.startsWith('[') && trimmed.endsWith(']')) ||
      (trimmed.startsWith('{') && trimmed.endsWith('}'))
    ) {
      try {
        return JSON.parse(trimmed);
      } catch {
        // Keep original string when not valid JSON text
      }
    }
    return unwrapped;
  }
  return rawValue;
}

/**
 * Extract the action type string from a button action that can be either
 * a plain string (e.g. "save") or an object (e.g. {type: "command", command: "sc:update_showcase"}).
 */
function resolveActionType(action: unknown): string {
  if (typeof action === 'string') return action;
  if (action && typeof action === 'object' && 'type' in action) {
    return String((action as Record<string, unknown>).type || '');
  }
  return '';
}

export function shouldBypassFormSubmit(
  button: { code?: string; commandCode?: string; action?: unknown } | null | undefined,
  actionType: string,
): boolean {
  const normalizedActionType = String(actionType || '').toLowerCase();
  if (['navigate', 'cancel', 'back', 'close', 'refresh', 'reload'].includes(normalizedActionType)) {
    return true;
  }

  const action = button?.action;
  const actionCommand =
    action && typeof action === 'object' ? (action as Record<string, unknown>).command : undefined;
  const buttonCode = String(button?.code || '').toLowerCase();
  return (
    ['cancel', 'back', 'close', 'refresh', 'reload'].includes(buttonCode) &&
    !button?.commandCode &&
    typeof actionCommand !== 'string'
  );
}

/**
 * Resolve the redirect target after a successful form submit.
 *
 * Honors `schema.extension.afterSubmitRedirect` when set. Supports placeholder
 * substitution from the response data (e.g. `{pid}` is replaced with the new
 * record's pid). Falls back to the list page (`/p/{tableName}`) when no
 * override is configured.
 */
export function resolveAfterSubmitRedirect(
  schema: any,
  tableName: string,
  responseData: any,
  recordId: string | null | undefined,
): string {
  const template = (schema?.extension as any)?.afterSubmitRedirect;
  if (typeof template !== 'string' || !template) {
    return `/p/${tableName}`;
  }
  // Build placeholder bag: response payload fields + the original recordId.
  const bag: Record<string, any> = {
    ...(responseData && typeof responseData === 'object' ? responseData : {}),
  };
  const nestedData =
    responseData && typeof responseData === 'object' && (responseData as any).data
      ? (responseData as any).data
      : null;
  if (nestedData && typeof nestedData === 'object') {
    Object.assign(bag, nestedData);
  }
  if (bag.pid == null && bag.recordId != null) bag.pid = bag.recordId;
  if (bag.id == null && bag.recordId != null) bag.id = bag.recordId;
  if (recordId && bag.pid == null) bag.pid = recordId;
  if (recordId && bag.id == null) bag.id = recordId;
  return template.replace(/\{(\w+)\}/g, (_match, key: string) => {
    const value = bag[key];
    return value != null ? String(value) : '';
  });
}

/**
 * Resolve the edit-mode record-prefill fetch endpoint.
 *
 * When the schema declares a `recordSource.endpoint`, that custom URL is used
 * and `{recordId}` / `${recordId}` placeholders are interpolated with the
 * URL-encoded record id. This unblocks `skipTableCreation` models (e.g.
 * `ab_qr_code`) whose reads are served by a custom REST endpoint and would
 * otherwise 400/500 against the generic `/api/dynamic/<model>/<id>` route.
 *
 * Falls back to the default `/api/dynamic/<tableName>/<recordId>` when no
 * `recordSource` is configured — preserving 100 % backward compatibility.
 */
export function resolveEditRecordEndpoint(
  schema: { recordSource?: { endpoint?: string } } | null | undefined,
  tableName: string,
  recordId: string,
): string {
  const custom = schema?.recordSource?.endpoint;
  if (custom && custom.trim()) {
    return custom.replace(/\$\{recordId\}|\{recordId\}/g, encodeURIComponent(recordId));
  }
  return `/api/dynamic/${tableName}/${recordId}`;
}

function inferEditCommandCode(commandCode: string | null, isEditMode: boolean): string | null {
  if (!isEditMode || !commandCode) return commandCode;
  if (commandCode.includes(':create_')) {
    return commandCode.replace(':create_', ':update_');
  }
  if (commandCode.startsWith('create_')) {
    return commandCode.replace(/^create_/, 'update_');
  }
  return commandCode;
}

/**
 * Resolve the submit command for a form, convention over configuration.
 *
 * An explicit command (URL `?commandCode=`, the form button's command, or the
 * action object's command — already passed through {@link inferEditCommandCode})
 * always wins. When none is provided, fall back to the model's CRUD command that
 * the server resolved onto `schema.commands`: `update` when editing (a record id
 * is present), `create` when new. Returns null when neither is available (a pure
 * CRUD model), so the caller persists via the dynamic CRUD API instead.
 */
export function resolveSubmitCommandCode(
  explicitCommandCode: string | null,
  schemaCommands: Record<string, string> | undefined,
  isEditMode: boolean,
): string | null {
  if (explicitCommandCode) return explicitCommandCode;
  return schemaCommands?.[isEditMode ? 'update' : 'create'] ?? null;
}

/**
 * Map field dataType to SubTableColumn type for sub-table cell editors.
 */
function mapDataTypeToColumnType(dataType: string): SubTableColumn['type'] {
  switch (dataType?.toLowerCase()) {
    case 'integer':
    case 'decimal':
      return 'number';
    case 'boolean':
      return 'boolean';
    case 'date':
    case 'datetime':
      return 'date';
    case 'enum':
      return 'select';
    default:
      return 'text';
  }
}

function mergeFieldValidationRules(
  rawField: any,
  meta?: FieldMetaInfo,
  t?: (key: string) => string,
  locale?: string,
): NormalizedValidationRule[] {
  const existingRules: NormalizedValidationRule[] = Array.isArray(rawField?.validation)
    ? [...rawField.validation]
    : [];
  const byType = new Set(existingRules.map((r) => r?.type));

  // Read-only fields are often populated by backend auto-set/default logic in create mode.
  // Enforcing them as user-entered required inputs blocks valid submissions before command execution.
  const required = !rawField?.readOnly && Boolean(rawField?.required ?? meta?.required);
  if (required && !byType.has('required')) {
    const label = rawField?.label || meta?.displayName || rawField?.field;
    existingRules.push({
      type: 'required',
      message: buildRequiredFieldMessage(label, {
        dataType: meta?.dataType,
        component: rawField?.component ?? meta?.component,
        locale,
        t,
      }),
    });
    byType.add('required');
  }

  const validationConfig = meta?.feature?.validation || {};
  const maxLength = Number(validationConfig?.maxLength);
  const minLength = Number(validationConfig?.minLength);
  const pattern =
    typeof validationConfig?.pattern === 'string' ? validationConfig.pattern : undefined;

  if (Number.isFinite(maxLength) && maxLength > 0 && !byType.has('maxLength')) {
    existingRules.push({ type: 'maxLength', maxLength });
    byType.add('maxLength');
  }
  if (Number.isFinite(minLength) && minLength > 0 && !byType.has('minLength')) {
    existingRules.push({ type: 'minLength', minLength });
    byType.add('minLength');
  }
  if (pattern && !byType.has('pattern')) {
    existingRules.push({ type: 'pattern', pattern });
  }

  // constraints use "min"/"max", feature.validation uses "minValue"/"maxValue"
  const minValue = Number(validationConfig?.minValue ?? validationConfig?.min);
  const maxValue = Number(validationConfig?.maxValue ?? validationConfig?.max);
  if (Number.isFinite(minValue) && !byType.has('minValue')) {
    existingRules.push({ type: 'minValue', minValue });
    byType.add('minValue');
  }
  if (Number.isFinite(maxValue) && !byType.has('maxValue')) {
    existingRules.push({ type: 'maxValue', maxValue });
    byType.add('maxValue');
  }

  return existingRules;
}

/**
 * FormPageContent renders a DSL-driven form page.
 *
 * Receives a pre-loaded schema from DynamicPageRenderer and sets up its own
 * runtime via usePageRuntime. Handles create and edit modes, sub-tables,
 * expression-driven visibility, and action dispatch.
 */
export function FormPageContent(props: PageContentProps) {
  const { schema, tableName, token, initialValues, fieldPermissions, onSubmitOverride } = props;

  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const showToast = useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  // State management
  const [formData, setFormData] = useState<Record<string, any>>({});
  const [initialFormData, setInitialFormData] = useState<Record<string, any> | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [summaryErrors, setSummaryErrors] = useState<string[]>([]);
  const dirtyFieldsRef = useRef<Set<string>>(new Set());
  // T10: holds the latest draft-clear fn so submit handlers (defined before the
  // draft hook) can clear the persisted local draft on success.
  const clearFormDraftRef = useRef<() => void>(() => {});

  // Read URL params:
  // - commandCode: explicit submit command provided by navigation actions
  // - sourceRecordId: source business record id for create-by-context flows
  const [searchParams] = useSearchParams();
  const urlCommandCode = searchParams.get('commandCode');
  const sourceRecordId = searchParams.get('sourceRecordId');
  const recordId = props.recordId;
  const isEditMode = !!recordId;

  // Flat field-name set from schema (form-section blocks).
  // Used for generic URL aliasing (e.g. ?modelCode=xxx → model_code) and for
  // deciding whether kind × capability validation applies to this form.
  const schemaFieldNames = useMemo(() => {
    const names = new Set<string>();
    const blocks = (schema as any)?.blocks ?? [];
    for (const b of blocks) {
      if (b?.blockType === 'form-section' && Array.isArray(b.fields)) {
        for (const f of b.fields) {
          if (f?.field) names.add(String(f.field));
        }
      }
    }
    return names;
  }, [schema]);

  // URL-based default values for create mode (e.g. ?dv.crm_qt_opportunity_id=01xxx)
  // Also supports a short form aliasing commonly-used query params to DB columns
  // when the form has a matching field:
  //   ?modelCode=xxx  → model_code (when form has `model_code` field)
  const urlDefaultValues = useMemo(() => {
    if (isEditMode) return {} as Record<string, string>;
    const defaults: Record<string, string> = {};
    for (const [key, value] of searchParams.entries()) {
      if (key.startsWith('dv.') && value) {
        defaults[key.substring(3)] = value;
      }
    }
    const modelCodeParam = searchParams.get('modelCode');
    if (modelCodeParam && schemaFieldNames.has('model_code') && !defaults.model_code) {
      defaults.model_code = modelCodeParam;
    }
    return defaults;
  }, [isEditMode, searchParams, schemaFieldNames]);

  // Field-config `defaultValue` for create mode (e.g. a select that should
  // pre-select an option). Lower precedence than URL defaults and user input.
  const schemaDefaultValues = useMemo(() => {
    if (isEditMode) return {} as Record<string, any>;
    const defaults: Record<string, any> = {};
    const blocks = (schema as any)?.blocks ?? [];
    for (const b of blocks) {
      if (b?.blockType === 'form-section' && Array.isArray(b.fields)) {
        for (const f of b.fields) {
          if (f?.field && f.defaultValue !== undefined && f.defaultValue !== null) {
            defaults[String(f.field)] = f.defaultValue;
          }
        }
      }
    }
    return defaults;
  }, [isEditMode, schema]);

  // Track whether modelCode was seeded from URL so we can show a hint.
  const urlSeededModelCode = useMemo(() => {
    if (isEditMode) return null;
    const mc = searchParams.get('modelCode');
    return mc && schemaFieldNames.has('model_code') ? mc : null;
  }, [isEditMode, searchParams, schemaFieldNames]);

  // Forms that edit both a model_code and a kind field (e.g. page_schema_form)
  // must validate that the selected kind is compatible with the model's
  // capabilities before submit. Fetch capabilities lazily when both fields
  // are present and the user has chosen a model code.
  const hasModelCodeAndKindFields =
    schemaFieldNames.has('model_code') && schemaFieldNames.has('kind');

  // Determine mode string for expression context
  const mode = isEditMode ? 'edit' : 'create';

  // Use unified page runtime hook (replaces useDynamicPageSetup)
  const { runtime, dataSourceManager, t, locale, navigate } = usePageRuntime(schema, {
    token: token || undefined,
    additionalContext: {
      mode,
      form: {
        mode,
        ...formData,
      },
      $page: {
        kind: (schema as any)?.kind,
        modelCode: (schema as any)?.modelCode,
        pageKey: (schema as any)?.pageKey,
        mode,
        recordId: recordId || undefined,
      },
    },
  });

  // Get user info and permissions from context
  const { user } = useUser();
  const { permissions } = usePermissions();

  // Kind × capability validation: fetch selected model's capabilities when the
  // form has both `model_code` and `kind` fields. No-op for other forms.
  const activeModelCodeForCaps = hasModelCodeAndKindFields
    ? typeof formData.model_code === 'string'
      ? formData.model_code
      : undefined
    : undefined;
  const { data: kindCapabilities } = useModelCapabilities(activeModelCodeForCaps);

  // Create complete ExpressionContext for field rendering
  const pageContext = useMemo(() => {
    // Transform user to match ExpressionContext requirements with real permissions
    // Support both formats: permissionCodes (string array) or permissions (object array)
    const permissionCodes =
      permissions?.permissionCodes || permissions?.permissions?.map((p: any) => p.code) || [];
    const contextUser = user
      ? {
          ...user,
          roles: permissions?.roles?.map((r: any) => r.code) || [],
          permissions: permissionCodes,
        }
      : undefined;

    // Transform tenantId to tenant object
    const contextTenant = user?.tenantId
      ? {
          id: String(user.tenantId),
          name: `Tenant ${user.tenantId}`,
        }
      : undefined;

    return createExpressionContext({
      global: {
        locale,
        theme: 'light',
        user: contextUser,
        tenant: contextTenant,
      },
      state: {
        mode,
      },
      form: {
        mode,
        ...formData,
      },
      // Expose current form values as `record` so DSL visibleWhen expressions
      // like `record.sc_status === 'active'` work uniformly on form + detail.
      // Detail page already provides `record`; form must match so the same
      // DSL works across both kinds.
      record: formData,
      locale,
      t: (key: string) => t(key),
      fetchResult,
      __dataSourceManager: dataSourceManager,
      __pageKey: (schema as any)?.pageKey,
      __modelCode: (schema as any)?.modelCode || tableName,
      __setFormFieldValue: (fieldCode: string, value: unknown) => {
        dirtyFieldsRef.current.add(fieldCode);
        setFormData((prev) => {
          if (prev[fieldCode] === value) return prev;
          return {
            ...prev,
            [fieldCode]: value,
          };
        });
      },
    });
  }, [locale, t, formData, dataSourceManager, user, permissions, mode]);

  useEffect(() => {
    const expectedPageKey = (schema as any)?.pageKey;
    const expectedModelCode = (schema as any)?.modelCode || tableName;
    const handleReferenceCreated = (event: Event) => {
      const detail = (event as CustomEvent)?.detail || {};
      if (detail.pageKey && detail.pageKey !== expectedPageKey) return;
      if (detail.modelCode && detail.modelCode !== expectedModelCode) return;
      if (!detail.fieldCode) return;
      dirtyFieldsRef.current.add(String(detail.fieldCode));
      setFormData((prev) => {
        if (prev[detail.fieldCode] === detail.value) return prev;
        return {
          ...prev,
          [detail.fieldCode]: detail.value,
        };
      });
    };
    window.addEventListener('aura:reference-field-created', handleReferenceCreated);
    return () => {
      window.removeEventListener('aura:reference-field-created', handleReferenceCreated);
    };
  }, [schema, tableName]);

  const [mainRecordLoaded, setMainRecordLoaded] = useState(!isEditMode);
  const fieldDataTypesRef = useRef<Record<string, string>>({});
  const loadMainRecord = useCallback(
    async (options?: { preserveDirty?: boolean }) => {
      if (!recordId) return;
      const preserveDirty = options?.preserveDirty ?? true;
      dirtyFieldsRef.current.clear();
      setMainRecordLoaded(false);
      try {
        const endpoint = resolveEditRecordEndpoint(schema, tableName, recordId);
        const resp = await fetchResult<any>(endpoint, {
          method: (schema?.recordSource?.method as any) || 'get',
          token: token || undefined,
        });
        if (ResultHelper.isSuccess(resp) && resp.data) {
          const normalizedRecord = normalizeLoadedRecordForForm(
            resp.data,
            fieldDataTypesRef.current,
          );
          setFormData((prev) =>
            preserveDirty
              ? mergeLoadedRecordWithDirtyFields(normalizedRecord, prev, dirtyFieldsRef.current)
              : normalizedRecord,
          );
          setInitialFormData(normalizedRecord);
        }
      } catch {
        // Keep the form usable when a transient refresh request fails; callers
        // surface action errors separately when needed.
      } finally {
        setMainRecordLoaded(true);
      }
    },
    [recordId, tableName, token],
  );
  const reloadMainRecord = useCallback(
    () => loadMainRecord({ preserveDirty: false }),
    [loadMainRecord],
  );

  const syncRuntimeFormScope = useCallback(
    (nextFormData: Record<string, any>) => {
      if (!runtime) return;
      const scopeId = runtime.getScopeId();
      runtime.getStateManager().updateScope(scopeId, {
        form: recordId ? { ...nextFormData, pid: recordId } : nextFormData,
      });
    },
    [runtime, recordId],
  );

  const syncRuntimeFieldValue = useCallback(
    (fieldCode: string, value: unknown) => {
      if (!runtime) return;
      const scopeId = runtime.getScopeId();
      runtime.getStateManager().updateScope(scopeId, (prev) => ({
        form: {
          ...(prev.form || {}),
          [fieldCode]: value,
          ...(recordId ? { pid: recordId } : {}),
        },
      }));
    },
    [runtime, recordId],
  );

  // Sync formData with runtime scope state
  useEffect(() => {
    syncRuntimeFormScope(formData);
  }, [formData, syncRuntimeFormScope]);

  // Use unified action handler hook with SchemaRuntime support
  const { handleAction, loading, error, setError } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      data: formData,
      setData: setFormData,
      loadData: reloadMainRecord,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  const clearFieldError = useCallback(
    (fieldCode: string) => {
      setFieldErrors((prev) => {
        if (!prev[fieldCode]) return prev;
        const next = { ...prev };
        delete next[fieldCode];
        return next;
      });
      setSummaryErrors([]);
      setError(null);
    },
    [setError],
  );

  // Fetch model field metadata for component resolution (must be before early returns)
  const [modelFields, setModelFields] = useState<Record<string, FieldMetaInfo>>({});
  const [fieldMetaLoaded, setFieldMetaLoaded] = useState(false);
  useEffect(() => {
    const types: Record<string, string> = {};
    const blocks = Array.isArray(schema?.blocks) ? schema.blocks : [];
    for (const block of blocks) {
      if (block?.blockType !== 'form-section' || !Array.isArray(block.fields)) continue;
      for (const rawField of block.fields) {
        if (rawField?.field && rawField.dataType) {
          types[String(rawField.field)] = String(rawField.dataType);
        }
      }
    }
    for (const [fieldCode, meta] of Object.entries(modelFields)) {
      if (meta?.dataType) {
        types[fieldCode] = meta.dataType;
      }
    }
    fieldDataTypesRef.current = types;
  }, [schema?.blocks, modelFields]);

  useEffect(() => {
    let cancelled = false;
    setFieldMetaLoaded(false);
    // For page-key routes (e.g. dp_issue_triage), schema.modelCode is the real model.
    const targetModelCode = schema?.modelCode || tableName;
    // Use /api/dynamic/{pageKey}/field-meta which requires model-level read permission
    // instead of /api/meta/models/{pid}/fields which requires management permission
    fetchResult<
      Array<{
        code: string;
        dataType: string;
        dictCode?: string;
        refTarget?: Record<string, any> | null;
        referenceModelCode?: string;
        extension?: any;
        required?: boolean;
        feature?: Record<string, any>;
        ruleSchema?: Record<string, any>;
      }>
    >(`/api/dynamic/${targetModelCode}/field-meta`, {
      method: 'get',
      token: token || undefined,
    })
      .then((fieldsResp) => {
        if (cancelled) return;
        if (fieldsResp && ResultHelper.isSuccess(fieldsResp) && Array.isArray(fieldsResp.data)) {
          const map: Record<string, FieldMetaInfo> = {};
          for (const f of fieldsResp.data) {
            const displayName = resolveExtensionDisplayName(f.extension, f.code, locale);
            // Merge constraints from extension.extension.constraints into feature.validation
            // Plugin imports store constraints (min, max, maxLength, minLength, pattern)
            // in extension.extension.constraints, but mergeFieldValidationRules reads from
            // feature.validation. Merge them so validation rules are picked up.
            const extConstraints = (f.extension?.extension as any)?.constraints;
            const mergedFeature = f.feature ? { ...f.feature } : {};
            if (extConstraints && typeof extConstraints === 'object') {
              mergedFeature.validation = { ...extConstraints, ...(mergedFeature.validation || {}) };
            }

            map[f.code] = {
              dataType: f.dataType,
              displayName,
              dictCode: f.dictCode,
              refTarget: mergeRefTarget(f.refTarget, f.extension?.refTarget),
              // Also resolve from nested extension (plugin import may store as extension.extension.referenceModelCode or .refModelCode)
              referenceModelCode:
                f.referenceModelCode ||
                (f.extension?.extension as any)?.referenceModelCode ||
                (f.extension?.extension as any)?.refModelCode,
              component: resolveComponentByFieldMeta(f.dataType, f.extension),
              required: Boolean(f.required || extConstraints?.required),
              feature: mergedFeature,
              ruleSchema: f.ruleSchema,
              // Preserve extension for component-specific config (levels, multiple, allowClear, etc.)
              extension: f.extension,
              // Pre-compute component-specific props from extension (stable reference).
              // Plugin import may store properties under nested `extension.extension`;
              // flatten both shapes so downstream renderers see a single bag of props.
              extensionProps: f.extension
                ? Object.fromEntries(
                    Object.entries({
                      ...((f.extension as any)?.extension ?? {}),
                      ...f.extension,
                    }).filter(
                      ([k]) =>
                        ![
                          'renderComponent',
                          'component',
                          'uiComponent',
                          'displayName',
                          'description',
                          'constraints',
                          'precision',
                          'scale',
                          'readOnly',
                          'extension',
                        ].includes(k),
                    ),
                  )
                : undefined,
            };
          }
          setModelFields(map);
        }
      })
      .catch(() => {
        if (!cancelled) setModelFields({});
      })
      .finally(() => {
        if (!cancelled) setFieldMetaLoaded(true);
      });
    return () => {
      cancelled = true;
    };
  }, [tableName, schema?.modelCode, token, locale]);

  const computedFieldDefs = useMemo<ComputedFieldDef[]>(() => {
    const defs: ComputedFieldDef[] = [];
    const blocks = Array.isArray(schema?.blocks) ? schema.blocks : [];

    for (const block of blocks) {
      if (block?.blockType !== 'form-section' || !Array.isArray(block.fields)) continue;
      for (const rawField of block.fields) {
        if (!rawField?.field) continue;
        const meta = modelFields[rawField.field];
        const extensionProps = meta?.extensionProps;
        const formula =
          typeof extensionProps?.formula === 'string' ? extensionProps.formula.trim() : '';
        const computed = extensionProps?.computed === true && formula.length > 0;
        if (!computed) continue;

        const dependencyConfig =
          extensionProps?.computeDependencies ?? extensionProps?.dependencies ?? [];
        const dependencies = Array.isArray(dependencyConfig)
          ? dependencyConfig.map((dep) => String(dep)).filter(Boolean)
          : typeof dependencyConfig === 'string'
            ? dependencyConfig
                .split(',')
                .map((dep) => dep.trim())
                .filter(Boolean)
            : [];

        defs.push({
          fieldCode: rawField.field,
          label: meta?.displayName || rawField.label,
          expression: formula,
          dependencies,
          type: extensionProps?.materialize === false ? 'computed_temp' : 'computed_materialized',
          dataType: meta?.dataType,
          fallbackValue: extensionProps?.computeFallbackValue ?? '',
        });
      }
    }

    return defs;
  }, [schema?.blocks, modelFields]);

  useComputedFields({
    fields: computedFieldDefs,
    formData,
    enabled: computedFieldDefs.length > 0,
    onChange: (fieldCode, value) => {
      syncRuntimeFieldValue(fieldCode, value);
      setFormData((prev) => {
        if (prev[fieldCode] === value) return prev;
        return {
          ...prev,
          [fieldCode]: value,
        };
      });
    },
    onError: (fieldCode, error) => {
      console.warn(`Failed to evaluate computed field "${fieldCode}":`, error);
    },
  });

  const validateFormBeforeSubmit = useCallback((): FormValidationResult => {
    if (!schema?.blocks) {
      return { fieldErrors: {}, summaryErrors: [] };
    }
    const nextFieldErrors: Record<string, string> = {};
    const nextSummaryErrors: string[] = [];
    const submissionData = recordId ? { ...(initialFormData || {}), ...formData } : formData;
    const blocks = schema.blocks;
    const formSectionBlocks = blocks.filter((b: any) => b.blockType === 'form-section');

    for (const block of formSectionBlocks) {
      if (block.visibleWhen && !evaluateCondition(block.visibleWhen, pageContext)) {
        continue;
      }
      const fields = Array.isArray(block.fields) ? block.fields : [];
      for (const rawField of fields) {
        const meta = modelFields[rawField.field];
        const rules = mergeFieldValidationRules(rawField, meta, t, locale);
        const label = rawField.label || meta?.displayName || rawField.field;

        if (rawField.visibleWhen && !evaluateCondition(rawField.visibleWhen, pageContext)) {
          continue;
        }

        const value = submissionData[rawField.field];
        for (const rule of rules) {
          if (!rule?.type) continue;
          if (rule.type === 'required') {
            const empty =
              value === undefined ||
              value === null ||
              value === '' ||
              (Array.isArray(value) && value.length === 0);
            if (empty) {
              nextFieldErrors[rawField.field] =
                rule.message ||
                buildRequiredFieldMessage(label, {
                  dataType: meta?.dataType,
                  component: rawField.component,
                  locale,
                  t,
                });
              break;
            }
          }
          if (
            rule.type === 'maxLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.maxLength)
          ) {
            if (value.length > Number(rule.maxLength)) {
              nextFieldErrors[rawField.field] = `${label} exceeds max length ${rule.maxLength}`;
              break;
            }
          }
          if (
            rule.type === 'minLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.minLength)
          ) {
            if (value.length < Number(rule.minLength)) {
              nextFieldErrors[rawField.field] =
                `${label} is shorter than min length ${rule.minLength}`;
              break;
            }
          }
          if (rule.type === 'pattern' && typeof value === 'string' && rule.pattern) {
            try {
              const regex = new RegExp(rule.pattern);
              if (!regex.test(value)) {
                nextFieldErrors[rawField.field] = `${label} format is invalid`;
                break;
              }
            } catch {
              // Ignore invalid regex rule configuration.
            }
          }
          if (rule.type === 'minValue' && Number.isFinite(rule.minValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num < rule.minValue!) {
              nextFieldErrors[rawField.field] = `${label} must be at least ${rule.minValue}`;
              break;
            }
          }
          if (rule.type === 'maxValue' && Number.isFinite(rule.maxValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num > rule.maxValue!) {
              nextFieldErrors[rawField.field] = `${label} must be at most ${rule.maxValue}`;
              break;
            }
          }
        }
      }
    }

    // Kind × ModelCapabilities validation (P2B-T2).
    // When this form binds both `model_code` and `kind`, block submit if the
    // selected kind is not supported by the selected model's capabilities.
    if (hasModelCodeAndKindFields) {
      const selectedKind =
        typeof submissionData.kind === 'string' ? submissionData.kind : undefined;
      const selectedModelCode =
        typeof submissionData.model_code === 'string' ? submissionData.model_code : undefined;
      if (selectedKind && selectedModelCode && kindCapabilities) {
        const compat = checkKindCompatibility(selectedKind, kindCapabilities);
        if (!compat.compatible) {
          pushUniqueMessage(
            nextSummaryErrors,
            compat.reason
              ? `Kind "${selectedKind}" is not supported by model "${selectedModelCode}": ${compat.reason}`
              : `Kind "${selectedKind}" is not supported by model "${selectedModelCode}"`,
          );
        }
      }
    }

    // Cross-field validation rules (from model-level rules + command overrides)
    if (schema?.rules && schema.rules.length > 0) {
      const finalRules = crossFieldMergeRules(schema.rules, schema.ruleOverrides ?? []);
      for (const rule of finalRules) {
        if (rule.when) {
          if (!crossFieldEvalCondition(rule.when, submissionData)) continue;
        }
        const result = crossFieldEvalAssert(rule.assert, submissionData);
        if (!result.skipped && !result.passed) {
          if (rule.severity !== 'warning') {
            const msg = rule.message?.replace(/\{(\w+)\}/g, (_, k: string) =>
              String(submissionData[k] ?? k),
            );
            const finalMessage = msg || `Validation failed: ${rule.id}`;
            if (rule.targetField && !nextFieldErrors[rule.targetField]) {
              nextFieldErrors[rule.targetField] = finalMessage;
            }
            pushUniqueMessage(nextSummaryErrors, finalMessage);
          }
        }
      }
    }

    return { fieldErrors: nextFieldErrors, summaryErrors: nextSummaryErrors };
  }, [
    schema,
    pageContext,
    modelFields,
    t,
    locale,
    formData,
    initialFormData,
    recordId,
    hasModelCodeAndKindFields,
    kindCapabilities,
  ]);

  const notifyValidationFailure = useCallback(
    (validationResult: FormValidationResult) => {
      const firstFieldCode = Object.keys(validationResult.fieldErrors)[0];
      const firstFieldError = firstFieldCode
        ? validationResult.fieldErrors[firstFieldCode]
        : undefined;
      const firstSummaryError = validationResult.summaryErrors[0];
      showErrorToast(firstSummaryError || firstFieldError || 'Please fix validation errors');
      // §4: scroll to + focus the first invalid field on submit (mixed-timing
      // validation — field-level errors render, and the page jumps to the first one).
      if (firstFieldCode && typeof document !== 'undefined') {
        requestAnimationFrame(() => scrollToFormField(firstFieldCode));
      }
    },
    [showErrorToast],
  );

  // In new mode, override form button commandCodes with the create command
  // so that save_draft/submit use CREATE instead of UPDATE
  const handleFormAction = useCallback(
    (button: { commandCode?: string; [key: string]: any }) => {
      // L1 SDK: delegate to external submit handler when provided
      if (onSubmitOverride) {
        const actionType = resolveActionType(button.action);
        if (shouldBypassFormSubmit(button, actionType)) {
          setFieldErrors({});
          setSummaryErrors([]);
          setError(null);
          return handleAction(button as any, formData as any);
        }
        const shouldValidate =
          ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
            actionType.toLowerCase(),
          ) || !actionType;
        if (shouldValidate && schemaFieldNames.size > 0 && !fieldMetaLoaded) {
          showInfoToast(t('common.loading') || 'Loading...');
          return;
        }
        if (shouldValidate) {
          const validationResult = validateFormBeforeSubmit();
          if (
            Object.keys(validationResult.fieldErrors).length > 0 ||
            validationResult.summaryErrors.length > 0
          ) {
            setFieldErrors(validationResult.fieldErrors);
            setSummaryErrors(validationResult.summaryErrors);
            setError(null);
            notifyValidationFailure(validationResult);
            return;
          }
        }
        setFieldErrors({});
        setSummaryErrors([]);
        onSubmitOverride(formData).catch((err) => {
          const errorMessage = err instanceof Error ? err.message : 'Submit failed';
          setError(errorMessage);
          showErrorToast(errorMessage);
        });
        return;
      }
      const mergedEditData = recordId
        ? { ...(initialFormData || {}), ...formData, pid: recordId }
        : formData;
      const actionRecord = recordId
        ? mergedEditData
        : sourceRecordId
          ? { ...formData, sourceRecordId }
          : formData;
      const effectiveAction = button.action;
      const effectiveActionType = resolveActionType(effectiveAction);
      const effectiveButton = {
        ...button,
        action: effectiveAction,
      };
      if (shouldBypassFormSubmit(effectiveButton, effectiveActionType)) {
        setFieldErrors({});
        setSummaryErrors([]);
        setError(null);
        // T10: an explicit cancel/back/close abandons the form — drop the draft
        // so it doesn't resurrect on the next create. (refresh/reload keep it.)
        const leaveActions = ['cancel', 'back', 'close'];
        if (
          leaveActions.includes(String(effectiveActionType).toLowerCase()) ||
          leaveActions.includes(String((button as any)?.code || '').toLowerCase())
        ) {
          clearFormDraftRef.current();
        }
        return handleAction(effectiveButton as any, actionRecord as any);
      }
      // When action is an object like {type: "command", command: "xx:update_xx"},
      // extract the command code from it as well.
      const actionCommandCode =
        effectiveAction && typeof effectiveAction === 'object'
          ? (effectiveAction as Record<string, unknown>).command
          : undefined;
      const explicitCommandCode = inferEditCommandCode(
        urlCommandCode ||
          effectiveButton.commandCode ||
          (typeof actionCommandCode === 'string' ? actionCommandCode : null) ||
          null,
        Boolean(recordId),
      );
      // Convention over configuration: an explicit command (URL / button /
      // action) wins; otherwise route through the model's CRUD command the
      // server resolved onto `schema.commands` (update when editing, create when
      // new). Pure-CRUD models stay null and fall back to the dynamic CRUD API
      // below.
      const effectiveCommandCode = resolveSubmitCommandCode(
        explicitCommandCode,
        schema?.commands as Record<string, string> | undefined,
        Boolean(recordId),
      );
      const shouldValidate =
        ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
          effectiveActionType.toLowerCase(),
        ) || !effectiveActionType;
      if (shouldValidate && schemaFieldNames.size > 0 && !fieldMetaLoaded) {
        showInfoToast(t('common.loading') || 'Loading...');
        return;
      }
      if (shouldValidate) {
        const validationResult = validateFormBeforeSubmit();
        if (
          Object.keys(validationResult.fieldErrors).length > 0 ||
          validationResult.summaryErrors.length > 0
        ) {
          setFieldErrors(validationResult.fieldErrors);
          setSummaryErrors(validationResult.summaryErrors);
          setError(null);
          notifyValidationFailure(validationResult);
          return;
        }
      }
      setFieldErrors({});
      setSummaryErrors([]);
      const modelFieldEntries = Object.entries(modelFields);
      const commandPayload = buildFormCommandPayload(actionRecord, modelFields, schema?.blocks);

      // Ensure sourceRecordId is passed through to backend for SideEffect resolution
      if (sourceRecordId && !commandPayload.sourceRecordId) {
        commandPayload.sourceRecordId = sourceRecordId;
      }

      // Form command path: execute command directly with explicit operation context.
      // This avoids ambiguity from generic action routing branches (navigate/action registry).
      if (effectiveCommandCode) {
        const operationType =
          effectiveActionType === 'delete'
            ? 'delete'
            : effectiveActionType === 'create'
              ? 'create'
              : effectiveActionType === 'edit' || effectiveActionType === 'update'
                ? 'update'
                : effectiveActionType === 'save' || effectiveActionType === 'command'
                  ? recordId
                    ? 'update'
                    : 'create'
                  : undefined;
        const targetRecordId = recordId || undefined;

        if ((operationType === 'update' || operationType === 'delete') && !targetRecordId) {
          showErrorToast('Missing target record for update/delete');
          return;
        }

        fetchResult(`/api/meta/commands/execute/${effectiveCommandCode}`, {
          method: 'post',
          params: {
            ...buildCommandTargetParams(targetRecordId),
            payload: commandPayload,
            operationType,
          },
          token: token || undefined,
        })
          .then(async (result) => {
            if (!ResultHelper.isSuccess(result)) {
              const contextError = (result as any).context?.error;
              if (contextError) {
                setError(null);
                setSummaryErrors(parseValidationSummaryMessages(contextError));
                return;
              }
              throw new Error(result.desc || result.message || 'Command execution failed');
            }
            const asyncDispatch = resolveAsyncCommandDispatch(result.data);
            const responseData = asyncDispatch
              ? await (async () => {
                  const message = t('common.asyncProcessing');
                  showInfoToast(
                    message && message !== 'common.asyncProcessing'
                      ? message
                      : '已提交，后台处理中...',
                  );
                  return pollFormAsyncCommandTask(asyncDispatch.taskCode, token || undefined);
                })()
              : result.data;
            setFieldErrors({});
            setSummaryErrors([]);
            dirtyFieldsRef.current.clear();
            clearFormDraftRef.current();
            navigate(resolveAfterSubmitRedirect(schema, tableName, responseData, recordId));
          })
          .catch((err) => {
            const errorMessage = err instanceof Error ? err.message : 'Failed to execute command';
            setError(errorMessage);
            showErrorToast(errorMessage);
          });
        return;
      }

      // MODEL form path: when no commandCode is provided, persist via dynamic CRUD API.
      if (
        !effectiveButton.commandCode &&
        (effectiveActionType === 'create' || effectiveActionType === 'update')
      ) {
        const targetModelCode = schema?.modelCode || tableName;
        const endpoint =
          effectiveActionType === 'update'
            ? `/api/dynamic/${targetModelCode}/${recordId}`
            : `/api/dynamic/${targetModelCode}`;
        const method = effectiveActionType === 'update' ? 'put' : 'post';
        const payload =
          modelFieldEntries.length > 0
            ? Object.fromEntries(
                Object.entries(actionRecord).flatMap(([key, rawValue]) => {
                  if (!modelFields[key]) return [];
                  const value = normalizePayloadValue(rawValue, modelFields[key].dataType);
                  if (Array.isArray(value) && value.length === 0) return [];
                  if (
                    value &&
                    typeof value === 'object' &&
                    !Array.isArray(value) &&
                    Object.keys(value).length === 0
                  )
                    return [];
                  return [[key, value]];
                }),
              )
            : Object.fromEntries(
                Object.entries(actionRecord).filter(
                  ([key]) =>
                    key !== 'id' &&
                    key !== 'pid' &&
                    key !== 'tenant_id' &&
                    key !== 'created_at' &&
                    key !== 'created_by' &&
                    key !== 'updated_at' &&
                    key !== 'updated_by' &&
                    !key.startsWith('_'),
                ),
              );
        const normalizedPayload =
          effectiveActionType === 'update' && initialFormData
            ? Object.fromEntries(
                Object.entries(payload).filter(([key, value]) => {
                  const initialValue = initialFormData[key];
                  return JSON.stringify(value) !== JSON.stringify(initialValue);
                }),
              )
            : payload;

        return fetchResult(endpoint, {
          method,
          params: normalizedPayload,
          token: token || undefined,
        }).then((result) => {
          if (!ResultHelper.isSuccess(result)) {
            throw new Error(
              result.desc || result.message || `Failed to ${effectiveActionType} record`,
            );
          }
          showSuccessToast(t('common.saveSuccess') || 'Saved successfully');
          dirtyFieldsRef.current.clear();
          clearFormDraftRef.current();
          navigate(resolveAfterSubmitRedirect(schema, targetModelCode, result.data, recordId));
        });
      }

      return handleAction(effectiveButton as any, actionRecord as any);
    },
    [
      formData,
      handleAction,
      initialFormData,
      modelFields,
      navigate,
      onSubmitOverride,
      notifyValidationFailure,
      recordId,
      schema?.modelCode,
      schema?.blocks,
      setError,
      fieldMetaLoaded,
      schemaFieldNames,
      showErrorToast,
      showInfoToast,
      showSuccessToast,
      sourceRecordId,
      t,
      tableName,
      token,
      urlCommandCode,
      validateFormBeforeSubmit,
    ],
  );

  // Create mode: apply field-config defaults then URL default values
  // (dv.fieldCode=value) to form. Precedence: user input > URL > field default.
  useEffect(() => {
    if (isEditMode) return;
    const base = { ...schemaDefaultValues, ...urlDefaultValues };
    if (Object.keys(base).length === 0) return;
    setFormData((prev) => ({ ...base, ...prev }));
  }, [isEditMode, schemaDefaultValues, urlDefaultValues]);

  // Edit mode: fetch existing record data to populate form
  useEffect(() => {
    if (!recordId) return;
    void loadMainRecord({ preserveDirty: true });
  }, [recordId, loadMainRecord]);

  // L1 SDK: merge external initialValues into form state (overlay on top of loaded data)
  useEffect(() => {
    if (!initialValues || Object.keys(initialValues).length === 0) return;
    setFormData((prev) => ({ ...prev, ...initialValues }));
  }, [initialValues]);

  // Sub-table row data: { [blockKey]: Record<string,any>[] }
  const [subTableData, setSubTableData] = useState<Record<string, Record<string, any>[]>>({});
  // Track whether edit-mode data (child records) has been loaded
  const [editDataLoaded, setEditDataLoaded] = useState(!isEditMode);

  // Compute subTableBlocks early so we can use it in rendering and column derivation
  const subTableBlocks = useMemo(() => {
    if (!schema?.blocks) return [];
    return schema.blocks.filter((b: any) => b.blockType === 'sub-table');
  }, [schema]);

  // Build SubTableColumn[] directly from DSL columns (instant, no API needed)
  // Enhanced with field metadata when available from parent model fields fetch
  const subTableColumnsMap = useMemo(() => {
    const result: Record<string, SubTableColumn[]> = {};
    for (const block of subTableBlocks) {
      const childModel = block.subTable?.childModel || (block as any).childModel;
      if (!childModel) continue;
      const dslColumns: any[] = block.subTable?.columns || (block as any).columns || [];
      result[childModel] = dslColumns.map((col: any) => ({
        field: col.field,
        label: typeof col.label === 'string' ? col.label : col.field,
        type: 'text' as const,
        width: typeof col.width === 'number' ? col.width : undefined,
        required: false,
        editable: col.readOnly ? false : true,
      }));
    }
    return result;
  }, [subTableBlocks]);

  // Async enhancement: fetch field metadata to get proper types and labels
  const [enhancedColumns, setEnhancedColumns] = useState<Record<string, SubTableColumn[]>>({});
  useEffect(() => {
    if (subTableBlocks.length === 0) return;

    for (const block of subTableBlocks) {
      const childModel = block.subTable?.childModel || (block as any).childModel;
      if (!childModel || enhancedColumns[childModel]) continue;

      const normalizedModel = childModel;

      // Use /api/dynamic/{modelCode}/field-meta which requires model-level read permission
      fetchResult<
        Array<{
          code: string;
          dataType: string;
          dictCode?: string;
          refTarget?: Record<string, any> | null;
          referenceModelCode?: string;
          extension?: any;
        }>
      >(`/api/dynamic/${normalizedModel}/field-meta`, {
        method: 'get',
        token: token || undefined,
      })
        .then((fieldsResp) => {
          if (!fieldsResp || !ResultHelper.isSuccess(fieldsResp) || !Array.isArray(fieldsResp.data))
            return;

          const fieldMap: Record<
            string,
            {
              dataType: string;
              displayName: string;
              dictCode?: string;
              component?: string;
              refTarget?: Record<string, any>;
              referenceModelCode?: string;
            }
          > = {};
          for (const f of fieldsResp.data) {
            fieldMap[f.code] = {
              dataType: f.dataType,
              displayName: resolveExtensionDisplayName(f.extension, f.code, locale),
              dictCode: f.dictCode,
              refTarget: mergeRefTarget(f.refTarget, f.extension?.refTarget),
              referenceModelCode:
                f.referenceModelCode ||
                (f.extension?.extension as any)?.referenceModelCode ||
                (f.extension?.extension as any)?.refModelCode,
              component: resolveComponentByFieldMeta(f.dataType, f.extension),
            };
          }

          const dslColumns: any[] = block.subTable?.columns || (block as any).columns || [];
          const mapped: SubTableColumn[] = dslColumns.map((col: any) => {
            const meta = fieldMap[col.field];
            return {
              field: col.field,
              label: meta?.displayName || (typeof col.label === 'string' ? col.label : col.field),
              type: meta ? mapDataTypeToColumnType(meta.dataType) : 'text',
              width: typeof col.width === 'number' ? col.width : undefined,
              required: false,
              editable: col.readOnly ? false : true,
            };
          });

          setEnhancedColumns((prev) => ({ ...prev, [childModel]: mapped }));
        })
        .catch(() => {});
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [subTableBlocks.length, token, locale]);

  // Edit mode: fetch existing child records for sub-table blocks
  useEffect(() => {
    if (!recordId) return; // Create mode -- no children to load
    if (!schema) return; // Wait for schema to load first

    if (subTableBlocks.length === 0) {
      setEditDataLoaded(true);
      return;
    }

    const loadChildren = async () => {
      const newData: Record<string, Record<string, any>[]> = {};

      for (const block of subTableBlocks) {
        const childModel = block.subTable?.childModel || (block as any).childModel;
        const parentField = block.subTable?.parentField || (block as any).parentField;
        if (!childModel || !parentField) continue;

        const blockKey = block.id || `sub-table-${subTableBlocks.indexOf(block)}`;

        try {
          const resp = await fetchResult<any>(`/api/dynamic/${childModel}/list`, {
            method: 'get',
            params: {
              pageNum: 1,
              pageSize: 200,
              filters: JSON.stringify([
                {
                  fieldName: parentField,
                  operator: 'EQ',
                  value: recordId,
                },
              ]),
            },
            token: token || undefined,
          });

          const records = resp.data?.records ?? [];
          if (ResultHelper.isSuccess(resp) && Array.isArray(records) && records.length > 0) {
            newData[blockKey] = records.map((row: any, i: number) => ({
              ...row,
              _key: row.pid || `loaded-${i}`,
            }));
          }
        } catch {
          // Silently skip failed child loads
        }
      }

      setSubTableData(newData);
      setEditDataLoaded(true);
    };

    loadChildren();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [recordId, schema, token]);

  // Render smart field using utility
  const renderSmartField = useMemo(
    () =>
      createFieldRenderer(formData, setFormData, pageContext, fieldErrors, (fieldCode, value) => {
        dirtyFieldsRef.current.add(fieldCode);
        clearFieldError(fieldCode);
        syncRuntimeFieldValue(fieldCode, value);
      }),
    [formData, pageContext, fieldErrors, clearFieldError, syncRuntimeFieldValue],
  );

  // Stable runtime context for custom blocks. Memoized so the props
  // reference passed to ComponentLoader is identity-stable across re-renders
  // unless one of the source values changes; otherwise every keystroke in
  // a form input would re-mount the custom block subtree and blow away
  // local state (drag selection, cached fetches, etc.).
  // Must be declared before the schema null-guard early return so hook order
  // stays stable across renders (react-hooks/rules-of-hooks).
  const customBlockRuntime = useMemo(
    () => ({
      record: formData,
      initialRecord: initialFormData ?? formData,
      recordId,
      tableName,
      token,
      locale,
      t,
      getFieldValue: (fieldCode: string) => getFormFieldValueWithAlias(formData, fieldCode),
      updateField: (fieldCode: string, value: unknown) => {
        dirtyFieldsRef.current.add(fieldCode);
        clearFieldError(fieldCode);
        setFormData((prev) => {
          if (prev[fieldCode] === value) return prev;
          return {
            ...prev,
            [fieldCode]: value,
          };
        });
      },
      getContext: () => ({ record: formData, pageContext }),
    }),
    [
      formData,
      initialFormData,
      recordId,
      tableName,
      token,
      locale,
      t,
      clearFieldError,
      pageContext,
    ],
  );

  // --- T10: local draft autosave (create-form focused) -----------------------
  // Persist in-progress create-form input to localStorage so an accidental
  // reload/navigation doesn't lose work, with restore-on-reopen + clear-on-submit.
  // Scoped to create mode: edit forms hydrate from the server record and we don't
  // want a stale local draft to silently shadow loaded values. (The store key
  // still scopes by recordId, so enabling edit later is a one-line change.)
  const draftModelCode = (schema as any)?.modelCode || tableName;
  const draftPageKey = (schema as any)?.pageKey;
  const draftEnabled = !isEditMode && !onSubmitOverride;
  const {
    restorable: restorableDraft,
    restore: restoreDraft,
    discard: discardDraft,
    clearDraft: clearFormDraft,
  } = useFormDraft({
    enabled: draftEnabled,
    modelCode: draftModelCode,
    pageKey: draftPageKey,
    recordId,
    values: formData,
    initialValues: useMemo(
      () => ({ ...schemaDefaultValues, ...urlDefaultValues }),
      [schemaDefaultValues, urlDefaultValues],
    ),
  });

  const handleRestoreDraft = useCallback(() => {
    const values = restoreDraft();
    if (!values) return;
    setFormData((prev) => {
      const next = { ...prev, ...values };
      for (const fieldCode of Object.keys(values)) {
        dirtyFieldsRef.current.add(fieldCode);
      }
      return next;
    });
  }, [restoreDraft]);

  // `handleFormAction` (declared above) clears the draft on a successful submit
  // via this ref, since the hook's `clearFormDraft` is defined after it.
  clearFormDraftRef.current = clearFormDraft;

  // Null schema guard
  if (!schema) {
    return null;
  }

  // Extract form, sub-table, and button blocks
  const allBlocks = schema.blocks || [];

  const formBlocks = allBlocks.filter((block: any) => block.blockType === 'form-section');
  const layoutBlocks = allBlocks.filter((block: any) => block.blockType === 'tabs');
  // Custom block support — surfaces blockType:"custom" entries that DSL
  // pages declare for visual companions to the form (e.g. position
  // ruler, designer panels). Rendered above the form-section blocks so
  // operators see the visualization first.
  const customBlocks = allBlocks.filter((block: any) => block.blockType === 'custom');
  // subTableBlocks computed via useMemo above (used for metadata fetching and rendering)
  const buttonBlock = allBlocks.find((block: any) => block.blockType === 'form-buttons');
  const effectiveButtonBlock = buttonBlock || null;
  const submitReady = mainRecordLoaded && (schemaFieldNames.size === 0 || fieldMetaLoaded);

  return (
    <DataSourceProvider manager={dataSourceManager}>
      {/* Centered, width-capped form: full-width inputs stretched edge-to-edge on
          wide screens read as sparse and hurt scanability. max-w-6xl (~1152px) keeps
          a comfortable 2-column line length while staying roomy for sub-tables. */}
      <div
        className="mx-auto w-full max-w-6xl px-2 py-3"
        data-testid={deriveTestId('form', schema?.modelCode || tableName, 'container')}
      >
        <div className="rounded-card bg-panel shadow-sm">
          {/* Page Header */}
          <div className="border-border border-b px-6 py-4">
            <div className="flex items-center justify-between">
              <h2 className="text-text text-lg font-medium">
                {getLocalizedText(schema.title, locale, t)}
              </h2>
              <Link
                to={`/p/${tableName}`}
                data-testid="form-back-link"
                className="text-accent text-sm hover:text-blue-800"
              >
                {t('action.back')}
              </Link>
            </div>
          </div>

          {/* Error Summary */}
          {summaryErrors.length > 0 ? (
            <div
              className="rounded-control bg-status-red-bg mx-6 mt-4 border border-red-200 p-4"
              data-testid="form-error-summary"
            >
              <p className="text-sm font-medium text-red-700">
                {summaryErrors.length > 1
                  ? `请先修正以下 ${summaryErrors.length} 项问题`
                  : '请先修正以下问题'}
              </p>
              <ul className="text-status-red mt-2 list-disc space-y-1 pl-5">
                {summaryErrors.map((message) => (
                  <li key={message}>{message}</li>
                ))}
              </ul>
            </div>
          ) : error ? (
            <div className="rounded-control bg-status-red-bg mx-6 mt-4 border border-red-200 p-4">
              <p className="text-status-red">{error}</p>
            </div>
          ) : null}

          {/* T10: restore-draft prompt — offered when a non-expired local draft
              differs from the initial create-form values. */}
          {restorableDraft && (
            <RestoreDraftBanner
              savedAt={restorableDraft.savedAt}
              locale={locale}
              t={t}
              onRestore={handleRestoreDraft}
              onDiscard={discardDraft}
            />
          )}

          {/* URL-prefill hint: shown when modelCode was seeded from ?modelCode=xxx */}
          {urlSeededModelCode && !isEditMode && (
            <div
              className="rounded-control bg-accent-weak mx-6 mt-4 border border-blue-200 p-3"
              data-testid="form-modelcode-prefill-hint"
            >
              <p className="text-accent text-sm">
                {(
                  t('pageSchemaForm.modelCodePrefillHint') || `Creating from model "{modelCode}"`
                ).replace('{modelCode}', urlSeededModelCode)}
              </p>
            </div>
          )}

          {/* Form Content - Using ComponentLoader Pattern */}
          <form className="p-6" data-testid="dynamic-form" onSubmit={(e) => e.preventDefault()}>
            {!mainRecordLoaded ? (
              <div
                className="text-text-3 py-8 text-center text-sm"
                data-testid="dynamic-form-loading"
              >
                {t('common.loading') || 'Loading...'}
              </div>
            ) : (
              <>
                {customBlocks.length > 0 &&
                  customBlocks.map((block: any) => {
                    // Honour DSL visibility condition (matches form-section behavior below).
                    if (block.visibleWhen && !evaluateCondition(block.visibleWhen, pageContext)) {
                      return null;
                    }
                    // Missing component name → surface a visible error, mirroring
                    // BlockRenderer's pattern. Silent-null would hide DSL typos.
                    if (!block.component) {
                      return (
                        <BlockErrorBoundary key={block.id} blockType="custom" blockId={block.id}>
                          <div
                            className="border-status-red bg-status-red-bg mb-5 rounded border p-4"
                            data-block-id={block.id}
                          >
                            <p className="text-red-800">
                              Custom block missing `component`: {block.id}
                            </p>
                          </div>
                        </BlockErrorBoundary>
                      );
                    }
                    return (
                      <BlockErrorBoundary key={block.id} blockType="custom" blockId={block.id}>
                        <div
                          data-block-id={block.id}
                          className={`block-custom mb-5 ${block.className || ''}`}
                        >
                          <ComponentLoader
                            componentName={block.component}
                            props={{ block, runtime: customBlockRuntime }}
                          />
                        </div>
                      </BlockErrorBoundary>
                    );
                  })}
                {formBlocks && formBlocks.length > 0 && (
                  <div className="space-y-5">
                    {formBlocks.map((block: any, blockIndex: number) => {
                      // Check block visibility condition
                      if (block.visibleWhen) {
                        const isVisible = evaluateCondition(block.visibleWhen, pageContext);
                        if (!isVisible) {
                          return null;
                        }
                      }

                      return (
                        <div key={block.id || `block-${blockIndex}`} className="form-section">
                          {/* Section Title */}
                          {block.title && (
                            <h3 className="border-border text-text mb-4 border-b pb-2 text-base font-semibold">
                              {getLocalizedText(block.title, locale, t)}
                            </h3>
                          )}

                          {/* Section Fields */}
                          {block.fields && block.fields.length > 0 && (
                            <div className="grid grid-cols-1 gap-x-6 gap-y-4 md:grid-cols-2">
                              {block.fields.map((rawField: any) => {
                                // L1 SDK: skip hidden fields from external fieldPermissions
                                const externalPerm = fieldPermissions?.[rawField.field];
                                if (externalPerm === 'hidden') return null;

                                // Enrich field with metadata (component, dictCode) when available
                                const meta = modelFields[rawField.field];
                                const mergedValidationRules = mergeFieldValidationRules(
                                  rawField,
                                  meta,
                                  t,
                                  locale,
                                );
                                const maxLength = Number(meta?.feature?.validation?.maxLength);
                                // Use pre-computed extensionProps (stable reference from modelFields)
                                const extensionProps = meta?.extensionProps;
                                let field = meta
                                  ? {
                                      ...rawField,
                                      modelCode: schema?.modelCode || tableName,
                                      label: rawField.label || meta.displayName || rawField.label,
                                      dataType: rawField.dataType || meta.dataType,
                                      component:
                                        rawField.component ||
                                        meta.component ||
                                        resolveComponentByFieldMeta(meta.dataType),
                                      dictCode:
                                        rawField.dictCode ||
                                        meta.dictCode ||
                                        extensionProps?.dictCode,
                                      refTarget: mergeRefTarget(rawField.refTarget, meta.refTarget),
                                      referenceModelCode:
                                        rawField.referenceModelCode || meta.referenceModelCode,
                                      // A read-only field is never user-required (it is
                                      // auto-generated / system-managed and cannot be typed
                                      // into), so the `*` marker must match the submit gate,
                                      // which excludes read-only fields from required
                                      // validation (mergeFieldValidationRules: `!rawField.readOnly`).
                                      // Without this guard an auto-numbered read-only field
                                      // (e.g. sc_code) shows a misleading required `*`.
                                      required: rawField.readOnly
                                        ? false
                                        : (rawField.required ?? meta.required),
                                      readOnly:
                                        rawField.readOnly ??
                                        meta.extension?.readOnly ??
                                        (meta.extension as any)?.extension?.readOnly ??
                                        (extensionProps?.computed === true ? true : undefined),
                                      validation: mergedValidationRules,
                                      props: {
                                        ...(extensionProps || {}),
                                        ...(rawField.props || {}),
                                        ...(Number.isFinite(maxLength) &&
                                        maxLength > 0 &&
                                        !rawField.props?.maxLength
                                          ? { maxLength }
                                          : {}),
                                      },
                                    }
                                  : { ...rawField, modelCode: schema?.modelCode || tableName };

                                // L1 SDK: apply external readonly permission override
                                if (externalPerm === 'readonly') {
                                  field = { ...field, readOnly: true };
                                }

                                // Check field visibility condition
                                if (field.visibleWhen) {
                                  const isVisible = evaluateCondition(
                                    field.visibleWhen,
                                    pageContext,
                                  );
                                  if (!isVisible) {
                                    return null;
                                  }
                                }

                                // Calculate column span based on field layout or DSL span
                                const colSpan =
                                  field.layout?.colSpan || (field.span ? field.span * 6 : 6);
                                const isFullWidth = colSpan >= 12;

                                return (
                                  <div
                                    key={field.field}
                                    data-testid={`form-field-${field.field}`}
                                    data-ab-testid={deriveTestId(
                                      'form',
                                      schema?.modelCode || tableName,
                                      'field',
                                      field.field,
                                    )}
                                    className={isFullWidth ? 'md:col-span-2' : ''}
                                  >
                                    {renderSmartField(field)}
                                  </div>
                                );
                              })}
                            </div>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
                {runtime && layoutBlocks.length > 0 && (
                  <div className="mt-5 space-y-5" data-testid="form-layout-blocks">
                    {layoutBlocks.map((block: any, blockIndex: number) => (
                      <BlockRenderer
                        key={block.id || `form-layout-${blockIndex}`}
                        block={block}
                        runtime={runtime}
                        areaId={`form-layout-${blockIndex}`}
                      />
                    ))}
                  </div>
                )}
              </>
            )}

            {/* Sub-table blocks */}
            {subTableBlocks.map((block: any, blockIndex: number) => {
              const childModel = block.subTable?.childModel || (block as any).childModel;
              const subTableConfig = block.subTable;
              const hasCommands =
                subTableConfig?.commands?.create || subTableConfig?.commands?.delete;
              // Use enhanced columns (with proper types) if available, fall back to DSL-derived columns
              const columns = childModel
                ? enhancedColumns[childModel] || subTableColumnsMap[childModel]
                : undefined;
              const blockKey = block.id || `sub-table-${blockIndex}`;

              return (
                <div key={blockKey} className="mt-6">
                  {block.title && (
                    <h3 className="border-border text-text mb-4 border-b pb-2 text-base font-semibold">
                      {getLocalizedText(block.title, locale, t)}
                    </h3>
                  )}
                  {isEditMode && hasCommands && subTableConfig ? (
                    /* Edit mode with commands: use SubTableViewer for command-based CRUD */
                    <SubTableViewer
                      key={`${blockKey}-${recordId}`}
                      config={subTableConfig}
                      parentRecordId={recordId!}
                      token={token || undefined}
                      locale={locale}
                      t={t}
                      isEditable={!subTableConfig.readOnly}
                    />
                  ) : !editDataLoaded ? (
                    <div className="text-text-3 py-4 text-center text-sm">
                      {t('common.loading') || 'Loading...'}
                    </div>
                  ) : !isEditMode ? (
                    /* Create mode: show placeholder — lines can be added after saving */
                    <div className="rounded-card border-border-strong text-text-3 border border-dashed py-6 text-center text-sm">
                      {t('common.saveFirstToAddLines') !== 'common.saveFirstToAddLines'
                        ? t('common.saveFirstToAddLines')
                        : 'Save the record first, then add line items on the detail page'}
                    </div>
                  ) : columns && columns.length > 0 ? (
                    <SubTable
                      key={`${blockKey}-${recordId || 'new'}`}
                      columns={columns}
                      value={subTableData[blockKey] || []}
                      onChange={(rows) =>
                        setSubTableData((prev) => ({ ...prev, [blockKey]: rows }))
                      }
                    />
                  ) : null}
                </div>
              );
            })}

            {/* Form Buttons */}
            {mainRecordLoaded &&
              effectiveButtonBlock &&
              effectiveButtonBlock.buttons &&
              effectiveButtonBlock.buttons.length > 0 && (
                <div className="border-border mt-6 flex justify-end space-x-3 border-t pt-6">
                  {effectiveButtonBlock.buttons.map((button: any) => {
                    // Check button visibility condition
                    if (button.visibleWhen) {
                      const isVisible = evaluateCondition(button.visibleWhen, pageContext);
                      if (!isVisible) {
                        return null;
                      }
                    }

                    return (
                      <button
                        type="button"
                        key={button.code}
                        data-testid={`form-btn-${button.code}`}
                        data-ab-testid={buttonTestId(
                          'form',
                          schema?.modelCode || tableName,
                          button.code,
                        )}
                        onClick={() => handleFormAction(button)}
                        disabled={loading || !submitReady}
                        className={`rounded-control px-4 py-2 text-sm font-medium ${
                          button.primary
                            ? 'bg-accent hover:bg-accent-hover text-white disabled:bg-blue-400'
                            : 'border-border-strong bg-panel text-text-2 hover:bg-hover border disabled:bg-gray-100'
                        } ${button.danger ? 'bg-red-600 text-white hover:bg-red-700' : ''} disabled:cursor-not-allowed`}
                      >
                        {loading && button.code === 'submit' && (
                          <span className="loading loading-spinner loading-sm mr-2"></span>
                        )}
                        {resolveFormButtonContent(button, locale, t)}
                      </button>
                    );
                  })}
                </div>
              )}
          </form>
        </div>
      </div>
    </DataSourceProvider>
  );
}
