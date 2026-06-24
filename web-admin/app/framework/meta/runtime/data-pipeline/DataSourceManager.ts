/**
 * DataSource Manager - 数据源管理器
 * 支持 API、字典、静态数据源，以及依赖追踪和自动刷新
 */

import { fetchResult } from '~/shared/services/http-client';
import { ResultHelper } from '~/utils/type';
import type { DataSourceConfig } from '~/framework/meta/schemas/types';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { bind, evaluate, expressionEvaluator } from '~/framework/meta/runtime/expression/evaluator';
import type { ScopedStateManager } from '~/framework/meta/runtime/state/scoped-state';

/**
 * 数据源状态
 */
export interface DataSourceState {
  data: any;
  loading: boolean;
  error: Error | null;
  lastFetch: number | null;
}

const DYNAMIC_LIST_CONTROL_PARAMS = new Set([
  'page',
  'pageNum',
  'current',
  'size',
  'pageSize',
  'sort',
  'sortField',
  'sortOrder',
  'orderBy',
  'keyword',
  'search',
  'filters',
]);

function normalizeApiParams(endpoint: string, params: Record<string, any>): Record<string, any> {
  if (!isDynamicListEndpoint(endpoint)) {
    return params;
  }

  const passthrough: Record<string, any> = {};
  const filters = existingFilters(params.filters);

  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return;
    }
    if (key === 'filters') {
      return;
    }
    if (DYNAMIC_LIST_CONTROL_PARAMS.has(key)) {
      passthrough[key] = value;
      return;
    }
    if (isLikelyDynamicFieldParam(key)) {
      filters.push({ fieldName: key, operator: 'EQ', value });
      return;
    }
    passthrough[key] = value;
  });

  if (filters.length > 0) {
    passthrough.filters = JSON.stringify(filters);
  }
  return passthrough;
}

function isDynamicListEndpoint(endpoint: string): boolean {
  return /\/api\/dynamic\/[^/]+\/list(?:$|\?)/.test(endpoint);
}

function isLikelyDynamicFieldParam(key: string): boolean {
  return key === 'pid' || key === 'id' || key.includes('_');
}

function existingFilters(
  value: unknown,
): Array<{ fieldName: string; operator: string; value: unknown }> {
  if (!value) {
    return [];
  }
  const removeBlankFilters = (
    filters: Array<{ fieldName: string; operator: string; value: unknown }>,
  ) =>
    filters.filter((filter) => {
      const filterValue = filter?.value;
      return !(
        filterValue === undefined ||
        filterValue === null ||
        filterValue === '' ||
        (Array.isArray(filterValue) && filterValue.length === 0)
      );
    });
  if (Array.isArray(value)) {
    return removeBlankFilters([...value] as Array<{
      fieldName: string;
      operator: string;
      value: unknown;
    }>);
  }
  if (typeof value !== 'string') {
    return [];
  }
  try {
    const parsed = JSON.parse(value);
    return Array.isArray(parsed) ? removeBlankFilters(parsed) : [];
  } catch {
    return [];
  }
}

const RESULT_ENVELOPE_KEYS = new Set(['code', 'desc', 'message', 'success', 'data', 'context']);

function unwrapApiResult(result: any): any {
  if (ResultHelper.isSuccess(result)) {
    return result.data;
  }

  if (result && typeof result === 'object') {
    const rawKeys = Object.keys(result).filter((key) => !RESULT_ENVELOPE_KEYS.has(key));
    const hasNoEnvelopeCode = result.code === undefined || result.code === null || result.code === '';
    if (hasNoEnvelopeCode && result.data == null && rawKeys.length > 0) {
      return rawKeys.reduce<Record<string, any>>((raw, key) => {
        raw[key] = result[key];
        return raw;
      }, {});
    }
  }

  throw new Error(result?.desc || result?.message || 'API request failed');
}

/**
 * 数据源管理器
 */
export class DataSourceManager {
  private dataSources = new Map<string, DataSourceConfig>();
  private dataSourceStates = new Map<string, DataSourceState>();
  private subscriptions = new Map<string, Set<(state: DataSourceState) => void>>();
  private baseContext: ExpressionContext;
  private contextGetter?: () => ExpressionContext;
  private stateManager?: ScopedStateManager;
  private scopeId?: string;
  private dependencySubscriptions = new Map<string, () => void>();
  /** Request version counter per data source for dedup — stale responses are discarded */
  private fetchVersions = new Map<string, number>();
  /** DataSource ID → modelCode mapping for real-time data sync */
  private dataSourceModelCodes = new Map<string, string>();

  constructor(context: ExpressionContext) {
    this.baseContext = context;
    (this.baseContext as any).__dataSourceManager = this;
  }

  updateContext(context: ExpressionContext): void {
    this.baseContext = context;
    (this.baseContext as any).__dataSourceManager = this;
  }

  private readRows(data: any): any[] {
    if (Array.isArray(data)) return data;
    if (Array.isArray(data?.records)) return data.records;
    if (Array.isArray(data?.list)) return data.list;
    if (Array.isArray(data?.items)) return data.items;
    return data ? [data] : [];
  }

  private buildDataSourceContext(): {
    data: Record<string, any>;
    dataSource: Record<string, any>;
    dataSources: Record<string, any>;
  } {
    const data: Record<string, any> = {};
    const raw: Record<string, any> = {};

    this.dataSourceStates.forEach((state, id) => {
      raw[id] = state.data;
      data[id] = this.readRows(state.data)[0];
    });

    return {
      data,
      dataSource: raw,
      dataSources: raw,
    };
  }

  private withDataSourceContext(context: ExpressionContext): ExpressionContext {
    const dataSourceContext = this.buildDataSourceContext();
    const ctx = {
      ...context,
      data: {
        ...((context as any).data || {}),
        ...dataSourceContext.data,
      },
      dataSource: {
        ...((context as any).dataSource || {}),
        ...dataSourceContext.dataSource,
      },
      dataSources: {
        ...((context as any).dataSources || {}),
        ...dataSourceContext.dataSources,
      },
    } as ExpressionContext;
    (ctx as any).__dataSourceManager = this;
    return ctx;
  }

  /**
   * 绑定 ScopedStateManager,用于依赖追踪和上下文刷新
   */
  bindStateManager(stateManager: ScopedStateManager, scopeId: string) {
    this.stateManager = stateManager;
    this.scopeId = scopeId;
    this.contextGetter = () => {
      const scopedContext = stateManager.getContext(scopeId);
      const ctx = {
        ...this.baseContext,
        ...scopedContext,
        state: {
          ...((this.baseContext as any).state || {}),
          ...(scopedContext.state || {}),
        },
        form: {
          ...((this.baseContext as any).form || {}),
          ...(scopedContext.form || {}),
        },
        record: (scopedContext as any).record ?? (this.baseContext as any).record,
        row: scopedContext.row ?? (this.baseContext as any).row,
        $page: {
          ...((this.baseContext as any).$page || {}),
          ...((scopedContext as any).$page || {}),
        },
      } as ExpressionContext;
      return this.withDataSourceContext(ctx);
    };

    this.dataSources.forEach((config, id) => {
      if (!config.dependOn || config.dependOn.length === 0) return;
      this.cleanupDependency(id);
      this.registerDependencies(id, config);
    });
  }

  private getContext(): ExpressionContext {
    return this.contextGetter ? this.contextGetter() : this.withDataSourceContext(this.baseContext);
  }

  /**
   * 注册数据源
   */
  register(id: string, config: DataSourceConfig): void {
    this.cleanupDependency(id);

    // 智能提取参数: 如果 config 中没有 params,从 ID 中提取
    // 例如: ds_storeTypes → { datasourceId: 'ds_storeTypes' }
    let defaultParams: Record<string, any> = {};
    if (config.type !== 'namedQuery' && !config.params && !config.body) {
      defaultParams = { datasourceId: id };
    }

    // 智能判断是否应该 autoFetch
    // 如果是分页数据源（pagination: true），强制禁用 autoFetch
    // 因为分页数据必须由组件控制加载（需要传入 page, size 参数）
    let shouldAutoFetch = true; // 默认自动加载
    if (config.pagination) {
      // 分页数据源强制不自动加载，无论 DSL 中如何配置
      shouldAutoFetch = false;
    } else if (config.autoFetch !== undefined) {
      // 非分页数据源，尊重配置
      shouldAutoFetch = config.autoFetch;
    }

    // Apply defaults and handle dict-to-API conversion
    const configWithDefaults: DataSourceConfig = {
      type: 'api',
      method: 'get',
      adaptor: 'optionList',
      valueField: 'value',
      labelField: 'name',
      endpoint: '/api/datasource/list',
      ...config, // 先展开用户配置
      params: config.params || defaultParams,
      autoFetch: shouldAutoFetch, // 最后设置 autoFetch，覆盖用户配置（针对分页数据源）
      dependOn: config.dependOn,
      id,
    };

    this.dataSources.set(id, configWithDefaults);

    // 初始化状态
    const initialState = {
      data: null,
      loading: false,
      error: null,
      lastFetch: null,
    };
    this.dataSourceStates.set(id, initialState);

    // 如果配置了 autoFetch，立即获取数据
    if (configWithDefaults.autoFetch) {
      this.fetch(id);
    }

    this.registerDependencies(id, configWithDefaults);
  }

  /**
   * 检查数据源是否已注册
   */
  has(id: string): boolean {
    return this.dataSources.has(id);
  }

  /**
   * 获取数据源配置
   */
  getConfig(id: string): DataSourceConfig | undefined {
    return this.dataSources.get(id);
  }

  /**
   * 获取数据源状态
   */
  getState(id: string): DataSourceState | undefined {
    return this.dataSourceStates.get(id);
  }

  /**
   * 获取数据源数据
   */
  getData(id: string): any {
    return this.dataSourceStates.get(id)?.data;
  }

  /**
   * 设置数据源数据
   */
  setData(id: string, data: any): void {
    let state = this.dataSourceStates.get(id);

    if (!state) {
      // Auto-initialize state if not found (defensive programming)
      state = {
        data: null,
        loading: false,
        error: null,
        lastFetch: null,
      };
      this.dataSourceStates.set(id, state);
    }

    const newState = {
      ...state,
      data,
      loading: false,
      error: null,
      lastFetch: Date.now(),
    };

    this.dataSourceStates.set(id, newState);
    this.notifySubscribers(id, newState);
    void this.notifyDataSourceChanged(id);
  }

  /**
   * 设置数据源错误
   */
  setError(id: string, error: Error): void {
    const state = this.dataSourceStates.get(id);
    if (!state) return;

    const newState = {
      ...state,
      loading: false,
      error,
    };

    this.dataSourceStates.set(id, newState);
    this.notifySubscribers(id, newState);
  }

  /**
   * 设置加载状态
   */
  setLoading(id: string, loading: boolean): void {
    const state = this.dataSourceStates.get(id);
    if (!state) return;

    const newState = {
      ...state,
      loading,
    };

    this.dataSourceStates.set(id, newState);
    this.notifySubscribers(id, newState);
  }

  private clearSkippedDataSource(id: string): void {
    const state = this.dataSourceStates.get(id);
    if (!state) return;

    const newState = {
      ...state,
      data: null,
      loading: false,
      error: null,
    };

    this.dataSourceStates.set(id, newState);
    this.notifySubscribers(id, newState);
  }

  /**
   * 获取数据 (with request dedup — stale responses are discarded)
   */
  async fetch(id: string, extraParams?: Record<string, any>): Promise<any> {
    const config = this.dataSources.get(id);
    if (!config) {
      return null;
    }

    if (this.hasMissingDependencyParent(config)) {
      this.clearSkippedDataSource(id);
      return null;
    }

    // Increment request version for dedup — stale responses will be discarded
    const version = (this.fetchVersions.get(id) ?? 0) + 1;
    this.fetchVersions.set(id, version);

    this.setLoading(id, true);

    try {
      let data: any;

      switch (config.type) {
        case 'api':
          data = await this.fetchApiDataSource(config, extraParams);
          break;
        case 'namedQuery':
          data = await this.fetchNamedQueryDataSource(config, extraParams);
          break;
        case 'static':
          data = config.data || [];
          break;
        default:
          throw new Error(`Unsupported data source type: ${config.type}`);
      }

      // Discard stale response if a newer request was issued
      if (this.fetchVersions.get(id) !== version) {
        return null;
      }

      this.setData(id, data);
      return data;
    } catch (error) {
      // Discard stale error if a newer request was issued
      if (this.fetchVersions.get(id) !== version) {
        return null;
      }
      console.error(`[DataSourceManager] Failed to fetch data source ${id}:`, error);
      this.setError(id, error as Error);
      return null;
    }
  }

  /**
   * 获取 API 数据源
   */
  private async fetchApiDataSource(
    config: DataSourceConfig,
    extraParams?: Record<string, any>,
  ): Promise<any> {
    if (!config.endpoint) {
      throw new Error('API endpoint is required');
    }

    const params = this.evaluateConfiguredParams(config.params);

    // 合并额外参数
    const mergedParams = {
      ...(params && typeof params === 'object' ? params : {}),
      ...extraParams,
    };

    const endpoint = expressionEvaluator.evaluateTemplate(config.endpoint, this.getContext());
    const requestParams = normalizeApiParams(endpoint, mergedParams);

    // 发起请求
    const result = await fetchResult(endpoint, {
      method: config.method,
      params: requestParams,
    });

    // 适配数据
    return this.adaptData(unwrapApiResult(result), config);
  }

  /**
   * Fetch options from a NamedQuery data source.
   * Uses the nq:{queryCode} format via /api/datasource/list endpoint.
   */
  private async fetchNamedQueryDataSource(
    config: DataSourceConfig,
    extraParams?: Record<string, any>,
  ): Promise<any> {
    if (!config.queryCode) {
      throw new Error('queryCode is required for namedQuery data source');
    }

    const configuredParams = this.evaluateConfiguredParams(config.params);
    const params: Record<string, any> = {
      ...(configuredParams && typeof configuredParams === 'object' ? configuredParams : {}),
      datasourceId: `nq:${config.queryCode}`,
      valueField: config.valueField || 'id',
      labelField: config.labelField || 'name',
      ...(config.searchField ? { searchField: config.searchField } : {}),
      ...(config.maxItems ? { maxItems: String(config.maxItems) } : {}),
      // format: 'records' asks the backend for raw query rows (multi-column aggregate rows)
      // instead of the default {key,value,label} option format — required for metric-strip / KPI
      // data sources that read raw columns (e.g. total_devices) via valueField.
      ...(config.format ? { format: config.format } : {}),
      ...extraParams,
    };

    const result = await fetchResult('/api/datasource/list', {
      method: 'get',
      params,
    });

    if (!ResultHelper.isSuccess(result)) {
      throw new Error(result.desc || 'NamedQuery data source request failed');
    }

    return this.adaptData(result.data, config);
  }

  private evaluateConfiguredParams(paramsConfig: DataSourceConfig['params']): any {
    if (typeof paramsConfig === 'string') {
      const bindResult = bind(paramsConfig, this.getContext());
      // 如果是绑定结果（包含 path 属性），使用 value；否则使用整个结果
      return bindResult && typeof bindResult === 'object' && 'path' in bindResult
        ? bindResult.value
        : bindResult;
    }
    if (paramsConfig && typeof paramsConfig === 'object' && !Array.isArray(paramsConfig)) {
      return expressionEvaluator.evaluateObject(paramsConfig, this.getContext());
    }
    return paramsConfig;
  }

  /**
   * 适配数据格式
   */
  private adaptData(data: any, config: DataSourceConfig): any {
    if (!config.adaptor) {
      return data;
    }

    switch (config.adaptor) {
      case 'optionList':
        // 转换为 { value, label } 格式
        if (Array.isArray(data)) {
          return data.map((item) => ({
            value: config.valueField ? item[config.valueField] : item.value,
            label: config.labelField ? item[config.labelField] : item.label,
          }));
        }
        if (data && typeof data === 'object') {
          const source = Array.isArray((data as any).data)
            ? (data as any).data
            : Array.isArray((data as any).records)
              ? (data as any).records
              : Array.isArray((data as any).list)
                ? (data as any).list
                : Array.isArray((data as any).items)
                  ? (data as any).items
                  : [];
          if (source.length > 0) {
            return source.map((item: any) => ({
              value: config.valueField ? item[config.valueField] : item.value,
              label: config.labelField ? item[config.labelField] : item.label,
            }));
          }
        }
        break;

      case 'dictData':
        // 字典数据适配器
        // 输入: { code, items: [{value, label, ...}] } 或直接是数组
        // 输出: [{value, label}]
        if (data && data.items && Array.isArray(data.items)) {
          return data.items.map((item: any) => ({
            value: item.value,
            label: item.label,
          }));
        }
        if (Array.isArray(data)) {
          return data.map((item) => ({
            value: item.value,
            label: item.label,
          }));
        }
        break;

      case 'table':
        // TODO(DESIGNER-001): swap for BlockRegistry.get('table')?.normalizeData
        // once the adaptor key is reconciled with blockType (this switch keys
        // on config.adaptor, not block.blockType, so the migration needs an
        // adaptor → blockType bridge first).
        // Design: docs/plans/2026-04/2026-04-25-blockrenderer-runtime-registry-design.md
        // A custom REST endpoint may return ResultData whose `data` is a plain array (not a
        // paginated { records } object); treat that array as the rows so table columns bind.
        if (Array.isArray(data)) {
          return {
            records: data,
            total: data.length,
            current: 1,
            pageSize: data.length,
          };
        }
        if (data && typeof data === 'object') {
          return {
            records: data.records || data.list || [],
            total: data.total || 0,
            current: data.current || 1,
            pageSize: data.pageSize || 10,
          };
        }
        break;
    }

    return data;
  }

  /**
   * 重新加载数据源
   */
  async reload(id: string | string[]): Promise<void> {
    const ids = Array.isArray(id) ? id : [id];

    await Promise.all(ids.map((dsId) => this.fetch(dsId)));
  }

  async notifyStateChanged(key: string): Promise<void> {
    const statePath = key.startsWith('state.') ? key : `state.${key}`;
    const ids: string[] = [];

    this.dataSources.forEach((config, id) => {
      if (!config.dependOn || config.dependOn.length === 0) {
        return;
      }

      const dependsOnStateKey = config.dependOn.some(
        (dependency) => dependency === statePath || dependency.startsWith(`${statePath}.`),
      );
      if (dependsOnStateKey) {
        ids.push(id);
      }
    });

    if (ids.length > 0) {
      await this.reload(ids);
    }
  }

  private async notifyDataSourceChanged(key: string): Promise<void> {
    const dataPaths = [`data.${key}`, `dataSource.${key}`, `dataSources.${key}`];
    const ids: string[] = [];

    this.dataSources.forEach((config, id) => {
      if (id === key || !config.dependOn || config.dependOn.length === 0) {
        return;
      }

      const dependsOnDataSource = config.dependOn.some((dependency) => dataPaths.some(
        (path) => dependency === path || dependency.startsWith(`${path}.`),
      ));
      if (dependsOnDataSource) {
        ids.push(id);
      }
    });

    if (ids.length > 0) {
      await this.reload(ids);
    }
  }

  /**
   * 订阅数据源变化
   */
  subscribe(id: string, callback: (state: DataSourceState) => void): () => void {
    if (!this.subscriptions.has(id)) {
      this.subscriptions.set(id, new Set());
    }

    this.subscriptions.get(id)!.add(callback);

    // 返回取消订阅函数
    return () => {
      this.subscriptions.get(id)?.delete(callback);
    };
  }

  /**
   * 通知订阅者
   */
  private notifySubscribers(id: string, state: DataSourceState): void {
    const subscribers = this.subscriptions.get(id);
    if (!subscribers) return;

    subscribers.forEach((callback) => {
      try {
        callback(state);
      } catch (error) {
        console.error('Subscriber callback error:', error);
      }
    });
  }

  /**
   * 设置依赖追踪
   * 当依赖的状态变化时，自动重新获取数据
   */
  setupDependencyTracking(id: string, dependencies: string[]): (() => void) | null {
    const config = this.dataSources.get(id);
    if (!config || !config.autoFetch) {
      return null;
    }

    const unsubscribe = this.subscribeDependencies(id, dependencies);
    if (unsubscribe) {
      return unsubscribe;
    }

    // Fallback: periodic check only when event-driven subscription is unavailable.
    // Pauses when page is hidden to avoid wasting CPU.
    let lastDeps: any = null;

    const checkInterval = setInterval(() => {
      // Skip polling when page is not visible
      if (typeof document !== 'undefined' && document.hidden) {
        return;
      }

      const currentDeps = dependencies.map((dep) => this.evaluateDependency(dep));

      if (lastDeps !== null && !areArraysEqual(currentDeps, lastDeps)) {
        this.fetch(id);
      }

      lastDeps = currentDeps;
    }, 500);

    return () => clearInterval(checkInterval);
  }

  /**
   * 清理数据源
   */
  unregister(id: string): void {
    this.dataSources.delete(id);
    this.dataSourceStates.delete(id);
    this.subscriptions.delete(id);
    this.fetchVersions.delete(id);
    this.dataSourceModelCodes.delete(id);
    this.cleanupDependency(id);
  }

  /**
   * 清理所有数据源
   */
  /**
   * Register a data source with its associated modelCode for real-time sync.
   */
  registerWithModel(id: string, config: DataSourceConfig, modelCode: string): void {
    this.register(id, config);
    this.dataSourceModelCodes.set(id, modelCode);
  }

  /**
   * Find DataSource IDs associated with a given modelCode.
   */
  getDataSourceIdsByModel(modelCode: string): string[] {
    const result: string[] = [];
    this.dataSourceModelCodes.forEach((mc, dsId) => {
      if (mc === modelCode) result.push(dsId);
    });
    return result;
  }

  clear(): void {
    this.dataSources.clear();
    this.dataSourceStates.clear();
    this.subscriptions.clear();
    this.fetchVersions.clear();
    this.dependencySubscriptions.forEach((unsubscribe) => unsubscribe());
    this.dependencySubscriptions.clear();
    this.dataSourceModelCodes.clear();
  }

  private evaluateDependency(expression: string) {
    const pathValue = this.readDependencyPath(expression);
    if (pathValue.matched) {
      return pathValue.value;
    }
    try {
      return evaluate(`\${${expression}}`, this.getContext());
    } catch {
      return undefined;
    }
  }

  private hasMissingDependencyParent(config: DataSourceConfig): boolean {
    const dependencies = config.dependOn || [];
    return dependencies.some((dependency) => this.readDependencyPath(dependency).missingParent);
  }

  /**
   * Whether a data source's declared dependencies are resolvable against the
   * current context (no missing parent in any `dependOn` path). A source with no
   * dependencies is always ready. Used to decide whether to fetch on mount: a
   * ready source (incl. dependency-less ones, or filter-bound lists whose filter
   * state simply isn't set yet) should load immediately; one waiting on an
   * unresolved parent (e.g. a detail bound to an unselected row) should defer
   * until its dependency changes.
   */
  dependenciesReady(config: DataSourceConfig): boolean {
    return !this.hasMissingDependencyParent(config);
  }

  private readDependencyPath(expression: string): {
    matched: boolean;
    missingParent: boolean;
    value: any;
  } {
    const path = String(expression || '').trim();
    if (!/^[A-Za-z_$][\w$]*(?:\.[A-Za-z_$][\w$]*)*$/.test(path)) {
      return { matched: false, missingParent: false, value: undefined };
    }

    const segments = path.split('.');
    let value: any = this.getContext() as any;
    for (let index = 0; index < segments.length; index += 1) {
      if (value === undefined || value === null) {
        return { matched: true, missingParent: true, value: undefined };
      }
      value = value[segments[index]];
      if ((value === undefined || value === null) && index < segments.length - 1) {
        return { matched: true, missingParent: true, value: undefined };
      }
    }

    return { matched: true, missingParent: false, value };
  }

  /**
   * 事件驱动的依赖追踪 (依赖 ScopedStateManager)
   */
  private subscribeDependencies(id: string, dependencies: string[]): (() => void) | null {
    if (!this.stateManager || !this.scopeId || dependencies.length === 0) {
      return null;
    }

    const store = this.stateManager.getStore(this.scopeId);
    if (!store) {
      return null;
    }

    const selector = () => dependencies.map((dep) => this.evaluateDependency(dep));

    const unsubscribe = store.subscribe(
      selector,
      () => {
        this.fetch(id);
      },
      {
        equalityFn: areArraysEqual,
      },
    );

    return () => unsubscribe();
  }

  private registerDependencies(id: string, config: DataSourceConfig): void {
    if (!config.dependOn || config.dependOn.length === 0) {
      return;
    }

    const unsubscribe = this.setupDependencyTracking(id, config.dependOn);
    if (unsubscribe) {
      this.dependencySubscriptions.set(id, unsubscribe);
    }
  }

  private cleanupDependency(id: string): void {
    const unsubscribe = this.dependencySubscriptions.get(id);
    if (unsubscribe) {
      unsubscribe();
      this.dependencySubscriptions.delete(id);
    }
  }
}

function areArraysEqual(a: any[] | null, b: any[] | null) {
  if (a === b) return true;
  if (!a || !b || a.length !== b.length) return false;
  for (let i = 0; i < a.length; i += 1) {
    const left = a[i];
    const right = b[i];
    if (typeof left === 'object' || typeof right === 'object') {
      if (JSON.stringify(left) !== JSON.stringify(right)) {
        return false;
      }
    } else if (!Object.is(left, right)) {
      return false;
    }
  }
  return true;
}
