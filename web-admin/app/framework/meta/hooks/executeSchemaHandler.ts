/**
 * executeSchemaHandler - SchemaRuntime Handler Execution
 *
 * 专门处理 SchemaRuntime.executeHandler() 调用
 *
 * 职责:
 * - 合并 handler args 和运行时上下文
 * - 执行 SchemaRuntime handler (ActionFlow)
 * - 错误传播给调用者
 *
 * 变更记录:
 * - 2025-12-03: 创建 (修复 P1-1 - 拆分 useActionHandler)
 */

import type { SchemaRuntime } from '~/framework/meta/runtime/schema-runtime';
import type { ButtonConfig } from '~/framework/meta/schemas/types';

export interface ExecuteSchemaHandlerOptions {
  runtime: SchemaRuntime;
  button: ButtonConfig;
  record?: Record<string, any>;
  context: {
    filters?: Record<string, any>;
    loadData?: (...args: any[]) => void | Promise<void>;
    record?: Record<string, any>;
    [key: string]: any;
  };
}

/**
 * 执行 SchemaRuntime handler
 *
 * @param options - 执行选项
 * @returns Promise that resolves when handler completes
 * @throws Error if handler execution fails
 */
export async function executeSchemaHandler(options: ExecuteSchemaHandlerOptions): Promise<void> {
  const { runtime, button, record, context } = options;

  const handlerName = button.events?.onClick?.handler;
  const handlerArgs = button.events?.onClick?.args || {};

  if (!handlerName) {
    throw new Error(
      '[executeSchemaHandler] No handler name found in button.events.onClick.handler',
    );
  }

  // 合并 args 和 context 数据
  const contextArgs = {
    ...handlerArgs,
    record: record || context.record,
    row: record || context.record,
    id: record?.id || record?.pid || context.record?.id || context.record?.pid,
    filters: context.filters,
    reload: context.loadData
      ? () => context.loadData!(context.filters ? { filters: context.filters } : {})
      : undefined,
    ...context, // 传递所有上下文
  };

  // 执行 SchemaRuntime handler
  await runtime.executeHandler(handlerName, contextArgs);
}
