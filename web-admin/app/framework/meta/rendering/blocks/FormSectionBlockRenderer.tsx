/**
 * FormSectionBlockRenderer - 表单分组块渲染器
 * 用于渲染带标题的表单字段分组
 */

import React, { useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface FormSectionBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FormSectionBlockRenderer: React.FC<FormSectionBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const fields = block.fields || [];

  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // 计算网格布局样式 - 基于 layout.cols (通常是 12 列)
  const gridStyle = useMemo(() => {
    const cols = 12; // 默认 12 列网格
    const colGap = block.layout?.colGap || 12;
    const rowGap = block.layout?.rowGap || 12;

    return {
      display: 'grid',
      gridTemplateColumns: `repeat(${cols}, 1fr)`,
      columnGap: `${colGap}px`,
      rowGap: `${rowGap}px`,
    };
  }, [block.layout]);

  // 渲染标题
  const renderTitle = () => {
    if (!block.title) return null;
    const title = getLocalizedText(block.title, locale, t);
    return (
      <div className="mb-4 border-b border-gray-200 pb-2">
        <h3 className="text-lg font-medium text-gray-900">{title}</h3>
      </div>
    );
  };

  return (
    <div className="form-section mb-6">
      {renderTitle()}
      <div style={gridStyle}>
        {fields.map((field) => {
          // 计算字段的列跨度
          const colSpan = field.layout?.colSpan || 12;
          const rowSpan = field.layout?.rowSpan || 1;

          return (
            <div
              key={field.field}
              style={{
                gridColumn: `span ${colSpan}`,
                gridRow: rowSpan > 1 ? `span ${rowSpan}` : undefined,
              }}
            >
              <FieldRenderer field={field} runtime={runtime} />
            </div>
          );
        })}
      </div>
    </div>
  );
};

export default FormSectionBlockRenderer;
