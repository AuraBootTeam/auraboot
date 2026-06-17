/**
 * ToolbarBlockRenderer - 工具栏/按钮组渲染器
 */

import React from 'react';
import * as LucideIcons from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';
import { useToastContext } from '~/contexts/ToastContext';

export interface ToolbarBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const renderIcon = (icon?: ButtonConfig['icon']) => {
  if (!icon || typeof icon !== 'string') return null;
  const Icon = (LucideIcons as unknown as Record<string, LucideIcon>)[icon];
  if (!Icon) return null;
  return <Icon className="mr-2 h-4 w-4" aria-hidden="true" />;
};

export const ToolbarBlockRenderer: React.FC<ToolbarBlockRendererProps> = ({ block, runtime }) => {
  const context = runtime.getContext();
  const evaluator = runtime.getEvaluator();
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);
  const buttons = block.buttons || [];

  // 路由 / 鉴权上下文 — useActionHandler hook 要求
  const navigate = useNavigate();
  const { token } = useAuth();
  const { showSuccessToast, showErrorToast, showWarningToast, showInfoToast } = useToastContext();
  const schema = runtime.getSchema();
  const tableName = (schema as any).modelCode || schema.id || '';
  const dataSourceManager = runtime.getDataSourceManager();
  const showToast = React.useCallback(
    (message: string, type: 'success' | 'error' | 'warning' | 'info' = 'info') => {
      switch (type) {
        case 'success':
          showSuccessToast(message);
          break;
        case 'error':
          showErrorToast(message);
          break;
        case 'warning':
          showWarningToast(message);
          break;
        case 'info':
          showInfoToast(message);
          break;
      }
    },
    [showSuccessToast, showErrorToast, showWarningToast, showInfoToast],
  );

  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {},
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
    showToast,
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
            className={`rounded-control inline-flex items-center px-4 py-2 text-sm font-medium ${
              button.variant === 'primary'
                ? 'bg-accent hover:bg-accent-hover text-white'
                : button.variant === 'danger'
                  ? 'bg-red-600 text-white hover:bg-red-700'
                  : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
            } ${button.disabled ? 'cursor-not-allowed opacity-50' : ''}`}
          >
            {renderIcon(button.icon)}
            {label}
          </button>
        );
      })}
    </div>
  );
};

export default ToolbarBlockRenderer;
