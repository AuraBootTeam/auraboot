/**
 * 表达式求值器
 * 提供安全的表达式计算环境，支持 ${...} 语法和 ICU 格式
 */

import type {
  ActionContext,
  ExpressionEvaluator as IExpressionEvaluator,
} from '~/plugins/core-designer/components/studio/services/runtime/execution/types';

/**
 * 表达式求值器实现
 */
export class ExpressionEvaluator implements IExpressionEvaluator {
  private static instance: ExpressionEvaluator;

  private constructor() {}

  public static getInstance(): ExpressionEvaluator {
    if (!ExpressionEvaluator.instance) {
      ExpressionEvaluator.instance = new ExpressionEvaluator();
    }
    return ExpressionEvaluator.instance;
  }

  /**
   * 静态方法：计算表达式
   */
  public static evaluate(expression: any, context: ActionContext): any {
    return ExpressionEvaluator.getInstance().evaluate(expression, context);
  }

  /**
   * 计算表达式
   */
  public evaluate(expression: any, context: ActionContext): any {
    // 如果不是字符串，直接返回
    if (typeof expression !== 'string') {
      return expression;
    }

    // 检查是否是表达式（以 {{ 开头，}} 结尾）
    if (!this.isExpression(expression)) {
      return expression;
    }

    try {
      // 提取表达式内容
      const expressionContent = this.extractExpression(expression);

      // 创建安全的执行环境
      const safeContext = this.createSafeContext(context);

      // 执行表达式
      return this.executeExpression(expressionContent, safeContext);
    } catch (error) {
      console.error('Expression evaluation error:', error);
      return expression; // 出错时返回原始值
    }
  }

  /**
   * 检查是否是表达式
   */
  private isExpression(value: string): boolean {
    // 支持 {{...}} 和 ${...} 两种语法
    return (
      (value.startsWith('{{') && value.endsWith('}}')) ||
      (value.startsWith('${') && value.endsWith('}'))
    );
  }

  /**
   * 提取表达式内容
   */
  private extractExpression(expression: string): string {
    if (expression.startsWith('{{') && expression.endsWith('}}')) {
      return expression.slice(2, -2).trim();
    } else if (expression.startsWith('${') && expression.endsWith('}')) {
      return expression.slice(2, -1).trim();
    }
    return expression;
  }

  /**
   * 创建安全的执行环境
   */
  private createSafeContext(context: ActionContext): Record<string, any> {
    return {
      // 只读上下文变量（按照规范要求）
      $state: context.pageState || {},
      $route: context.route || {},
      $user: context.user || {},
      $utils: this.createUtilityFunctions(),
      $vars: context.vars || {},

      // 兼容旧版本的变量名
      state: context.pageState || {},
      componentState: context.componentState || {},
      formData: context.formData || {},
      event: context.eventData || {},
      user: context.user || {},
      env: context.env || {},
      utils: this.createUtilityFunctions(),

      // 数学函数（白名单）
      Math: {
        abs: Math.abs,
        ceil: Math.ceil,
        floor: Math.floor,
        round: Math.round,
        max: Math.max,
        min: Math.min,
        random: Math.random,
        pow: Math.pow,
        sqrt: Math.sqrt,
        PI: Math.PI,
        E: Math.E,
      },

      // 日期函数（白名单）
      Date: {
        now: Date.now,
        parse: Date.parse,
      },

      // 字符串函数（白名单）
      String: {
        fromCharCode: String.fromCharCode,
      },

      // 数组函数（白名单）
      Array: {
        isArray: Array.isArray,
      },

      // JSON 函数（白名单）
      JSON: {
        parse: JSON.parse,
        stringify: JSON.stringify,
      },
    };
  }

  /**
   * 创建工具函数
   */
  private createUtilityFunctions(): Record<string, any> {
    return {
      // 字符串工具
      isEmpty: (value: any) => value == null || value === '',
      isNotEmpty: (value: any) => value != null && value !== '',
      trim: (str: string) => str?.trim?.() || '',
      toLowerCase: (str: string) => str?.toLowerCase?.() || '',
      toUpperCase: (str: string) => str?.toUpperCase?.() || '',
      substring: (str: string, start: number, end?: number) => str?.substring?.(start, end) || '',
      replace: (str: string, search: string, replace: string) =>
        str?.replace?.(search, replace) || '',

      // 数组工具
      length: (arr: any[]) => (Array.isArray(arr) ? arr.length : 0),
      includes: (arr: any[], item: any) => (Array.isArray(arr) ? arr.includes(item) : false),
      join: (arr: any[], separator: string) => (Array.isArray(arr) ? arr.join(separator) : ''),
      slice: (arr: any[], start: number, end?: number) =>
        Array.isArray(arr) ? arr.slice(start, end) : [],

      // 对象工具
      keys: (obj: any) => (obj && typeof obj === 'object' ? Object.keys(obj) : []),
      values: (obj: any) => (obj && typeof obj === 'object' ? Object.values(obj) : []),
      hasProperty: (obj: any, key: string) =>
        obj && typeof obj === 'object' ? obj.hasOwnProperty(key) : false,

      // 类型检查
      isString: (value: any) => typeof value === 'string',
      isNumber: (value: any) => typeof value === 'number' && !isNaN(value),
      isBoolean: (value: any) => typeof value === 'boolean',
      isObject: (value: any) =>
        value !== null && typeof value === 'object' && !Array.isArray(value),
      isArray: (value: any) => Array.isArray(value),
      isNull: (value: any) => value === null,
      isUndefined: (value: any) => value === undefined,

      // 数值工具
      toNumber: (value: any) => {
        const num = Number(value);
        return isNaN(num) ? 0 : num;
      },
      toString: (value: any) => String(value),
      toBoolean: (value: any) => Boolean(value),

      // 日期工具
      formatDate: (date: Date | string | number, format: string = 'YYYY-MM-DD') => {
        const d = new Date(date);
        if (isNaN(d.getTime())) return '';

        const year = d.getFullYear();
        const month = String(d.getMonth() + 1).padStart(2, '0');
        const day = String(d.getDate()).padStart(2, '0');
        const hours = String(d.getHours()).padStart(2, '0');
        const minutes = String(d.getMinutes()).padStart(2, '0');
        const seconds = String(d.getSeconds()).padStart(2, '0');

        return format
          .replace('yyyy', String(year))
          .replace('MM', month)
          .replace('DD', day)
          .replace('HH', hours)
          .replace('mm', minutes)
          .replace('ss', seconds);
      },

      // 条件工具
      if: (condition: any, trueValue: any, falseValue: any) => (condition ? trueValue : falseValue),

      // URL 工具
      encodeURI: encodeURIComponent,
      decodeURI: decodeURIComponent,
    };
  }

  /**
   * 执行表达式（带安全限制和超时控制）
   */
  private executeExpression(expression: string, context: Record<string, any>): any {
    try {
      // 安全检查：禁止危险操作
      if (this.containsDangerousCode(expression)) {
        throw new Error('表达式包含不安全的代码');
      }

      // 创建函数来执行表达式
      const func = new Function(
        ...Object.keys(context),
        `
        "use strict";
        return (${expression});
      `,
      );

      // 设置执行超时（5秒）
      const timeout = 5000;
      const startTime = Date.now();

      // 执行函数
      const result = func(...Object.values(context));

      // 检查执行时间
      if (Date.now() - startTime > timeout) {
        throw new Error('表达式执行超时');
      }

      return result;
    } catch (error) {
      console.error('Expression execution error:', error);
      throw error;
    }
  }

  /**
   * 检查是否包含危险代码
   */
  private containsDangerousCode(expression: string): boolean {
    const dangerousPatterns = [
      /\beval\b/,
      /\bFunction\b/,
      /\bsetTimeout\b/,
      /\bsetInterval\b/,
      /\bsetImmediate\b/,
      /\bprocess\b/,
      /\brequire\b/,
      /\bimport\b/,
      /\bexport\b/,
      /\bglobal\b/,
      /\bwindow\b/,
      /\bdocument\b/,
      /\blocation\b/,
      /\bnavigator\b/,
      /\bhistory\b/,
      /\blocalStorage\b/,
      /\bsessionStorage\b/,
      /\bfetch\b/,
      /\bXMLHttpRequest\b/,
      /\bWebSocket\b/,
      /\b__proto__\b/,
      /\bconstructor\b/,
      /\bprototype\b/,
      /\bdelete\b/,
      /\bthis\b/,
    ];

    return dangerousPatterns.some((pattern) => pattern.test(expression));
  }

  /**
   * 验证表达式语法
   */
  public validateExpression(expression: string): { valid: boolean; error?: string } {
    if (!this.isExpression(expression)) {
      return { valid: true };
    }

    try {
      const expressionContent = this.extractExpression(expression);

      // 尝试创建函数来验证语法
      new Function(`return (${expressionContent})`);

      return { valid: true };
    } catch (error) {
      return {
        valid: false,
        error: error instanceof Error ? error.message : '表达式语法错误',
      };
    }
  }

  /**
   * 提取表达式中的变量
   */
  public extractVariables(expression: string): string[] {
    if (!this.isExpression(expression)) {
      return [];
    }

    const expressionContent = this.extractExpression(expression);
    const variables: string[] = [];

    // 简单的变量提取（可以进一步优化）
    const variableRegex = /\b([a-zA-Z_$][a-zA-Z0-9_$]*(?:\.[a-zA-Z_$][a-zA-Z0-9_$]*)*)\b/g;
    let match;

    while ((match = variableRegex.exec(expressionContent)) !== null) {
      const variable = match[1];

      // 排除 JavaScript 关键字和内置对象
      if (!this.isReservedWord(variable) && !variables.includes(variable)) {
        variables.push(variable);
      }
    }

    return variables;
  }

  /**
   * 检查是否是保留字
   */
  private isReservedWord(word: string): boolean {
    const reservedWords = [
      'true',
      'false',
      'null',
      'undefined',
      'Math',
      'Date',
      'String',
      'Array',
      'Object',
      'if',
      'else',
      'for',
      'while',
      'do',
      'switch',
      'case',
      'default',
      'function',
      'return',
      'var',
      'let',
      'const',
      'typeof',
      'instanceof',
      'new',
      'this',
      'utils',
    ];

    return reservedWords.includes(word) || word.startsWith('utils.');
  }

  /**
   * 批量计算表达式
   */
  public evaluateBatch(
    expressions: Record<string, any>,
    context: ActionContext,
  ): Record<string, any> {
    const result: Record<string, any> = {};

    for (const [key, expression] of Object.entries(expressions)) {
      result[key] = this.evaluate(expression, context);
    }

    return result;
  }

  /**
   * 创建表达式模板（支持 ICU 格式）
   */
  public createTemplate(template: string, context: ActionContext): string {
    // 处理 ${...} 表达式
    let result = template.replace(/\$\{([^}]+)\}/g, (match, expression) => {
      try {
        const evalResult = this.evaluate(`\${${expression}}`, context);
        return String(evalResult);
      } catch (error) {
        console.error('Template expression error:', error);
        return match; // 出错时保留原始表达式
      }
    });

    // 处理 {{...}} 表达式
    result = result.replace(/\{\{([^}]+)\}\}/g, (match, expression) => {
      try {
        const evalResult = this.evaluate(`{{${expression}}}`, context);
        return String(evalResult);
      } catch (error) {
        console.error('Template expression error:', error);
        return match; // 出错时保留原始表达式
      }
    });

    // 处理 ICU 格式（简单实现）
    result = this.processICUFormat(result, context);

    return result;
  }

  /**
   * 处理 ICU 格式
   */
  private processICUFormat(template: string, context: ActionContext): string {
    // 处理复数格式：{count, plural, =0 {no items} =1 {one item} other {# items}}
    const pluralRegex = /\{(\w+),\s*plural,\s*([^}]+)\}/g;

    return template.replace(pluralRegex, (match, variable, rules) => {
      try {
        const value = this.evaluate(`\${${variable}}`, context);
        const numValue = Number(value);

        if (isNaN(numValue)) {
          return match;
        }

        // 解析规则
        const ruleMatches = rules.match(/(=\d+|zero|one|two|few|many|other)\s*\{([^}]*)\}/g);
        if (!ruleMatches) {
          return match;
        }

        // 查找匹配的规则
        for (const ruleMatch of ruleMatches) {
          const ruleRegex = /(=\d+|zero|one|two|few|many|other)\s*\{([^}]*)\}/;
          const ruleResult = ruleMatch.match(ruleRegex);

          if (!ruleResult) continue;

          const [, condition, text] = ruleResult;

          if (condition.startsWith('=')) {
            const exactValue = parseInt(condition.slice(1));
            if (numValue === exactValue) {
              return text.replace(/#/g, String(numValue));
            }
          } else if (condition === 'other') {
            return text.replace(/#/g, String(numValue));
          } else if (condition === 'one' && numValue === 1) {
            return text.replace(/#/g, String(numValue));
          } else if (condition === 'zero' && numValue === 0) {
            return text.replace(/#/g, String(numValue));
          }
        }

        return match;
      } catch (error) {
        console.error('ICU format error:', error);
        return match;
      }
    });
  }
  /**
   * 接口实现：验证表达式
   */
  public validate(expression: string): { valid: boolean; error?: string } {
    return this.validateExpression(expression);
  }

  /**
   * 接口实现：获取可用变量
   */
  public getAvailableVariables(context: ActionContext): string[] {
    const safeContext = this.createSafeContext(context);
    return Object.keys(safeContext);
  }

  /**
   * 接口实现：获取可用函数
   */
  public getAvailableFunctions(): string[] {
    // 创建一个空上下文来获取默认工具函数
    const safeContext = this.createSafeContext({
      pageState: {},
      globalState: {},
      env: {},
      utils: {} as any,
      componentId: 'temp',
      pageId: 'temp',
    } as ActionContext);

    const utils = safeContext.$utils || {};
    return Object.keys(utils);
  }
}
