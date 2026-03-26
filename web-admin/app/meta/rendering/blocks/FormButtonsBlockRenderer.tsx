/**
 * FormButtonsBlockRenderer - 表单按钮块渲染器
 * 用于渲染表单底部的操作按钮组
 */

import React, { useMemo } from 'react';
import type { BlockConfig, ButtonConfig } from '~/meta/schemas/types';
import type { SchemaRuntime } from '~/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';

export interface FormButtonsBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

export const FormButtonsBlockRenderer: React.FC<FormButtonsBlockRendererProps> = ({
  block,
  runtime,
}) => {
  const context = runtime.getContext();
  const evaluator = runtime.getEvaluator();
  const buttons = block.buttons || [];

  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // 处理按钮点击事件
  const handleButtonClick = (button: ButtonConfig) => {
    if (!button.events?.onClick) return;

    const handler = button.events.onClick.handler;
    if (handler) {
      // 执行 handler
      runtime.executeHandler(handler, button.events.onClick.args || {});
    }
  };

  // 渲染单个按钮
  const renderButton = (button: ButtonConfig) => {
    // 检查按钮可见性
    if (button.visibleWhen) {
      const visible = evaluator.evaluateCondition(button.visibleWhen, context);
      if (!visible) return null;
    }

    // 检查按钮禁用状态
    let disabled = false;
    if (button.disableWhen) {
      disabled = evaluator.evaluateCondition(button.disableWhen, context);
    }
    if (button.enableWhen) {
      disabled = !evaluator.evaluateCondition(button.enableWhen, context);
    }

    // 渲染按钮文本
    const content = getLocalizedText(button.content || button.label || button.code, locale, t);

    // 确定按钮样式
    const getButtonClassName = () => {
      const baseClass =
        'px-4 py-2 text-sm font-medium rounded-md focus:outline-none focus:ring-2 focus:ring-offset-2';

      if (button.danger) {
        return `${baseClass} text-white bg-red-600 hover:bg-red-700 focus:ring-red-500 ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
      }

      if (button.primary) {
        return `${baseClass} text-white bg-blue-600 hover:bg-blue-700 focus:ring-blue-500 ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
      }

      return `${baseClass} text-gray-700 bg-white border border-gray-300 hover:bg-gray-50 focus:ring-blue-500 ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
    };

    return (
      <button
        key={button.code}
        type="button"
        data-testid={`form-btn-${button.code}`}
        onClick={() => handleButtonClick(button)}
        disabled={disabled}
        className={getButtonClassName()}
      >
        {button.icon && <span className="mr-2">{button.icon}</span>}
        {content}
      </button>
    );
  };

  return (
    <div className="form-buttons mt-6 border-t border-gray-200 pt-4">
      <div className="flex justify-end space-x-3">
        {buttons.map((button) => renderButton(button))}
      </div>
    </div>
  );
};

export default FormButtonsBlockRenderer;
