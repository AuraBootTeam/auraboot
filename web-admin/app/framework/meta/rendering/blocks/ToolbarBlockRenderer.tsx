/**
 * ToolbarBlockRenderer - 工具栏/按钮组渲染器
 */

import React from 'react';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';

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
    context: {},
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
  });

  // 处理按钮点击 - 委托给 useActionHandler
  // Toolbar 通常无 record（列表页工具栏），传 undefined 是合法的
  const handleButtonClick = (button: ButtonConfig) => {
    // Legacy compatibility: bare `button.handler` (not wrapped in events.onClick)
    // is not recognized by normalizeAction — normalize it here to preserve
    // original behavior where `button.handler` on a toolbar button was fire-able.
    const normalized: ButtonConfig =
      button.handler && !button.events?.onClick && !button.action
        ? { ...button, events: { ...(button.events || {}), onClick: { handler: button.handler } } }
        : button;

    // Preserve original gate: only fire for buttons with a recognized action source.
    if (
      !normalized.events?.onClick &&
      !normalized.action &&
      !normalized.commandCode &&
      !normalized.navigateTo &&
      !normalized.apiAction
    ) {
      return;
    }
    handleAction(normalized, undefined);
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
