import type {
  AuthoringRoleStructureDecision,
  AuthoringRoleStructurePreview,
} from '~/framework/meta/authoring/types';
import type { PageSchemaV3 } from '../types';
import {
  RuntimeExecutionError,
  type RuntimeExecutionServices,
} from '../runtime/runtimeExecution';

const RECORD_VALUE_KEYS = new Set([
  'rows',
  'records',
  'record',
  'items',
  'data',
  'value',
  'defaultValue',
  'currentRow',
  'selectedRows',
  'sampleData',
]);

/**
 * A no-data execution boundary for role structure preview. Every read resolves to an empty
 * projection, while every business action fails closed even if a caller bypasses the disabled UI.
 */
export const roleStructurePreviewRuntimeServices: RuntimeExecutionServices = {
  loadWidgetData: async () => null,
  loadPickerOptions: async () => [],
  loadHelperBlockData: async () => null,
  executeAction: async () => {
    throw new RuntimeExecutionError({
      kind: 'permission',
      code: 'ROLE_STRUCTURE_PREVIEW_ACTION_DISABLED',
      message: '角色结构预览不执行业务动作',
      hint: '退出角色结构预览后再执行操作',
    });
  },
};

/** Remove any record-shaped values embedded in the schema while preserving layout metadata. */
export function sanitizeRoleStructurePreviewDocument(document: PageSchemaV3): PageSchemaV3 {
  return sanitizeValue(document) as PageSchemaV3;
}

export function createRoleStructurePermissionEvaluator(
  preview: AuthoringRoleStructurePreview,
): (permissionCode: string) => boolean {
  const decisions = new Map<string, boolean>();
  preview.decisions.forEach((decision) => {
    const code = normalizePermissionCode(decision.permissionCode);
    if (!code) return;
    decisions.set(code, Boolean(decisions.get(code)) || decision.allowed);
  });
  return (permissionCode: string) => decisions.get(normalizePermissionCode(permissionCode) ?? '') === true;
}

export function summarizeRoleStructureDecisions(
  decisions: AuthoringRoleStructureDecision[],
): Array<{ nodeType: AuthoringRoleStructureDecision['nodeType']; allowed: number; total: number }> {
  const nodeTypes: AuthoringRoleStructureDecision['nodeType'][] = [
    'MENU',
    'BLOCK',
    'FIELD',
    'ACTION',
  ];
  return nodeTypes
    .map((nodeType) => {
      const matching = decisions.filter((decision) => decision.nodeType === nodeType);
      return {
        nodeType,
        allowed: matching.filter((decision) => decision.visible).length,
        total: matching.length,
      };
    })
    .filter((item) => item.total > 0);
}

function sanitizeValue(value: unknown, key?: string): unknown {
  if (key && RECORD_VALUE_KEYS.has(key)) return undefined;
  if (Array.isArray(value)) {
    return value.map((item) => sanitizeValue(item)).filter((item) => item !== undefined);
  }
  if (!value || typeof value !== 'object') return value;
  return Object.fromEntries(
    Object.entries(value as Record<string, unknown>)
      .map(([entryKey, entryValue]) => [entryKey, sanitizeValue(entryValue, entryKey)] as const)
      .filter((entry): entry is readonly [string, unknown] => entry[1] !== undefined),
  );
}

function normalizePermissionCode(value?: string | null): string | null {
  const normalized = value?.trim().toLowerCase();
  return normalized || null;
}
