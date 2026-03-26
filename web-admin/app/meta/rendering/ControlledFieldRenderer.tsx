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

import React, { useMemo } from 'react';
import type { FieldConfig, DataSourceConfig } from '~/meta/schemas/types';
import type { ExpressionContext } from '~/meta/runtime/expression/context';
import { evaluateCondition } from '~/meta/runtime/expression/evaluator';
import { ComponentLoader } from '~/meta/rendering/components/ComponentLoader';

export interface ControlledFieldRendererProps {
  field: FieldConfig;
  value: any;
  onChange: (value: any) => void;
  context: ExpressionContext;
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
}) => {
  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // 条件渲染 - 检查 visibleWhen
  const visible = useMemo(() => {
    if (!field.visibleWhen) return true;
    return evaluateCondition(field.visibleWhen, context);
  }, [field.visibleWhen, context]);

  if (!visible) return null;

  // 计算条件属性
  const disableExpr = field.disableWhen || (field as any).disabledWhen;
  const enableExpr = field.enableWhen;
  const isDisabled = disableExpr
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

  const isRequired =
    Boolean((field as any).required) || field.validation?.some((rule) => rule.type === 'required');

  // 构建组件 props
  // 如果有 dictCode 且未指定组件或组件为 SmartInput，自动使用 SmartSelect
  const componentName = useMemo(() => {
    if (field.dictCode && (!field.component || field.component === 'SmartInput')) {
      return 'SmartSelect';
    }
    return field.component || 'SmartInput';
  }, [field.dictCode, field.component]);

  // Resolve field label from i18n keys with progressive fallback.
  let resolvedLabel = field.label;
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

  const componentProps: Record<string, any> = {
    name: field.field,
    label: resolvedLabel,
    value,
    onChange,
    disabled: isDisabled,
    readOnly: isReadOnly,
    required: isRequired,
    context,
    ...field.props, // 合并字段配置的其他 props
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
  } else if (field.dataSource) {
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

  // 处理布局
  const colSpan = field.layout?.colSpan || 6;
  const isFullWidth = colSpan >= 12;

  return (
    <div
      className={`controlled-field-renderer ${isFullWidth ? 'col-span-full' : ''}`}
      data-testid={`field-${field.field}`}
    >
      <ComponentLoader componentName={componentName} props={componentProps} />
    </div>
  );
};

export default ControlledFieldRenderer;
