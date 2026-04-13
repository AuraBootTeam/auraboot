/**
 * FormBlockRenderer - 表单块渲染器
 */

import React, { useMemo } from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { FieldRenderer } from '~/framework/meta/rendering/FieldRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface FormBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FormBlockRenderer: React.FC<FormBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const fields = block.fields || [];

  // 计算布局样式
  const gridStyle = useMemo(() => {
    const columns = typeof block.columns === 'number' ? block.columns : block.layout?.columns || 1;
    return {
      display: 'grid',
      gridTemplateColumns: `repeat(${columns}, 1fr)`,
      gap: block.gap || '1.5rem',
    };
  }, [block.columns, block.gap]);

  // 渲染标题
  const renderTitle = () => {
    if (!block.title) return null;
    const title = getLocalizedText(block.title, locale, t);
    return (
      <div className="mb-4">
        <h3 className="text-lg font-medium text-gray-900">{title}</h3>
      </div>
    );
  };

  return (
    <div className="form-block">
      {renderTitle()}
      <div style={gridStyle}>
        {fields.map((field) => (
          <div
            key={field.field}
            data-testid={`form-field-${field.field}`}
            style={{
              gridColumn: field.span ? `span ${field.span}` : undefined,
            }}
          >
            <FieldRenderer field={field} runtime={runtime} />
          </div>
        ))}
      </div>
    </div>
  );
};

export default FormBlockRenderer;
