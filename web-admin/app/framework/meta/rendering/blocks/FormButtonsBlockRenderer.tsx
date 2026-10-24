import { hasButtonPermissions } from '~/framework/meta/utils/buttonPermissions';
/**
 * FormButtonsBlockRenderer - 表单按钮块渲染器
 * 用于渲染表单底部的操作按钮组
 */

import React, { useEffect, useReducer } from 'react';
import * as LucideIcons from 'lucide-react';
import type { LucideIcon } from 'lucide-react';
import { useNavigate } from 'react-router';
import type { BlockConfig, ButtonConfig } from '~/framework/meta/schemas/types';
import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import { getLocalizedText } from '~/routes/_shared/dynamic-route-utils';
import { useActionHandler } from '~/framework/meta/hooks/useActionHandler';
import { useAuth } from '~/contexts/AuthContext';
import { ExpressionParser } from '~/framework/meta/runtime/expression/parser';
import { useRuntimeStateSubscription } from './workbenchBlockUtils';

export interface FormButtonsBlockRendererProps {
  block: BlockConfig;
  runtime: SchemaRuntime;
}

const renderIcon = (icon?: ButtonConfig['icon']) => {
  if (!icon || typeof icon !== 'string') return null;
  const Icon = (LucideIcons as unknown as Record<string, LucideIcon>)[icon];
  if (!Icon) return null;
  return <Icon className="mr-2 h-4 w-4" aria-hidden="true" />;
};

/** Only static named source references in declared gate expressions are subscribed. */
export function formButtonSourceDependencies(buttons: ButtonConfig[]): string[] {
  const names = new Set<string>();
  const parser = new ExpressionParser({} as any);
  const visit = (node: any): void => {
    if (!node || typeof node !== 'object') return;
    if (
      node.type === 'MemberExpression' &&
      node.object?.type === 'Identifier' &&
      node.object.name === 'dataSources'
    ) {
      const name = node.computed ? node.property?.value : node.property?.name;
      if (
        (!node.computed || node.property?.type === 'Literal') &&
        typeof name === 'string' &&
        /^[A-Za-z_][A-Za-z0-9_]*$/.test(name) &&
        !['__proto__', 'constructor', 'prototype'].includes(name)
      )
        names.add(name);
    }
    for (const child of Object.values(node)) {
      if (Array.isArray(child)) child.forEach(visit);
      else if (child && typeof child === 'object') visit(child);
    }
  };
  for (const button of buttons) {
    for (const expression of [button.visibleWhen, button.enableWhen, button.disableWhen]) {
      if (typeof expression !== 'string') continue;
      try {
        visit(parser.parse(expression).ast);
      } catch {
        throw new Error('Invalid form button condition');
      }
    }
  }
  return [...names].sort();
}

export const FormButtonsBlockRenderer: React.FC<FormButtonsBlockRendererProps> = ({
  block,
  runtime,
}) => {
  useRuntimeStateSubscription(runtime);
  const [, refresh] = useReducer((value: number) => value + 1, 0);
  const sourceKey = JSON.stringify(formButtonSourceDependencies(block.buttons || []));
  useEffect(() => {
    const manager = runtime.getDataSourceManager?.();
    if (!manager?.subscribe) return;
    const unsubscribe = (JSON.parse(sourceKey) as string[]).map((name) =>
      manager.subscribe(name, refresh),
    );
    return () => unsubscribe.forEach((stop) => stop());
  }, [runtime, sourceKey]);
  const context = runtime.getContext();
  const evaluator = runtime.getEvaluator();
  const buttons = block.buttons || [];

  // 获取 locale 和 t 函数
  const locale = context.locale || 'zh-CN';
  const t = context.t || ((key: string) => key);

  // 路由 / 鉴权上下文 — useActionHandler hook 要求
  const navigate = useNavigate();
  const { token, hasPermission } = useAuth();
  const schema = runtime.getSchema();
  const tableName = (schema as any).modelCode || schema.id || '';
  const dataSourceManager = runtime.getDataSourceManager();

  const { handleAction } = useActionHandler({
    runtime,
    navigate,
    tableName,
    context: {
      data: (context.form as Record<string, any>) || (context.data as Record<string, any>),
      loadData: typeof context.loadData === 'function' ? context.loadData : undefined,
    },
    dataSourceManager,
    locale,
    t,
    token: token || undefined,
  });

  // 处理按钮点击事件 - 委托给 useActionHandler
  const handleButtonClick = (button: ButtonConfig) => {
    // Mutable configuration must pass the same syntax boundary at dispatch time.
    formButtonSourceDependencies([button]);
    const freshContext = runtime.getContext();
    if (!hasButtonPermissions(button, hasPermission)) return;
    if (button.visibleWhen && !evaluator.evaluateCondition(button.visibleWhen, freshContext))
      return;
    if (
      button.enableWhen
        ? !evaluator.evaluateCondition(button.enableWhen, freshContext)
        : button.disableWhen && evaluator.evaluateCondition(button.disableWhen, freshContext)
    )
      return;
    // Only dispatch buttons that have at least one recognizable action source
    // (preserves original behavior: buttons without events.onClick were no-ops)
    const isBuiltinRefresh = String(button.code || '').toLowerCase() === 'refresh';
    if (
      !isBuiltinRefresh &&
      !button.events?.onClick &&
      !button.action &&
      !button.commandCode &&
      !button.navigateTo &&
      !button.apiAction &&
      !button.handler
    ) {
      return;
    }
    handleAction(
      button,
      (freshContext.form as Record<string, any>) || (freshContext.data as Record<string, any>),
    );
  };

  // 渲染单个按钮
  const renderButton = (button: ButtonConfig) => {
    if (!hasButtonPermissions(button, hasPermission)) return null;

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
        'inline-flex items-center px-4 py-2 text-sm font-medium rounded-control focus:outline-none focus-visible:shadow-focus';

      if (button.danger) {
        return `${baseClass} text-white bg-status-red hover:opacity-90 ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
      }

      if (button.primary) {
        return `${baseClass} text-white bg-accent hover:bg-accent-hover ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
      }

      return `${baseClass} text-text-2 bg-panel border border-border-strong hover:bg-subtle ${disabled ? 'opacity-50 cursor-not-allowed' : ''}`;
    };

    return (
      <button
        key={button.code}
        data-authoring-node-id={(button as any).id || button.code}
        type="button"
        data-testid={`form-btn-${button.code}`}
        onClick={() => handleButtonClick(button)}
        disabled={disabled}
        className={getButtonClassName()}
      >
        {renderIcon(button.icon)}
        {content}
      </button>
    );
  };

  return (
    <div className="form-buttons border-border mt-6 border-t pt-4">
      <div className="flex justify-end space-x-3">
        {buttons.map((button) => renderButton(button))}
      </div>
    </div>
  );
};

export default FormButtonsBlockRenderer;
