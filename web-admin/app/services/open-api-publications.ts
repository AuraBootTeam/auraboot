export type PublicOperation = 'list' | 'get' | 'create' | 'update' | 'delete';
export type PublicType = 'string' | 'pid' | 'integer' | 'decimal' | 'boolean' | 'date' | 'datetime' | 'object' | 'array';
export interface PublicField {
  source: string; type: PublicType; readable: boolean; writable: boolean; required: boolean; nullable: boolean;
  operators: string[]; sortable: boolean; referenceResource: string | null;
  properties: Record<string, PublicField>; items: PublicField | null;
}
export interface PublicResource {
  code: string; modelCode: string; schemaVersion: number; operations: PublicOperation[];
  scopes: Partial<Record<PublicOperation, string>>; dataPolicy: 'tenant_wide' | 'installation_filter';
  fields: Record<string, PublicField>; maxPageSize: number; maxConditions: number; timeoutMs: number; events: string[];
}
export interface PublicationView { code: string; draft: PublicResource; draftRevision: number; activeVersion: number | null; status: string }
export interface PublicationSourceField {
  code: string; name: string; types: PublicType[]; writable: boolean; required: boolean;
  sortable: boolean; filterable: boolean; referenceModel: string | null; referencePid: boolean; eventReadable: boolean;
}
export interface PublicationSource { modelCode: string; name: string; operations: PublicOperation[]; eventsAllowed: boolean; fields: PublicationSourceField[] }
export interface ModelChoice { code: string; displayName: string; status: string }
export class PublicationApiError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}
export async function publicationRequest<T>(path: string, init?: RequestInit): Promise<T> {
  const response = await fetch(path, { ...init, headers: { 'Content-Type': 'application/json', ...init?.headers } });
  const body = await response.json();
  if (!response.ok || String(body.code) !== '0') throw new PublicationApiError(response.status, body.message ?? `HTTP ${response.status}`);
  return body.data as T;
}
export const identityField = (): PublicField => ({ source: 'pid', type: 'pid', readable: true, writable: false,
  required: false, nullable: false, operators: [], sortable: false, referenceResource: null, properties: {}, items: null });
export const sourceField = (source: PublicationSourceField): PublicField => ({ source: source.code,
  type: source.types.includes('string') ? 'string' : source.types[0], readable: true, writable: source.writable,
  required: source.required, nullable: !source.required, operators: [], sortable: false,
  referenceResource: null, properties: {}, items: null });

export function publicValueType(field: PublicField, type: PublicType): PublicField {
  const scalar: PublicField = { ...field, source: 'value', type: 'string', required: false,
    operators: [], sortable: false, referenceResource: null, properties: {}, items: null };
  return { ...field, type, operators: ['object', 'array'].includes(type) ? [] : field.operators,
    sortable: ['object', 'array'].includes(type) ? false : field.sortable,
    properties: type === 'object' ? Object.keys(field.properties).length ? field.properties : { value: scalar } : {},
    items: type === 'array' ? field.items ?? { ...scalar, source: 'item' } : null };
}
