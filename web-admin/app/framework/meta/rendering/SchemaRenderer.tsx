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
import type { UnifiedSchema, BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { BlockRenderer } from '~/framework/meta/rendering/BlockRenderer';
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
 * 1. 解析 UnifiedSchema 的 layout 和 blocks
 * 2. 按照 layout 定义的结构渲染 blocks
 * 3. 将每个 block 交给 BlockRenderer 处理
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

  return (
    <div className={`schema-renderer ${className}`}>
      {renderTitle()}

      <div className="schema-layout" style={layoutStyle}>
        {(schema.blocks || []).map((block: BlockConfig, index: number) => {
          // 处理条件渲染
          if (block.visibleWhen) {
            const visible = evaluator.evaluateCondition(block.visibleWhen, context);
            if (!visible) return null;
          }

          return (
            <BlockRenderer
              key={block.id || `block-${index}`}
              block={block}
              runtime={runtime}
              areaId="main"
            />
          );
        })}
      </div>
    </div>
  );
};

/**
 * 带默认容器的 SchemaRenderer
 */
export const SchemaRendererWithContainer: React.FC<SchemaRendererProps> = (props) => {
  return (
    <div className="mx-auto w-full px-2 py-3">
      <div className="rounded-lg bg-white p-6 shadow-sm">
        <SchemaRenderer {...props} />
      </div>
    </div>
  );
};

export default SchemaRenderer;
