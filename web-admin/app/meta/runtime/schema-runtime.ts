/**
 * Schema Runtime - 统一的 Schema 运行时
 * 整合状态管理、数据源、事件处理、表达式求值等核心功能
 */

import type { UnifiedSchema } from '~/meta/schemas/types';
import type { ExpressionContext, GlobalState } from '~/meta/runtime/expression/context';
import { ScopedStateManager } from '~/meta/runtime/state/scoped-state';
import { DataSourceManager } from '~/meta/runtime/data-pipeline/DataSourceManager';
import {
  getBuiltinHandler,
  getDefaultButtonHandler,
  interpolateHandler,
} from '~/meta/runtime/events/builtin-handlers';
import { expressionEvaluator } from '~/meta/runtime/expression/evaluator';
import { actionRegistry } from '~/meta/runtime/actions/ActionRegistry';
import { FlowRunner } from '~/meta/runtime/schema-runtime/FlowRunner';
import { LinkageEngine } from '~/meta/runtime/linkage/LinkageEngine';
import type { TriggerEvent } from '~/plugins/core-designer/components/studio/workbench/panels/linkage/types';
import { fetchResult } from '~/services/http-client';

/**
 * Schema Runtime 配置
 *
 * P0-3 更新: DataSourceManager 强制单例
 * - dataSourceManager 从可选改为必需
 * - 移除内部创建 DataSourceManager 的逻辑
 * - 要求外部通过 usePageDataSources 创建并传入
 */
export interface SchemaRuntimeConfig {
  schema: UnifiedSchema;
  globalState: GlobalState;
  scopeId?: string;
  navigate?: (path: string) => void;
  showToast?: (message: string, level?: 'success' | 'error' | 'info') => void;
  dataSourceManager: DataSourceManager; // P0-3: 必需 - 外部传入的 DataSourceManager (强制单例)
  disableAutoFetch?: boolean; // 可选:禁用所有数据源的 autoFetch
}

/**
 * Schema Runtime 类
 */
export class SchemaRuntime {
  private schema: UnifiedSchema;
  private scopeId: string;
  private context: ExpressionContext;
  private stateManager: ScopedStateManager;
  private dataSourceManager: DataSourceManager;
  private handlers: Map<string, any>;
  private navigate?: (path: string) => void;
  private showToast?: (message: string, level?: 'success' | 'error' | 'info') => void;
  private flowRunner: FlowRunner;
  private linkageEngine: LinkageEngine | null = null;
  private readonly registeredDataSources = new Set<string>();

  constructor(config: SchemaRuntimeConfig) {
    this.schema = config.schema;
    this.scopeId = config.scopeId || config.schema.id;
    this.navigate = config.navigate;
    this.showToast = config.showToast;

    // 初始化状态管理器
    this.stateManager = new ScopedStateManager(config.globalState);

    // 创建作用域
    this.stateManager.createScope(this.scopeId, {
      state: config.schema.state || {},
    });

    // P0-3: 强制使用外部传入的 DataSourceManager (单例模式)
    this.dataSourceManager = config.dataSourceManager;
    this.dataSourceManager.bindStateManager(this.stateManager, this.scopeId);

    // 获取上下文
    this.context = this.stateManager.getContext(this.scopeId);
    (this.context as any).__dataSourceManager = this.dataSourceManager;
    (this.context as any).fetchResult = fetchResult; // 用于 api.request action

    // 初始化 handlers
    this.handlers = new Map();

    this.flowRunner = new FlowRunner({
      evaluator: expressionEvaluator,
      actionRegistry,
      navigate: this.navigate,
      showToast: this.showToast,
      stateManager: this.stateManager,
      scopeId: this.scopeId,
      dataSourceManager: this.dataSourceManager,
      schema: this.schema,
      getAllFormFields: () => this.getAllFormFields(),
    });

    // 执行初始化 (如果禁用 autoFetch,跳过数据源注册)
    this.initialize(config.disableAutoFetch);
  }

  /**
   * 初始化
   */
  private initialize(disableAutoFetch?: boolean): void {
    this.initializeStateBinding();
    this.registerSchemaDataSources(disableAutoFetch);
    this.initializeLinkageEngine();

    if (this.schema.handlers) {
      this.registerHandlers(this.schema.handlers);
    }

    this.runOnEnterEvent();
  }

  /**
   * 初始化状态绑定
   */
  private initializeStateBinding(): void {
    if (this.schema.stateBinding) {
      this.stateManager.initFromBinding(this.scopeId, this.schema.stateBinding);
    }
  }

  /**
   * Initialize the LinkageEngine if schema has linkageRules.
   */
  private initializeLinkageEngine(): void {
    const rules = this.schema.linkageRules;
    if (!rules || rules.length === 0) return;

    this.linkageEngine = new LinkageEngine({
      stateManager: this.stateManager,
      scopeId: this.scopeId,
      onFieldValueChange: (fieldCode, value) => {
        this.stateManager.updateField(this.scopeId, fieldCode, value);
      },
      onError: (ruleId, error) => {
        console.error(`[SchemaRuntime] Linkage rule ${ruleId} failed:`, error);
      },
      getContext: () => {
        const ctx = this.stateManager.getContext(this.scopeId);
        (ctx as any).__dataSourceManager = this.dataSourceManager;
        return ctx;
      },
    });

    this.linkageEngine.register(rules);
  }

  /**
   * Trigger linkage evaluation for a field event.
   * Called by RuntimeFieldRenderer on change / blur / focus.
   */
  triggerFieldLinkage(fieldCode: string, event: TriggerEvent): void {
    if (!this.linkageEngine) return;

    const freshContext = this.stateManager.getContext(this.scopeId);
    (freshContext as any).__dataSourceManager = this.dataSourceManager;
    this.linkageEngine.onFieldEvent(fieldCode, event, freshContext);
  }

  /**
   * 注册 Schema 中声明的数据源
   */
  private registerSchemaDataSources(disableAutoFetch?: boolean): void {
    if (!this.schema.dataSources) {
      return;
    }

    Object.entries(this.schema.dataSources).forEach(([id, config]) => {
      if (this.dataSourceManager.has(id)) {
        return;
      }

      const finalConfig = disableAutoFetch ? { ...config, autoFetch: false } : config;
      this.dataSourceManager.register(id, finalConfig);
      this.registeredDataSources.add(id);
    });
  }

  /**
   * 执行 onEnter 事件
   */
  private runOnEnterEvent(): void {
    const onEnter = this.schema.events?.onEnter;
    if (!onEnter) return;

    const handler = onEnter.handler;
    this.executeHandler(handler, onEnter.args);
  }

  /**
   * 注册 handlers
   */
  private registerHandlers(handlers: Record<string, any>): void {
    Object.entries(handlers).forEach(([name, handler]) => {
      // 如果是 builtin handler，需要插值替换
      if (handler.type?.startsWith('builtin.')) {
        const builtinHandler = getBuiltinHandler(handler.type);
        if (builtinHandler) {
          // 插值替换变量
          const interpolated = interpolateHandler(builtinHandler, handler);
          this.handlers.set(name, interpolated);
          return;
        }
      }

      this.handlers.set(name, handler);
    });
  }

  /**
   * 执行 handler
   *
   * 关键修复：每次执行时获取最新 context，而不是使用构造函数时创建的旧快照
   * 这样可以确保 handler 执行时能访问到最新的表单数据和状态
   */
  async executeHandler(handlerName: string, args?: Record<string, any>): Promise<void> {
    // 检查是否有自定义 handler
    let handler = this.handlers.get(handlerName);

    // 如果没有自定义 handler，尝试使用默认按钮行为
    if (!handler) {
      const defaultHandler = getDefaultButtonHandler(handlerName);
      if (defaultHandler) {
        const builtinHandler = getBuiltinHandler(defaultHandler);
        if (builtinHandler) {
          handler = builtinHandler;
        }
      }
    }

    if (!handler) {
      const error = new Error(`Handler not found: ${handlerName}`);
      console.error(error.message, `(scopeId: ${this.scopeId})`);
      throw error;
    }

    // 关键修复：每次执行时获取最新 context
    // 避免使用构造函数中缓存的旧 this.context，确保能读取到最新的 form 和 state
    const freshContext = this.stateManager.getContext(this.scopeId);
    (freshContext as any).__dataSourceManager = this.dataSourceManager;
    (freshContext as any).fetchResult = fetchResult;

    const contextWithArgs = {
      ...freshContext,
      ...(args || {}), // Flatten: row, record, id directly accessible as ${row.pid}
      args: args || {}, // Keep nested for backward compat: ${args.row.pid}
    };

    // 执行 handler
    if (handler.type === 'flow') {
      await this.flowRunner.run(handler.steps, contextWithArgs);
    } else if (handler.type === 'script') {
      await this.executeScript(handler.code, contextWithArgs);
    }
  }

  /**
   * 执行脚本
   * @deprecated 脚本执行存在安全风险，已禁用此功能
   */
  private async executeScript(code: string, _context: ExpressionContext): Promise<void> {
    console.error('[SchemaRuntime] Script execution is disabled for security reasons:', code);
    throw new Error('Script execution is not supported');
  }

  /**
   * 获取所有表单字段
   */
  private getAllFormFields(): any[] {
    const fields: any[] = [];

    if (!this.schema.blocks) return fields;

    // 遍历所有块
    for (const block of this.schema.blocks) {
      if (block.fields) {
        fields.push(...block.fields);
      }
    }

    return fields;
  }

  /**
   * 获取上下文
   */
  getContext(): ExpressionContext {
    const ctx = this.stateManager.getContext(this.scopeId);
    (ctx as any).__dataSourceManager = this.dataSourceManager;
    return ctx;
  }

  /**
   * 获取 scopeId
   */
  getScopeId(): string {
    return this.scopeId;
  }

  /**
   * 获取状态管理器
   */
  getStateManager(): ScopedStateManager {
    return this.stateManager;
  }

  /**
   * 获取数据源管理器
   */
  getDataSourceManager(): DataSourceManager {
    return this.dataSourceManager;
  }

  /**
   * 获取 Schema
   */
  getSchema(): UnifiedSchema {
    return this.schema;
  }

  /**
   * 获取表达式求值器 (公开 API)
   * 用于列表页等需要表达式求值的场景
   */
  getEvaluator() {
    return expressionEvaluator;
  }

  /**
   * Get the FlowRunner instance for executing flow steps externally.
   */
  getFlowRunner(): FlowRunner {
    return this.flowRunner;
  }

  /**
   * 销毁运行时
   */
  destroy(): void {
    this.linkageEngine?.dispose();
    this.linkageEngine = null;
    this.registeredDataSources.forEach((id) => {
      if (this.dataSourceManager.has(id)) {
        this.dataSourceManager.unregister(id);
      }
    });
    this.registeredDataSources.clear();
    this.stateManager.deleteScope(this.scopeId);
    this.handlers.clear();
  }
}
