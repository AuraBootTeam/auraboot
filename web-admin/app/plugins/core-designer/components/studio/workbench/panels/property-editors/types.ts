/**
 * Property Editor Types
 *
 * Common types for property editors.
 *
 * @since 3.2.0
 */

/**
 * Base property editor props
 */
export interface BaseEditorProps<T> {
  /** Current value */
  value: T;
  /** Value change handler */
  onChange: (value: T) => void;
  /** Whether the editor is disabled */
  disabled?: boolean;
  /** Placeholder text */
  placeholder?: string;
  /** Label */
  label?: string;
  /** Error message */
  error?: string;
  /** Additional class name */
  className?: string;
}

/**
 * Color value types
 */
export type ColorFormat = 'hex' | 'rgb' | 'rgba' | 'hsl' | 'hsla';

export interface ColorValue {
  /** Hex color code */
  hex: string;
  /** RGB values */
  rgb: { r: number; g: number; b: number };
  /** Alpha/opacity (0-1) */
  alpha: number;
}

/**
 * Color preset
 */
export interface ColorPreset {
  /** Preset name */
  name: string;
  /** Color value */
  color: string;
}

/**
 * Icon categories
 */
export type IconCategory =
  | 'action'
  | 'alert'
  | 'av'
  | 'communication'
  | 'content'
  | 'device'
  | 'editor'
  | 'file'
  | 'hardware'
  | 'image'
  | 'maps'
  | 'navigation'
  | 'notification'
  | 'places'
  | 'social'
  | 'toggle';

/**
 * Icon definition
 */
export interface IconDefinition {
  /** Icon name/id */
  name: string;
  /** Category */
  category: IconCategory;
  /** Display label */
  label: string;
  /** SVG path or icon component */
  icon: string;
  /** Tags for search */
  tags?: string[];
}

/**
 * JSON validation result
 */
export interface JsonValidationResult {
  /** Whether JSON is valid */
  valid: boolean;
  /** Error message if invalid */
  error?: string;
  /** Error position */
  position?: {
    line: number;
    column: number;
  };
}

/**
 * JSON schema for validation
 */
export interface JsonSchema {
  /** Schema type */
  type?: 'object' | 'array' | 'string' | 'number' | 'boolean' | 'null';
  /** Object properties */
  properties?: Record<string, JsonSchema>;
  /** Required properties */
  required?: string[];
  /** Array items schema */
  items?: JsonSchema;
  /** Enum values */
  enum?: unknown[];
}
