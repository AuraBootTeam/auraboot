/**
 * Preview enhancement types for mock data generation and preview modes.
 *
 * @since 3.6.0
 */

export type PreviewMode = 'empty' | 'mock';

export type FieldDataType =
  | 'string'
  | 'text'
  | 'integer'
  | 'decimal'
  | 'boolean'
  | 'date'
  | 'datetime'
  | 'enum'
  | 'email'
  | 'phone'
  | 'url';

export interface MockFieldConfig {
  fieldCode: string;
  label: string;
  dataType: FieldDataType;
  options?: string[]; // for ENUM type
  locked: boolean; // if true, value is user-fixed and won't regenerate
  value: any;
}

export interface MockDataConfig {
  fields: MockFieldConfig[];
  autoGenerate: boolean;
}

export interface PreviewFieldDef {
  code: string;
  label: string;
  dataType: string;
  semanticType?: string;
  required?: boolean;
  options?: { label: string; value: string }[];
}
