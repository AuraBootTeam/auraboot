/**
 * Built-in Handlers 测试
 * 测试内置处理器注册表和约定映射
 */

import { describe, it, expect } from 'vitest';
import {
  BUILTIN_HANDLERS,
  DEFAULT_BUTTON_BEHAVIORS,
  getBuiltinHandler,
  isBuiltinHandler,
  getDefaultButtonHandler,
  interpolateHandler,
} from '~/framework/meta/runtime/events/builtin-handlers';

const requireSteps = (handlerId: string | undefined, handler: any) => {
  expect(handler.steps, `${handlerId || 'handler'} should define steps`).toBeDefined();
  return handler.steps ?? [];
};

describe('Built-in Handlers', () => {
  describe('BUILTIN_HANDLERS 注册表', () => {
    it('应该包含所有必需的 CRUD handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.create');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.update');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.delete');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.batchDelete');
    });

    it('应该包含表单相关 handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.formSubmit');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.reset');
    });

    it('应该包含搜索和筛选 handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.search');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.resetFilters');
    });

    it('应该包含导航 handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.navigateToCreate');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.navigateToEdit');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.navigateToView');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.back');
    });

    it('应该包含数据导出和刷新 handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.export');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.refresh');
    });

    it('应该包含UI容器 handlers', () => {
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.openDrawer');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.closeDrawer');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.openModal');
      expect(BUILTIN_HANDLERS).toHaveProperty('builtin.closeModal');
    });
  });

  describe('Handler 结构验证', () => {
    it('builtin.create 应该包含完整的创建工作流', () => {
      const handler = BUILTIN_HANDLERS['builtin.create'];
      const steps = requireSteps('builtin.create', handler);

      expect(handler.type).toBe('flow');
      expect(steps).toHaveLength(5);

      // 1. 表单验证
      expect(steps[0].action).toBe('form.validate');
      // 2. API 调用
      expect(steps[1].action).toBe('api.request');
      expect(steps[1].method).toBe('post');
      // 3. 成功提示
      expect(steps[2].action).toBe('toast.show');
      expect(steps[2].level).toBe('success');
      // 4. 刷新数据源
      expect(steps[3].action).toBe('dataSource.reload');
      // 5. 返回上一页
      expect(steps[4].action).toBe('router.back');
    });

    it('builtin.update 应该包含完整的更新工作流', () => {
      const handler = BUILTIN_HANDLERS['builtin.update'];
      const steps = requireSteps('builtin.update', handler);

      expect(handler.type).toBe('flow');
      expect(steps).toHaveLength(5);

      // 验证 -> API PUT -> 提示 -> 刷新 -> 返回
      expect(steps[0].action).toBe('form.validate');
      expect(steps[1].action).toBe('api.request');
      expect(steps[1].method).toBe('put');
      expect(steps[2].level).toBe('success');
    });

    it('builtin.batchDelete 应该包含条件判断和确认流程', () => {
      const handler = BUILTIN_HANDLERS['builtin.batchDelete'];
      const steps = requireSteps('builtin.batchDelete', handler);

      expect(handler.type).toBe('flow');
      expect(steps.length).toBeGreaterThan(5);

      // 第一步应该是条件判断
      expect(steps[0].type).toBe('if');
      expect(steps[0].condition).toContain('selectedIds');

      // 应该包含确认对话框
      const confirmStep = steps.find((step: any) => step.action === 'dialog.confirm');
      expect(confirmStep).toBeDefined();

      // 应该包含 API 删除请求
      const deleteStep = steps.find(
        (step: any) => step.action === 'api.request' && step.method === 'post',
      );
      expect(deleteStep).toBeDefined();

      // 应该清空选中项
      const clearStep = steps.find((step: any) => step.action === 'state.set');
      expect(clearStep).toBeDefined();
    });

    it('builtin.delete 应该包含确认对话框', () => {
      const handler = BUILTIN_HANDLERS['builtin.delete'];
      const steps = requireSteps('builtin.delete', handler);

      // 第一步应该是确认对话框
      expect(steps[0].action).toBe('dialog.confirm');

      // 第二步应该是 DELETE 请求
      expect(steps[1].action).toBe('api.request');
      expect(steps[1].method).toBe('delete');
    });

    it('builtin.search 应该重置页码并触发查询', () => {
      const handler = BUILTIN_HANDLERS['builtin.search'];
      const steps = requireSteps('builtin.search', handler);

      // 应该重置当前页为1
      expect(steps[0].action).toBe('state.set');
      expect(steps[0].args).toEqual({ 'pagination.current': 1 });

      // 应该触发数据源请求
      expect(steps[1].action).toBe('dataSource.fetch');
    });

    it('builtin.resetFilters 应该重置筛选条件和页码', () => {
      const handler = BUILTIN_HANDLERS['builtin.resetFilters'];
      const steps = requireSteps('builtin.resetFilters', handler);

      expect(steps).toHaveLength(3);

      // 1. 重置筛选状态
      expect(steps[0].action).toBe('state.reset');
      expect(steps[0].target).toBe('state.filters');

      // 2. 重置页码
      expect(steps[1].action).toBe('state.set');

      // 3. 重新查询
      expect(steps[2].action).toBe('dataSource.fetch');
    });
  });

  describe('DEFAULT_BUTTON_BEHAVIORS 映射', () => {
    it('应该包含常见按钮 code 的默认映射', () => {
      expect(DEFAULT_BUTTON_BEHAVIORS.create).toBe('builtin.navigateToCreate');
      expect(DEFAULT_BUTTON_BEHAVIORS.edit).toBe('builtin.navigateToEdit');
      expect(DEFAULT_BUTTON_BEHAVIORS.delete).toBe('builtin.delete');
      expect(DEFAULT_BUTTON_BEHAVIORS.search).toBe('builtin.search');
      expect(DEFAULT_BUTTON_BEHAVIORS.reset).toBe('builtin.resetFilters');
      expect(DEFAULT_BUTTON_BEHAVIORS.export).toBe('builtin.export');
      expect(DEFAULT_BUTTON_BEHAVIORS.refresh).toBe('builtin.refresh');
      expect(DEFAULT_BUTTON_BEHAVIORS.back).toBe('builtin.back');
    });

    it('应该支持批量删除的多种命名', () => {
      expect(DEFAULT_BUTTON_BEHAVIORS.deleteSelected).toBe('builtin.batchDelete');
      expect(DEFAULT_BUTTON_BEHAVIORS.batchDelete).toBe('builtin.batchDelete');
    });

    it('应该支持筛选重置的多种命名', () => {
      expect(DEFAULT_BUTTON_BEHAVIORS.reset).toBe('builtin.resetFilters');
      expect(DEFAULT_BUTTON_BEHAVIORS.resetFilters).toBe('builtin.resetFilters');
    });
  });

  describe('getBuiltinHandler', () => {
    it('应该返回存在的 handler', () => {
      const handler = getBuiltinHandler('builtin.create');
      expect(handler).toBeDefined();
      expect(handler?.type).toBe('flow');
    });

    it('应该对不存在的 handler 返回 undefined', () => {
      const handler = getBuiltinHandler('builtin.nonexistent');
      expect(handler).toBeUndefined();
    });
  });

  describe('isBuiltinHandler', () => {
    it('应该正确识别内置 handler', () => {
      expect(isBuiltinHandler('builtin.create')).toBe(true);
      expect(isBuiltinHandler('builtin.update')).toBe(true);
      expect(isBuiltinHandler('builtin.delete')).toBe(true);
    });

    it('应该正确识别非内置 handler', () => {
      expect(isBuiltinHandler('customHandler')).toBe(false);
      expect(isBuiltinHandler('user.create')).toBe(false);
      expect(isBuiltinHandler('builtin.nonexistent')).toBe(false);
    });
  });

  describe('getDefaultButtonHandler', () => {
    it('应该返回按钮 code 的默认 handler', () => {
      expect(getDefaultButtonHandler('create')).toBe('builtin.navigateToCreate');
      expect(getDefaultButtonHandler('delete')).toBe('builtin.delete');
      expect(getDefaultButtonHandler('search')).toBe('builtin.search');
    });

    it('应该对未映射的 code 返回 undefined', () => {
      expect(getDefaultButtonHandler('customAction')).toBeUndefined();
      expect(getDefaultButtonHandler('unknownCode')).toBeUndefined();
    });
  });

  describe('interpolateHandler', () => {
    it('应该替换字符串中的变量占位符', () => {
      const handler = {
        type: 'flow',
        steps: [
          {
            action: 'form.validate',
            target: '{{formRef}}',
          },
          {
            action: 'api.request',
            endpoint: '{{api}}',
            body: '{{state.form}}',
          },
        ],
      };

      const vars = {
        formRef: 'form.store',
        api: '/api/stores',
      };

      const result = interpolateHandler(handler as any, vars);
      const steps = result.steps ?? [];

      expect(steps[0].target).toBe('form.store');
      expect(steps[1].endpoint).toBe('/api/stores');
      expect(steps[1].body).toBe('{{state.form}}'); // 不在 vars 中的保持不变
    });

    it('应该处理嵌套对象', () => {
      const handler = {
        type: 'flow',
        steps: [
          {
            action: 'api.request',
            args: {
              url: '{{api}}',
              headers: {
                Authorization: 'Bearer {{token}}',
              },
            },
          },
        ],
      };

      const vars = {
        api: '/api/data',
        token: 'abc123',
      };

      const result = interpolateHandler(handler as any, vars);
      const steps = (result.steps ?? []) as any[];

      expect(steps[0]!.args.url).toBe('/api/data');
      expect(steps[0]!.args.headers.Authorization).toBe('Bearer abc123');
    });

    it('应该处理数组', () => {
      const handler = {
        type: 'flow',
        steps: [
          {
            action: 'dataSource.reload',
            targets: ['{{ds1}}', '{{ds2}}', 'static_ds'],
          },
        ],
      };

      const vars = {
        ds1: 'dataSource1',
        ds2: 'dataSource2',
      };

      const result = interpolateHandler(handler as any, vars);
      const steps = (result.steps ?? []) as any[];

      expect(steps[0]!.targets).toEqual(['dataSource1', 'dataSource2', 'static_ds']);
    });

    it('应该处理多个变量在同一字符串中', () => {
      const handler = {
        type: 'flow',
        steps: [
          {
            action: 'navigate',
            to: '{{basePath}}/{{id}}/edit',
          },
        ],
      };

      const vars = {
        basePath: '/stores',
        id: '123',
      };

      const result = interpolateHandler(handler as any, vars);
      const steps = (result.steps ?? []) as any[];

      expect(steps[0]!.to).toBe('/stores/123/edit');
    });

    it('应该保留未提供值的占位符', () => {
      const handler = {
        type: 'flow',
        steps: [
          {
            action: 'api.request',
            endpoint: '{{api}}/{{resource}}',
          },
        ],
      };

      const vars = {
        api: '/api',
      };

      const result = interpolateHandler(handler as any, vars);
      const steps = result.steps ?? [];

      expect(steps[0].endpoint).toBe('/api/{{resource}}');
    });
  });

  describe('真实 DSL 使用场景', () => {
    it('应该支持按钮 code 自动映射到内置 handler', () => {
      // DSL 配置
      const buttonConfig = {
        code: 'create',
        content: { 'zh-CN': '新建', 'en-US': 'Create' },
      };

      // 获取默认 handler
      const handlerType = getDefaultButtonHandler(buttonConfig.code);
      expect(handlerType).toBe('builtin.navigateToCreate');

      // 获取 handler 配置
      const handler = getBuiltinHandler(handlerType!);
      expect(handler).toBeDefined();
      const steps = handler?.steps ?? [];
      expect(steps[0].action).toBe('router.push');
    });

    it('应该支持覆盖内置 handler 的参数', () => {
      // DSL 中自定义 handler，覆盖内置行为
      const customHandler = {
        type: 'builtin.create',
        formRef: 'form.customStore',
        api: '/api/custom/stores',
        reload: ['ds_customStoreList'],
      };

      // 获取内置 handler
      const handler = getBuiltinHandler('builtin.create');
      expect(handler).toBeDefined();

      // 插值替换
      const interpolated = interpolateHandler(handler!, {
        formRef: customHandler.formRef,
        api: customHandler.api,
        reload: customHandler.reload,
      });
      const steps = interpolated.steps ?? [];

      expect(steps[0].target).toBe('form.customStore');
      expect(steps[1].endpoint).toBe('/api/custom/stores');
    });

    it('应该支持条件分支的批量删除场景', () => {
      const handler = BUILTIN_HANDLERS['builtin.batchDelete'];
      const steps = requireSteps('builtin.batchDelete', handler);
      // 检查是否有选中项
      const checkStep = steps[0];
      expect(checkStep.type).toBe('if');
      expect(checkStep.condition).toContain('selectedIds.length > 0');

      // 有选中项时跳转到确认
      expect(checkStep.trueNext).toBe('confirmDelete');

      // 无选中项时跳转到 noop
      expect(checkStep.falseNext).toBe('noSelection');

      // 确认步骤
      const confirmStep = steps.find((step: any) => step.id === 'confirmDelete');
      expect(confirmStep).toBeDefined();
      expect(confirmStep?.action).toBe('dialog.confirm');
    });
  });

  describe('Handler 完整性检查', () => {
    it('所有 handler 都应该有 type 字段', () => {
      Object.entries(BUILTIN_HANDLERS).forEach(([name, handler]) => {
        expect(handler.type, `${name} should have type field`).toBeDefined();
      });
    });

    it('所有 flow 类型的 handler 都应该有 steps', () => {
      Object.entries(BUILTIN_HANDLERS).forEach(([name, handler]) => {
        if (handler.type === 'flow') {
          const steps = requireSteps(name, handler);
          expect(Array.isArray(steps), `${name} steps should be an array`).toBe(true);
          expect(steps.length, `${name} should have at least one step`).toBeGreaterThan(0);
        }
      });
    });

    it('所有步骤都应该有 action 或 type 字段', () => {
      Object.entries(BUILTIN_HANDLERS).forEach(([name, handler]) => {
        if (handler.type === 'flow' && handler.steps) {
          handler.steps.forEach((step: any, index: number) => {
            expect(
              step.action || step.type,
              `${name} step ${index} should have action or type`,
            ).toBeDefined();
          });
        }
      });
    });
  });
});
