/**
 * createFieldRenderer - 创建字段渲染函数
 *
 * 提取 renderSmartField 重复逻辑为独立工具函数
 *
 * 使用场景:
 * - 动态路由的过滤器渲染
 * - 动态路由的表单字段渲染
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-5)
 * - 2025-12-03: 改用 ControlledFieldRenderer (修复警告)
 *
 * @example
 * ```tsx
 * const renderField = createFieldRenderer(filters, setFilters, pageContext);
 * return <div>{fields.map(renderField)}</div>;
 * ```
 */

import React from 'react';
import { ControlledFieldRenderer } from '~/framework/meta/rendering/ControlledFieldRenderer';
import type { FieldConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';

/**
 * 创建字段渲染函数
 *
 * @param data - 当前数据对象 (filters, formData 等)
 * @param setData - 数据更新函数
 * @param context - ExpressionContext
 * @returns 字段渲染函数
 */
export function createFieldRenderer(
  data: Record<string, any>,
  setData: (data: Record<string, any>) => void,
  context: ExpressionContext,
) {
  return (field: FieldConfig) => (
    <ControlledFieldRenderer
      key={field.field}
      field={field}
      value={data[field.field]}
      onChange={(newValue) => {
        setData({
          ...data,
          [field.field]: newValue,
        });
      }}
      context={context}
    />
  );
}
