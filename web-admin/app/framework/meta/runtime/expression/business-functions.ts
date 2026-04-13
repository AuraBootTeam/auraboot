/**
 * Business Functions Library for DSL Expressions
 *
 * Provides ~50 business-oriented functions accessible via the `fn` namespace
 * in expressions: ${fn.IF(form.status === 'active', 'Yes', 'No')}
 *
 * Categories:
 * - Logical: IF, CASE, AND, OR, NOT, SWITCH
 * - Text: TEXT, CONCAT, LEFT, RIGHT, MID, LEN, UPPER, LOWER, TRIM, REPLACE, SUBSTITUTE, CONTAINS, SPLIT, PAD
 * - Number: ROUND, FLOOR, CEIL, ABS, MOD, POWER, SUM, AVG, MIN, MAX, CLAMP, PERCENT
 * - Date: NOW, TODAY, DATEADD, DATEDIFF, DATEFORMAT, YEAR, MONTH, DAY, WEEKDAY, STARTOFMONTH, ENDOFMONTH
 * - Type: ISBLANK, ISNUMBER, ISTEXT, ISBOOLEAN, ISARRAY, TYPEOF, COALESCE, DEFAULT
 * - Collection: COUNT, COUNTIF, SUMIF, PLUCK, UNIQUE, FIRST, LAST, NTH, FLATTEN, GROUPBY
 */

import dayjs from 'dayjs';

// ==================== Logical Functions ====================

function IF(condition: any, trueValue: any, falseValue?: any): any {
  return condition ? trueValue : (falseValue ?? null);
}

function CASE(value: any, ...pairs: any[]): any {
  // CASE(value, match1, result1, match2, result2, ..., defaultResult?)
  for (let i = 0; i < pairs.length - 1; i += 2) {
    if (value === pairs[i]) return pairs[i + 1];
  }
  // If odd number of args, last is default
  return pairs.length % 2 === 1 ? pairs[pairs.length - 1] : null;
}

function SWITCH(value: any, ...pairs: any[]): any {
  return CASE(value, ...pairs);
}

function AND(...args: any[]): boolean {
  return args.every(Boolean);
}

function OR(...args: any[]): boolean {
  return args.some(Boolean);
}

function NOT(value: any): boolean {
  return !value;
}

function IFS(...pairs: any[]): any {
  // IFS(cond1, val1, cond2, val2, ...)
  for (let i = 0; i < pairs.length - 1; i += 2) {
    if (pairs[i]) return pairs[i + 1];
  }
  return null;
}

// ==================== Text Functions ====================

function TEXT(value: any): string {
  if (value === null || value === undefined) return '';
  if (value instanceof Date) return dayjs(value).format('YYYY-MM-DD HH:mm:ss');
  return String(value);
}

function CONCAT(...args: any[]): string {
  return args.map((a) => (a === null || a === undefined ? '' : String(a))).join('');
}

function LEFT(text: string, count: number): string {
  return String(text || '').slice(0, count);
}

function RIGHT(text: string, count: number): string {
  const s = String(text || '');
  return s.slice(Math.max(0, s.length - count));
}

function MID(text: string, start: number, count: number): string {
  return String(text || '').slice(start, start + count);
}

function LEN(text: any): number {
  if (text === null || text === undefined) return 0;
  if (Array.isArray(text)) return text.length;
  return String(text).length;
}

function UPPER(text: string): string {
  return String(text || '').toUpperCase();
}

function LOWER(text: string): string {
  return String(text || '').toLowerCase();
}

function TRIM_FN(text: string): string {
  return String(text || '').trim();
}

function REPLACE(text: string, search: string, replacement: string): string {
  return String(text || '')
    .split(search)
    .join(replacement);
}

function SUBSTITUTE(text: string, oldText: string, newText: string, occurrence?: number): string {
  const s = String(text || '');
  if (occurrence !== undefined && occurrence > 0) {
    let count = 0;
    return s.replace(new RegExp(oldText.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'g'), (match) => {
      count++;
      return count === occurrence ? newText : match;
    });
  }
  return s.split(oldText).join(newText);
}

function CONTAINS(text: string, search: string): boolean {
  return String(text || '').includes(String(search || ''));
}

function SPLIT(text: string, delimiter: string): string[] {
  return String(text || '').split(delimiter);
}

function PAD(
  text: string,
  length: number,
  char = ' ',
  direction: 'left' | 'right' = 'right',
): string {
  const s = String(text || '');
  return direction === 'left' ? s.padStart(length, char) : s.padEnd(length, char);
}

function REPEAT(text: string, count: number): string {
  return String(text || '').repeat(Math.max(0, Math.floor(count)));
}

function STARTSWITH(text: string, prefix: string): boolean {
  return String(text || '').startsWith(String(prefix || ''));
}

function ENDSWITH(text: string, suffix: string): boolean {
  return String(text || '').endsWith(String(suffix || ''));
}

// ==================== Number Functions ====================

function ROUND_FN(value: number, decimals = 0): number {
  const factor = Math.pow(10, decimals);
  return Math.round(Number(value) * factor) / factor;
}

function FLOOR_FN(value: number, decimals = 0): number {
  const factor = Math.pow(10, decimals);
  return Math.floor(Number(value) * factor) / factor;
}

function CEIL_FN(value: number, decimals = 0): number {
  const factor = Math.pow(10, decimals);
  return Math.ceil(Number(value) * factor) / factor;
}

function ABS_FN(value: number): number {
  return Math.abs(Number(value));
}

function MOD(a: number, b: number): number {
  return Number(a) % Number(b);
}

function POWER(base: number, exp: number): number {
  return Math.pow(Number(base), Number(exp));
}

function SUM(...values: any[]): number {
  const flat = values.flat(Infinity);
  return flat.reduce((sum: number, v: any) => sum + (Number(v) || 0), 0);
}

function AVG(...values: any[]): number {
  const flat = values.flat(Infinity);
  if (flat.length === 0) return 0;
  return SUM(...flat) / flat.length;
}

function MIN_FN(...values: any[]): number {
  const flat = values
    .flat(Infinity)
    .map(Number)
    .filter((n) => !isNaN(n));
  return flat.length > 0 ? Math.min(...flat) : 0;
}

function MAX_FN(...values: any[]): number {
  const flat = values
    .flat(Infinity)
    .map(Number)
    .filter((n) => !isNaN(n));
  return flat.length > 0 ? Math.max(...flat) : 0;
}

function CLAMP(value: number, min: number, max: number): number {
  return Math.min(Math.max(Number(value), Number(min)), Number(max));
}

function PERCENT(value: number, total: number, decimals = 2): number {
  if (!total) return 0;
  return ROUND_FN((Number(value) / Number(total)) * 100, decimals);
}

function RANDOM(min = 0, max = 1): number {
  return Math.random() * (max - min) + min;
}

// ==================== Date Functions ====================

function NOW(): Date {
  return new Date();
}

function TODAY(): string {
  return dayjs().format('YYYY-MM-DD');
}

function DATEADD(
  date: any,
  amount: number,
  unit: 'day' | 'week' | 'month' | 'year' | 'hour' | 'minute' | 'second' = 'day',
): string {
  const d = dayjs(date);
  if (!d.isValid()) return '';
  return d.add(amount, unit).format('YYYY-MM-DD HH:mm:ss');
}

function DATEDIFF(
  date1: any,
  date2: any,
  unit: 'day' | 'week' | 'month' | 'year' | 'hour' | 'minute' | 'second' = 'day',
): number {
  const d1 = dayjs(date1);
  const d2 = dayjs(date2);
  if (!d1.isValid() || !d2.isValid()) return 0;
  return d2.diff(d1, unit);
}

function DATEFORMAT(date: any, format = 'YYYY-MM-DD'): string {
  const d = dayjs(date);
  if (!d.isValid()) return '';
  return d.format(format);
}

function YEAR(date: any): number {
  const d = dayjs(date);
  return d.isValid() ? d.year() : 0;
}

function MONTH(date: any): number {
  const d = dayjs(date);
  return d.isValid() ? d.month() + 1 : 0; // 1-indexed
}

function DAY(date: any): number {
  const d = dayjs(date);
  return d.isValid() ? d.date() : 0;
}

function WEEKDAY(date: any): number {
  const d = dayjs(date);
  return d.isValid() ? d.day() : 0; // 0=Sunday
}

function STARTOFMONTH(date: any): string {
  const d = dayjs(date);
  if (!d.isValid()) return '';
  return d.startOf('month').format('YYYY-MM-DD');
}

function ENDOFMONTH(date: any): string {
  const d = dayjs(date);
  if (!d.isValid()) return '';
  return d.endOf('month').format('YYYY-MM-DD');
}

function STARTOFWEEK(date: any): string {
  const d = dayjs(date);
  if (!d.isValid()) return '';
  return d.startOf('week').format('YYYY-MM-DD');
}

function ISTODAY(date: any): boolean {
  const d = dayjs(date);
  return d.isValid() && d.isSame(dayjs(), 'day');
}

function ISPAST(date: any): boolean {
  const d = dayjs(date);
  return d.isValid() && d.isBefore(dayjs());
}

function ISFUTURE(date: any): boolean {
  const d = dayjs(date);
  return d.isValid() && d.isAfter(dayjs());
}

// ==================== Type Check Functions ====================

function ISBLANK(value: any): boolean {
  if (value === null || value === undefined) return true;
  if (typeof value === 'string') return value.trim() === '';
  if (Array.isArray(value)) return value.length === 0;
  return false;
}

function ISNUMBER(value: any): boolean {
  if (typeof value === 'number') return !isNaN(value);
  if (typeof value === 'string') return value.trim() !== '' && !isNaN(Number(value));
  return false;
}

function ISTEXT(value: any): boolean {
  return typeof value === 'string';
}

function ISBOOLEAN(value: any): boolean {
  return typeof value === 'boolean';
}

function ISARRAY(value: any): boolean {
  return Array.isArray(value);
}

function TYPEOF(value: any): string {
  if (value === null) return 'null';
  if (Array.isArray(value)) return 'array';
  if (value instanceof Date) return 'date';
  return typeof value;
}

function COALESCE(...values: any[]): any {
  for (const v of values) {
    if (v !== null && v !== undefined) return v;
  }
  return null;
}

function DEFAULT(value: any, defaultValue: any): any {
  return ISBLANK(value) ? defaultValue : value;
}

function TONUMBER(value: any, fallback = 0): number {
  const n = Number(value);
  return isNaN(n) ? fallback : n;
}

function TOBOOLEAN(value: any): boolean {
  if (typeof value === 'string') {
    return value.toLowerCase() === 'true' || value === '1';
  }
  return Boolean(value);
}

// ==================== Collection Functions ====================

function COUNT(arr: any): number {
  if (Array.isArray(arr)) return arr.length;
  if (arr && typeof arr === 'object') return Object.keys(arr).length;
  return 0;
}

function COUNTIF(arr: any[], predicate: (item: any) => boolean): number {
  if (!Array.isArray(arr)) return 0;
  return arr.filter(predicate).length;
}

function SUMIF(arr: any[], predicate: (item: any) => boolean, field?: string): number {
  if (!Array.isArray(arr)) return 0;
  return arr.filter(predicate).reduce((sum, item) => {
    const v = field ? item?.[field] : item;
    return sum + (Number(v) || 0);
  }, 0);
}

function PLUCK(arr: any[], field: string): any[] {
  if (!Array.isArray(arr)) return [];
  return arr.map((item) => item?.[field]);
}

function UNIQUE(arr: any[]): any[] {
  if (!Array.isArray(arr)) return [];
  return [...new Set(arr)];
}

function FIRST(arr: any[]): any {
  if (!Array.isArray(arr) || arr.length === 0) return null;
  return arr[0];
}

function LAST(arr: any[]): any {
  if (!Array.isArray(arr) || arr.length === 0) return null;
  return arr[arr.length - 1];
}

function NTH(arr: any[], index: number): any {
  if (!Array.isArray(arr)) return null;
  const i = index < 0 ? arr.length + index : index;
  return arr[i] ?? null;
}

function FLATTEN(arr: any[]): any[] {
  if (!Array.isArray(arr)) return [];
  return arr.flat(Infinity);
}

function GROUPBY(arr: any[], field: string): Record<string, any[]> {
  if (!Array.isArray(arr)) return {};
  const result: Record<string, any[]> = {};
  for (const item of arr) {
    const key = String(item?.[field] ?? 'undefined');
    if (!result[key]) result[key] = [];
    result[key].push(item);
  }
  return result;
}

function SORTBY(arr: any[], field: string, direction: 'asc' | 'desc' = 'asc'): any[] {
  if (!Array.isArray(arr)) return [];
  const copy = [...arr];
  copy.sort((a, b) => {
    const va = a?.[field];
    const vb = b?.[field];
    if (va < vb) return direction === 'asc' ? -1 : 1;
    if (va > vb) return direction === 'asc' ? 1 : -1;
    return 0;
  });
  return copy;
}

function JOIN(arr: any[], separator = ', '): string {
  if (!Array.isArray(arr)) return '';
  return arr.map((v) => (v === null || v === undefined ? '' : String(v))).join(separator);
}

// ==================== Export ====================

/**
 * All business functions grouped by category.
 * Exposed as the `fn` namespace in expression context.
 *
 * Usage in DSL expressions:
 *   ${fn.IF(form.amount > 1000, 'vip', 'Standard')}
 *   ${fn.DATEADD(form.startDate, 30, 'day')}
 *   ${fn.ISBLANK(form.email) ? 'Required' : 'OK'}
 *   ${fn.SUM(form.lineItems.map(i => i.amount))}
 */
export const businessFunctions = {
  // Logical (7)
  IF,
  CASE,
  SWITCH,
  AND,
  OR,
  NOT,
  IFS,

  // Text (16)
  TEXT,
  CONCAT,
  LEFT,
  RIGHT,
  MID,
  LEN,
  UPPER,
  LOWER,
  TRIM: TRIM_FN,
  REPLACE,
  SUBSTITUTE,
  CONTAINS,
  SPLIT,
  PAD,
  REPEAT,
  STARTSWITH,
  ENDSWITH,

  // Number (12)
  ROUND: ROUND_FN,
  FLOOR: FLOOR_FN,
  CEIL: CEIL_FN,
  ABS: ABS_FN,
  MOD,
  POWER,
  SUM,
  AVG,
  MIN: MIN_FN,
  MAX: MAX_FN,
  CLAMP,
  PERCENT,
  RANDOM,

  // Date (14)
  NOW,
  TODAY,
  DATEADD,
  DATEDIFF,
  DATEFORMAT,
  YEAR,
  MONTH,
  DAY,
  WEEKDAY,
  STARTOFMONTH,
  ENDOFMONTH,
  STARTOFWEEK,
  ISTODAY,
  ISPAST,
  ISFUTURE,

  // Type (9)
  ISBLANK,
  ISNUMBER,
  ISTEXT,
  ISBOOLEAN,
  ISARRAY,
  TYPEOF,
  COALESCE,
  DEFAULT,
  TONUMBER,
  TOBOOLEAN,

  // Collection (11)
  COUNT,
  COUNTIF,
  SUMIF,
  PLUCK,
  UNIQUE,
  FIRST,
  LAST,
  NTH,
  FLATTEN,
  GROUPBY,
  SORTBY,
  JOIN,
} as const;

/** Names of all business functions for SAFE_FUNCTIONS whitelist */
export const BUSINESS_FUNCTION_NAMES = Object.keys(businessFunctions);

export type BusinessFunctions = typeof businessFunctions;
