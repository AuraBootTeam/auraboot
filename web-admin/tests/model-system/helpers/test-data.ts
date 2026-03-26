/**
 * Test data factory for E2E tests
 * Generates unique test data to avoid conflicts
 */

export type ModelType = 'entity' | 'view' | 'aggregate';
export type FieldDataType = 'string' | 'integer' | 'decimal' | 'date' | 'datetime' | 'json' | 'reference' | 'boolean';
export type DictType = 'simple' | 'tree';
export type VirtualType = 'computed_readonly' | 'materialized' | 'transient';

/**
 * Generate unique code with prefix and timestamp
 */
export function generateCode(prefix: string): string {
  const timestamp = Date.now();
  const random = Math.random().toString(36).slice(2, 7);
  return `e2e_${prefix}_${timestamp}_${random}`;
}

/**
 * Generate unique PID
 */
export function generatePid(): string {
  const timestamp = Date.now();
  const random = Math.random().toString(36).slice(2, 10);
  return `pid_${timestamp}_${random}`;
}

/**
 * Model test data interface
 */
export interface ModelTestData {
  code: string;
  displayName: string;
  modelType: ModelType;
  description?: string;
  namespace?: string;
  env?: string;
  extension?: Record<string, any>;
}

/**
 * Create model test data
 */
export function createModelData(overrides: Partial<ModelTestData> = {}): ModelTestData {
  const code = generateCode('model');
  return {
    code,
    displayName: `Test Model ${code}`,
    modelType: 'entity',
    description: 'E2E test model',
    namespace: 'default',
    env: 'dev',
    ...overrides
  };
}

/**
 * Field test data interface
 */
export interface FieldTestData {
  code: string;
  dataType: FieldDataType;
  modelPid?: string;
  feature?: {
    required?: boolean;
    unique?: boolean;
    length?: number;
    precision?: number;
    scale?: number;
  };
  extension?: Record<string, any>;
  uiSchema?: {
    label?: string;
    placeholder?: string;
    component?: string;
  };
}

/**
 * Create field test data
 */
export function createFieldData(
  dataType: FieldDataType = 'string',
  overrides: Partial<FieldTestData> = {}
): FieldTestData {
  const code = generateCode('field');
  return {
    code,
    dataType,
    feature: {
      required: false,
      unique: false,
      ...overrides.feature
    },
    uiSchema: {
      label: `Test Field ${code}`,
      placeholder: `Enter ${dataType.toLowerCase()} value`,
      ...overrides.uiSchema
    },
    ...overrides
  };
}

/**
 * Virtual field test data interface
 */
export interface VirtualFieldTestData extends FieldTestData {
  extension: {
    virtualType: VirtualType;
    expression?: string;
    dependsOn?: string[];
    materialized?: boolean;
  };
}

/**
 * Create virtual field test data
 */
export function createVirtualFieldData(
  virtualType: VirtualType,
  overrides: Partial<VirtualFieldTestData> = {}
): VirtualFieldTestData {
  const code = generateCode('vfield');
  return {
    code,
    dataType: 'string',
    feature: {
      required: false,
      unique: false
    },
    extension: {
      virtualType,
      expression: overrides.extension?.expression || '#value',
      dependsOn: overrides.extension?.dependsOn || [],
      materialized: virtualType === 'materialized',
      ...overrides.extension
    },
    uiSchema: {
      label: `Virtual Field ${code}`,
      ...overrides.uiSchema
    },
    ...overrides
  };
}

/**
 * Dictionary test data interface
 */
export interface DictTestData {
  code: string;
  name: string;
  dictType: DictType;
  sourceType: string;  // Required: STATIC, API, SQL, etc.
  description?: string;
  items: DictItemData[];
}

/**
 * Dictionary item test data interface
 */
export interface DictItemData {
  value: string;
  label: string;
  sortOrder?: number;
  parentValue?: string;
  disabled?: boolean;
  extension?: Record<string, any>;
}

/**
 * Create dictionary test data
 */
export function createDictData(
  dictType: DictType = 'simple',
  overrides: Partial<DictTestData> = {}
): DictTestData {
  const code = generateCode('dict');
  const defaultItems: DictItemData[] = dictType === 'tree'
    ? [
        { value: 'root', label: 'Root', sortOrder: 1 },
        { value: 'child1', label: 'Child 1', parentValue: 'root', sortOrder: 2 },
        { value: 'child2', label: 'Child 2', parentValue: 'root', sortOrder: 3 }
      ]
    : [
        { value: 'option1', label: 'Option 1', sortOrder: 1 },
        { value: 'option2', label: 'Option 2', sortOrder: 2 },
        { value: 'option3', label: 'Option 3', sortOrder: 3 }
      ];

  return {
    code,
    name: `Test Dictionary ${code}`,
    dictType,
    sourceType: 'static',  // Required field - default to STATIC for manual items
    description: 'E2E test dictionary',
    items: overrides.items || defaultItems,
    ...overrides
  };
}

/**
 * Field binding test data interface
 */
export interface FieldBindingTestData {
  fieldPid: string;
  required?: boolean;
  readonly?: boolean;
  visible?: boolean;
  displayOrder?: number;
  dictCode?: string;
  defaultValue?: any;
  extension?: Record<string, any>;
}

/**
 * Create field binding test data
 */
export function createFieldBindingData(
  fieldPid: string,
  overrides: Partial<FieldBindingTestData> = {}
): FieldBindingTestData {
  return {
    fieldPid,
    required: false,
    readonly: false,
    visible: true,
    displayOrder: 0,
    ...overrides
  };
}

/**
 * Test data collections for cleanup
 */
export class TestDataRegistry {
  private models: string[] = [];
  private fields: string[] = [];
  private dicts: string[] = [];

  registerModel(code: string): void {
    this.models.push(code);
  }

  registerField(code: string): void {
    this.fields.push(code);
  }

  registerDict(code: string): void {
    this.dicts.push(code);
  }

  getModels(): string[] {
    return [...this.models];
  }

  getFields(): string[] {
    return [...this.fields];
  }

  getDicts(): string[] {
    return [...this.dicts];
  }

  clear(): void {
    this.models = [];
    this.fields = [];
    this.dicts = [];
  }
}

/**
 * Global test data registry instance
 */
export const testDataRegistry = new TestDataRegistry();
