/**
 * AuraBoot 低代码平台 - 表达式解析器
 *
 * 支持解析和求值各种表达式，包括：
 * - JavaScript 表达式：${form.name}、${user.id}、${form.status === 'active'}
 * - 函数调用（白名单 utils）：${utils.formatDate(form.createdAt)}
 * - 可选链/空合并：${form.owner?.name ?? '—'}
 * - ICU MessageFormat：{count, plural, one {# item} other {# items}}
 * - 支持替换label中的表达式: 确认删除${count} 个吗?
 */

import jsep from 'jsep';
import MessageFormat from '@messageformat/core';
import type { ExpressionContext } from '~/meta/runtime/expression/context';
import { BUSINESS_FUNCTION_NAMES } from './business-functions';

// ==================== Security Configuration ====================

/**
 * Safe built-in functions that can be called in expressions.
 * These functions are provided by ExpressionContext or are safe JavaScript built-ins.
 */
export const SAFE_FUNCTIONS = new Set([
  // Context built-in functions
  'hasPermission',
  'hasRole',
  'formatDate',
  'formatCurrency',
  't',

  // Safe string methods
  'includes',
  'startsWith',
  'endsWith',
  'trim',
  'toUpperCase',
  'toLowerCase',
  'split',
  'join',
  'replace',
  'substring',
  'slice',
  'charAt',
  'indexOf',
  'lastIndexOf',
  'padStart',
  'padEnd',

  // Safe array methods
  'length',
  'filter',
  'map',
  'find',
  'findIndex',
  'some',
  'every',
  'concat',
  'reverse',
  'sort',
  'flat',
  'flatMap',

  // Safe Math functions (accessed via utils.Math or context)
  'abs',
  'round',
  'floor',
  'ceil',
  'min',
  'max',
  'pow',
  'sqrt',

  // Safe Date functions
  'now',
  'getTime',
  'getFullYear',
  'getMonth',
  'getDate',
  'getDay',
  'getHours',
  'getMinutes',
  'getSeconds',

  // Safe JSON functions (via utils)
  'stringify',
  'parse',

  // Safe type checking
  'isArray',
  'isNaN',
  'isFinite',
  'parseInt',
  'parseFloat',
  'toString',
  'valueOf',

  // Business functions (fn.* namespace)
  ...BUSINESS_FUNCTION_NAMES,
]);

/**
 * Forbidden global identifiers that must never be accessed.
 * Attempting to access these will throw a security error.
 */
export const FORBIDDEN_GLOBALS = new Set([
  // Browser globals
  'window',
  'document',
  'self',
  'top',
  'parent',
  'frames',
  'opener',
  'location',
  'navigator',
  'history',
  'screen',

  // Code execution
  'eval',
  'Function',
  'AsyncFunction',
  'GeneratorFunction',

  // Network access
  'fetch',
  'XMLHttpRequest',
  'WebSocket',
  'EventSource',
  'Request',
  'Response',

  // Storage
  'localStorage',
  'sessionStorage',
  'indexedDB',
  'caches',

  // Dangerous constructors
  'Proxy',
  'Reflect',
  'Symbol',
  'WeakMap',
  'WeakSet',
  'WeakRef',
  'FinalizationRegistry',

  // Process/Node.js
  'process',
  'require',
  'module',
  'exports',
  '__dirname',
  '__filename',
  'global',
  'globalThis',

  // Dangerous objects
  'cookie',
  'cookies',
  'credentials',
  'crypto',
  'Crypto',
  'SubtleCrypto',

  // DOM manipulation
  'Element',
  'Node',
  'HTMLElement',
  'DocumentFragment',
  'MutationObserver',
  'IntersectionObserver',
  'ResizeObserver',

  // Timers (can be used for DoS)
  'setTimeout',
  'setInterval',
  'setImmediate',
  'requestAnimationFrame',
  'requestIdleCallback',

  // Workers
  'Worker',
  'SharedWorker',
  'ServiceWorker',

  // Other dangerous APIs
  'Blob',
  'File',
  'FileReader',
  'FileList',
  'FormData',
  'url',
  'URLSearchParams',
  'atob',
  'btoa',
  'importScripts',
]);

/**
 * 表达式解析错误类型
 */
export class ExpressionError extends Error {
  constructor(
    message: string,
    public readonly expression: string,
    public readonly position?: number,
    public readonly cause?: Error,
  ) {
    super(message);
    this.name = 'ExpressionError';
  }
}

/**
 * Security violation error - thrown when expression attempts forbidden access
 */
export class ExpressionSecurityError extends ExpressionError {
  constructor(
    message: string,
    expression: string,
    public readonly violationType: 'forbidden_global' | 'unsafe_function' | 'unsafe_property',
  ) {
    super(`安全错误: ${message}`, expression);
    this.name = 'ExpressionSecurityError';
  }
}

/**
 * 表达式语法错误
 */
export class ExpressionSyntaxError extends ExpressionError {
  constructor(message: string, expression: string, position?: number) {
    super(`语法错误: ${message}`, expression, position);
    this.name = 'ExpressionSyntaxError';
  }
}

/**
 * 表达式运行时错误
 */
export class ExpressionRuntimeError extends ExpressionError {
  constructor(message: string, expression: string, cause?: Error) {
    super(`运行时错误: ${message}`, expression, undefined, cause);
    this.name = 'ExpressionRuntimeError';
  }
}

/**
 * 表达式类型枚举
 */
enum ExpressionType {
  JAVASCRIPT = 'javascript', // ${...} 语法
  ICU_MESSAGE = 'icu_message', // {...} 语法
}

/**
 * 表达式解析结果
 */
interface ParsedExpression {
  type: ExpressionType;
  content: string;
  ast?: jsep.Expression;
  messageFormat?: MessageFormat;
}

/**
 * 表达式解析器类
 */
export class ExpressionParser {
  private context: ExpressionContext;
  private cache = new Map<string, ParsedExpression>();
  private messageFormatCache = new Map<string, MessageFormat>();

  constructor(context: ExpressionContext) {
    this.context = context;

    // 配置 jsep 支持的操作符
    this.configureJsep();
  }

  /**
   * 配置 jsep 解析器
   */
  private configureJsep(): void {
    // 添加自定义操作符支持
    jsep.addBinaryOp('===', 9);
    jsep.addBinaryOp('!==', 9);
    jsep.addBinaryOp('??', 3); // 空值合并操作符

    // 注意：可选链操作符 ?. 需要在 evaluateMemberExpression 中特殊处理
    // jsep 本身不直接支持 ?. 操作符，我们将在成员表达式求值时处理
  }

  // 静态方法用于向后兼容
  static parse(expression: any, context: ExpressionContext): any {
    const parser = new ExpressionParser(context);
    return parser.evaluate(expression);
  }

  static evaluate(expression: any, context: ExpressionContext): any {
    const parser = new ExpressionParser(context);
    return parser.evaluate(expression);
  }

  /**
   * 更新上下文
   */
  updateContext(context: Partial<ExpressionContext>): void {
    this.context = { ...this.context, ...context };
  }

  /**
   * 解析表达式字符串
   */
  parse(expression: any): ParsedExpression {
    // 先转换为字符串用于缓存键
    const cacheKey = String(expression);

    // 检查缓存
    if (this.cache.has(cacheKey)) {
      return this.cache.get(cacheKey)!;
    }

    try {
      const parsed = this.parseExpressionType(expression);

      if (parsed.type === ExpressionType.JAVASCRIPT) {
        // 如果是混合表达式，不需要解析 AST
        if (!parsed.content.includes('${') || parsed.content.startsWith('${')) {
          // 使用 jsep 解析 JavaScript 表达式
          parsed.ast = jsep(parsed.content);
        }
      } else if (parsed.type === ExpressionType.ICU_MESSAGE) {
        // 使用 MessageFormat 解析 ICU 消息
        if (!this.messageFormatCache.has(parsed.content)) {
          const mf = new MessageFormat('zh-CN'); // 默认中文
          this.messageFormatCache.set(parsed.content, mf);
        }
        parsed.messageFormat = this.messageFormatCache.get(parsed.content)!;
      }

      // 缓存结果
      this.cache.set(cacheKey, parsed);

      return parsed;
    } catch (error) {
      if (error instanceof ExpressionError) {
        throw error;
      }
      throw new ExpressionSyntaxError(
        `解析失败: ${error instanceof Error ? error.message : String(error)}`,
        cacheKey,
      );
    }
  }

  /**
   * 求值表达式
   */
  evaluate(expression: any): any {
    const expressionStr = String(expression);

    try {
      const parsed = this.parse(expression);

      if (parsed.type === ExpressionType.JAVASCRIPT) {
        return this.evaluateJavaScript(parsed.ast, expressionStr);
      } else if (parsed.type === ExpressionType.ICU_MESSAGE) {
        return this.evaluateICUMessage(parsed.content, expressionStr);
      }

      return parsed.content; // 普通字符串直接返回
    } catch (error) {
      if (error instanceof ExpressionError) {
        throw error;
      }
      throw new ExpressionRuntimeError(
        `求值失败: ${error instanceof Error ? error.message : String(error)}`,
        expressionStr,
        error instanceof Error ? error : undefined,
      );
    }
  }

  /**
   * 批量求值多个表达式
   */
  evaluateMultiple(expressions: string[]): any[] {
    return expressions.map((expr) => this.evaluate(expr));
  }

  /**
   * 检查表达式是否有效
   */
  isValid(expression: any): boolean {
    try {
      this.parse(expression);
      return true;
    } catch {
      return false;
    }
  }

  /**
   * 清理缓存
   */
  clearCache(): void {
    this.cache.clear();
    this.messageFormatCache.clear();
  }

  /**
   * 获取缓存统计信息
   */
  getCacheStats(): { size: number; maxSize: number } {
    return {
      size: this.cache.size + this.messageFormatCache.size,
      maxSize: 1000, // 可配置的最大缓存大小
    };
  }

  /**
   * 解析表达式类型和内容
   */
  private parseExpressionType(expression: any): ParsedExpression {
    // 类型检查和转换
    let expressionStr: string;

    if (expression === null || expression === undefined) {
      expressionStr = '';
    } else if (typeof expression === 'string') {
      expressionStr = expression;
    } else if (typeof expression === 'number' || typeof expression === 'boolean') {
      expressionStr = String(expression);
    } else if (typeof expression === 'object') {
      try {
        expressionStr = JSON.stringify(expression);
      } catch {
        expressionStr = String(expression);
      }
    } else {
      expressionStr = String(expression);
    }

    const trimmed = expressionStr.trim();

    // JavaScript 表达式：${...}
    if (trimmed.startsWith('${') && trimmed.endsWith('}')) {
      return {
        type: ExpressionType.JAVASCRIPT,
        content: trimmed.slice(2, -1).trim(),
      };
    }

    // ICU MessageFormat：{...}
    if (trimmed.startsWith('{') && trimmed.endsWith('}')) {
      return {
        type: ExpressionType.ICU_MESSAGE,
        content: trimmed,
      };
    }

    // 检查是否包含 ${...} 表达式的混合文本
    if (trimmed.includes('${')) {
      return {
        type: ExpressionType.JAVASCRIPT,
        content: trimmed,
      };
    }

    // 普通字符串
    return {
      type: ExpressionType.JAVASCRIPT,
      content: trimmed,
    };
  }

  /**
   * 求值 JavaScript 表达式
   */
  private evaluateJavaScript(ast: jsep.Expression | undefined, originalExpression: string): any {
    try {
      // 如果是混合文本（包含 ${...} 表达式），需要特殊处理
      if (originalExpression.includes('${') && !originalExpression.startsWith('${')) {
        return this.evaluateMixedExpression(originalExpression);
      }

      if (!ast) {
        throw new Error('AST 为空');
      }

      return this.evaluateNode(ast);
    } catch (error) {
      // Security errors should be re-thrown without wrapping
      if (error instanceof ExpressionSecurityError) {
        throw error;
      }
      throw new ExpressionRuntimeError(
        `JavaScript 表达式求值失败: ${error instanceof Error ? error.message : String(error)}`,
        originalExpression,
        error instanceof Error ? error : undefined,
      );
    }
  }

  /**
   * 求值混合表达式（包含文本和 ${...} 表达式）
   */
  private evaluateMixedExpression(expression: string): string {
    return expression.replace(/\$\{([^}]+)\}/g, (match, code) => {
      try {
        const ast = jsep(code.trim());
        const result = this.evaluateNode(ast);
        return String(result);
      } catch (error) {
        throw new ExpressionRuntimeError(
          `混合表达式中的 JavaScript 代码求值失败: ${error instanceof Error ? error.message : String(error)}`,
          expression,
          error instanceof Error ? error : undefined,
        );
      }
    });
  }

  /**
   * 求值 ICU MessageFormat
   */
  private evaluateICUMessage(content: string, originalExpression: string): any {
    try {
      const mf = new MessageFormat('zh-CN');
      const compiled = mf.compile(content);
      return compiled(this.context);
    } catch (error) {
      throw new ExpressionRuntimeError(
        `ICU MessageFormat 求值失败: ${error instanceof Error ? error.message : String(error)}`,
        originalExpression,
        error instanceof Error ? error : undefined,
      );
    }
  }

  /**
   * 递归求值 AST 节点
   */
  private evaluateNode(node: jsep.Expression): any {
    switch (node.type) {
      case 'Literal':
        return (node as jsep.Literal).value;

      case 'Identifier':
        return this.evaluateIdentifier(node as jsep.Identifier);

      case 'MemberExpression':
        return this.evaluateMemberExpression(node as jsep.MemberExpression);

      case 'CallExpression':
        return this.evaluateCallExpression(node as jsep.CallExpression);

      case 'BinaryExpression':
        return this.evaluateBinaryExpression(node as jsep.BinaryExpression);

      case 'UnaryExpression':
        return this.evaluateUnaryExpression(node as jsep.UnaryExpression);

      case 'LogicalExpression':
        return this.evaluateLogicalExpression(node as any);

      case 'ConditionalExpression':
        return this.evaluateConditionalExpression(node as jsep.ConditionalExpression);

      case 'ArrayExpression':
        return this.evaluateArrayExpression(node as jsep.ArrayExpression);

      default:
        throw new Error(`不支持的节点类型: ${node.type}`);
    }
  }

  /**
   * 求值标识符节点
   * Security: Checks against FORBIDDEN_GLOBALS before allowing access
   * Note: Context properties take precedence over forbidden globals check
   */
  private evaluateIdentifier(node: jsep.Identifier): any {
    const name = node.name;

    // 检查 JavaScript 字面量
    const globalVars: Record<string, any> = {
      true: true,
      false: false,
      null: null,
      undefined: undefined,
    };

    if (name in globalVars) {
      return globalVars[name];
    }

    // 检查 utils 白名单函数
    if (name === 'utils' && 'utils' in this.context) {
      return this.context.utils;
    }

    // 检查 Math 对象（提供安全的数学函数）
    if (name === 'Math') {
      return this.createSafeMathProxy();
    }

    // 检查 JSON 对象（提供安全的 JSON 函数）
    if (name === 'json') {
      return this.createSafeJSONProxy();
    }

    // 直接从上下文中查找标识符（上下文属性优先于禁止列表）
    if (name in this.context) {
      return this.context[name as keyof ExpressionContext];
    }

    // 向后兼容：支持 $ 前缀的变量
    if (name.startsWith('$')) {
      const contextKey = name.slice(1);
      if (contextKey in this.context) {
        return this.context[contextKey as keyof ExpressionContext];
      }
    }

    // SECURITY CHECK: Block forbidden global identifiers
    // This check is done AFTER context lookup to allow context properties like 'global'
    if (FORBIDDEN_GLOBALS.has(name)) {
      throw new ExpressionSecurityError(`禁止访问危险标识符: ${name}`, name, 'forbidden_global');
    }

    throw new Error(`未定义的标识符: ${name}`);
  }

  /**
   * Create a safe Math proxy that only exposes whitelisted methods
   */
  private createSafeMathProxy(): Record<string, (...args: number[]) => number> {
    const safeMathMethods = [
      'abs',
      'round',
      'floor',
      'ceil',
      'min',
      'max',
      'pow',
      'sqrt',
      'sign',
      'trunc',
    ];
    const proxy: Record<string, (...args: number[]) => number> = {};
    for (const method of safeMathMethods) {
      if (typeof (Math as any)[method] === 'function') {
        proxy[method] = (Math as any)[method].bind(Math);
      }
    }
    return proxy;
  }

  /**
   * Create a safe JSON proxy that only exposes stringify and parse
   */
  private createSafeJSONProxy(): { stringify: typeof JSON.stringify; parse: typeof JSON.parse } {
    return {
      stringify: (value: any) => JSON.stringify(value),
      parse: (text: string) => JSON.parse(text),
    };
  }

  /**
   * 危险属性名列表 - 可用于原型链攻击或绕过安全限制
   */
  private static readonly DANGEROUS_PROPERTIES = new Set([
    'constructor',
    '__proto__',
    'prototype',
    '__defineGetter__',
    '__defineSetter__',
    '__lookupGetter__',
    '__lookupSetter__',
    'caller',
    'callee',
    'arguments',
  ]);

  /**
   * 求值成员表达式节点
   * 支持可选链操作符 ?.
   * Security: Blocks access to dangerous properties
   */
  private evaluateMemberExpression(node: jsep.MemberExpression): any {
    // 检查是否是可选链操作符（通过 node.optional 属性检测）
    const isOptionalChaining = (node as any).optional === true;

    const object = this.evaluateNode(node.object);

    // 如果是可选链且对象为 null 或 undefined，直接返回 undefined
    if (isOptionalChaining && object == null) {
      return undefined;
    }

    // 如果不是可选链且对象为 null 或 undefined，返回 undefined 而不是抛出错误
    // 这样可以支持类似 form.mode === 'edit' 的表达式，即使 form 未初始化
    if (!isOptionalChaining && object == null) {
      console.warn(`[ExpressionParser] 尝试访问 null 或 undefined 的属性，返回 undefined`);
      return undefined;
    }

    let property: string | number;
    if (node.computed) {
      property = this.evaluateNode(node.property);
    } else {
      property = (node.property as jsep.Identifier).name;
    }

    // SECURITY CHECK: Block access to dangerous properties
    if (typeof property === 'string' && ExpressionParser.DANGEROUS_PROPERTIES.has(property)) {
      throw new ExpressionSecurityError(
        `禁止访问危险属性: ${property}`,
        String(property),
        'unsafe_property',
      );
    }

    return object[property];
  }

  /**
   * 求值函数调用节点
   * Security: Validates function calls against whitelist
   */
  private evaluateCallExpression(node: jsep.CallExpression): any {
    // SECURITY CHECK: Extract function name for validation
    const functionName = this.extractFunctionName(node.callee);

    // Check if function name is in FORBIDDEN_GLOBALS (e.g., eval(), Function())
    if (functionName && FORBIDDEN_GLOBALS.has(functionName)) {
      throw new ExpressionSecurityError(
        `禁止调用危险函数: ${functionName}`,
        functionName,
        'unsafe_function',
      );
    }

    // For method calls (obj.method()), we need to preserve `this` binding
    let thisArg: any = null;
    let callee: any;

    if (node.callee.type === 'MemberExpression') {
      const memberExpr = node.callee as jsep.MemberExpression;
      thisArg = this.evaluateNode(memberExpr.object);

      // Get the method from the object
      let property: string | number;
      if (memberExpr.computed) {
        property = this.evaluateNode(memberExpr.property);
      } else {
        property = (memberExpr.property as jsep.Identifier).name;
      }

      // SECURITY CHECK: Block dangerous property access
      if (typeof property === 'string' && ExpressionParser.DANGEROUS_PROPERTIES.has(property)) {
        throw new ExpressionSecurityError(
          `禁止访问危险属性: ${property}`,
          String(property),
          'unsafe_property',
        );
      }

      callee = thisArg?.[property];
    } else {
      callee = this.evaluateNode(node.callee);
    }

    if (typeof callee !== 'function') {
      throw new Error('尝试调用非函数值');
    }

    const args = node.arguments.map((arg: jsep.Expression) => this.evaluateNode(arg));

    // Execute the function call safely with proper `this` binding
    try {
      return callee.apply(thisArg, args);
    } catch (error) {
      throw new ExpressionRuntimeError(
        `函数调用失败: ${functionName || 'unknown'} - ${error instanceof Error ? error.message : String(error)}`,
        String(node),
        error instanceof Error ? error : undefined,
      );
    }
  }

  /**
   * Extract function name from callee node for security validation
   */
  private extractFunctionName(callee: jsep.Expression): string | null {
    if (callee.type === 'Identifier') {
      return (callee as jsep.Identifier).name;
    }
    if (callee.type === 'MemberExpression') {
      const member = callee as jsep.MemberExpression;
      if (!member.computed && member.property.type === 'Identifier') {
        return (member.property as jsep.Identifier).name;
      }
    }
    return null;
  }

  /**
   * 求值二元表达式节点
   */
  private evaluateBinaryExpression(node: jsep.BinaryExpression): any {
    const left = this.evaluateNode(node.left);
    const right = this.evaluateNode(node.right);

    switch (node.operator) {
      case '+':
        return left + right;
      case '-':
        return left - right;
      case '*':
        return left * right;
      case '/':
        return left / right;
      case '%':
        return left % right;
      case '==':
        return left == right;
      case '!=':
        return left != right;
      case '===':
        return left === right;
      case '!==':
        return left !== right;
      case '<':
        return left < right;
      case '<=':
        return left <= right;
      case '>':
        return left > right;
      case '>=':
        return left >= right;
      case '??':
        return left ?? right;
      case '&&':
        return left && right;
      case '||':
        return left || right;
      default:
        throw new Error(`不支持的二元操作符: ${node.operator}`);
    }
  }

  /**
   * 求值一元表达式节点
   */
  private evaluateUnaryExpression(node: jsep.UnaryExpression): any {
    const argument = this.evaluateNode(node.argument);

    switch (node.operator) {
      case '+':
        return +argument;
      case '-':
        return -argument;
      case '!':
        return !argument;
      case 'typeof':
        return typeof argument;
      default:
        throw new Error(`不支持的一元操作符: ${node.operator}`);
    }
  }

  /**
   * 求值逻辑表达式节点
   */
  private evaluateLogicalExpression(node: any): any {
    const left = this.evaluateNode(node.left);

    switch (node.operator) {
      case '&&':
        return left && this.evaluateNode(node.right);
      case '||':
        return left || this.evaluateNode(node.right);
      default:
        throw new Error(`不支持的逻辑操作符: ${node.operator}`);
    }
  }

  /**
   * 求值条件表达式节点
   */
  private evaluateConditionalExpression(node: jsep.ConditionalExpression): any {
    const test = this.evaluateNode(node.test);
    return test ? this.evaluateNode(node.consequent) : this.evaluateNode(node.alternate);
  }

  /**
   * 求值数组表达式节点
   */
  private evaluateArrayExpression(node: jsep.ArrayExpression): any {
    return node.elements.map((element: jsep.Expression | null) =>
      element ? this.evaluateNode(element) : null,
    );
  }
}

/**
 * 创建表达式解析器实例
 */
export function createExpressionParser(context: ExpressionContext): ExpressionParser {
  return new ExpressionParser(context);
}
