/**
 * ControlledFieldRenderer - 用于本地 useState 状态管理
 *
 * 使用场景:
 * - 在动态路由页面中使用
 * - 使用组件的 useState 管理表单数据
 * - 需要自定义状态更新逻辑（如过滤、分页等）
 *
 * 使用示例:
 * ```tsx
 * <ControlledFieldRenderer
 *   field={field}
 *   value={formData[field.field]}
 *   onChange={(v) => setFormData(prev => ({ ...prev, [field.field]: v }))}
 *   context={pageContext}
 * />
 * ```
 */

import React, { useMemo, useState } from 'react';
import type { FieldConfig, DataSourceConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';
import { FieldError, FieldHelp } from '~/ui/ui/field-meta';
import { FieldErrorOwnedByWrapperContext } from '~/ui/ui/field-base';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { usePermission } from '~/contexts/AuthContext';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useDataSourceManagerOptional } from '~/framework/meta/contexts/DataSourceContext';
import { ReferenceCreateDialog } from '~/framework/meta/runtime/reference-create/ReferenceCreateDialog';
import { hasMissingFieldDependency } from '~/framework/meta/rendering/fieldDependencies';

export interface ControlledFieldRendererProps {
  field: FieldConfig;
  value: any;
  onChange: (value: any) => void;
  context: ExpressionContext;
  error?: string;
}

const SYSTEM_MODEL_ENDPOINTS: Record<
  string,
  { endpoint: string; valueField: string; labelField: string }
> = {
  sys_user: {
    endpoint: '/api/admin/users/search',
    valueField: 'pid',
    labelField: 'displayName',
  },
};

const MAX_DYNAMIC_REFERENCE_PAGE_SIZE = 500;

function resolveCreateInitialValues(
  configured: Record<string, unknown> | undefined,
  context: ExpressionContext,
): Record<string, unknown> | undefined {
  if (!configured) return undefined;
  return Object.fromEntries(
    Object.entries(configured).map(([key, value]) => {
      if (typeof value !== 'string') return [key, value];
      const match = value.trim().match(/^\$\{form\.([^}]+)\}$/);
      return [
        key,
        match
          ? match[1].split('.').reduce((current, part) => current?.[part], context.form)
          : value,
      ];
    }),
  );
}

function isJsonLikeField(field: FieldConfig): boolean {
  return ['json', 'jsonb'].includes(
    String((field as any).dataType || field.type || '').toLowerCase(),
  );
}

function shouldInferJsonEditor(component?: string): boolean {
  const normalized = String(component || '')
    .replace(/[-_]/g, '')
    .toLowerCase();
  return !normalized || ['input', 'smartinput', 'textarea', 'smarttextarea'].includes(normalized);
}

/**
 * Controlled 模式字段渲染器
 *
 * 特点:
 * - 状态由父组件的 useState 管理
 * - 轻量级，无需 SchemaRuntime 开销
 * - 灵活，页面完全控制状态更新逻辑
 */
export const ControlledFieldRenderer: React.FC<ControlledFieldRendererProps> = ({
  field,
  value,
  onChange,
  context,
  error,
}) => {
  const t = context.t || ((key: string) => key);

  // 条件渲染 - 检查 visibleWhen
  const visible = useMemo(() => {
    if (!field.visibleWhen) return true;
    return evaluateCondition(field.visibleWhen, context);
  }, [field.visibleWhen, context]);

  // 计算条件属性
  const disableExpr = field.disableWhen || (field as any).disabledWhen;
  const enableExpr = field.enableWhen;
  const dependencyMissing = hasMissingFieldDependency(field, context);
  const isDisabled = disableExpr
    ? dependencyMissing || evaluateCondition(disableExpr, context)
    : enableExpr
      ? dependencyMissing || !evaluateCondition(enableExpr, context)
      : dependencyMissing;

  const readOnlyExpr = field.readOnlyWhen || (field as any).readonlyWhen;
  const isRollUp = Boolean((field as any).feature?.rollUp);
  const isReadOnlyStatic =
    isRollUp ||
    Boolean(
      field.readOnly ?? (field as any).readonly ?? field.props?.readOnly ?? field.props?.readonly,
    );
  const isReadOnly =
    isReadOnlyStatic || (readOnlyExpr ? evaluateCondition(readOnlyExpr, context) : false);

  const isRequired =
    Boolean((field as any).required) || field.validation?.some((rule) => rule.type === 'required');

  // Tree components (cascadeselect, treeselect) load their own dict data via useDictTree hook.
  // They receive dictCode through field.props (from extensionProps).
  const TREE_COMPONENTS = ['cascadeselect', 'treeselect'];
  const isTreeComponent = TREE_COMPONENTS.includes(String(field.component || '').toLowerCase());

  // 构建组件 props
  // 如果有 dictCode 且未指定组件或组件为 SmartInput，自动使用 SmartSelect
  const componentName = useMemo(() => {
    const explicitComponent = field.component || (field as any).fieldType;
    const component = String(explicitComponent || '').toLowerCase();
    if (isJsonLikeField(field) && shouldInferJsonEditor(explicitComponent)) {
      return 'SmartJsonEditor';
    }
    if (
      field.dictCode &&
      !isTreeComponent &&
      (!component || component === 'input' || component === 'smartinput')
    ) {
      return 'SmartSelect';
    }
    const dataType = String((field as any).dataType || field.type || '').toLowerCase();
    if (
      ['enum', 'reference'].includes(dataType) &&
      !isTreeComponent &&
      (!component || component === 'input' || component === 'smartinput')
    ) {
      return 'SmartSelect';
    }
    return explicitComponent || 'SmartInput';
  }, [
    field,
    field.dictCode,
    field.component,
    (field as any).dataType,
    (field as any).fieldType,
    (field as any).type,
    isTreeComponent,
  ]);

  // Resolve field label from i18n keys with progressive fallback.
  let resolvedLabel = getLocalizedText(field.label, context.locale || 'zh-CN', t) || undefined;
  if (!resolvedLabel) {
    const modelCode = (field as any).modelCode || (context as any).modelCode;
    if (modelCode) {
      const modelKey = `model.${modelCode}.${field.field}.label`;
      const modelLabel = t(modelKey);
      if (modelLabel && modelLabel !== modelKey) {
        resolvedLabel = modelLabel;
      }
    }
  }
  if (!resolvedLabel) {
    const directFieldKey = `field.${field.field}.label`;
    const directFieldLabel = t(directFieldKey);
    if (directFieldLabel && directFieldLabel !== directFieldKey) {
      resolvedLabel = directFieldLabel;
    } else {
      const commonKey = `common.field.${field.field}`;
      const commonLabel = t(commonKey);
      if (commonLabel && commonLabel !== commonKey) {
        resolvedLabel = commonLabel;
      } else {
        resolvedLabel = field.field
          .replace(/^[a-z0-9]+_[a-z0-9]+_/, '')
          .replace(/_/g, ' ')
          .replace(/\b\w/g, (c: string) => c.toUpperCase());
      }
    }
  }

  // Help text is rendered by this wrapper (like the label), not by the control.
  // Only the smart *Input* ever forwarded a plain `helpText` prop into its FieldBase, so
  // every other control (SmartSelect / SmartSwitch / SmartUpload / pickers …) silently
  // dropped a configured `helpText`. Rendering it here makes help work for every control
  // and keeps the vertical label→control→error→help rhythm identical across components.
  const rawHelpText = (field as any).helpText ?? field.props?.helpText;
  const resolvedHelpText =
    rawHelpText == null || rawHelpText === ''
      ? undefined
      : getLocalizedText(rawHelpText, context.locale || 'zh-CN', t) || undefined;

  const componentLower = String(componentName).toLowerCase();

  // GAP-258: UI output adapters for pickers whose native shape does not match
  // backend field types. Backend is the source of truth (dataType:string), so we
  // narrow array outputs at the edge.
  // - cascadeselect: component emits string[] of all levels; keep that full
  //   path in UI state so subsequent levels stay enabled. FormPageContent
  //   narrows the command payload to the deepest leaf for string fields.
  // - memberpicker (multiple): component emits string[]; backend rejects arrays
  //   for string fields, so we serialize as a JSON string ('["id1","id2"]').
  const isMemberPickerMultiple =
    componentLower === 'memberpicker' && Boolean(field.props?.multiple);
  const isUploadComponent = componentLower === 'upload' || componentLower === 'smartupload';
  const expectsJsonObjectValue =
    componentLower === 'daterange' ||
    componentLower === 'timerangepicker' ||
    componentLower === 'addressfield';

  const parseJsonObject = (raw: unknown) => {
    if (raw && typeof raw === 'object' && !Array.isArray(raw)) return raw;
    if (typeof raw !== 'string') return raw;
    const trimmed = raw.trim();
    if (!trimmed.startsWith('{') || !trimmed.endsWith('}')) return raw;
    try {
      const parsed = JSON.parse(trimmed);
      return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : raw;
    } catch {
      return raw;
    }
  };

  const adaptedValue = useMemo(() => {
    if (componentLower === 'cascadeselect') {
      // Re-inflate stored leaf string into a single-element path so the picker
      // can display the selected leaf value. Parent-level labels won't repopulate
      // from just the leaf, but the leaf trigger will reflect the persisted value.
      if (typeof value === 'string' && value !== '') return [value];
      if (Array.isArray(value)) return value;
      return undefined;
    }
    if (isMemberPickerMultiple) {
      if (typeof value === 'string' && value.startsWith('[')) {
        try {
          const parsed = JSON.parse(value);
          if (Array.isArray(parsed)) return parsed;
        } catch {
          // CATCH: non-transactional JSON parse guard; fall through to raw value
        }
      }
      return value;
    }
    if (isUploadComponent) {
      if (typeof value === 'string' && value.startsWith('[')) {
        try {
          const parsed = JSON.parse(value);
          if (Array.isArray(parsed)) {
            return parsed.map((item) => ({
              uid:
                typeof item?.fileId === 'string'
                  ? item.fileId
                  : typeof item?.url === 'string'
                    ? item.url
                    : `${item?.name || 'file'}-${item?.size || 0}`,
              name: item?.name || 'file',
              status: 'done',
              url: item?.url,
              size: item?.size,
              type: item?.type,
              response: item,
            }));
          }
        } catch {
          // Ignore malformed persisted file JSON and fall through to raw value.
        }
      }
      return Array.isArray(value) ? value : [];
    }
    if (expectsJsonObjectValue) {
      return parseJsonObject(value);
    }
    return value;
  }, [componentLower, expectsJsonObjectValue, isMemberPickerMultiple, isUploadComponent, value]);

  const adaptedOnChange = useMemo(() => {
    if (componentLower === 'cascadeselect') {
      return (next: unknown) => {
        onChange(next);
      };
    }
    if (isMemberPickerMultiple) {
      return (next: unknown) => {
        if (Array.isArray(next)) {
          onChange(next.length > 0 ? JSON.stringify(next) : '');
          return;
        }
        onChange(next);
      };
    }
    if (expectsJsonObjectValue) {
      return (next: unknown) => {
        if (next && typeof next === 'object') {
          onChange(JSON.stringify(next));
          return;
        }
        onChange(next);
      };
    }
    return onChange;
  }, [componentLower, expectsJsonObjectValue, isMemberPickerMultiple, onChange]);

  const fieldKind = String((field as any).dataType || field.type || '').toLowerCase();
  const refTarget = {
    ...(((field as any).props?.refTarget || {}) as Record<string, any>),
    ...(((field as any).refTarget || {}) as Record<string, any>),
  };
  const refTargetModel =
    refTarget?.targetModel ||
    refTarget?.modelCode ||
    refTarget?.targetEntity ||
    (field as any).referenceModelCode ||
    (typeof field.dataSource === 'object' ? (field.dataSource as any).modelCode : undefined);
  const refDisplayField =
    refTarget?.displayField ||
    refTarget?.labelField ||
    refTarget?.targetField ||
    (typeof field.dataSource === 'object' ? (field.dataSource as any).labelField : undefined);
  const createCommandCode =
    field.createCommand || (refTargetModel ? `${refTargetModel}:create` : '');
  const createPermissionCode = field.createPermission || createCommandCode;
  const hasCreatePerm = usePermission(createPermissionCode);
  const allowCreate =
    Boolean(field.allowCreate) && fieldKind === 'reference' && !!refTargetModel && hasCreatePerm;
  const [createOpen, setCreateOpen] = useState(false);
  const dataSourceManager = useDataSourceManagerOptional();
  const { executeCommand } = useActionHandler({
    runtime: null,
    navigate: (() => undefined) as any,
    tableName: refTargetModel || field.field,
    dataSourceManager,
    locale: context.locale || 'zh-CN',
    t,
    token: (context as any).token,
  });

  const handleCreated = (selected: { value: string; label: string }) => {
    const nextValue = Array.isArray(value)
      ? value.some((item) => String(item) === String(selected.value))
        ? value
        : [...value, selected.value]
      : selected.value;

    const ids =
      refTargetModel && typeof (dataSourceManager as any)?.getDataSourceIdsByModel === 'function'
        ? (dataSourceManager as any).getDataSourceIdsByModel(refTargetModel)
        : [];
    const pinCreatedOption = () => {
      if (
        typeof (dataSourceManager as any)?.getState !== 'function' ||
        typeof (dataSourceManager as any)?.setData !== 'function'
      ) {
        return;
      }
      const createdOption = { value: selected.value, label: selected.label };
      ids.forEach((dsId: string) => {
        const currentData = (dataSourceManager as any).getState(dsId)?.data;
        const options = Array.isArray(currentData) ? currentData : [];
        const exists = options.some(
          (option: any) => String(option?.value) === String(selected.value),
        );
        if (!exists) {
          (dataSourceManager as any).setData(dsId, [createdOption, ...options]);
        }
      });
    };
    if (ids.length > 0) {
      pinCreatedOption();
    }

    onChange(nextValue);

    const setFormFieldValue = (context as any).__setFormFieldValue;
    if (typeof setFormFieldValue === 'function') {
      setFormFieldValue(field.field, nextValue);
    }
    if (typeof window !== 'undefined') {
      window.dispatchEvent(
        new CustomEvent('aura:reference-field-created', {
          detail: {
            pageKey: (context as any).__pageKey,
            modelCode: (context as any).__modelCode,
            fieldCode: field.field,
            value: nextValue,
          },
        }),
      );
    }

    if (ids.length > 0 && typeof (dataSourceManager as any)?.reload === 'function') {
      void Promise.resolve((dataSourceManager as any).reload(ids)).then(pinCreatedOption);
    }
    setCreateOpen(false);
  };

  if (!visible) return null;

  const componentProps: Record<string, any> = {
    name: field.field,
    // label is rendered by ControlledFieldRenderer wrapper, not passed to component
    // to ensure consistent vertical label-above-input layout across all components
    value: adaptedValue,
    onChange: adaptedOnChange,
    disabled: isDisabled,
    readOnly: isReadOnly,
    required: isRequired,
    error,
    context,
    ...field.props, // 合并字段配置的其他 props
  };
  // Owned by this wrapper (see resolvedHelpText); keep it out of the control's props so
  // SmartInput does not render a second copy under its own FieldBase.
  delete componentProps.helpText;

  // 如果有 dictCode 且没有 dataSource，自动生成字典数据源配置（不适用于 tree 组件）
  if (field.dictCode && !isTreeComponent && !field.dataSource) {
    const dictDataSource: DataSourceConfig = {
      type: 'api',
      endpoint: `/api/meta/dict/by-code/${field.dictCode}/data`,
      method: 'get',
      adaptor: 'dictData',
      labelField: 'label',
      valueField: 'value',
      autoFetch: true,
    };
    componentProps.dataSource = dictDataSource;
  } else if (!dependencyMissing && fieldKind === 'reference' && !field.dataSource) {
    const targetModelCode = refTargetModel;
    const labelField = refDisplayField;
    if (targetModelCode) {
      const systemModel = SYSTEM_MODEL_ENDPOINTS[targetModelCode];
      if (systemModel) {
        componentProps.dataSource = {
          type: 'api',
          modelCode: targetModelCode,
          endpoint: systemModel.endpoint,
          method: 'get',
          params: { size: 200 },
          adaptor: 'optionList',
          valueField: systemModel.valueField,
          labelField: labelField || systemModel.labelField,
          autoFetch: true,
        } satisfies DataSourceConfig;
      } else {
        const configuredPageSize = Number(refTarget?.pageSize || refTarget?.maxItems || 200);
        const pageSize =
          Number.isFinite(configuredPageSize) && configuredPageSize > 0
            ? Math.min(configuredPageSize, MAX_DYNAMIC_REFERENCE_PAGE_SIZE)
            : 200;
        const params: Record<string, any> = { pageNum: 1, pageSize };
        if (refTarget?.sortField) {
          params.sortField = refTarget.sortField;
          params.sortOrder = refTarget.sortOrder || 'desc';
        }
        const referenceDataSource: DataSourceConfig = {
          type: 'api',
          modelCode: targetModelCode,
          endpoint: `/api/dynamic/${targetModelCode}/list`,
          method: 'get',
          params,
          adaptor: 'optionList',
          valueField: 'pid',
          autoFetch: true,
        };
        if (labelField) {
          referenceDataSource.labelField = labelField;
        }
        componentProps.dataSource = referenceDataSource;
      }
    }
  } else if (!dependencyMissing && field.dataSource) {
    // 如果有 dataSource 配置，传递给组件
    componentProps.dataSource = field.dataSource;
  }

  // 如果有静态 options，传递给组件
  if (field.props?.options) {
    componentProps.options = field.props.options;
  }

  // 如果有 validation，转换为验证规则
  if (field.validation) {
    componentProps.validationRules = field.validation;
  }

  if (allowCreate) {
    componentProps.canCreateNew = true;
    componentProps.onCreateNew = () => setCreateOpen(true);
  }

  // 处理布局
  const colSpan = field.layout?.colSpan || 6;
  const isFullWidth = colSpan >= 12;

  // For readOnly picker components that don't have built-in readOnly rendering,
  // display the raw value as text instead of loading the interactive component.
  const READONLY_TEXT_COMPONENTS = [
    'cascadeselect',
    'treeselect',
    'userselect',
    'memberpicker',
    'organizationselect',
    'addressfield',
  ];
  const shouldRenderAsText =
    isReadOnly &&
    READONLY_TEXT_COMPONENTS.includes(String(componentName).toLowerCase()) &&
    value != null &&
    value !== '';

  return (
    <>
      <div
        className={`controlled-field-renderer ${isFullWidth ? 'col-span-full' : ''}`}
        data-testid={`field-${field.field}`}
      >
        {resolvedLabel && (
          <label htmlFor={field.field} className="text-text-2 mb-1 block text-sm font-medium">
            {resolvedLabel}
            {isRequired && <span className="text-status-red ml-0.5">*</span>}
          </label>
        )}
        {shouldRenderAsText ? (
          <div className="text-text py-2 text-sm">
            {typeof value === 'object' ? JSON.stringify(value) : String(value)}
          </div>
        ) : (
          <>
            {/* While this wrapper shows the field error, the control suppresses its own
                copy of it — otherwise the same message renders twice (form-context error
                here + the control's identical validationRules run inside its FieldBase). */}
            <FieldErrorOwnedByWrapperContext.Provider value={Boolean(error)}>
              <ComponentLoader componentName={componentName} props={componentProps} />
            </FieldErrorOwnedByWrapperContext.Provider>
            <FieldError message={error} />
          </>
        )}
        <FieldHelp message={resolvedHelpText} />
      </div>
      {allowCreate && (
        <ReferenceCreateDialog
          open={createOpen}
          targetModel={refTargetModel}
          createPageKey={field.createPageKey}
          createCommand={createCommandCode}
          displayField={refDisplayField}
          initialValues={resolveCreateInitialValues(field.createInitialValues, context)}
          executeCommand={executeCommand}
          onCreated={handleCreated}
          onClose={() => setCreateOpen(false)}
        />
      )}
    </>
  );
};

export default ControlledFieldRenderer;
