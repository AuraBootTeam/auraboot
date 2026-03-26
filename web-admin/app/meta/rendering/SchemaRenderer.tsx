/**
 * SchemaRenderer - 统一渲染引擎 (新 DSL)
 * 负责将 UnifiedSchema 渲染为 React 组件树
 *
 * 设计原则:
 * - 配置驱动: 完全由 DSL schema 驱动 UI 渲染
 * - 单一职责: 只负责渲染，不处理业务逻辑
 * - 开闭原则: 通过 BlockRenderer 扩展，无需修改此文件
 */

import React, { useMemo } from 'react';
import type { UnifiedSchema, AreaConfig } from '~/meta/schemas/types';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import { BlockRenderer } from '~/meta/rendering/BlockRenderer';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface SchemaRendererProps {
  schema: UnifiedSchema;
  runtime: SchemaRuntime;
  className?: string;
}

/**
 * SchemaRenderer - 主渲染引擎
 *
 * 功能:
 * 1. 解析 UnifiedSchema 的 layout 和 areas
 * 2. 按照 layout 定义的结构渲染各个 area
 * 3. 将每个 area 的 blocks 交给 BlockRenderer 处理
 * 4. 支持响应式布局 (grid/flex)
 */
export const SchemaRenderer: React.FC<SchemaRendererProps> = ({
  schema,
  runtime,
  className = '',
}) => {
  const context = runtime.getContext();
  const evaluator = runtime.getEvaluator();

  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // 解析 layout 配置 (新 DSL)
  const layoutStyle = useMemo(() => {
    const styles: React.CSSProperties = {
      display: 'flex',
      flexDirection: 'column',
      gap: '1.5rem',
    };
    return styles;
  }, []);

  // 渲染页面标题
  const renderTitle = () => {
    if (!schema.title) return null;

    const title = getLocalizedText(schema.title, locale, t);
    return (
      <div className="mb-6">
        <h1 className="text-2xl font-bold text-gray-900">{title}</h1>
      </div>
    );
  };

  // 渲染单个 area
  const renderArea = (areaId: string, areaConfig: AreaConfig) => {
    const areaStyle: React.CSSProperties = {
      gridArea: areaId,
    };

    // 处理条件渲染
    if (areaConfig.visibleWhen) {
      const visible = evaluator.evaluateCondition(areaConfig.visibleWhen, context);
      if (!visible) return null;
    }

    return (
      <div
        key={areaId}
        className={`area-${areaId} ${areaConfig.className || ''}`}
        style={areaStyle}
      >
        {areaConfig.blocks.map((block, index) => (
          <BlockRenderer
            key={`${areaId}-block-${index}`}
            block={block}
            runtime={runtime}
            areaId={areaId}
          />
        ))}
      </div>
    );
  };

  return (
    <div className={`schema-renderer ${className}`}>
      {renderTitle()}

      <div className="schema-layout" style={layoutStyle}>
        {Object.entries(schema.areas).map(([areaId, areaConfig]) => renderArea(areaId, areaConfig))}
      </div>
    </div>
  );
};

/**
 * 带默认容器的 SchemaRenderer
 */
export const SchemaRendererWithContainer: React.FC<SchemaRendererProps> = (props) => {
  return (
    <div className="mx-auto w-full px-6 py-8">
      <div className="rounded-lg bg-white p-6 shadow-sm">
        <SchemaRenderer {...props} />
      </div>
    </div>
  );
};

export default SchemaRenderer;
