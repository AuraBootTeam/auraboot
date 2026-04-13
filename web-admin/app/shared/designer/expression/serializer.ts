import type { ConditionGroup, ConditionRow } from './types';

const UNQUOTED_VALUES = new Set(['true', 'false', 'null', 'undefined']);

function isNumeric(value: string): boolean {
  return /^\d+(\.\d+)?$/.test(value);
}

function formatValue(value: string): string {
  if (UNQUOTED_VALUES.has(value) || isNumeric(value)) {
    return value;
  }
  return `'${value}'`;
}

function serializeCondition(row: ConditionRow): string {
  const { field, operator, value } = row;
  if (operator === 'includes') {
    return `${field}.includes(${formatValue(value)})`;
  }
  if (operator === '!includes') {
    return `!${field}.includes(${formatValue(value)})`;
  }
  return `${field} ${operator} ${formatValue(value)}`;
}

export function serialize(group: ConditionGroup): string {
  if (group.conditions.length === 0) return '';
  const joiner = group.operator === 'and' ? ' && ' : ' || ';
  return group.conditions.map(serializeCondition).join(joiner);
}

const COMPARISON_RE = /^([\w.$]+)\s*(===|!==|>=|<=|>|<)\s*('([^']*)'|([\d.]+)|true|false|null|undefined)$/;
const INCLUDES_RE = /^([\w.$]+)\.includes\('([^']*)'\)$/;
const NOT_INCLUDES_RE = /^!([\w.$]+)\.includes\('([^']*)'\)$/;

let nextId = 1;
function genId(): string {
  return `cond_${nextId++}`;
}

function parseCondition(token: string): ConditionRow | null {
  const trimmed = token.trim();

  const notInclMatch = NOT_INCLUDES_RE.exec(trimmed);
  if (notInclMatch) {
    return { id: genId(), field: notInclMatch[1], operator: '!includes', value: notInclMatch[2] };
  }

  const inclMatch = INCLUDES_RE.exec(trimmed);
  if (inclMatch) {
    return { id: genId(), field: inclMatch[1], operator: 'includes', value: inclMatch[2] };
  }

  const compMatch = COMPARISON_RE.exec(trimmed);
  if (compMatch) {
    const field = compMatch[1];
    const op = compMatch[2] as ConditionRow['operator'];
    const value = compMatch[4] ?? compMatch[5] ?? compMatch[3];
    return { id: genId(), field, operator: op, value };
  }

  return null;
}

export function deserialize(expr: string): ConditionGroup | null {
  if (!expr || !expr.trim()) return null;

  const trimmed = expr.trim();

  const hasAnd = trimmed.includes('&&');
  const hasOr = trimmed.includes('||');
  if (hasAnd && hasOr) return null;

  if (trimmed.includes('(') || trimmed.includes(')')) {
    const withoutIncludes = trimmed.replace(/!?[\w.$]+\.includes\('[^']*'\)/g, '');
    if (withoutIncludes.includes('(') || withoutIncludes.includes(')')) {
      return null;
    }
  }

  const groupOp: 'and' | 'or' = hasOr ? 'or' : 'and';
  const separator = hasOr ? '||' : '&&';
  const tokens = trimmed.split(separator);

  const conditions: ConditionRow[] = [];
  for (const token of tokens) {
    const parsed = parseCondition(token);
    if (!parsed) return null;
    conditions.push(parsed);
  }

  return { operator: groupOp, conditions };
}
