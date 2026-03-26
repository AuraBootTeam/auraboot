/**
 * React Router v7 集成类型定义
 *
 * 支持动态路由生成、页面间导航、参数传递和权限控制
 */

import type { PageSchema, PageType } from '~/types/page-schema';
import type { LowCodeContext } from '~/types/lowcode';

// 路由配置
export interface RouteConfig {
  path: string;
  pageType?: PageType;
  schemaPath?: string;
  schema?: PageSchema;
  name?: string;
  component?: string;
  title?: string;
  icon?: string;
  permissions?: string[];
  guards?: string[];
  middleware?: string[];
  meta?: Record<string, any>;
  children?: RouteConfig[];
}

// 路由参数
export interface RouteParams {
  [key: string]: string | undefined;
}

// 路由查询参数
export interface RouteQuery {
  [key: string]: string | string[] | undefined;
}

// 路由状态
export interface RouteState {
  from?: string;
  data?: Record<string, any>;
  context?: Partial<LowCodeContext>;
}

// 导航选项
export interface NavigationOptions {
  replace?: boolean;
  state?: RouteState;
  preventScrollReset?: boolean;
}

// 路由守卫
export interface RouteGuard {
  name: string;
  check?: (
    path: string,
    context: LowCodeContext,
    route?: RouteConfig,
  ) => boolean | Promise<boolean>;
  canActivate?: (
    path: string,
    context: LowCodeContext,
    route?: RouteConfig,
  ) => boolean | Promise<boolean>;
  redirect?: string;
  message?: string;
}

// 路由中间件
export interface RouteMiddleware {
  name: string;
  execute: (
    path: string,
    context: LowCodeContext,
    route?: RouteConfig,
  ) => Promise<boolean | void> | boolean | void;
}

// 动态路由生成器配置
export interface DynamicRouteConfig {
  basePath: string;
  schemaDirectory: string;
  defaultPageType: PageType;
  guards?: RouteGuard[];
  middleware?: RouteMiddleware[];
}

// 路由上下文
export interface RouterContext {
  currentRoute: RouteConfig;
  params: RouteParams;
  query: RouteQuery;
  state: RouteState;
  navigate: (to: string, options?: NavigationOptions) => void;
  goBack: () => void;
  goForward: () => void;
  refresh: () => void;
}

// 页面路由映射
export interface PageRouteMapping {
  [pageType: string]: {
    component: React.ComponentType<any>;
    layout?: React.ComponentType<any>;
    guards?: string[];
    middleware?: string[];
  };
}

// 路由事件
export interface RouteEvent {
  type: 'beforeNavigate' | 'afterNavigate' | 'navigationError';
  from?: RouteConfig;
  to?: RouteConfig;
  error?: Error;
  timestamp: number;
}

// 路由事件监听器
export type RouteEventListener = (event: RouteEvent) => void;

// 面包屑项
export interface BreadcrumbItem {
  title: string;
  path?: string;
  icon?: string;
  active?: boolean;
}

// 路由管理器接口
export interface RouteManager {
  // 路由注册
  registerRoute(config: RouteConfig): void;
  registerRoutes(configs: RouteConfig[]): void;

  // 路由查找
  findRoute(path: string): RouteConfig | undefined;
  getRoutesByType(pageType: PageType): RouteConfig[];

  // 路由生成
  generateRoutes(config: DynamicRouteConfig): Promise<RouteConfig[]>;

  // 权限检查
  checkPermissions(route: RouteConfig, context: LowCodeContext): boolean;

  // 守卫管理
  addGuard(guard: RouteGuard): void;
  removeGuard(name: string): void;

  // 中间件管理
  addMiddleware(middleware: RouteMiddleware): void;
  removeMiddleware(name: string): void;

  // 事件管理
  addEventListener(listener: RouteEventListener): void;
  removeEventListener(listener: RouteEventListener): void;

  // 面包屑生成
  generateBreadcrumbs(path: string): BreadcrumbItem[];
}

// 路由配置构建器
export interface RouteConfigBuilder {
  path(path: string): RouteConfigBuilder;
  pageType(type: PageType): RouteConfigBuilder;
  schema(schema: PageSchema | string): RouteConfigBuilder;
  title(title: string): RouteConfigBuilder;
  icon(icon: string): RouteConfigBuilder;
  permissions(permissions: string[]): RouteConfigBuilder;
  meta(meta: Record<string, any>): RouteConfigBuilder;
  children(children: RouteConfig[]): RouteConfigBuilder;
  build(): RouteConfig;
}

// 路由模板
export interface RouteTemplate {
  name: string;
  pattern: string;
  pageType: PageType;
  defaultTitle?: string;
  defaultIcon?: string;
  defaultPermissions?: string[];
  variables?: string[];
}

// 路由生成选项
export interface RouteGenerationOptions {
  includeHidden?: boolean;
  filterByPermissions?: boolean;
  context?: LowCodeContext;
  templates?: RouteTemplate[];
}

export default {};
