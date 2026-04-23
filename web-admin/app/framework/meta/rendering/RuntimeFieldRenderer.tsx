/**
 * RuntimeFieldRenderer - 用于 SchemaRuntime 集中式状态管理
 *
 * 使用场景:
 * - 在 BlockRenderer 体系中使用
 * - SchemaRuntime 管理所有表单状态
 * - 支持 ActionFlow、验证、事件系统等高级功能
 *
 * 使用示例:
 * ```tsx
 * <RuntimeFieldRenderer field={field} runtime={runtime} />
 * ```
 */

import React, { useMemo } from 'react';
import type { FieldConfig, DataSourceConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { getLocalizedText } from '~/framework/meta/runtime/expression/i18n-renderer';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';

export interface RuntimeFieldRendererProps {
  field: FieldConfig;
  runtime: SchemaRuntime;
}

/**
 * Platform-provided reference models that are NOT stored in ab_meta_model and therefore cannot
 * be resolved via the generic /api/dynamic/{code}/list route. Each entry maps a system model
 * code to the picker endpoint that returns its options.
 *
 * Add new entries here (e.g. sys_role, sys_dept) when new platform pickers come online.
 */
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

/**
 * Runtime 模式字段渲染器
 *
 * 特点:
 * - 状态由 SchemaRuntime 的 ScopedStateManager 管理
 * - 自动处理表单验证和错误收集
 * - 支持复杂的 ActionFlow 流程
 */
export const RuntimeFieldRenderer: React.FC<RuntimeFieldRendererProps> = ({ field, runtime }) => {
  // 从 runtime 获取 context
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t;

  // Get fieldMeta from state manager (linkage-driven overrides)
  const stateManager = runtime.getStateManager();
  const scopeId = runtime.getScopeId();
  const fieldMeta = stateManager.getFieldMeta(scopeId, field.field);

  // Visibility: fieldMeta.hidden takes priority over visibleWhen
  const visible = useMemo(() => {
    if (fieldMeta?.hidden === true) return false;
    if (!field.visibleWhen) return true;
    return evaluateCondition(field.visibleWhen, context);
  }, [fieldMeta?.hidden, field.visibleWhen, context]);

  // 从 SchemaRuntime 获取字段值
  const value = stateManager.getFieldValue(scopeId, field.field);

  // 字段更新函数 — also triggers linkage
  const handleChange = (newValue: any) => {
    stateManager.updateField(scopeId, field.field, newValue);
    runtime?.triggerFieldLinkage(field.field, 'change');
  };

  const handleBlur = () => {
    runtime?.triggerFieldLinkage(field.field, 'blur');
  };

  const handleFocus = () => {
    runtime?.triggerFieldLinkage(field.field, 'focus');
  };

  // Disabled: fieldMeta.disabled takes priority over disableWhen
  const disableExpr = field.disableWhen || (field as any).disabledWhen;
  const enableExpr = field.enableWhen;
  const isDisabled =
    fieldMeta?.disabled === true
      ? true
      : disableExpr
        ? evaluateCondition(disableExpr, context)
        : enableExpr
          ? !evaluateCondition(enableExpr, context)
          : false;

  const readOnlyExpr = field.readOnlyWhen || (field as any).readonlyWhen;
  const isRollUp = Boolean((field as any).feature?.rollUp);
  const isReadOnlyStatic =
    isRollUp ||
    Boolean(
      field.readOnly ?? (field as any).readonly ?? field.props?.readOnly ?? field.props?.readonly,
    );
  const isReadOnly =
    isReadOnlyStatic || (readOnlyExpr ? evaluateCondition(readOnlyExpr, context) : false);

  // Required: fieldMeta.required takes priority over validation rules
  const isRequired =
    fieldMeta?.required !== undefined
      ? fieldMeta.required
      : Boolean((field as any).required) ||
        field.validation?.some((rule) => rule.type === 'required');

  // 构建组件 props
  // 如果有 dictCode 且未指定组件或组件为 SmartInput，自动使用 SmartSelect
  const componentName = useMemo(() => {
    if (field.dictCode && (!field.component || field.component === 'SmartInput')) {
      return 'SmartSelect';
    }
    return field.component || 'SmartInput';
  }, [field.dictCode, field.component]);

  const localizeText = useMemo(
    () => (value: unknown) =>
      typeof value === 'string' || (value && typeof value === 'object')
        ? getLocalizedText(value as any, locale, t)
        : value,
    [locale, t],
  );

  const localizedFieldLabel = useMemo(
    () => (field.label ? localizeText(field.label) : undefined),
    [field.label, localizeText],
  );

  const localizedProps = useMemo(() => {
    const props = { ...(field.props || {}) };
    if ('placeholder' in props) {
      props.placeholder = localizeText(props.placeholder);
    }
    if ('checkedLabel' in props) {
      props.checkedLabel = localizeText(props.checkedLabel);
    }
    if ('uncheckedLabel' in props) {
      props.uncheckedLabel = localizeText(props.uncheckedLabel);
    }
    if (Array.isArray(props.options)) {
      props.options = props.options.map((option: any) => ({
        ...option,
        label: localizeText(option?.label),
      }));
    }
    return props;
  }, [field.props, localizeText]);

  const localizedFieldDataSource = useMemo(() => {
    if (!field.dataSource || typeof field.dataSource === 'string') {
      return field.dataSource;
    }
    if (field.dataSource.type !== 'static' || !Array.isArray(field.dataSource.data)) {
      return field.dataSource;
    }
    return {
      ...field.dataSource,
      data: field.dataSource.data.map((item) => ({
        ...item,
        label: localizeText(item?.label),
      })),
    } satisfies DataSourceConfig;
  }, [field.dataSource, localizeText]);

  if (!visible) return null;

  const componentProps: Record<string, any> = {
    name: field.field,
    label: localizedFieldLabel,
    value,
    onChange: handleChange,
    onBlur: handleBlur,
    onFocus: handleFocus,
    disabled: isDisabled,
    readOnly: isReadOnly,
    required: isRequired,
    context,
    ...localizedProps, // 合并字段配置的其他 props
  };

  // 如果有 dictCode 且没有 dataSource，自动生成字典数据源配置
  if (field.dictCode && !field.dataSource) {
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
  } else if (
    String((field as any).dataType || '').toLowerCase() === 'reference' &&
    !field.dataSource
  ) {
    const refTarget = {
      ...(((field as any).props?.refTarget || {}) as Record<string, any>),
      ...(((field as any).refTarget || {}) as Record<string, any>),
    };
    const targetModelCode =
      refTarget?.targetModel ||
      refTarget?.modelCode ||
      refTarget?.targetEntity ||
      (field as any).referenceModelCode;
    const labelField = refTarget?.targetField;
    if (targetModelCode) {
      // System-model dispatch: sys_user / sys_role / sys_dept live in platform tables,
      // not in ab_meta_model, so they are not reachable via /api/dynamic/{code}/list.
      // Each one exposes a dedicated picker endpoint.
      const systemModel = SYSTEM_MODEL_ENDPOINTS[targetModelCode];
      if (systemModel) {
        componentProps.dataSource = {
          type: 'api',
          endpoint: systemModel.endpoint,
          method: 'get',
          params: { size: 200 },
          adaptor: 'optionList',
          valueField: systemModel.valueField,
          labelField: labelField || systemModel.labelField,
          autoFetch: true,
        } satisfies DataSourceConfig;
      } else {
        const referenceDataSource: DataSourceConfig = {
          type: 'api',
          endpoint: `/api/dynamic/${targetModelCode}/list`,
          method: 'get',
          params: { page: 1, pageSize: 200 },
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
  } else if (localizedFieldDataSource) {
    // 如果有 dataSource 配置，传递给组件
    componentProps.dataSource = localizedFieldDataSource;
  }

  // Options: fieldMeta.options takes priority over static props.options
  if (fieldMeta?.options) {
    componentProps.options = fieldMeta.options;
  } else if (localizedProps.options) {
    componentProps.options = localizedProps.options;
  }

  // 如果有 validation，转换为验证规则
  if (field.validation) {
    componentProps.validationRules = field.validation;
  }

  // 处理布局
  const colSpan = field.layout?.colSpan || 6;
  const colSpanClass =
    colSpan >= 12
      ? 'col-span-full'
      : colSpan >= 9
        ? 'col-span-9'
        : colSpan >= 6
          ? 'col-span-6'
          : colSpan >= 4
            ? 'col-span-4'
            : colSpan >= 3
              ? 'col-span-3'
              : '';

  return (
    <div className={`runtime-field-renderer ${colSpanClass}`} data-testid={`field-${field.field}`}>
      <ComponentLoader componentName={componentName} props={componentProps} />
    </div>
  );
};

export default RuntimeFieldRenderer;
