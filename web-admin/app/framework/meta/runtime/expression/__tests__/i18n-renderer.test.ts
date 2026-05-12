/**
 * i18n Renderer 测试
 * 测试 ICU MessageFormat 功能
 */

import { describe, it, expect } from 'vitest';
import { renderText } from '~/framework/meta/runtime/expression/i18n-renderer';
import {
  createExpressionContext,
  type ExpressionContext,
} from '~/framework/meta/runtime/expression/context';

describe('i18n-renderer', () => {
  const createContext = (
    locale: string = 'zh-CN',
    i18n: Record<string, Record<string, string>> = {},
  ): ExpressionContext =>
    createExpressionContext({
      locale,
      i18n,
    });

  describe('基础功能', () => {
    it('应该渲染普通字符串', () => {
      const context = createContext();
      expect(renderText('Hello World', context)).toBe('Hello World');
    });

    it('应该处理 undefined/null', () => {
      const context = createContext();
      expect(renderText(undefined, context)).toBe('');
      expect(renderText(null, context)).toBe('');
    });

    it('应该渲染 LocalizedText 对象', () => {
      const context = createContext('zh-CN');
      const text = {
        'zh-CN': '你好',
        'en-US': 'Hello',
      };
      expect(renderText(text, context)).toBe('你好');
    });

    it('应该在 en-US locale 下兼容 en 简写', () => {
      const context = createContext('en-US');
      const text = {
        'zh-CN': '收入',
        en: 'Revenue',
      };
      expect(renderText(text, context)).toBe('Revenue');
    });
  });

  describe('$i18n: 简写语法', () => {
    it('应该渲染简单 i18n key', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          greeting: '你好',
        },
      });
      expect(renderText('$i18n:greeting', context)).toBe('你好');
    });

    it('应该在 key 不存在时返回 key 本身', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {},
      });
      expect(renderText('$i18n:missing.key', context)).toBe('missing.key');
    });
  });

  describe('ICU MessageFormat - 变量替换', () => {
    it('应该支持简单变量替换', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          welcome: '欢迎, {name}!',
        },
      });

      const result = renderText({ $i18nKey: 'welcome', vars: { name: '张三' } }, context);

      expect(result).toBe('欢迎, 张三!');
    });

    it('应该支持多个变量', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          userInfo: '{name} 在 {date} 注册',
        },
      });

      const result = renderText(
        {
          $i18nKey: 'userInfo',
          vars: { name: '李四', date: '2025-01-24' },
        },
        context,
      );

      expect(result).toBe('李四 在 2025-01-24 注册');
    });
  });

  describe('ICU MessageFormat - 复数规则 (plural)', () => {
    it('应该支持中文复数规则', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          messages: '你有 {count, plural, =0 {没有消息} other {#条消息}}',
        },
      });

      // 0 条消息
      expect(renderText({ $i18nKey: 'messages', vars: { count: 0 } }, context)).toBe(
        '你有 没有消息',
      );

      // 1 条消息
      expect(renderText({ $i18nKey: 'messages', vars: { count: 1 } }, context)).toBe(
        '你有 1条消息',
      );

      // 多条消息
      expect(renderText({ $i18nKey: 'messages', vars: { count: 5 } }, context)).toBe(
        '你有 5条消息',
      );
    });

    it('应该支持英文复数规则', () => {
      const context = createContext('en-US', {
        'en-US': {
          items: 'You have {count, plural, =0 {no items} one {# item} other {# items}}',
        },
      });

      // 0 items
      expect(renderText({ $i18nKey: 'items', vars: { count: 0 } }, context)).toBe(
        'You have no items',
      );

      // 1 item (singular)
      expect(renderText({ $i18nKey: 'items', vars: { count: 1 } }, context)).toBe(
        'You have 1 item',
      );

      // 5 items (plural)
      expect(renderText({ $i18nKey: 'items', vars: { count: 5 } }, context)).toBe(
        'You have 5 items',
      );
    });

    it('应该支持复杂的复数规则', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          // 中文不区分 one/other，只用 other
          cart: '{count, plural, =0 {购物车是空的} other {购物车有#件商品}}',
        },
      });

      expect(renderText({ $i18nKey: 'cart', vars: { count: 0 } }, context)).toBe('购物车是空的');

      expect(renderText({ $i18nKey: 'cart', vars: { count: 1 } }, context)).toBe('购物车有1件商品');

      expect(renderText({ $i18nKey: 'cart', vars: { count: 10 } }, context)).toBe(
        '购物车有10件商品',
      );
    });
  });

  describe('ICU MessageFormat - 选择规则 (select)', () => {
    it('应该支持性别选择', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          greeting: '{gender, select, male {他} female {她} other {他们}} 已登录',
        },
      });

      expect(renderText({ $i18nKey: 'greeting', vars: { gender: 'male' } }, context)).toBe(
        '他 已登录',
      );

      expect(renderText({ $i18nKey: 'greeting', vars: { gender: 'female' } }, context)).toBe(
        '她 已登录',
      );

      expect(renderText({ $i18nKey: 'greeting', vars: { gender: 'unknown' } }, context)).toBe(
        '他们 已登录',
      );
    });

    it('应该支持状态选择', () => {
      const context = createContext('en-US', {
        'en-US': {
          status:
            'Order is {status, select, pending {pending} shipped {on the way} delivered {delivered} other {unknown}}',
        },
      });

      expect(renderText({ $i18nKey: 'status', vars: { status: 'pending' } }, context)).toBe(
        'Order is pending',
      );

      expect(renderText({ $i18nKey: 'status', vars: { status: 'shipped' } }, context)).toBe(
        'Order is on the way',
      );

      expect(renderText({ $i18nKey: 'status', vars: { status: 'delivered' } }, context)).toBe(
        'Order is delivered',
      );
    });
  });

  describe('ICU MessageFormat - 日期和数字格式化', () => {
    it('应该支持日期格式化', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          appointment: '预约时间: {date, date, ::yMMMd}',
        },
      });

      const date = new Date('2025-01-24');
      const result = renderText({ $i18nKey: 'appointment', vars: { date } }, context);

      // 日期格式化结果可能因环境而异，只检查包含年份
      expect(result).toContain('2025');
    });

    it('应该支持数字格式化', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          price: '价格: {amount, number, ::currency/CNY}',
        },
      });

      const result = renderText({ $i18nKey: 'price', vars: { amount: 1234.56 } }, context);

      // 数字格式化结果可能因环境而异，检查包含逗号分隔的数字
      expect(result).toMatch(/1,234/);
    });
  });

  describe('复杂场景', () => {
    it('应该支持嵌套的复数和选择', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          notification:
            '{gender, select, male {他} female {她} other {他们}}有{count, plural, =0 {没有} other {#条}}新消息',
        },
      });

      expect(
        renderText({ $i18nKey: 'notification', vars: { gender: 'male', count: 0 } }, context),
      ).toBe('他有没有新消息');

      expect(
        renderText({ $i18nKey: 'notification', vars: { gender: 'female', count: 3 } }, context),
      ).toBe('她有3条新消息');
    });

    it('应该支持变量中的表达式求值', () => {
      const contextWithState = {
        ...createContext('zh-CN', {
          'zh-CN': {
            welcome: '欢迎, {name}!',
          },
        }),
        state: { user: { name: '王五' } },
      };

      const result = renderText(
        {
          $i18nKey: 'welcome',
          vars: { name: '${state.user.name}' },
        },
        contextWithState,
      );

      expect(result).toBe('欢迎, 王五!');
    });
  });

  describe('表达式语法', () => {
    it('应该支持 ${} 表达式', () => {
      const context = {
        ...createContext(),
        state: { user: { name: '赵六' } },
      };

      const result = renderText('你好, ${state.user.name}!', context);
      expect(result).toBe('你好, 赵六!');
    });

    it('应该支持多个表达式', () => {
      const context = {
        ...createContext(),
        state: {
          user: { firstName: '三', lastName: '张' },
        },
      };

      const result = renderText('全名: ${state.user.lastName}${state.user.firstName}', context);

      expect(result).toBe('全名: 张三');
    });
  });

  describe('错误处理', () => {
    it('应该在 ICU 格式错误时返回原始模板', () => {
      const context = createContext('zh-CN', {
        'zh-CN': {
          invalid: 'This has {invalid syntax',
        },
      });

      // 格式错误应该返回原始模板而不是抛出错误
      const result = renderText({ $i18nKey: 'invalid', vars: {} }, context);

      expect(result).toBe('This has {invalid syntax');
    });

    it('应该在表达式求值失败时返回空字符串', () => {
      const context = createContext();

      const result = renderText('Value: ${nonexistent.value}', context);

      // 求值失败应该返回空字符串，保留其他文本
      expect(result).toBe('Value: ');
    });
  });
});
