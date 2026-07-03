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
import { LoadingOverlay } from '~/ui/LoadingOverlay';

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

  const { handleAction, loading } = useActionHandler({
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

  // In-flight guard: while an action runs, disable every toolbar button and show a
  // loading state on the one that was clicked. This makes rapid double-clicks
  // idempotent at the UI layer — a second click is ignored and the button is
  // disabled — which is the front-line defence against firing a command twice
  // (e.g. triggering Kingdee material sync twice and racing into a duplicate-pid
  // insert; the backend lock is the correctness backstop). Mirrors
  // WorkbenchActionBarBlockRenderer's in-flight behaviour.
  const [runningCode, setRunningCode] = React.useState<string | null>(null);
  const busy = loading || runningCode !== null;

  // 处理按钮点击 - 委托给 useActionHandler
  // Toolbar 通常无 record（列表页工具栏），传 undefined 是合法的
  const handleButtonClick = async (button: ButtonConfig) => {
    if (busy) {
      return; // ignore re-entrant clicks while an action is in flight
    }
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
    setRunningCode(button.code);
    try {
      await handleAction(normalized, undefined);
    } finally {
      setRunningCode(null);
    }
  };

  return (
    <>
      <LoadingOverlay visible={busy} label={t('common.loading')} />
      <div className="toolbar-block flex space-x-2">
      {buttons.map((button) => {
        // 条件渲染
        if (button.visibleWhen) {
          const visible = evaluator.evaluateCondition(button.visibleWhen, context);
          if (!visible) return null;
        }

        const label = getLocalizedText(button.label || button.content || button.code, locale, t);
        const isRunning = runningCode === button.code;
        const isDisabled = Boolean(button.disabled) || busy;
        const loadingLabel = t('common.loading') || '加载中...';

        return (
          <button
            key={button.code}
            data-testid={`toolbar-btn-${button.code}`}
            onClick={() => handleButtonClick(button)}
            disabled={isDisabled}
            aria-busy={isRunning}
            className={`rounded-control inline-flex items-center px-4 py-2 text-sm font-medium ${
              button.variant === 'primary'
                ? 'bg-accent hover:bg-accent-hover text-white'
                : button.variant === 'danger'
                  ? 'bg-red-600 text-white hover:bg-red-700'
                  : 'border-border-strong bg-panel text-text-2 hover:bg-hover border'
            } ${isDisabled ? 'cursor-not-allowed opacity-50' : ''}`}
          >
            {!isRunning && renderIcon(button.icon)}
            {isRunning ? loadingLabel : label}
          </button>
        );
      })}
      </div>
    </>
  );
};

export default ToolbarBlockRenderer;
