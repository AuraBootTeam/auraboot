import type {
  RouteConfig,
  RouteGuard,
  RouteMiddleware,
  BreadcrumbItem,
  DynamicRouteConfig,
} from '~/types/router';
import type { LowCodeContext } from '~/types/lowcode';
import type { PageSchema } from '~/types/page-schema';
import { PageType } from '~/types/page-schema';

type GuardResult = boolean | Promise<boolean>;

export class RouteManagerImpl {
  private static instance: RouteManagerImpl;
  private routes = new Map<string, RouteConfig>();
  private guards = new Map<string, RouteGuard>();
  private middleware = new Map<string, RouteMiddleware>();

  private constructor() {
    this.registerDefaultGuards();
  }

  static getInstance(): RouteManagerImpl {
    if (!RouteManagerImpl.instance) {
      RouteManagerImpl.instance = new RouteManagerImpl();
    }
    return RouteManagerImpl.instance;
  }

  registerRoute(config: RouteConfig): void {
    this.routes.set(config.path, config);
    if (config.children) {
      config.children.forEach((child) => this.registerRoute(child));
    }
  }

  registerRoutes(configs: RouteConfig[]): void {
    configs.forEach((config) => this.registerRoute(config));
  }

  findRoute(path: string): RouteConfig | null {
    if (this.routes.has(path)) {
      return this.routes.get(path) ?? null;
    }

    for (const [pattern, route] of this.routes.entries()) {
      if (this.matchRoutePattern(pattern, path)) {
        return route;
      }
    }

    return null;
  }

  async canAccess(path: string, context: LowCodeContext): Promise<boolean> {
    const route = this.findRoute(path);
    if (!route) return false;

    if (!this.checkPermissions(route, context)) {
      return false;
    }

    if (route.guards && route.guards.length > 0) {
      for (const guardName of route.guards) {
        const guard = this.guards.get(guardName);
        if (!guard) return false;

        const checker = guard.canActivate ?? guard.check;
        if (!checker) return false;

        try {
          const result = await checker(path, context, route);
          if (!result) return false;
        } catch {
          return false;
        }
      }
    }

    return true;
  }

  checkPermissions(route: RouteConfig, context: LowCodeContext): boolean {
    const required = route.permissions;
    if (!required || required.length === 0) {
      return true;
    }

    const userPermissions = context.$user?.permissions ?? context.user?.roles ?? [];

    if (!Array.isArray(userPermissions)) {
      return false;
    }

    return required.every((permission) => userPermissions.includes(permission));
  }

  addGuard(guard: RouteGuard): void {
    this.guards.set(guard.name, guard);
  }

  removeGuard(name: string): void {
    this.guards.delete(name);
  }

  addMiddleware(middleware: RouteMiddleware): void {
    this.middleware.set(middleware.name, middleware);
  }

  removeMiddleware(name: string): void {
    this.middleware.delete(name);
  }

  async executeMiddleware(path: string, context: LowCodeContext): Promise<boolean> {
    const route = this.findRoute(path);
    if (!route) return false;

    if (!route.middleware || route.middleware.length === 0) {
      return true;
    }

    for (const middlewareName of route.middleware) {
      const handler = this.middleware.get(middlewareName);
      if (!handler) return false;
      try {
        const result = await handler.execute(path, context, route);
        if (result === false) {
          return false;
        }
      } catch {
        return false;
      }
    }

    return true;
  }

  generateRouteFromSchema(schema: PageSchema, basePath: string): RouteConfig {
    const name = schema.meta?.name ?? 'untitled';
    const title = this.getLocalizedTitle(schema.meta?.title);
    const pageType = this.resolvePageType(schema);
    const component = this.resolveComponent(pageType);

    return {
      path: `${basePath}/${name}`,
      name,
      component,
      pageType,
      meta: {
        schema,
        title,
        pageType,
      },
    };
  }

  generateDynamicRoute(template: string, params: Record<string, string>): string {
    return template.replace(/:([A-Za-z0-9_]+)/g, (match, key) => {
      return params[key] ?? match;
    });
  }

  generateBreadcrumbs(path: string): BreadcrumbItem[] {
    const matches: Array<{ depth: number; route: RouteConfig; params?: Record<string, string> }> =
      [];

    for (const [pattern, route] of this.routes.entries()) {
      const match = this.matchRoutePattern(pattern, path, true);
      if (match) {
        matches.push({
          depth: this.getDepth(pattern),
          route,
          params: match,
        });
      }
    }

    matches.sort((a, b) => a.depth - b.depth);

    return matches.map(({ route, params }) => {
      const title =
        (typeof route.meta?.title === 'string' ? route.meta?.title : undefined) ??
        route.title ??
        route.name ??
        route.path;

      const resolvedPath = params ? this.generateDynamicRoute(route.path, params) : route.path;

      return { title, path: resolvedPath };
    });
  }

  async preloadRoute(path: string): Promise<boolean> {
    const route = this.findRoute(path);
    return !!route;
  }

  async generateRoutes(_config: DynamicRouteConfig): Promise<RouteConfig[]> {
    return [];
  }

  getRoutesByType(pageType: RouteConfig['pageType']): RouteConfig[] {
    return Array.from(this.routes.values()).filter((route) => route.pageType === pageType);
  }

  private registerDefaultGuards(): void {
    const authGuard: RouteGuard = {
      name: 'auth',
      canActivate: (_path, context) => Boolean(context.$user || context.user),
    };

    const permissionGuard: RouteGuard = {
      name: 'permission',
      canActivate: (path, context, route) => {
        if (!route) {
          const resolved = this.findRoute(path);
          if (!resolved) return false;
          return this.checkPermissions(resolved, context);
        }
        return this.checkPermissions(route, context);
      },
    };

    const tenantGuard: RouteGuard = {
      name: 'tenant',
      canActivate: (_path, context) => Boolean(context.$tenant || context.tenant),
    };

    this.addGuard(authGuard);
    this.addGuard(permissionGuard);
    this.addGuard(tenantGuard);
  }

  private resolvePageType(schema: PageSchema): PageType {
    const regions = schema.regions ?? [];
    if (regions.some((region) => region.type === 'table')) return PageType.LIST;
    if (regions.some((region) => region.type === 'form')) return PageType.FORM;
    return PageType.DETAIL;
  }

  private resolveComponent(pageType: PageType): string {
    switch (pageType) {
      case PageType.FORM:
        return 'FormPageContainer';
      case PageType.LIST:
        return 'ListPageContainer';
      default:
        return 'DetailPageContainer';
    }
  }

  private getLocalizedTitle(title?: Record<string, string>): string {
    if (!title) return '';
    return title['zh-CN'] || title['en-US'] || Object.values(title)[0] || '';
  }

  private matchRoutePattern(
    pattern: string,
    path: string,
    allowPrefix = false,
  ): Record<string, string> | null {
    if (pattern === path) return {};

    const patternSegments = this.trimSlash(pattern).split('/');
    const pathSegments = this.trimSlash(path).split('/');

    if (!allowPrefix && patternSegments.length !== pathSegments.length) {
      return null;
    }

    if (allowPrefix && patternSegments.length > pathSegments.length) {
      return null;
    }

    const params: Record<string, string> = {};

    for (let i = 0; i < patternSegments.length; i += 1) {
      const patternSegment = patternSegments[i];
      const pathSegment = pathSegments[i];

      if (patternSegment.startsWith(':')) {
        params[patternSegment.slice(1)] = pathSegment;
        continue;
      }

      if (patternSegment !== pathSegment) {
        return null;
      }
    }

    return params;
  }

  private trimSlash(value: string): string {
    return value.replace(/^\/+|\/+$/g, '');
  }

  private getDepth(path: string): number {
    const trimmed = this.trimSlash(path);
    if (!trimmed) return 0;
    return trimmed.split('/').length;
  }
}
