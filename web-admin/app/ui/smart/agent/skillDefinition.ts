/** Lossless adapters for the structured skill editor. Unsupported schemas stay read-only. */
export interface InputField {
  name: string;
  title: string;
  type: 'string' | 'number' | 'integer' | 'boolean';
  required: boolean;
}
export interface SkillSchema {
  type?: string;
  properties?: Record<string, Record<string, unknown>>;
  required?: string[];
  additionalProperties?: boolean;
  [key: string]: unknown;
}
export interface SkillRecord {
  pid?: string;
  skill_code: string;
  skill_name: string;
  skill_description?: string;
  skill_level?: string;
  skill_category?: string;
  skill_version?: string;
  skill_status?: string;
  skill_tools?: unknown;
  skill_input_schema?: unknown;
  prompt_template?: string;
  execution_mode?: string;
  max_steps?: number;
  is_builtin?: boolean;
  step_input_mappings?: unknown;
}
export function stringList(value: unknown): string[] {
  const parsed = typeof value === 'string' ? JSON.parse(value || '[]') : value;
  if (parsed == null) return [];
  if (!Array.isArray(parsed) || parsed.some((item) => typeof item !== 'string'))
    throw new Error('Invalid tool binding');
  return parsed;
}
export function inputSchema(value: unknown): SkillSchema {
  const parsed = typeof value === 'string' ? JSON.parse(value || '{}') : value;
  if (parsed == null) return { type: 'object', properties: {} };
  if (typeof parsed !== 'object' || Array.isArray(parsed)) throw new Error('Invalid input schema');
  return parsed as SkillSchema;
}
export function editableSchema(schema: SkillSchema): boolean {
  const rootKeys = ['type', 'properties', 'required', 'additionalProperties'];
  return (
    (!schema.type || schema.type === 'object') &&
    Object.keys(schema).every((key) => rootKeys.includes(key)) &&
    Object.values(schema.properties ?? {}).every(
      (field) =>
        ['string', 'number', 'integer', 'boolean'].includes(String(field.type)) &&
        Object.keys(field).every((key) => ['type', 'title', 'description'].includes(key)),
    )
  );
}
export function fieldsFromSchema(schema: SkillSchema): InputField[] {
  return Object.entries(schema.properties ?? {}).map(([name, field]) => ({
    name,
    title: String(field.title ?? field.description ?? name),
    type: field.type as InputField['type'],
    required: (schema.required ?? []).includes(name),
  }));
}
export function schemaFromFields(fields: InputField[], original: SkillSchema): SkillSchema {
  const names = fields.map((field) => field.name);
  if (
    names.some((name) => !/^[A-Za-z_][A-Za-z0-9_]*$/.test(name)) ||
    new Set(names).size !== names.length
  )
    throw new Error('invalidFields');
  return {
    ...original,
    type: 'object',
    properties: Object.fromEntries(
      fields.map((field) => [
        field.name,
        { ...(original.properties?.[field.name] ?? {}), type: field.type, title: field.title },
      ]),
    ),
    required: fields.filter((field) => field.required).map((field) => field.name),
  };
}
export function bindSkill(existing: unknown, code: string): string[] {
  return [...new Set([...stringList(existing), code])];
}
