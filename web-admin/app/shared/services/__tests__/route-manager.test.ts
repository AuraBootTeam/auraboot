/**
 * RouteManager 服务单元测试
 */

import { describe, it, expect, vi, beforeEach } from 'vitest';
import { RouteManagerImpl } from '~/shared/services/route-manager';
import type { RouteConfig, RouteGuard, RouteMiddleware } from '~/types/router';
import type { LowCodeContext } from '~/types/lowcode';
import type { PageSchema } from '~/types/page-schema';

describe('RouteManagerImpl', () => {
  let routeManager: RouteManagerImpl;

  const mockContext: LowCodeContext = {
    $user: {
      id: 'user-001',
      name: 'Test User',
      role: 'admin',
      permissions: ['read', 'create', 'update', 'delete'],
    },
    $tenant: {
      id: 'tenant-001',
      name: 'Test Tenant',
    },
  };

  beforeEach(() => {
    routeManager = RouteManagerImpl.getInstance();
    // 清空路由配置
    (routeManager as any).routes.clear();
    (routeManager as any).guards.clear();
    (routeManager as any).middleware.clear();
  });

  describe('路由注册和查找', () => {
    it('应该注册和查找路由', () => {
      const route: RouteConfig = {
        path: '/test',
        name: 'test-route',
        component: 'TestComponent',
        permissions: ['read'],
      };

      routeManager.registerRoute(route);
      const foundRoute = routeManager.findRoute('/test');

      expect(foundRoute).toEqual(route);
    });

    it('应该处理路由参数', () => {
      const route: RouteConfig = {
        path: '/users/:id',
        name: 'user-detail',
        component: 'UserDetail',
        permissions: ['read'],
      };

      routeManager.registerRoute(route);
      const foundRoute = routeManager.findRoute('/users/123');

      expect(foundRoute).toEqual(route);
    });

    it('应该返回null当路由不存在', () => {
      const foundRoute = routeManager.findRoute('/non-existent');
      expect(foundRoute).toBeNull();
    });

    it('应该批量注册路由', () => {
      const routes: RouteConfig[] = [
        {
          path: '/route1',
          name: 'route1',
          component: 'Component1',
          permissions: ['read'],
        },
        {
          path: '/route2',
          name: 'route2',
          component: 'Component2',
          permissions: ['read'],
        },
      ];

      routeManager.registerRoutes(routes);

      expect(routeManager.findRoute('/route1')).toEqual(routes[0]);
      expect(routeManager.findRoute('/route2')).toEqual(routes[1]);
    });
  });

  describe('权限检查', () => {
    it('应该允许有权限的用户访问', async () => {
      const route: RouteConfig = {
        path: '/admin',
        name: 'admin',
        component: 'AdminPanel',
        permissions: ['read'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/admin', mockContext);

      expect(canAccess).toBe(true);
    });

    it('应该拒绝没有权限的用户访问', async () => {
      const route: RouteConfig = {
        path: '/admin',
        name: 'admin',
        component: 'AdminPanel',
        permissions: ['super-admin'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/admin', mockContext);

      expect(canAccess).toBe(false);
    });

    it('应该允许访问没有权限要求的路由', async () => {
      const route: RouteConfig = {
        path: '/public',
        name: 'public',
        component: 'PublicPage',
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/public', mockContext);

      expect(canAccess).toBe(true);
    });
  });

  describe('路由守卫', () => {
    it('应该注册和执行路由守卫', async () => {
      const mockGuard: RouteGuard = {
        name: 'test-guard',
        canActivate: vi.fn().mockResolvedValue(true),
      };

      routeManager.addGuard(mockGuard);

      const route: RouteConfig = {
        path: '/protected',
        name: 'protected',
        component: 'ProtectedPage',
        guards: ['test-guard'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/protected', mockContext);

      expect(canAccess).toBe(true);
      expect(mockGuard.canActivate).toHaveBeenCalledWith('/protected', mockContext);
    });

    it('应该拒绝访问当守卫返回false', async () => {
      const mockGuard: RouteGuard = {
        name: 'blocking-guard',
        canActivate: vi.fn().mockResolvedValue(false),
      };

      routeManager.addGuard(mockGuard);

      const route: RouteConfig = {
        path: '/blocked',
        name: 'blocked',
        component: 'BlockedPage',
        guards: ['blocking-guard'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/blocked', mockContext);

      expect(canAccess).toBe(false);
    });

    it('应该执行多个守卫', async () => {
      const guard1: RouteGuard = {
        name: 'guard1',
        canActivate: vi.fn().mockResolvedValue(true),
      };

      const guard2: RouteGuard = {
        name: 'guard2',
        canActivate: vi.fn().mockResolvedValue(true),
      };

      routeManager.addGuard(guard1);
      routeManager.addGuard(guard2);

      const route: RouteConfig = {
        path: '/multi-guard',
        name: 'multi-guard',
        component: 'MultiGuardPage',
        guards: ['guard1', 'guard2'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/multi-guard', mockContext);

      expect(canAccess).toBe(true);
      expect(guard1.canActivate).toHaveBeenCalled();
      expect(guard2.canActivate).toHaveBeenCalled();
    });
  });

  describe('中间件', () => {
    it('应该注册和执行中间件', async () => {
      const mockMiddleware: RouteMiddleware = {
        name: 'test-middleware',
        execute: vi.fn().mockResolvedValue(true),
      };

      routeManager.addMiddleware(mockMiddleware);

      const route: RouteConfig = {
        path: '/middleware-test',
        name: 'middleware-test',
        component: 'MiddlewareTestPage',
        middleware: ['test-middleware'],
      };

      routeManager.registerRoute(route);
      const result = await routeManager.executeMiddleware('/middleware-test', mockContext);

      expect(result).toBe(true);
      expect(mockMiddleware.execute).toHaveBeenCalledWith('/middleware-test', mockContext);
    });

    it('应该处理中间件执行失败', async () => {
      const mockMiddleware: RouteMiddleware = {
        name: 'failing-middleware',
        execute: vi.fn().mockResolvedValue(false),
      };

      routeManager.addMiddleware(mockMiddleware);

      const route: RouteConfig = {
        path: '/failing-middleware',
        name: 'failing-middleware',
        component: 'FailingPage',
        middleware: ['failing-middleware'],
      };

      routeManager.registerRoute(route);
      const result = await routeManager.executeMiddleware('/failing-middleware', mockContext);

      expect(result).toBe(false);
    });
  });

  describe('动态路由生成', () => {
    it('应该从PageSchema生成路由', () => {
      const schema: PageSchema = {
        meta: {
          name: 'user-form',
          title: { 'zh-CN': '用户表单' },
          version: '1.0.0',
        },
        regions: [
          {
            type: 'form',
            name: 'userForm',
            title: { 'zh-CN': '用户表单' },
            sections: [],
            actions: [],
          },
        ],
      };

      const route = routeManager.generateRouteFromSchema(schema, '/forms');

      expect(route.path).toBe('/forms/user-form');
      expect(route.name).toBe('user-form');
      expect(route.component).toBe('FormPageContainer');
      expect(route.meta).toEqual({
        schema,
        title: '用户表单',
        pageType: 'form',
      });
    });

    it('应该生成列表页面路由', () => {
      const schema: PageSchema = {
        meta: {
          name: 'user-list',
          title: { 'zh-CN': '用户列表' },
          version: '1.0.0',
        },
        api: {
          list: { url: '/api/users', method: 'get' },
        },
        regions: [
          {
            type: 'table',
            name: 'userTable',
            title: { 'zh-CN': '用户列表' },
            columns: [],
            actions: [],
          },
        ],
      };

      const route = routeManager.generateRouteFromSchema(schema, '/lists');

      expect(route.path).toBe('/lists/user-list');
      expect(route.component).toBe('ListPageContainer');
      expect(route.meta?.pageType).toBe('list');
    });

    it('应该生成动态路由路径', () => {
      const template = '/users/:userId/orders/:orderId';
      const params = { userId: '123', orderId: '456' };

      const path = routeManager.generateDynamicRoute(template, params);

      expect(path).toBe('/users/123/orders/456');
    });
  });

  describe('面包屑生成', () => {
    it('应该生成面包屑导航', () => {
      const routes: RouteConfig[] = [
        {
          path: '/',
          name: 'home',
          component: 'HomePage',
          meta: { title: '首页' },
        },
        {
          path: '/users',
          name: 'users',
          component: 'UsersPage',
          meta: { title: '用户管理' },
        },
        {
          path: '/users/:id',
          name: 'user-detail',
          component: 'UserDetailPage',
          meta: { title: '用户详情' },
        },
      ];

      routeManager.registerRoutes(routes);
      const breadcrumbs = routeManager.generateBreadcrumbs('/users/123');

      expect(breadcrumbs).toEqual([
        { title: '首页', path: '/' },
        { title: '用户管理', path: '/users' },
        { title: '用户详情', path: '/users/123' },
      ]);
    });

    it('应该处理根路径', () => {
      const route: RouteConfig = {
        path: '/',
        name: 'home',
        component: 'HomePage',
        meta: { title: '首页' },
      };

      routeManager.registerRoute(route);
      const breadcrumbs = routeManager.generateBreadcrumbs('/');

      expect(breadcrumbs).toEqual([{ title: '首页', path: '/' }]);
    });
  });

  describe('路由预加载', () => {
    it('应该预加载路由', async () => {
      const route: RouteConfig = {
        path: '/preload-test',
        name: 'preload-test',
        component: 'PreloadTestPage',
      };

      routeManager.registerRoute(route);
      const result = await routeManager.preloadRoute('/preload-test');

      expect(result).toBe(true);
    });

    it('应该处理不存在的路由预加载', async () => {
      const result = await routeManager.preloadRoute('/non-existent');
      expect(result).toBe(false);
    });
  });

  describe('默认守卫', () => {
    it('应该有认证守卫', () => {
      const guards = (routeManager as any).guards;
      expect(guards.has('auth')).toBe(true);
    });

    it('应该有授权守卫', () => {
      const guards = (routeManager as any).guards;
      expect(guards.has('permission')).toBe(true);
    });

    it('应该有租户守卫', () => {
      const guards = (routeManager as any).guards;
      expect(guards.has('tenant')).toBe(true);
    });

    it('认证守卫应该检查用户登录状态', async () => {
      const authGuard = (routeManager as any).guards.get('auth');

      // 有用户时应该通过
      const resultWithUser = await authGuard.canActivate('/test', mockContext);
      expect(resultWithUser).toBe(true);

      // 无用户时应该拒绝
      const contextWithoutUser = { ...mockContext, $user: null };
      const resultWithoutUser = await authGuard.canActivate('/test', contextWithoutUser);
      expect(resultWithoutUser).toBe(false);
    });

    it('租户守卫应该检查租户信息', async () => {
      const tenantGuard = (routeManager as any).guards.get('tenant');

      // 有租户时应该通过
      const resultWithTenant = await tenantGuard.canActivate('/test', mockContext);
      expect(resultWithTenant).toBe(true);

      // 无租户时应该拒绝
      const contextWithoutTenant = { ...mockContext, $tenant: null };
      const resultWithoutTenant = await tenantGuard.canActivate('/test', contextWithoutTenant);
      expect(resultWithoutTenant).toBe(false);
    });
  });

  describe('错误处理', () => {
    it('应该处理守卫执行错误', async () => {
      const errorGuard: RouteGuard = {
        name: 'error-guard',
        canActivate: vi.fn().mockRejectedValue(new Error('Guard error')),
      };

      routeManager.addGuard(errorGuard);

      const route: RouteConfig = {
        path: '/error-test',
        name: 'error-test',
        component: 'ErrorTestPage',
        guards: ['error-guard'],
      };

      routeManager.registerRoute(route);
      const canAccess = await routeManager.canAccess('/error-test', mockContext);

      expect(canAccess).toBe(false);
    });

    it('应该处理中间件执行错误', async () => {
      const errorMiddleware: RouteMiddleware = {
        name: 'error-middleware',
        execute: vi.fn().mockRejectedValue(new Error('Middleware error')),
      };

      routeManager.addMiddleware(errorMiddleware);

      const route: RouteConfig = {
        path: '/middleware-error',
        name: 'middleware-error',
        component: 'MiddlewareErrorPage',
        middleware: ['error-middleware'],
      };

      routeManager.registerRoute(route);
      const result = await routeManager.executeMiddleware('/middleware-error', mockContext);

      expect(result).toBe(false);
    });
  });
});
