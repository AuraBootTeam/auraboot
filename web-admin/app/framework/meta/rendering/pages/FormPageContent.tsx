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

import { useState, useCallback, useMemo, useEffect } from 'react';
import { Link, useSearchParams } from 'react-router';
import { useUser, usePermissions } from '~/contexts/AuthContext';
import { usePageRuntime } from '~/framework/meta/rendering/pages/hooks/usePageRuntime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useToastContext } from '~/contexts/ToastContext';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { createFieldRenderer } from '~/framework/meta/utils/createFieldRenderer';
import { ErrorAlert } from '~/ui/ErrorAlert';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import { SubTable } from '~/framework/meta/components/SubTable';
import { SubTableViewer } from '~/framework/meta/rendering/blocks/SubTableViewer';
import type { SubTableColumn } from '~/framework/meta/components/types';
import { resolveExtensionDisplayName } from '~/framework/meta/utils/i18nResolver';
import type { PageContentProps } from '~/framework/meta/profiles/types';
import { mergeRules as crossFieldMergeRules } from '~/framework/meta/validation/ruleMerger';
import { evaluateCondition as crossFieldEvalCondition } from '~/framework/meta/validation/conditionEvaluator';
import { evaluateAssert as crossFieldEvalAssert } from '~/framework/meta/validation/assertEvaluator';
import ConsistencyViolationAlert from '~/ui/consistency/ConsistencyViolationAlert';
import {
  isConsistencyViolationError,
  extractViolations,
  type ConsistencyViolation,
} from '~/shared/services/consistencyRuleService';
import { deriveTestId, buttonTestId } from '~/framework/meta/rendering/utils/deriveTestId';

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

function normalizePayloadValue(rawValue: any, dataType?: string) {
  if (
    rawValue === '' &&
    ['date', 'datetime', 'decimal', 'integer'].includes(String(dataType || '').toLowerCase())
  ) {
    return null;
  }
  if (typeof rawValue === 'string') {
    const trimmed = rawValue.trim();
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
): NormalizedValidationRule[] {
  const existingRules: NormalizedValidationRule[] = Array.isArray(rawField?.validation)
    ? [...rawField.validation]
    : [];
  const byType = new Set(existingRules.map((r) => r?.type));

  // Read-only fields are often populated by backend auto-set/default logic in create mode.
  // Enforcing them as user-entered required inputs blocks valid submissions before command execution.
  const required = !rawField?.readOnly && Boolean(rawField?.required ?? meta?.required);
  if (required && !byType.has('required')) {
    const requiredMsg = t?.('common.validation.required');
    const label = rawField?.label || meta?.displayName || rawField?.field;
    existingRules.push({
      type: 'required',
      message:
        requiredMsg && requiredMsg !== 'common.validation.required'
          ? `${label} ${requiredMsg}`
          : `${label} is required`,
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
  const [consistencyViolations, setConsistencyViolations] = useState<ConsistencyViolation[]>([]);

  // Read URL params:
  // - commandCode: explicit submit command provided by navigation actions
  // - sourceRecordId: source business record id for create-by-context flows
  const [searchParams] = useSearchParams();
  const urlCommandCode = searchParams.get('commandCode');
  const sourceRecordId = searchParams.get('sourceRecordId');
  const recordId = props.recordId;
  const isEditMode = !!recordId;

  // URL-based default values for create mode (e.g. ?dv.crm_qt_opportunity_id=01xxx)
  const urlDefaultValues = useMemo(() => {
    if (isEditMode) return {};
    const defaults: Record<string, string> = {};
    for (const [key, value] of searchParams.entries()) {
      if (key.startsWith('dv.') && value) {
        defaults[key.substring(3)] = value;
      }
    }
    return defaults;
  }, [isEditMode, searchParams]);

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
      locale,
      t: (key: string) => t(key),
      fetchResult,
      __dataSourceManager: dataSourceManager,
    });
  }, [locale, t, formData, dataSourceManager, user, permissions, mode]);

  // Sync formData with runtime scope state
  useEffect(() => {
    if (runtime) {
      const scopeId = runtime.getScopeId();
      // Update the form data in the scope so handlers can access it via {{state.form}}
      runtime.getStateManager().updateScope(scopeId, {
        form: formData,
      });
    }
  }, [runtime, formData]);

  // Use unified action handler hook with SchemaRuntime support
  const { handleAction, loading, error, setError } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      data: formData,
      setData: setFormData,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
  });

  // Fetch model field metadata for component resolution (must be before early returns)
  const [modelFields, setModelFields] = useState<Record<string, FieldMetaInfo>>({});
  useEffect(() => {
    // For page-key routes (e.g. dp_issue_triage), schema.modelCode is the real model.
    const targetModelCode = (schema?.modelCode || tableName);
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
      .catch(() => {});
  }, [tableName, schema?.modelCode, token, locale]);

  const validateFormBeforeSubmit = useCallback((): string[] => {
    if (!schema?.blocks) return [];
    const errors: string[] = [];
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
        const rules = mergeFieldValidationRules(rawField, meta, t);
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
              errors.push(rule.message || `${label} is required`);
              break;
            }
          }
          if (
            rule.type === 'maxLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.maxLength)
          ) {
            if (value.length > Number(rule.maxLength)) {
              errors.push(`${label} exceeds max length ${rule.maxLength}`);
              break;
            }
          }
          if (
            rule.type === 'minLength' &&
            typeof value === 'string' &&
            Number.isFinite(rule.minLength)
          ) {
            if (value.length < Number(rule.minLength)) {
              errors.push(`${label} is shorter than min length ${rule.minLength}`);
              break;
            }
          }
          if (rule.type === 'pattern' && typeof value === 'string' && rule.pattern) {
            try {
              const regex = new RegExp(rule.pattern);
              if (!regex.test(value)) {
                errors.push(`${label} format is invalid`);
                break;
              }
            } catch {
              // Ignore invalid regex rule configuration.
            }
          }
          if (rule.type === 'minValue' && Number.isFinite(rule.minValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num < rule.minValue!) {
              errors.push(`${label} must be at least ${rule.minValue}`);
              break;
            }
          }
          if (rule.type === 'maxValue' && Number.isFinite(rule.maxValue)) {
            const num = Number(value);
            if (Number.isFinite(num) && num > rule.maxValue!) {
              errors.push(`${label} must be at most ${rule.maxValue}`);
              break;
            }
          }
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
            errors.push(msg || `Validation failed: ${rule.id}`);
          }
        }
      }
    }

    return errors;
  }, [schema, pageContext, modelFields, t, formData, initialFormData, recordId]);

  // In new mode, override form button commandCodes with the create command
  // so that save_draft/submit use CREATE instead of UPDATE
  const handleFormAction = useCallback(
    (button: { commandCode?: string; [key: string]: any }) => {
      // L1 SDK: delegate to external submit handler when provided
      if (onSubmitOverride) {
        const actionType = resolveActionType(button.action);
        const shouldValidate =
          ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
            actionType.toLowerCase(),
          ) || !actionType;
        if (shouldValidate) {
          const validationErrors = validateFormBeforeSubmit();
          if (validationErrors.length > 0) {
            const firstError = validationErrors[0];
            setError(firstError);
            showErrorToast(firstError);
            return;
          }
        }
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
      // When action is an object like {type: "command", command: "xx:update_xx"},
      // extract the command code from it as well.
      const actionCommandCode =
        effectiveAction && typeof effectiveAction === 'object'
          ? (effectiveAction as Record<string, unknown>).command
          : undefined;
      const effectiveCommandCode = inferEditCommandCode(
        urlCommandCode ||
          effectiveButton.commandCode ||
          (typeof actionCommandCode === 'string' ? actionCommandCode : null) ||
          null,
        Boolean(recordId),
      );
      const shouldValidate =
        ['submit', 'create', 'update', 'edit', 'save', 'command'].includes(
          effectiveActionType.toLowerCase(),
        ) || !effectiveActionType;
      if (shouldValidate) {
        const validationErrors = validateFormBeforeSubmit();
        if (validationErrors.length > 0) {
          const firstError = validationErrors[0];
          setError(firstError);
          showErrorToast(firstError);
          return;
        }
      }
      const modelFieldEntries = Object.entries(modelFields);
      const commandPayload =
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
                  key !== 'deleted_flag' &&
                  key !== 'deleted_at' &&
                  key !== 'deleted_by' &&
                  !key.startsWith('_'),
              ),
            );

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
            targetRecordId,
            payload: commandPayload,
            operationType,
          },
          token: token || undefined,
        })
          .then((result) => {
            if (!ResultHelper.isSuccess(result)) {
              // Check for consistency violation structured response
              if (result.data && isConsistencyViolationError(result.data)) {
                const violations = extractViolations(result.data);
                setConsistencyViolations(violations);
                setError(result.desc || 'Consistency validation failed');
                return;
              }
              const contextError = (result as any).context?.error;
              throw new Error(
                contextError || result.desc || result.message || 'Command execution failed',
              );
            }
            setConsistencyViolations([]);
            navigate(`/p/${tableName}`);
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
        const targetModelCode = (schema?.modelCode || tableName);
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
          navigate(`/p/${targetModelCode}`);
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
      recordId,
      schema?.modelCode,
      setError,
      showErrorToast,
      showSuccessToast,
      sourceRecordId,
      t,
      tableName,
      token,
      urlCommandCode,
      validateFormBeforeSubmit,
    ],
  );

  // Create mode: apply URL default values (dv.fieldCode=value) to form
  useEffect(() => {
    if (isEditMode || Object.keys(urlDefaultValues).length === 0) return;
    setFormData((prev) => ({ ...urlDefaultValues, ...prev }));
  }, [isEditMode, urlDefaultValues]);

  // Edit mode: fetch existing record data to populate form
  const [mainRecordLoaded, setMainRecordLoaded] = useState(!isEditMode);
  useEffect(() => {
    if (!recordId) return;
    setMainRecordLoaded(false);
    fetchResult<any>(`/api/dynamic/${tableName}/${recordId}`, {
      method: 'get',
      token: token || undefined,
    })
      .then((resp) => {
        if (ResultHelper.isSuccess(resp) && resp.data) {
          setFormData(resp.data);
          setInitialFormData(resp.data);
        }
      })
      .catch(() => {})
      .finally(() => {
        setMainRecordLoaded(true);
      });
  }, [recordId, tableName, token]);

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
    () => createFieldRenderer(formData, setFormData, pageContext),
    [formData, pageContext],
  );

  // Null schema guard
  if (!schema) {
    return null;
  }

  // Extract form, sub-table, and button blocks
  const allBlocks = schema.blocks || [];

  const formBlocks = allBlocks.filter((block: any) => block.blockType === 'form-section');
  // subTableBlocks computed via useMemo above (used for metadata fetching and rendering)
  const buttonBlock = allBlocks.find((block: any) => block.blockType === 'form-buttons');
  const effectiveButtonBlock = buttonBlock || null;

  return (
    <DataSourceProvider manager={dataSourceManager}>
      <div
        className="mx-auto w-full px-2 py-3"
        data-testid={deriveTestId(
          'form',
          (schema?.modelCode || tableName),
          'container',
        )}
      >
        <div className="rounded-lg bg-white shadow-sm">
          {/* Page Header */}
          <div className="border-b border-gray-200 px-6 py-4">
            <div className="flex items-center justify-between">
              <h2 className="text-lg font-medium text-gray-900">
                {getLocalizedText(schema.title, locale, t)}
              </h2>
              <Link
                to={`/p/${tableName}`}
                data-testid="form-back-link"
                className="text-sm text-blue-600 hover:text-blue-800"
              >
                {t('action.back')}
              </Link>
            </div>
          </div>

          {/* Consistency Violation Alert */}
          {consistencyViolations.length > 0 && (
            <div className="mx-6 mt-4">
              <ConsistencyViolationAlert
                violations={consistencyViolations}
                onDismiss={() => setConsistencyViolations([])}
              />
            </div>
          )}

          {/* Error Alert */}
          {error && consistencyViolations.length === 0 && (
            <div className="mx-6 mt-4 rounded-md border border-red-200 bg-red-50 p-4">
              <p className="text-red-600">{error}</p>
            </div>
          )}

          {/* Form Content - Using ComponentLoader Pattern */}
          <form className="p-6" data-testid="dynamic-form" onSubmit={(e) => e.preventDefault()}>
            {!mainRecordLoaded ? (
              <div className="py-8 text-center text-sm text-gray-400">
                {t('common.loading') || 'Loading...'}
              </div>
            ) : (
              formBlocks &&
              formBlocks.length > 0 && (
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
                          <h3 className="mb-4 border-b border-gray-200 pb-2 text-base font-semibold text-gray-900">
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
                              );
                              const maxLength = Number(meta?.feature?.validation?.maxLength);
                              // Use pre-computed extensionProps (stable reference from modelFields)
                              const extensionProps = meta?.extensionProps;
                              let field = meta
                                ? {
                                    ...rawField,
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
                                    required: rawField.required ?? meta.required,
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
                                : rawField;

                              // L1 SDK: apply external readonly permission override
                              if (externalPerm === 'readonly') {
                                field = { ...field, readOnly: true };
                              }

                              // Check field visibility condition
                              if (field.visibleWhen) {
                                const isVisible = evaluateCondition(field.visibleWhen, pageContext);
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
                                    (schema?.modelCode || tableName),
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
              )
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
                    <h3 className="mb-4 border-b border-gray-200 pb-2 text-base font-semibold text-gray-900">
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
                    <div className="py-4 text-center text-sm text-gray-400">
                      {t('common.loading') || 'Loading...'}
                    </div>
                  ) : !isEditMode ? (
                    /* Create mode: show placeholder — lines can be added after saving */
                    <div className="rounded-lg border border-dashed border-gray-300 py-6 text-center text-sm text-gray-400">
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
                <div className="mt-6 flex justify-end space-x-3 border-t border-gray-200 pt-6">
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
                        data-ab-testid={buttonTestId('form', (schema?.modelCode || tableName), button.code)}
                        onClick={() => handleFormAction(button)}
                        disabled={loading}
                        className={`rounded-md px-4 py-2 text-sm font-medium ${
                          button.primary
                            ? 'bg-blue-600 text-white hover:bg-blue-700 disabled:bg-blue-400'
                            : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50 disabled:bg-gray-100'
                        } ${button.danger ? 'bg-red-600 text-white hover:bg-red-700' : ''} disabled:cursor-not-allowed`}
                      >
                        {loading && button.code === 'submit' && (
                          <span className="loading loading-spinner loading-sm mr-2"></span>
                        )}
                        {getLocalizedText(button.content, locale, t) ||
                          (typeof button.action === 'string'
                            ? t(`action.${button.action}`)
                            : undefined) ||
                          button.code}
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
