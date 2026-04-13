/**
 * ToolbarBlockRenderer - 工具栏/按钮组渲染器
 */

import React from 'react';
import type { BlockConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface ToolbarBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const ToolbarBlockRenderer: React.FC<ToolbarBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const evaluator = runtime.getEvaluator();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const buttons = block.buttons || [];

  // 处理按钮点击
  const handleButtonClick = async (button: any) => {
    if (button.handler) {
      try {
        await runtime.executeHandler(button.handler, {});
      } catch (err) {
        console.error('Button handler failed:', err);
      }
    }
  };

  return (
    <div className="toolbar-block flex space-x-2">
      {buttons.map((button) => {
        // 条件渲染
        if (button.visibleWhen) {
          const visible = evaluator.evaluateCondition(button.visibleWhen, context);
          if (!visible) return null;
        }

        const label = getLocalizedText(button.label || button.content || button.code, locale, t);

        return (
          <button
            key={button.code}
            data-testid={`toolbar-btn-${button.code}`}
            onClick={() => handleButtonClick(button)}
            disabled={button.disabled}
            className={`rounded-md px-4 py-2 text-sm font-medium ${
              button.variant === 'primary'
                ? 'bg-blue-600 text-white hover:bg-blue-700'
                : button.variant === 'danger'
                  ? 'bg-red-600 text-white hover:bg-red-700'
                  : 'border border-gray-300 bg-white text-gray-700 hover:bg-gray-50'
            } ${button.disabled ? 'cursor-not-allowed opacity-50' : ''}`}
          >
            {button.icon && <span className="mr-2">{button.icon}</span>}
            {label}
          </button>
        );
      })}
    </div>
  );
};

export default ToolbarBlockRenderer;
