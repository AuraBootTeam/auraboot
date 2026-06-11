/**
 * Client-side Condition AST model + safe preview evaluator for AuraBoot DecisionOps.
 *
 * Mirrors the backend `com.auraboot.framework.decision.ast` contract (docs/1.md §14): the same
 * node/operand shapes, operator whitelist, and three-valued (TRUE/FALSE/UNKNOWN) semantics, so the
 * front-end can build, serialize and *preview* conditions while the back-end stays authoritative.
 * Preview is advisory only — publish/run go through the backend validate/test-run/evaluate APIs.
 */

export type Truth = 'TRUE' | 'FALSE' | 'UNKNOWN';

export type Scope =
  | 'meta' | 'event' | 'record' | 'before' | 'after' | 'process'
  | 'task' | 'sla' | 'actor' | 'tenant' | 'time' | 'env';

export type DataType =
  | 'string' | 'text' | 'integer' | 'decimal' | 'boolean' | 'date' | 'time'
  | 'datetime' | 'duration' | 'enum' | 'dict' | 'user' | 'role' | 'group'
  | 'department' | 'collection' | 'object';

export type Operator =
  | 'EQ' | 'NE' | 'GT' | 'GTE' | 'LT' | 'LTE' | 'IN' | 'NOT_IN' | 'BETWEEN'
  | 'CONTAINS_TEXT' | 'CONTAINS_ELEMENT' | 'STARTS_WITH' | 'ENDS_WITH'
  | 'IS_NULL' | 'IS_NOT_NULL' | 'IS_EMPTY' | 'IS_NOT_EMPTY' | 'CHANGED' | 'MATCHES';

export const UNARY_OPERATORS: ReadonlySet<Operator> = new Set<Operator>([
  'IS_NULL', 'IS_NOT_NULL', 'IS_EMPTY', 'IS_NOT_EMPTY', 'CHANGED',
]);

export interface PathOperand { type: 'path'; scope: Scope; path: string; dataType?: DataType }
export interface LiteralOperand { type: 'literal'; value: unknown; dataType?: DataType }
export interface FunctionCallOperand { type: 'functionCall'; name: string; args?: Operand[]; returnType?: DataType }
export type Operand = PathOperand | LiteralOperand | FunctionCallOperand;

export interface GroupNode { type: 'group'; op: 'AND' | 'OR'; children: ConditionNode[] }
export interface NotNode { type: 'not'; child: ConditionNode }
export interface CompareNode {
  type: 'compare';
  id?: string;
  enabled?: boolean;
  left: Operand;
  operator: Operator;
  right?: Operand;
}
export type ConditionNode = GroupNode | NotNode | CompareNode;

// ── builders ────────────────────────────────────────────────────────────────

export const path = (scope: Scope, p: string, dataType?: DataType): PathOperand =>
  ({ type: 'path', scope, path: p, dataType });
export const lit = (value: unknown, dataType?: DataType): LiteralOperand =>
  ({ type: 'literal', value, dataType });
export const cmp = (left: Operand, operator: Operator, right?: Operand): CompareNode =>
  ({ type: 'compare', enabled: true, left, operator, right });
export const group = (op: 'AND' | 'OR', children: ConditionNode[]): GroupNode =>
  ({ type: 'group', op, children });
export const not = (child: ConditionNode): NotNode => ({ type: 'not', child });

/** A disabled leaf is treated as absent in its group (absent `enabled` = active). */
export const isActive = (n: CompareNode): boolean => n.enabled === undefined || n.enabled;

// ── three-valued logic ───────────────────────────────────────────────────────

export const and = (a: Truth, b: Truth): Truth =>
  a === 'FALSE' || b === 'FALSE' ? 'FALSE' : a === 'UNKNOWN' || b === 'UNKNOWN' ? 'UNKNOWN' : 'TRUE';
export const or = (a: Truth, b: Truth): Truth =>
  a === 'TRUE' || b === 'TRUE' ? 'TRUE' : a === 'UNKNOWN' || b === 'UNKNOWN' ? 'UNKNOWN' : 'FALSE';
export const negate = (a: Truth): Truth => (a === 'TRUE' ? 'FALSE' : a === 'FALSE' ? 'TRUE' : 'UNKNOWN');

// ── context + path resolution (missing vs present-null) ───────────────────────

export type ScopedContext = Partial<Record<Scope, unknown>>;

interface Resolved { present: boolean; value: unknown }

function resolvePath(ctx: ScopedContext, scope: Scope, p: string): Resolved {
  if (!(scope in ctx)) return { present: false, value: undefined };
  let cur: unknown = ctx[scope];
  if (!p) return { present: true, value: cur };
  for (const seg of p.split('.')) {
    if (cur === null || typeof cur !== 'object' || !(seg in (cur as Record<string, unknown>))) {
      return { present: false, value: undefined };
    }
    cur = (cur as Record<string, unknown>)[seg];
  }
  return { present: true, value: cur };
}

function resolveOperand(op: Operand | undefined, ctx: ScopedContext): Resolved {
  if (!op) return { present: false, value: undefined };
  if (op.type === 'literal') return { present: true, value: op.value };
  if (op.type === 'path') return resolvePath(ctx, op.scope, op.path);
  // functionCall is not evaluated client-side (backend authoritative) -> UNKNOWN-yielding
  return { present: false, value: undefined };
}

function toNum(v: unknown): number | null {
  if (typeof v === 'number') return v;
  if (typeof v === 'string' && v.trim() !== '' && !Number.isNaN(Number(v))) return Number(v);
  return null;
}

function parseDateOnly(value: string): number | null {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value.trim());
  if (!match) return null;
  const [, y, m, d] = match;
  const time = Date.UTC(Number(y), Number(m) - 1, Number(d));
  return Number.isNaN(time) ? null : time;
}

function parseTimeOnly(value: string): number | null {
  const match = /^(\d{2}):(\d{2})(?::(\d{2})(?:\.(\d{1,3}))?)?$/.exec(value.trim());
  if (!match) return null;
  const [, hh, mm, ss = '0', ms = '0'] = match;
  const h = Number(hh);
  const m = Number(mm);
  const s = Number(ss);
  const milli = Number(ms.padEnd(3, '0'));
  if (h > 23 || m > 59 || s > 59 || milli > 999) return null;
  return (((h * 60) + m) * 60 + s) * 1000 + milli;
}

function parseIsoDuration(value: string): number | null {
  const match = /^P(?:(\d+(?:\.\d+)?)D)?(?:T(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?)?$/i.exec(value.trim());
  if (!match) return null;
  const [, days = '0', hours = '0', minutes = '0', seconds = '0'] = match;
  if ([days, hours, minutes, seconds].every((part) => part === '0')) return null;
  const totalSeconds = Number(days) * 86400 + Number(hours) * 3600 + Number(minutes) * 60 + Number(seconds);
  return Number.isNaN(totalSeconds) ? null : totalSeconds * 1000;
}

function toOrderedValue(v: unknown, dt?: DataType): number | null {
  if (!dt) return null;
  if (dt === 'date') {
    if (v instanceof Date) return Date.UTC(v.getUTCFullYear(), v.getUTCMonth(), v.getUTCDate());
    if (typeof v !== 'string') return null;
    return parseDateOnly(v) ?? (Number.isNaN(Date.parse(v)) ? null : Date.parse(v));
  }
  if (dt === 'time') {
    return typeof v === 'string' ? parseTimeOnly(v) : null;
  }
  if (dt === 'datetime') {
    if (v instanceof Date) return v.getTime();
    if (typeof v !== 'string') return null;
    return parseDateOnly(v) ?? (Number.isNaN(Date.parse(v)) ? null : Date.parse(v));
  }
  if (dt === 'duration') {
    return typeof v === 'string' ? parseIsoDuration(v) : null;
  }
  return null;
}

function asArray(v: unknown): unknown[] {
  if (Array.isArray(v)) return v;
  if (v === null || v === undefined) return [];
  return [v];
}

function isEmptyVal(r: Resolved): boolean {
  if (!r.present || r.value === null || r.value === undefined) return true;
  if (typeof r.value === 'string') return r.value === '';
  if (Array.isArray(r.value)) return r.value.length === 0;
  return false;
}

function valueEquals(left: unknown, right: unknown, dt?: DataType): boolean {
  if (dt === 'integer' || dt === 'decimal') {
    const a = toNum(left); const b = toNum(right);
    return a !== null && b !== null && a === b;
  }
  const orderedLeft = toOrderedValue(left, dt);
  const orderedRight = toOrderedValue(right, dt);
  if (orderedLeft !== null && orderedRight !== null) return orderedLeft === orderedRight;
  // enum/dict/etc compare by code (string), default case-sensitive string compare
  return String(left) === String(right);
}

function orderedCompare(left: unknown, right: unknown, dt: DataType | undefined, pred: (c: number) => boolean): Truth {
  const a = toOrderedValue(left, dt); const b = toOrderedValue(right, dt);
  if (a !== null && b !== null) return pred(a - b) ? 'TRUE' : 'FALSE';
  return numericCompare(left, right, pred);
}

function numericCompare(left: unknown, right: unknown, pred: (c: number) => boolean): Truth {
  const a = toNum(left); const b = toNum(right);
  if (a === null || b === null) return 'UNKNOWN'; // no implicit coercion
  return pred(a - b) ? 'TRUE' : 'FALSE';
}

function evalCompare(node: CompareNode, ctx: ScopedContext): Truth {
  const op = node.operator;
  if (op === 'CHANGED') {
    if (node.left.type !== 'path') return 'UNKNOWN';
    const b = resolvePath(ctx, 'before', node.left.path);
    const a = resolvePath(ctx, 'after', node.left.path);
    if (!b.present && !a.present) return 'UNKNOWN';
    return JSON.stringify(b.value) !== JSON.stringify(a.value) ? 'TRUE' : 'FALSE';
  }
  const left = resolveOperand(node.left, ctx);
  switch (op) {
    case 'IS_NULL': return !left.present || left.value === null || left.value === undefined ? 'TRUE' : 'FALSE';
    case 'IS_NOT_NULL': return left.present && left.value !== null && left.value !== undefined ? 'TRUE' : 'FALSE';
    case 'IS_EMPTY': return isEmptyVal(left) ? 'TRUE' : 'FALSE';
    case 'IS_NOT_EMPTY': return isEmptyVal(left) ? 'FALSE' : 'TRUE';
    default: break;
  }
  if (!left.present) return 'UNKNOWN';                 // missing field
  if (left.value === null || left.value === undefined) return 'UNKNOWN'; // null vs value
  const rv = resolveOperand(node.right, ctx).value;
  const dt = (node.left as PathOperand).dataType ?? (node.left as LiteralOperand).dataType;
  const bool = (b: boolean): Truth => (b ? 'TRUE' : 'FALSE');
  switch (op) {
    case 'EQ': return bool(valueEquals(left.value, rv, dt));
    case 'NE': return bool(!valueEquals(left.value, rv, dt));
    case 'GT': return orderedCompare(left.value, rv, dt, (c) => c > 0);
    case 'GTE': return orderedCompare(left.value, rv, dt, (c) => c >= 0);
    case 'LT': return orderedCompare(left.value, rv, dt, (c) => c < 0);
    case 'LTE': return orderedCompare(left.value, rv, dt, (c) => c <= 0);
    case 'IN': return bool(asArray(rv).some((x) => valueEquals(left.value, x, dt)));
    case 'NOT_IN': return bool(!asArray(rv).some((x) => valueEquals(left.value, x, dt)));
    case 'BETWEEN': {
      const arr = asArray(rv);
      if (arr.length !== 2) return 'UNKNOWN';
      const ordered = toOrderedValue(left.value, dt);
      const orderedLo = toOrderedValue(arr[0], dt);
      const orderedHi = toOrderedValue(arr[1], dt);
      if (ordered !== null && orderedLo !== null && orderedHi !== null) {
        return bool(ordered >= orderedLo && ordered <= orderedHi);
      }
      const v = toNum(left.value);
      const lo = toNum(arr[0]); const hi = toNum(arr[1]);
      if (v === null || lo === null || hi === null) return 'UNKNOWN';
      return bool(v >= lo && v <= hi);
    }
    case 'CONTAINS_TEXT': return bool(String(left.value).includes(String(rv)));
    case 'STARTS_WITH': return bool(String(left.value).startsWith(String(rv)));
    case 'ENDS_WITH': return bool(String(left.value).endsWith(String(rv)));
    case 'CONTAINS_ELEMENT': return bool(Array.isArray(left.value) && left.value.some((x) => valueEquals(x, rv, dt)));
    default: return 'UNKNOWN';
  }
}

/** Three-valued preview evaluation of a Condition AST against a scoped context. */
export function evaluatePreview(node: ConditionNode, ctx: ScopedContext): Truth {
  if (node.type === 'compare') return evalCompare(node, ctx);
  if (node.type === 'not') return negate(evaluatePreview(node.child, ctx));
  const active = node.children.filter((c) => !(c.type === 'compare' && !isActive(c)));
  if (active.length === 0) return 'UNKNOWN';
  return active.map((c) => evaluatePreview(c, ctx)).reduce((acc, t) => (node.op === 'AND' ? and(acc, t) : or(acc, t)));
}

/** Only TRUE is a match (mirrors backend). */
export const isMatch = (t: Truth): boolean => t === 'TRUE';

// ── serialize / parse (backend content_json round-trip) ───────────────────────

export const serialize = (node: ConditionNode): string => JSON.stringify(node);
export const parse = (json: string): ConditionNode => JSON.parse(json) as ConditionNode;

// ── natural-language preview (mockup previewNode) ─────────────────────────────

const OP_LABEL: Record<Operator, string> = {
  EQ: '等于', NE: '不等于', GT: '大于', GTE: '大于等于', LT: '小于', LTE: '小于等于',
  IN: '属于集合', NOT_IN: '不在集合', BETWEEN: '介于', CONTAINS_TEXT: '包含文本',
  CONTAINS_ELEMENT: '包含元素', STARTS_WITH: '开头是', ENDS_WITH: '结尾是',
  IS_NULL: '为空', IS_NOT_NULL: '不为空', IS_EMPTY: '为空', IS_NOT_EMPTY: '不为空',
  CHANGED: '发生变化', MATCHES: '匹配',
};

function operandLabel(op: Operand | undefined, labelOf?: (o: PathOperand) => string): string {
  if (!op) return '';
  if (op.type === 'path') return labelOf ? labelOf(op) : `${op.scope}.${op.path}`;
  if (op.type === 'literal') return Array.isArray(op.value) ? op.value.join('、') : String(op.value ?? '');
  return `${op.name}(...)`;
}

/** Render a Condition AST to a human-readable Chinese preview string. */
export function toNaturalLanguage(node: ConditionNode, labelOf?: (o: PathOperand) => string): string {
  if (node.type === 'compare') {
    const l = operandLabel(node.left, labelOf);
    const opl = OP_LABEL[node.operator] ?? node.operator;
    const r = UNARY_OPERATORS.has(node.operator) ? '' : ` ${operandLabel(node.right, labelOf)}`;
    return `【${l} ${opl}${r}】`;
  }
  if (node.type === 'not') return `非(${toNaturalLanguage(node.child, labelOf)})`;
  const parts = node.children
    .filter((c) => !(c.type === 'compare' && !isActive(c)))
    .map((c) => toNaturalLanguage(c, labelOf));
  return `(${parts.join(node.op === 'AND' ? ' 并且 ' : ' 或 ')})`;
}

// ── complexity limits (docs/1.md §14.10) — client-side pre-check ──────────────

export interface ComplexityLimits { maxDepth: number; maxNodes: number; maxInSize: number }
export const DEFAULT_LIMITS: ComplexityLimits = { maxDepth: 8, maxNodes: 100, maxInSize: 200 };

export function checkComplexity(node: ConditionNode, limits: ComplexityLimits = DEFAULT_LIMITS): string[] {
  const violations: string[] = [];
  let count = 0;
  const walk = (n: ConditionNode, depth: number): void => {
    count += 1;
    if (depth > limits.maxDepth) violations.push(`AST depth ${depth} exceeds max ${limits.maxDepth}`);
    if (n.type === 'group') n.children.forEach((c) => walk(c, depth + 1));
    else if (n.type === 'not') walk(n.child, depth + 1);
    else if (n.type === 'compare' && (n.operator === 'IN' || n.operator === 'NOT_IN')
      && n.right?.type === 'literal' && Array.isArray(n.right.value) && n.right.value.length > limits.maxInSize) {
      violations.push(`IN set size ${n.right.value.length} exceeds max ${limits.maxInSize}`);
    }
  };
  walk(node, 1);
  if (count > limits.maxNodes) violations.push(`AST node count ${count} exceeds max ${limits.maxNodes}`);
  return violations;
}
