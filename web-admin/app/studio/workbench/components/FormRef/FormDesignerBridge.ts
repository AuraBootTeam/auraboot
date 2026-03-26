/**
 * 表单设计器桥接器
 *
 * 建立表单与页面设计器的解耦机制，提供统一的接口和通信协议
 */

import { EventEmitter } from 'events';
import type { FormSchema, FormRefProps } from '~/studio/workbench/components/FormRef/types';

/**
 * 设计器事件类型
 */
export type DesignerEventType =
  | 'form:created'
  | 'form:updated'
  | 'form:deleted'
  | 'form:published'
  | 'form:draft'
  | 'formref:added'
  | 'formref:updated'
  | 'formref:removed'
  | 'designer:sync'
  | 'designer:conflict';

/**
 * 设计器事件数据
 */
export interface DesignerEvent {
  type: DesignerEventType;
  timestamp: number;
  source: 'form-designer' | 'page-designer';
  data: any;
  metadata?: Record<string, any>;
}

/**
 * 表单设计器接口
 */
export interface FormDesignerInterface {
  /** 获取表单列表 */
  getForms(): Promise<
    Array<{ id: string; title: string; schema: FormSchema; lastModified: string }>
  >;

  /** 获取表单详情 */
  getForm(
    id: string,
  ): Promise<{ id: string; title: string; schema: FormSchema; lastModified: string } | null>;

  /** 创建表单 */
  createForm(title: string, schema: FormSchema): Promise<string>;

  /** 更新表单 */
  updateForm(id: string, schema: FormSchema): Promise<void>;

  /** 删除表单 */
  deleteForm(id: string): Promise<void>;

  /** 发布表单 */
  publishForm(id: string): Promise<void>;

  /** 保存草稿 */
  saveDraft(id: string, schema: FormSchema): Promise<void>;

  /** 监听事件 */
  on(event: DesignerEventType, listener: (data: any) => void): void;

  /** 移除事件监听 */
  off(event: DesignerEventType, listener: (data: any) => void): void;

  /** 触发事件 */
  emit(event: DesignerEventType, data: any): void;
}

/**
 * 页面设计器接口
 */
export interface PageDesignerInterface {
  /** 获取页面中的 FormRef 组件 */
  getFormRefs(): Array<{ id: string; props: FormRefProps }>;

  /** 添加 FormRef 组件 */
  addFormRef(props: FormRefProps): string;

  /** 更新 FormRef 组件 */
  updateFormRef(id: string, props: Partial<FormRefProps>): void;

  /** 删除 FormRef 组件 */
  removeFormRef(id: string): void;

  /** 同步表单变更 */
  syncFormChanges(formId: string, schema: FormSchema): void;

  /** 监听事件 */
  on(event: DesignerEventType, listener: (data: any) => void): void;

  /** 移除事件监听 */
  off(event: DesignerEventType, listener: (data: any) => void): void;

  /** 触发事件 */
  emit(event: DesignerEventType, data: any): void;
}

/**
 * 表单引用同步策略
 */
export type FormRefSyncStrategy = 'auto' | 'manual' | 'prompt';

/**
 * 桥接器配置
 */
export interface FormDesignerBridgeConfig {
  /** 同步策略 */
  syncStrategy: FormRefSyncStrategy;

  /** 自动同步延迟 (毫秒) */
  syncDelay: number;

  /** 冲突解决策略 */
  conflictResolution: 'form-wins' | 'page-wins' | 'manual';

  /** 启用调试模式 */
  debug: boolean;

  /** 事件缓冲区大小 */
  eventBufferSize: number;
}

/**
 * 默认桥接器配置
 */
export const DEFAULT_BRIDGE_CONFIG: FormDesignerBridgeConfig = {
  syncStrategy: 'prompt',
  syncDelay: 1000,
  conflictResolution: 'manual',
  debug: false,
  eventBufferSize: 100,
};

/**
 * 表单设计器桥接器实现
 */
export class FormDesignerBridge extends EventEmitter {
  private formDesigner: FormDesignerInterface | null = null;
  private pageDesigner: PageDesignerInterface | null = null;
  private config: FormDesignerBridgeConfig;
  private eventBuffer: DesignerEvent[] = [];
  private syncTimers: Map<string, NodeJS.Timeout> = new Map();
  private conflictQueue: Array<{
    formId: string;
    formSchema: FormSchema;
    pageRefs: Array<{ id: string; props: FormRefProps }>;
    timestamp: number;
  }> = [];

  constructor(config: Partial<FormDesignerBridgeConfig> = {}) {
    super();
    this.config = { ...DEFAULT_BRIDGE_CONFIG, ...config };
    this.setupEventHandlers();
  }

  /**
   * 连接表单设计器
   */
  connectFormDesigner(designer: FormDesignerInterface): void {
    if (this.formDesigner) {
      this.disconnectFormDesigner();
    }

    this.formDesigner = designer;

    // 监听表单设计器事件
    designer.on('form:created', this.handleFormCreated.bind(this));
    designer.on('form:updated', this.handleFormUpdated.bind(this));
    designer.on('form:deleted', this.handleFormDeleted.bind(this));
    designer.on('form:published', this.handleFormPublished.bind(this));

    this.log('Form designer connected');
    this.emit('designer:connected', { type: 'form-designer' });
  }

  /**
   * 连接页面设计器
   */
  connectPageDesigner(designer: PageDesignerInterface): void {
    if (this.pageDesigner) {
      this.disconnectPageDesigner();
    }

    this.pageDesigner = designer;

    // 监听页面设计器事件
    designer.on('formref:added', this.handleFormRefAdded.bind(this));
    designer.on('formref:updated', this.handleFormRefUpdated.bind(this));
    designer.on('formref:removed', this.handleFormRefRemoved.bind(this));

    this.log('Page designer connected');
    this.emit('designer:connected', { type: 'page-designer' });
  }

  /**
   * 断开表单设计器
   */
  disconnectFormDesigner(): void {
    if (this.formDesigner) {
      this.formDesigner.off('form:created', this.handleFormCreated.bind(this));
      this.formDesigner.off('form:updated', this.handleFormUpdated.bind(this));
      this.formDesigner.off('form:deleted', this.handleFormDeleted.bind(this));
      this.formDesigner.off('form:published', this.handleFormPublished.bind(this));
      this.formDesigner = null;

      this.log('Form designer disconnected');
      this.emit('designer:disconnected', { type: 'form-designer' });
    }
  }

  /**
   * 断开页面设计器
   */
  disconnectPageDesigner(): void {
    if (this.pageDesigner) {
      this.pageDesigner.off('formref:added', this.handleFormRefAdded.bind(this));
      this.pageDesigner.off('formref:updated', this.handleFormRefUpdated.bind(this));
      this.pageDesigner.off('formref:removed', this.handleFormRefRemoved.bind(this));
      this.pageDesigner = null;

      this.log('Page designer disconnected');
      this.emit('designer:disconnected', { type: 'page-designer' });
    }
  }

  /**
   * 获取可用的表单列表
   */
  async getAvailableForms(): Promise<
    Array<{ id: string; title: string; description?: string; lastModified: string }>
  > {
    if (!this.formDesigner) {
      throw new Error('Form designer not connected');
    }

    const forms = await this.formDesigner.getForms();
    return forms.map((form) => ({
      id: form.id,
      title: form.title,
      description: form.schema.description,
      lastModified: form.lastModified,
    }));
  }

  /**
   * 获取表单详情
   */
  async getFormDetails(
    formId: string,
  ): Promise<{ id: string; title: string; schema: FormSchema; lastModified: string } | null> {
    if (!this.formDesigner) {
      throw new Error('Form designer not connected');
    }

    return await this.formDesigner.getForm(formId);
  }

  /**
   * 同步表单到页面设计器
   */
  async syncFormToPage(formId: string): Promise<void> {
    if (!this.formDesigner || !this.pageDesigner) {
      throw new Error('Both designers must be connected');
    }

    const form = await this.formDesigner.getForm(formId);
    if (!form) {
      throw new Error(`Form ${formId} not found`);
    }

    // 获取页面中引用此表单的组件
    const formRefs = this.pageDesigner.getFormRefs().filter((ref) => ref.props.formId === formId);

    if (formRefs.length === 0) {
      this.log(`No FormRef components found for form ${formId}`);
      return;
    }

    // 根据同步策略处理
    switch (this.config.syncStrategy) {
      case 'auto':
        this.performSync(formId, form.schema, formRefs);
        break;
      case 'manual':
        // 不自动同步，等待手动触发
        break;
      case 'prompt':
        this.promptForSync(formId, form.schema, formRefs);
        break;
    }
  }

  /**
   * 手动同步表单
   */
  async manualSync(formId: string): Promise<void> {
    if (!this.formDesigner || !this.pageDesigner) {
      throw new Error('Both designers must be connected');
    }

    const form = await this.formDesigner.getForm(formId);
    if (!form) {
      throw new Error(`Form ${formId} not found`);
    }

    const formRefs = this.pageDesigner.getFormRefs().filter((ref) => ref.props.formId === formId);
    this.performSync(formId, form.schema, formRefs);
  }

  /**
   * 解决同步冲突
   */
  resolveConflict(formId: string, resolution: 'form-wins' | 'page-wins' | 'merge'): void {
    const conflict = this.conflictQueue.find((c) => c.formId === formId);
    if (!conflict) {
      throw new Error(`No conflict found for form ${formId}`);
    }

    switch (resolution) {
      case 'form-wins':
        this.performSync(formId, conflict.formSchema, conflict.pageRefs);
        break;
      case 'page-wins':
        // 保持页面中的配置不变
        break;
      case 'merge':
        // 实现合并逻辑
        this.performMergeSync(formId, conflict.formSchema, conflict.pageRefs);
        break;
    }

    // 从冲突队列中移除
    this.conflictQueue = this.conflictQueue.filter((c) => c.formId !== formId);
    this.emit('conflict:resolved', { formId, resolution });
  }

  /**
   * 获取待解决的冲突
   */
  getPendingConflicts(): Array<{ formId: string; timestamp: number }> {
    return this.conflictQueue.map((c) => ({
      formId: c.formId,
      timestamp: c.timestamp,
    }));
  }

  /**
   * 设置事件处理器
   */
  private setupEventHandlers(): void {
    // 清理过期的事件缓冲区
    setInterval(() => {
      const now = Date.now();
      this.eventBuffer = this.eventBuffer.filter(
        (event) => now - event.timestamp < 60000, // 保留1分钟内的事件
      );
    }, 30000); // 每30秒清理一次
  }

  /**
   * 处理表单创建事件
   */
  private handleFormCreated(data: any): void {
    this.addToEventBuffer({
      type: 'form:created',
      timestamp: Date.now(),
      source: 'form-designer',
      data,
    });

    this.emit('form:created', data);
  }

  /**
   * 处理表单更新事件
   */
  private handleFormUpdated(data: any): void {
    this.addToEventBuffer({
      type: 'form:updated',
      timestamp: Date.now(),
      source: 'form-designer',
      data,
    });

    // 延迟同步，避免频繁更新
    const formId = data.id;
    if (this.syncTimers.has(formId)) {
      clearTimeout(this.syncTimers.get(formId)!);
    }

    const timer = setTimeout(() => {
      this.syncFormToPage(formId).catch((error) => {
        this.log(`Failed to sync form ${formId}:`, error);
      });
      this.syncTimers.delete(formId);
    }, this.config.syncDelay);

    this.syncTimers.set(formId, timer);
    this.emit('form:updated', data);
  }

  /**
   * 处理表单删除事件
   */
  private handleFormDeleted(data: any): void {
    this.addToEventBuffer({
      type: 'form:deleted',
      timestamp: Date.now(),
      source: 'form-designer',
      data,
    });

    // 清理页面中的引用
    if (this.pageDesigner) {
      const formRefs = this.pageDesigner
        .getFormRefs()
        .filter((ref) => ref.props.formId === data.id);
      formRefs.forEach((ref) => {
        this.pageDesigner!.removeFormRef(ref.id);
      });
    }

    this.emit('form:deleted', data);
  }

  /**
   * 处理表单发布事件
   */
  private handleFormPublished(data: any): void {
    this.addToEventBuffer({
      type: 'form:published',
      timestamp: Date.now(),
      source: 'form-designer',
      data,
    });

    this.emit('form:published', data);
  }

  /**
   * 处理 FormRef 添加事件
   */
  private handleFormRefAdded(data: any): void {
    this.addToEventBuffer({
      type: 'formref:added',
      timestamp: Date.now(),
      source: 'page-designer',
      data,
    });

    this.emit('formref:added', data);
  }

  /**
   * 处理 FormRef 更新事件
   */
  private handleFormRefUpdated(data: any): void {
    this.addToEventBuffer({
      type: 'formref:updated',
      timestamp: Date.now(),
      source: 'page-designer',
      data,
    });

    this.emit('formref:updated', data);
  }

  /**
   * 处理 FormRef 删除事件
   */
  private handleFormRefRemoved(data: any): void {
    this.addToEventBuffer({
      type: 'formref:removed',
      timestamp: Date.now(),
      source: 'page-designer',
      data,
    });

    this.emit('formref:removed', data);
  }

  /**
   * 执行同步
   */
  private performSync(
    formId: string,
    schema: FormSchema,
    formRefs: Array<{ id: string; props: FormRefProps }>,
  ): void {
    if (!this.pageDesigner) return;

    formRefs.forEach((ref) => {
      // 只同步指针引用模式的组件
      if (ref.props.mode === 'pointer') {
        this.pageDesigner!.syncFormChanges(formId, schema);
      }
    });

    this.log(`Synced form ${formId} to ${formRefs.length} FormRef components`);
    this.emit('designer:sync', { formId, refCount: formRefs.length });
  }

  /**
   * 提示同步
   */
  private promptForSync(
    formId: string,
    schema: FormSchema,
    formRefs: Array<{ id: string; props: FormRefProps }>,
  ): void {
    this.emit('sync:prompt', {
      formId,
      schema,
      refCount: formRefs.length,
      refs: formRefs.map((ref) => ({ id: ref.id, mode: ref.props.mode })),
    });
  }

  /**
   * 执行合并同步
   */
  private performMergeSync(
    formId: string,
    schema: FormSchema,
    formRefs: Array<{ id: string; props: FormRefProps }>,
  ): void {
    // 实现表单和页面配置的合并逻辑
    // 这里可以根据具体需求实现复杂的合并策略
    this.performSync(formId, schema, formRefs);
  }

  /**
   * 添加事件到缓冲区
   */
  private addToEventBuffer(event: DesignerEvent): void {
    this.eventBuffer.push(event);

    // 保持缓冲区大小
    if (this.eventBuffer.length > this.config.eventBufferSize) {
      this.eventBuffer.shift();
    }
  }

  /**
   * 日志输出
   */
  private log(...args: any[]): void {
    if (this.config.debug) {
      console.log('[FormDesignerBridge]', ...args);
    }
  }

  /**
   * 获取事件历史
   */
  getEventHistory(): DesignerEvent[] {
    return [...this.eventBuffer];
  }

  /**
   * 清理资源
   */
  destroy(): void {
    this.disconnectFormDesigner();
    this.disconnectPageDesigner();

    // 清理定时器
    this.syncTimers.forEach((timer) => clearTimeout(timer));
    this.syncTimers.clear();

    // 清理事件缓冲区
    this.eventBuffer = [];
    this.conflictQueue = [];

    this.removeAllListeners();
    this.log('Bridge destroyed');
  }
}

/**
 * 创建表单设计器桥接器
 */
export const createFormDesignerBridge = (
  config?: Partial<FormDesignerBridgeConfig>,
): FormDesignerBridge => {
  return new FormDesignerBridge(config);
};
