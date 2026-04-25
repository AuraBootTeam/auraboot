/**
 * i18n Renderer - 国际化文本渲染器（统一版本）
 *
 * 这是项目唯一的i18n文本渲染实现，整合了以下功能：
 * - app/utils/i18n.ts 的 getLocalizedText (简化版)
 * - app/routes/_shared/dynamic-route-utils.tsx 的 getLocalizedText (兼容 LocalizedText 对象)
 *
 * 支持的格式：
 * 1. 简单字符串: "Hello"
 * 2. $i18n: 简写: "$i18n:form.store.submit.success"
 * 3. 表达式: "${user.name}"
 * 4. 对象风格: { $i18nKey: "key", vars: { name: "${user.name}" } }
 * 5. LocalizedText 对象: { "zh-CN": "你好", "en-US": "Hello" }
 */

import MessageFormat from '@messageformat/core';
import type { ExpressionContext } from '~/framework/meta/runtime/expression/context';
import { evaluate } from '~/framework/meta/runtime/expression/evaluator';
// 从 schemas/types 导入统一的 LocalizedText 定义，避免重复
import type { LocalizedText } from '~/framework/meta/schemas/types';

// 重新导出，方便外部使用
export type { LocalizedText } from '~/framework/meta/schemas/types';

/**
 * i18n 配置接口
 */
export interface I18nTextConfig {
  $i18nKey: string;
  vars?: Record<string, any>;
}

/**
 * 支持的文本类型
 */
export type TranslatableText = string | LocalizedText | I18nTextConfig | null | undefined;

/**
 * 翻译函数类型（ICU MessageFormat）
 */
export type TranslateFunction = (key: string, vars?: Record<string, any>) => string;

/**
 * 渲染国际化文本
 *
 * 支持多种格式:
 * 1. 简单字符串: "Hello"
 * 2. $i18n: 简写: "$i18n:form.store.submit.success"
 * 3. 表达式: "${user.name}"
 * 4. 对象风格: { $i18nKey: "key", vars: { name: "${user.name}" } }
 */
export function renderText(text: any, context: ExpressionContext): string {
  // 处理 undefined/null
  if (text === undefined || text === null) {
    return '';
  }

  // 1. 字符串类型处理
  if (typeof text === 'string') {
    // $i18n: 简写语法
    if (text.startsWith('$i18n:')) {
      const key = text.slice(6); // 移除 "$i18n:" 前缀
      return renderI18nKey(key, context, context);
    }

    // ${} 表达式
    if (text.includes('${')) {
      return evaluateTextExpression(text, context);
    }

    // 普通字符串
    return text;
  }

  // 2. 对象风格（高级用法）
  if (typeof text === 'object' && '$i18nKey' in text) {
    const config = text as I18nTextConfig;
    const evaluatedVars = evaluateVars(config.vars || {}, context);
    return renderI18nKey(config.$i18nKey, evaluatedVars, context);
  }

  // 3. LocalizedText 对象
  if (typeof text === 'object' && hasLocaleKeys(text)) {
    return getLocalizedTextFromObject(text, context.locale);
  }

  // 其他类型转为字符串
  return String(text);
}

/**
 * 简化版 - 获取本地化文本（兼容旧API）
 *
 * 这是 utils/i18n.ts 和 dynamic-route-utils.tsx 的统一替代
 *
 * @param text - 待处理文本（字符串、LocalizedText对象或空值）
 * @param locale - 当前语言环境（可选，默认 zh-CN）
 * @param t - ICU MessageFormat 翻译函数（可选）
 * @returns 本地化后的字符串
 *
 * @example
 * getLocalizedText('Hello', 'zh-CN', t)              // => 'Hello'
 * getLocalizedText('$i18n:common.hello', 'zh-CN', t) // => '你好'
 * getLocalizedText({ 'zh-CN': '你好' }, 'zh-CN')     // => '你好'
 * getLocalizedText(null)                              // => ''
 */
export function getLocalizedText(
  text: string | LocalizedText | null | undefined,
  locale: string = 'zh-CN',
  t?: TranslateFunction,
): string {
  // 处理空值
  if (text == null) {
    return '';
  }

  // 字符串类型
  if (typeof text === 'string') {
    // 处理 $i18n: 前缀
    if (text.startsWith('$i18n:')) {
      if (t) {
        const key = text.slice(6);
        return t(key);
      }
      // 没有 t 函数时返回 key（调试用）
      return text;
    }
    // 普通字符串直接返回
    return text;
  }

  // LocalizedText 对象
  if (typeof text === 'object') {
    return getLocalizedTextFromObject(text, locale);
  }

  return '';
}

/**
 * 渲染 i18n key（内部函数）
 */
function renderI18nKey(key: string, vars: Record<string, any>, context: ExpressionContext): string {
  const locale = context.locale || 'zh-CN';
  const messages = context.i18n?.[locale] || {};
  const template = messages[key];

  if (template != null) {
    // 没有变量，直接返回
    if (!template.includes('{')) {
      return template;
    }

    try {
      // 使用 ICU MessageFormat 格式化
      const mf = new MessageFormat(locale);
      const msg = mf.compile(template);
      return String(msg(vars));
    } catch (error) {
      console.error('i18n rendering failed with ICU MessageFormat:', key, error);
      return template;
    }
  }

  // 回退到 context.t 函数（来自 I18nContext）
  if (context.t && typeof context.t === 'function') {
    try {
      return context.t(key, vars);
    } catch (error) {
      console.error('i18n rendering failed via context.t:', key, error);
    }
  }

  return key;
}

/**
 * 求值变量对象中的表达式
 */
function evaluateVars(vars: Record<string, any>, context: ExpressionContext): Record<string, any> {
  const result: Record<string, any> = {};

  for (const [key, value] of Object.entries(vars)) {
    if (typeof value === 'string' && value.startsWith('${')) {
      result[key] = evaluate(value, context);
    } else {
      result[key] = value;
    }
  }

  return result;
}

/**
 * 求值包含表达式的文本
 */
function evaluateTextExpression(text: string, context: ExpressionContext): string {
  return text.replace(/\$\{([^}]+)\}/g, (match, expr) => {
    try {
      const value = evaluate(`\${${expr}}`, context);
      return String(value ?? '');
    } catch (error) {
      console.error('Text expression evaluation failed:', expr, error);
      return match;
    }
  });
}

/**
 * 检查对象是否为 LocalizedText
 */
function hasLocaleKeys(obj: any): boolean {
  const localeKeys = ['zh-CN', 'en-US', 'ja-JP', 'ko-KR'];
  return Object.keys(obj).some((key) => localeKeys.includes(key));
}

/**
 * 从 LocalizedText 对象获取当前语言的文本（内部函数）
 */
function getLocalizedTextFromObject(
  obj: Record<string, string | undefined>,
  locale: string,
): string {
  // 优先使用当前语言
  if (obj[locale]) {
    return obj[locale] as string;
  }

  // 回退到中文
  if (obj['zh-CN']) {
    return obj['zh-CN'];
  }

  // 回退到英文
  if (obj['en-US']) {
    return obj['en-US'];
  }

  // 返回第一个可用值
  const firstValue = Object.values(obj).find((v) => v != null);
  return firstValue || '';
}

/**
 * 批量渲染文本
 */
export function renderTexts(texts: Array<any>, context: ExpressionContext): string[] {
  return texts.map((text) => renderText(text, context));
}

/**
 * 渲染对象中的所有文本字段
 */
export function renderTextFields<T extends Record<string, any>>(
  obj: T,
  context: ExpressionContext,
  fields: Array<keyof T>,
): T {
  const result = { ...obj };

  for (const field of fields) {
    if (field in obj) {
      result[field] = renderText(obj[field], context) as any;
    }
  }

  return result;
}

/**
 * 批量翻译数组元素的指定字段
 *
 * 这是 utils/i18n.ts 的 translateArray 的兼容实现
 *
 * @param array - 对象数组
 * @param fields - 需要翻译的字段名数组
 * @param locale - 当前语言环境
 * @param t - 翻译函数
 * @returns 翻译后的数组（浅拷贝）
 *
 * @example
 * const options = [
 *   { label: '$i18n:common.yes', value: 1 },
 *   { label: '$i18n:common.no', value: 0 }
 * ];
 * const translated = translateArray(options, ['label'], 'zh-CN', t);
 * // => [
 * //   { label: '是', value: 1 },
 * //   { label: '否', value: 0 }
 * // ]
 */
export function translateArray<T extends Record<string, any>>(
  array: T[],
  fields: Array<keyof T>,
  locale: string,
  t?: TranslateFunction,
): T[] {
  return array.map((item) => {
    const result = { ...item };
    fields.forEach((field) => {
      if (field in item && item[field] != null) {
        result[field] = getLocalizedText(item[field], locale, t) as any;
      }
    });
    return result;
  });
}
