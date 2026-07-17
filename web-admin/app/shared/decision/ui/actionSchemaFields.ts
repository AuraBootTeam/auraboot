import type { DecisionAction } from '../api/decisionApi';

export interface ActionSchemaField {
  path: string;
  label: string;
  dataType: string;
  required: boolean;
}

function recordOf(value: unknown): Record<string, unknown> | undefined {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? (value as Record<string, unknown>)
    : undefined;
}

function stringValue(value: unknown): string | undefined {
  return typeof value === 'string' && value.trim() ? value : undefined;
}

function requiredPaths(schema: Record<string, unknown> | undefined): string[] {
  const required = schema?.required;
  return Array.isArray(required)
    ? required.filter((item): item is string => typeof item === 'string' && item.trim().length > 0)
    : [];
}

export function actionSchemaFields(action?: DecisionAction): ActionSchemaField[] {
  const schema = recordOf(action?.inputSchema);
  const fields = recordOf(schema?.fields);
  if (!fields) return [];

  const required = requiredPaths(schema);
  const requiredOrder = new Map(required.map((path, index) => [path, index]));
  return Object.entries(fields)
    .map(([path, meta]) => {
      const field = recordOf(meta);
      return {
        path,
        label: stringValue(field?.label) ?? path,
        dataType: stringValue(field?.dataType) ?? 'string',
        required: Boolean(field?.required) || requiredOrder.has(path),
      };
    })
    .sort((a, b) => {
      const aRequired = requiredOrder.get(a.path);
      const bRequired = requiredOrder.get(b.path);
      if (aRequired !== undefined && bRequired !== undefined) return aRequired - bRequired;
      if (aRequired !== undefined) return -1;
      if (bRequired !== undefined) return 1;
      return a.path.localeCompare(b.path);
    });
}

export function actionDefinitionFor(
  actionType: string,
  catalog: DecisionAction[],
): DecisionAction | undefined {
  return catalog.find((item) => item.actionType === actionType);
}

export function parsePayloadJson(value: string): Record<string, unknown> {
  if (!value.trim()) return {};
  const parsed = JSON.parse(value) as unknown;
  return recordOf(parsed) ?? {};
}

export function payloadToJson(payload: Record<string, unknown> | undefined): string {
  return JSON.stringify(payload ?? {}, null, 2);
}

export function readActionFieldValue(
  target: string | undefined,
  payload: Record<string, unknown> | undefined,
  field: ActionSchemaField,
): string {
  if (field.path === 'target') return target ?? '';
  if (field.path === 'payload') return payloadToJson(payload);
  if (!field.path.startsWith('payload.')) return '';

  const key = field.path.slice('payload.'.length);
  const value = payload?.[key];
  if (value == null) return '';
  if (field.dataType === 'object' || Array.isArray(value) || typeof value === 'object') {
    return JSON.stringify(value, null, 2);
  }
  return String(value);
}

export function writeActionFieldValue(
  target: string | undefined,
  payload: Record<string, unknown> | undefined,
  field: ActionSchemaField,
  rawValue: string,
): { target?: string; payload: Record<string, unknown> } {
  if (field.path === 'target') {
    return { target: rawValue, payload: payload ?? {} };
  }
  if (field.path === 'payload') {
    return { target, payload: parsePayloadJson(rawValue) };
  }
  if (!field.path.startsWith('payload.')) {
    return { target, payload: payload ?? {} };
  }

  const key = field.path.slice('payload.'.length);
  const nextPayload = { ...(payload ?? {}) };
  if (field.dataType === 'object') {
    nextPayload[key] = parsePayloadJson(rawValue);
  } else {
    nextPayload[key] = rawValue;
  }
  return { target, payload: nextPayload };
}

export function actionFieldInputKind(field: ActionSchemaField): 'json' | 'textarea' | 'text' {
  if (field.dataType === 'object') return 'json';
  if (field.dataType === 'text') return 'textarea';
  return 'text';
}
