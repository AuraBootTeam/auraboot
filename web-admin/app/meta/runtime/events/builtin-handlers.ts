/**
 * Built-in Handlers - 内置处理器注册表
 * 提供常用的预定义 handler，遵循约定大于配置原则
 */

import type { HandlerConfig, FlowStep } from '~/meta/schemas/types';

/**
 * 内置 Handler 模板
 */
export const BUILTIN_HANDLERS: Record<string, HandlerConfig> = {
  // 表单提交
  'builtin.formSubmit': {
    type: 'flow',
    steps: [
      { action: 'form.validate', target: '{{formRef}}' },
      {
        action: 'api.request',
        method: 'post',
        endpoint: '{{api}}',
        body: '{{state.form}}',
        next: 'afterSubmit',
      },
      {
        id: 'afterSubmit',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.submit_success',
      },
      {
        action: 'dataSource.reload',
        target: '{{reload}}',
      },
    ],
  },

  // 表单创建
  'builtin.create': {
    type: 'flow',
    steps: [
      { action: 'form.validate', target: '{{formRef}}' },
      {
        action: 'api.request',
        method: 'post',
        endpoint: '{{api}}',
        body: '{{state.form}}',
        next: 'afterCreate',
      },
      {
        id: 'afterCreate',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.create_success',
      },
      {
        action: 'dataSource.reload',
        target: '{{reload}}',
      },
      {
        action: 'router.back',
      },
    ],
  },

  // 表单更新
  'builtin.update': {
    type: 'flow',
    steps: [
      { action: 'form.validate', target: '{{formRef}}' },
      {
        action: 'api.request',
        method: 'put',
        endpoint: '{{api}}',
        body: '{{state.form}}',
        next: 'afterUpdate',
      },
      {
        id: 'afterUpdate',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.update_success',
      },
      {
        action: 'dataSource.reload',
        target: '{{reload}}',
      },
      {
        action: 'router.back',
      },
    ],
  },

  // 批量删除
  'builtin.batchDelete': {
    type: 'flow',
    steps: [
      {
        type: 'if',
        condition: '${state.selectedIds.length > 0}',
        trueNext: 'confirmDelete',
        falseNext: 'noSelection',
      },
      {
        id: 'confirmDelete',
        action: 'dialog.confirm',
        args: {
          title: '$i18n:dialog.confirm_delete_title',
          content: '$i18n:dialog.confirm_delete_content',
        },
        next: 'doDelete',
      },
      {
        id: 'doDelete',
        action: 'api.request',
        method: 'post',
        endpoint: '{{api}}',
        body: { ids: '{{state.selectedIds}}' },
        next: 'afterDelete',
      },
      {
        id: 'afterDelete',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.delete_success',
      },
      {
        action: 'dataSource.reload',
        target: '{{reload}}',
      },
      {
        action: 'state.set',
        args: { selectedIds: [] },
      },
      { id: 'noSelection', action: 'noop' },
    ],
  },

  // 单条删除
  'builtin.delete': {
    type: 'flow',
    steps: [
      {
        action: 'dialog.confirm',
        args: {
          title: '$i18n:dialog.confirm_delete_title',
          content: '$i18n:dialog.confirm_delete_content',
        },
        next: 'doDelete',
      },
      {
        id: 'doDelete',
        action: 'api.request',
        method: 'delete',
        endpoint: '{{api}}/${args.id}',
        next: 'afterDelete',
      },
      {
        id: 'afterDelete',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.delete_success',
      },
      {
        action: 'dataSource.reload',
        target: '{{reload}}',
      },
    ],
  },

  // 表单重置
  'builtin.reset': {
    type: 'flow',
    steps: [{ action: 'form.reset', target: '{{formRef}}' }],
  },

  // 搜索/筛选
  'builtin.search': {
    type: 'flow',
    steps: [
      {
        action: 'state.set',
        args: { 'pagination.current': 1 },
      },
      {
        action: 'dataSource.fetch',
        target: '{{dataSource}}',
      },
    ],
  },

  // 重置筛选
  'builtin.resetFilters': {
    type: 'flow',
    steps: [
      {
        action: 'state.reset',
        target: 'state.filters',
      },
      {
        action: 'state.set',
        args: { 'pagination.current': 1 },
      },
      {
        action: 'dataSource.fetch',
        target: '{{dataSource}}',
      },
    ],
  },

  // 导出数据
  'builtin.export': {
    type: 'flow',
    steps: [
      {
        action: 'api.request',
        method: 'post',
        endpoint: '{{api}}',
        body: '{{state.filters}}',
        next: 'afterExport',
      },
      {
        id: 'afterExport',
        action: 'toast.show',
        level: 'success',
        content: '$i18n:msg.export_success',
      },
    ],
  },

  // 刷新数据
  'builtin.refresh': {
    type: 'flow',
    steps: [
      {
        action: 'dataSource.fetch',
        target: '{{dataSource}}',
      },
    ],
  },

  // 导航到新建页面
  'builtin.navigateToCreate': {
    type: 'flow',
    steps: [
      {
        action: 'router.push',
        args: { path: '{{path}}' },
      },
    ],
  },

  // 导航到编辑页面
  'builtin.navigateToEdit': {
    type: 'flow',
    steps: [
      {
        action: 'router.push',
        args: { path: '{{path}}/${args.id}' },
      },
    ],
  },

  // 导航到详情页面
  'builtin.navigateToView': {
    type: 'flow',
    steps: [
      {
        action: 'router.push',
        args: { path: '{{path}}/${args.id}' },
      },
    ],
  },

  // 返回上一页
  'builtin.back': {
    type: 'flow',
    steps: [{ action: 'router.back' }],
  },

  // 打开抽屉
  'builtin.openDrawer': {
    type: 'flow',
    steps: [
      {
        action: 'ui.openContainer',
        target: '{{container}}',
        args: '{{args}}' as unknown as Record<string, any>,
      },
    ],
  },

  // 关闭抽屉
  'builtin.closeDrawer': {
    type: 'flow',
    steps: [
      {
        action: 'ui.closeContainer',
        target: '{{container}}',
      },
    ],
  },

  // 打开弹窗
  'builtin.openModal': {
    type: 'flow',
    steps: [
      {
        action: 'ui.openContainer',
        target: '{{container}}',
        args: '{{args}}' as unknown as Record<string, any>,
      },
    ],
  },

  // 关闭弹窗
  'builtin.closeModal': {
    type: 'flow',
    steps: [
      {
        action: 'ui.closeContainer',
        target: '{{container}}',
      },
    ],
  },
};

/**
 * 默认按钮行为映射
 * 当按钮的 code 匹配时，自动使用对应的内置 handler
 */
export const DEFAULT_BUTTON_BEHAVIORS: Record<string, string> = {
  create: 'builtin.navigateToCreate',
  edit: 'builtin.navigateToEdit',
  view: 'builtin.navigateToView',
  delete: 'builtin.delete',
  deleteSelected: 'builtin.batchDelete',
  batchDelete: 'builtin.batchDelete',
  search: 'builtin.search',
  reset: 'builtin.resetFilters',
  resetFilters: 'builtin.resetFilters',
  export: 'builtin.export',
  refresh: 'builtin.refresh',
  back: 'builtin.back',
  submit: 'builtin.formSubmit',
  cancel: 'builtin.closeDrawer',
};

/**
 * 获取内置 Handler
 */
export function getBuiltinHandler(name: string): HandlerConfig | undefined {
  return BUILTIN_HANDLERS[name];
}

/**
 * 检查是否为内置 Handler
 */
export function isBuiltinHandler(name: string): boolean {
  return name in BUILTIN_HANDLERS;
}

/**
 * 获取按钮的默认 Handler
 */
export function getDefaultButtonHandler(buttonCode: string): string | undefined {
  return DEFAULT_BUTTON_BEHAVIORS[buttonCode];
}

/**
 * 插值替换 Handler 中的变量
 * 例如: {{formRef}} -> form.store
 */
export function interpolateHandler(
  handler: HandlerConfig,
  vars: Record<string, any>,
): HandlerConfig {
  const interpolate = (value: any): any => {
    if (typeof value === 'string') {
      return value.replace(/\{\{(\w+)\}\}/g, (_, key) => {
        return vars[key] !== undefined ? vars[key] : `{{${key}}}`;
      });
    }

    if (Array.isArray(value)) {
      return value.map(interpolate);
    }

    if (typeof value === 'object' && value !== null) {
      const result: any = {};
      for (const [k, v] of Object.entries(value)) {
        result[k] = interpolate(v);
      }
      return result;
    }

    return value;
  };

  return interpolate(handler);
}
