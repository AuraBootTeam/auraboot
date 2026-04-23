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
import type { FieldConfig, DataSourceConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluateCondition } from '~/framework/meta/runtime/expression/evaluator';
import { ComponentLoader } from '~/framework/meta/rendering/components/ComponentLoader';
import { FieldError } from '~/ui/ui/field-meta';

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

  // Tree components (cascadeselect, treeselect) load their own dict data via useDictTree hook.
  // They receive dictCode through field.props (from extensionProps).
  const TREE_COMPONENTS = ['cascadeselect', 'treeselect'];
  const isTreeComponent = TREE_COMPONENTS.includes(
    String(field.component || '').toLowerCase(),
  );

  // 构建组件 props
  // 如果有 dictCode 且未指定组件或组件为 SmartInput，自动使用 SmartSelect
  const componentName = useMemo(() => {
    if (
      field.dictCode &&
      !isTreeComponent &&
      (!field.component || field.component === 'SmartInput')
    ) {
      return 'SmartSelect';
    }
    return field.component || 'SmartInput';
  }, [field.dictCode, field.component, isTreeComponent]);

  // Resolve field label from i18n keys with progressive fallback.
  let resolvedLabel: string | undefined = typeof field.label === 'string' ? field.label : undefined;
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

  const componentLower = String(componentName).toLowerCase();

  // GAP-258: UI output adapters for pickers whose native shape does not match
  // backend field types. Backend is the source of truth (dataType:string), so we
  // narrow array outputs at the edge.
  // - cascadeselect: component emits string[] of all levels; backend wants the
  //   most specific leaf as a single string. We forward the last non-empty value.
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
        if (Array.isArray(next)) {
          // Find the last non-empty entry (= the deepest selected level).
          let leaf: string | undefined;
          for (let i = next.length - 1; i >= 0; i--) {
            const candidate = next[i];
            if (typeof candidate === 'string' && candidate !== '') {
              leaf = candidate;
              break;
            }
          }
          onChange(leaf ?? '');
          return;
        }
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

  // For readOnly picker components that don't have built-in readOnly rendering,
  // display the raw value as text instead of loading the interactive component.
  const READONLY_TEXT_COMPONENTS = [
    'cascadeselect', 'treeselect', 'userselect', 'memberpicker',
    'organizationselect', 'addressfield',
  ];
  const shouldRenderAsText =
    isReadOnly &&
    READONLY_TEXT_COMPONENTS.includes(String(componentName).toLowerCase()) &&
    value != null &&
    value !== '';

  return (
    <div
      className={`controlled-field-renderer ${isFullWidth ? 'col-span-full' : ''}`}
      data-testid={`field-${field.field}`}
    >
      {resolvedLabel && (
        <label
          htmlFor={field.field}
          className="mb-1 block text-sm font-medium text-gray-700"
        >
          {resolvedLabel}
          {isRequired && <span className="ml-0.5 text-red-500">*</span>}
        </label>
      )}
      {shouldRenderAsText ? (
        <div className="py-2 text-sm text-gray-900">
          {typeof value === 'object' ? JSON.stringify(value) : String(value)}
        </div>
      ) : (
        <>
          <ComponentLoader componentName={componentName} props={componentProps} />
          <FieldError message={error} />
        </>
      )}
    </div>
  );
};

export default ControlledFieldRenderer;
