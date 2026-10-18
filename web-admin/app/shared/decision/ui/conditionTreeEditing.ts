import type { ConditionNode, GroupNode, CompareNode, PathOperand } from '../ast/conditionAst';
import type { FieldOption } from './ConditionBuilder';

export const modelKey = (field: FieldOption): string =>
  `${field.scope}:${field.modelCode ?? field.entityCode ?? field.scope}`;
export const operandKey = (operand: Pick<PathOperand, 'scope' | 'path'>): string =>
  `${operand.scope}:${operand.path}`;
export const nodeKey = (parts: number[]): string => ['root', ...parts].join('.');

export function compatibleFields(left: FieldOption, right: FieldOption): boolean {
  if (right.visible === false || right.masked) return false;
  if (left.dataType !== right.dataType) return false;
  if (left.dictCode || right.dictCode)
    return Boolean(left.dictCode && left.dictCode === right.dictCode);
  if (left.dataType === 'enum')
    return Boolean(
      left.options &&
      right.options &&
      left.options.length === right.options.length &&
      left.options.every((value) => right.options!.includes(value)),
    );
  if (left.reference || right.reference)
    return left.reference?.targetEntity === right.reference?.targetEntity;
  return true;
}

export function nodeAt(root: ConditionNode, parts: number[]): ConditionNode | undefined {
  if (!parts.length) return root;
  if (root.type === 'compare') return undefined;
  const child =
    root.type === 'not' ? (parts[0] === 0 ? root.child : undefined) : root.children[parts[0]];
  return child ? nodeAt(child, parts.slice(1)) : undefined;
}

export function replaceAt(
  root: ConditionNode,
  parts: number[],
  next: ConditionNode,
): ConditionNode {
  if (!parts.length) return next;
  if (root.type === 'compare') throw new Error('Invalid condition path');
  if (root.type === 'not') return { ...root, child: replaceAt(root.child, parts.slice(1), next) };
  return {
    ...root,
    children: root.children.map((child, index) =>
      index === parts[0] ? replaceAt(child, parts.slice(1), next) : child,
    ),
  };
}

/** Only contiguous siblings can be wrapped, so no unselected operand silently moves. */
export function wrapSelection(root: GroupNode, selected: number[][]): GroupNode | null {
  if (selected.length < 2) return null;
  const parentPath = selected[0].slice(0, -1);
  if (selected.some((parts) => JSON.stringify(parts.slice(0, -1)) !== JSON.stringify(parentPath)))
    return null;
  const parent = nodeAt(root, parentPath);
  if (parent?.type !== 'group') return null;
  const indices = selected.map((parts) => parts[parts.length - 1]).sort((a, b) => a - b);
  if (indices.some((index, offset) => index !== indices[0] + offset)) return null;
  const children = [...parent.children];
  const grouped: GroupNode = {
    type: 'group',
    op: parent.op,
    children: children.splice(indices[0], indices.length),
  };
  children.splice(indices[0], 0, grouped);
  return replaceAt(root, parentPath, { ...parent, children }) as GroupNode;
}

export function conditionLeaves(
  root: ConditionNode,
  parts: number[] = [],
): { node: CompareNode; parts: number[] }[] {
  if (root.type === 'compare') return root.enabled === false ? [] : [{ node: root, parts }];
  if (root.type === 'not') return conditionLeaves(root.child, [...parts, 0]);
  return root.children.flatMap((child, index) => conditionLeaves(child, [...parts, index]));
}
