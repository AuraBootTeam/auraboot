/**
 * FormButtonsBlockRenderer - 表单按钮块渲染器
 * 用于渲染表单底部的操作按钮组
 */

import React from 'react';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';

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

  // 路由 / 鉴权上下文 — useActionHandler hook 要求
  const navigate = useNavigate();
  const { token } = useAuth();
  const schema = runtime.getSchema();
  const tableName = (schema as any).modelCode || schema.id || '';
  const dataSourceManager = runtime.getDataSourceManager();

  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      data: (context.form as Record<string, any>) || (context.data as Record<string, any>),
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
  });

  // 处理按钮点击事件 - 委托给 useActionHandler
  const handleButtonClick = (button: ButtonConfig) => {
    // Only dispatch buttons that have at least one recognizable action source
    // (preserves original behavior: buttons without events.onClick were no-ops)
    if (!button.events?.onClick && !button.action && !button.commandCode && !button.navigateTo && !button.apiAction && !button.handler) {
      return;
    }
    handleAction(button, (context.form as Record<string, any>) || (context.data as Record<string, any>));
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
