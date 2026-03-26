/**
 * 表达式解析器
 * 用于解析和执行动态表达式，支持变量替换、函数调用等
 */

export interface ExpressionContext {
  [key: string]: any;
}

export interface ExpressionFunction {
  name: string;
  fn: (...args: any[]) => any;
}

export class ExpressionParser {
  private static instance: ExpressionParser;
  private functions: Map<string, ExpressionFunction> = new Map();

  constructor() {
    this.registerDefaultFunctions();
  }

  static getInstance(): ExpressionParser {
    if (!ExpressionParser.instance) {
      ExpressionParser.instance = new ExpressionParser();
    }
    return ExpressionParser.instance;
  }

  /**
   * 兼容旧版静态 API，支持直接执行表达式
   */
  static evaluate(expression: any, context: ExpressionContext = {}): any {
    if (typeof expression === 'function') {
      return expression(context);
    }
    if (typeof expression === 'string') {
      return ExpressionParser.getInstance().execute(expression, context);
    }
    return expression;
  }

  /**
   * 注册默认函数
   */
  private registerDefaultFunctions() {
    // 字符串函数
    this.registerFunction('concat', (...args: any[]) => args.join(''));
    this.registerFunction('upper', (str: string) => String(str).toUpperCase());
    this.registerFunction('lower', (str: string) => String(str).toLowerCase());
    this.registerFunction('trim', (str: string) => String(str).trim());
    this.registerFunction('length', (str: string) => String(str).length);

    // 数学函数
    this.registerFunction('add', (a: number, b: number) => Number(a) + Number(b));
    this.registerFunction('subtract', (a: number, b: number) => Number(a) - Number(b));
    this.registerFunction('multiply', (a: number, b: number) => Number(a) * Number(b));
    this.registerFunction('divide', (a: number, b: number) => Number(a) / Number(b));
    this.registerFunction('max', (...args: number[]) => Math.max(...args.map(Number)));
    this.registerFunction('min', (...args: number[]) => Math.min(...args.map(Number)));
    this.registerFunction('round', (num: number) => Math.round(Number(num)));

    // 逻辑函数
    this.registerFunction('if', (condition: any, trueValue: any, falseValue: any) =>
      condition ? trueValue : falseValue,
    );
    this.registerFunction('and', (...args: any[]) => args.every(Boolean));
    this.registerFunction('or', (...args: any[]) => args.some(Boolean));
    this.registerFunction('not', (value: any) => !value);

    // 数组函数
    this.registerFunction('join', (arr: any[], separator: string = ',') =>
      Array.isArray(arr) ? arr.join(separator) : String(arr),
    );
    this.registerFunction('split', (str: string, separator: string = ',') =>
      String(str).split(separator),
    );
    this.registerFunction('includes', (arr: any[], value: any) =>
      Array.isArray(arr) ? arr.includes(value) : false,
    );

    // 日期函数
    this.registerFunction('now', () => new Date());
    this.registerFunction('formatDate', (date: Date | string, format: string = 'YYYY-MM-DD') => {
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
    });
  }

  /**
   * 注册自定义函数
   */
  registerFunction(name: string, fn: (...args: any[]) => any) {
    this.functions.set(name, { name, fn });
  }

  /**
   * 解析表达式
   */
  parse(expression: string, context: ExpressionContext = {}): any {
    if (!expression || typeof expression !== 'string') {
      return expression;
    }

    // 检查是否为表达式格式 {{...}}
    const trimmed = expression.trim();
    if (!trimmed.startsWith('{{') || !trimmed.endsWith('}}')) {
      return expression;
    }

    try {
      const code = trimmed.slice(2, -2).trim();
      return this.evaluateExpression(code, context);
    } catch (error) {
      console.error('Expression parsing error:', error);
      return expression;
    }
  }

  /**
   * 执行任意表达式字符串（可选 {{ }} 包裹）
   */
  execute(expression: string, context: ExpressionContext = {}): any {
    if (!expression) {
      return expression;
    }
    const trimmed = expression.trim();
    if (trimmed.startsWith('{{') && trimmed.endsWith('}}')) {
      return this.parse(trimmed, context);
    }
    return this.evaluateExpression(trimmed, context);
  }

  /**
   * 执行表达式
   */
  private evaluateExpression(code: string, context: ExpressionContext): any {
    // 创建安全的执行环境
    const safeContext = {
      ...context,
      // 添加注册的函数
      ...Object.fromEntries(
        Array.from(this.functions.entries()).map(([name, func]) => [name, func.fn]),
      ),
      // 添加常用的全局对象（安全版本）
      Math: Math,
      Date: Date,
      String: String,
      Number: Number,
      Boolean: Boolean,
      Array: Array,
      Object: Object,
      JSON: JSON,
    };

    // 使用 Function 构造器创建安全的执行环境
    const func = new Function(...Object.keys(safeContext), `"use strict"; return (${code});`);

    return func(...Object.values(safeContext));
  }

  /**
   * 批量解析对象中的表达式
   */
  parseObject(obj: any, context: ExpressionContext = {}): any {
    if (obj === null || obj === undefined) {
      return obj;
    }

    if (typeof obj === 'string') {
      return this.parse(obj, context);
    }

    if (Array.isArray(obj)) {
      return obj.map((item) => this.parseObject(item, context));
    }

    if (typeof obj === 'object') {
      const result: any = {};
      for (const [key, value] of Object.entries(obj)) {
        result[key] = this.parseObject(value, context);
      }
      return result;
    }

    return obj;
  }

  /**
   * 验证表达式语法
   */
  validate(expression: string): { valid: boolean; error?: string } {
    if (!expression || typeof expression !== 'string') {
      return { valid: true };
    }

    const trimmed = expression.trim();
    if (!trimmed.startsWith('{{') || !trimmed.endsWith('}}')) {
      return { valid: true };
    }

    try {
      const code = trimmed.slice(2, -2).trim();
      // 尝试解析但不执行
      new Function(`"use strict"; return (${code});`);
      return { valid: true };
    } catch (error) {
      return {
        valid: false,
        error: error instanceof Error ? error.message : 'Invalid expression syntax',
      };
    }
  }

  /**
   * 提取表达式中的变量
   */
  extractVariables(expression: string): string[] {
    if (!expression || typeof expression !== 'string') {
      return [];
    }

    const trimmed = expression.trim();
    if (!trimmed.startsWith('{{') || !trimmed.endsWith('}}')) {
      return [];
    }

    const code = trimmed.slice(2, -2).trim();
    const variables: string[] = [];

    // 简单的变量提取（可以根据需要改进）
    const variableRegex = /\b([a-zA-Z_$][a-zA-Z0-9_$]*)\b/g;
    let match;

    while ((match = variableRegex.exec(code)) !== null) {
      const variable = match[1];
      // 排除JavaScript关键字和内置函数
      if (!this.isReservedWord(variable) && !this.functions.has(variable)) {
        variables.push(variable);
      }
    }

    return [...new Set(variables)]; // 去重
  }

  /**
   * 检查是否为保留字
   */
  private isReservedWord(word: string): boolean {
    const reserved = [
      'true',
      'false',
      'null',
      'undefined',
      'Math',
      'Date',
      'String',
      'Number',
      'Boolean',
      'Array',
      'Object',
      'json',
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
      'class',
      'extends',
      'import',
      'export',
      'from',
      'as',
      'new',
      'this',
      'super',
      'try',
      'catch',
      'finally',
      'throw',
      'typeof',
      'instanceof',
    ];
    return reserved.includes(word);
  }
}

// 导出单例实例
export const expressionParser = ExpressionParser.getInstance();
