/**
 * Expression Evaluator - 表达式求值器
 * 支持 ${} 和 {{}} 两种语法
 */

import { ExpressionParser } from '~/framework/meta/runtime/expression/parser';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';

/**
 * 表达式求值器类
 */
export class ExpressionEvaluator {
  /**
   * 求值单个表达式 ${expression}
   */
  evaluate(expr: string, context: ExpressionContext): any {
    if (!expr || typeof expr !== 'string') {
      return expr;
    }

    // 移除 ${} 包装
    const unwrapped = expr.trim();
    let expression = unwrapped;

    if (unwrapped.startsWith('${') && unwrapped.endsWith('}')) {
      expression = unwrapped.slice(2, -1).trim();
    }

    try {
      // 使用现有的 expression-parser
      return ExpressionParser.parse(expression, context);
    } catch (error) {
      console.error('Expression evaluation failed:', expression, error);
      return undefined;
    }
  }

  /**
   * 双向绑定表达式 {{expression}}
   * 返回绑定路径和当前值
   *
   * 支持路径别名：state.form → form (兼容旧 DSL 写法)
   */
  bind(template: string, context: ExpressionContext): { path: string; value: any } | any {
    if (!template || typeof template !== 'string') {
      return template;
    }

    const unwrapped = template.trim();

    // {{}} 语法用于数据绑定
    if (unwrapped.startsWith('{{') && unwrapped.endsWith('}}')) {
      let path = unwrapped.slice(2, -2).trim();

      // 路径别名：兼容旧 DSL 中的 state.form 写法
      // ExpressionContext 结构中 form 是顶层属性，不在 state 下
      if (path === 'state.form') {
        path = 'form';
      }

      const value = this.evaluate(`\${${path}}`, context);
      return { path, value };
    }

    // 非绑定表达式，直接求值
    return this.evaluate(template, context);
  }

  /**
   * 求值包含多个插值的字符串模板
   * 例如: "Hello ${user.name}, you have ${count} messages"
   */
  evaluateTemplate(template: string, context: ExpressionContext): string {
    if (!template || typeof template !== 'string') {
      return String(template || '');
    }

    // 替换所有 ${...} 表达式
    return template.replace(/\$\{([^}]+)\}/g, (match, expr) => {
      try {
        const value = this.evaluate(expr, context);
        return String(value ?? '');
      } catch (error) {
        console.error('Template expression evaluation failed:', expr, error);
        return match;
      }
    });
  }

  /**
   * 求值对象中的所有表达式
   */
  evaluateObject<T extends Record<string, any>>(obj: T, context: ExpressionContext): T {
    if (!obj || typeof obj !== 'object') {
      return obj;
    }

    const result: any = Array.isArray(obj) ? [] : {};

    for (const [key, value] of Object.entries(obj)) {
      if (typeof value === 'string') {
        if (value.startsWith('${') && value.endsWith('}')) {
          result[key] = this.evaluate(value, context);
        } else if (value.includes('${')) {
          result[key] = this.evaluateTemplate(value, context);
        } else {
          result[key] = value;
        }
      } else if (typeof value === 'object' && value !== null) {
        result[key] = this.evaluateObject(value, context);
      } else {
        result[key] = value;
      }
    }

    return result;
  }

  /**
   * 检查条件表达式是否为真
   */
  evaluateCondition(condition: string | undefined, context: ExpressionContext): boolean {
    if (!condition) {
      return true;
    }

    try {
      const result = this.evaluate(condition, context);
      return Boolean(result);
    } catch (error) {
      console.error('Condition evaluation failed:', condition, error);
      return false;
    }
  }
}

/**
 * 全局单例
 */
export const expressionEvaluator = new ExpressionEvaluator();

/**
 * 便捷函数
 */
export function evaluate(expr: string, context: ExpressionContext): any {
  return expressionEvaluator.evaluate(expr, context);
}

export function evaluateTemplate(template: string, context: ExpressionContext): string {
  return expressionEvaluator.evaluateTemplate(template, context);
}

export function evaluateCondition(
  condition: string | undefined,
  context: ExpressionContext,
): boolean {
  return expressionEvaluator.evaluateCondition(condition, context);
}

export function bind(template: string, context: ExpressionContext) {
  return expressionEvaluator.bind(template, context);
}
