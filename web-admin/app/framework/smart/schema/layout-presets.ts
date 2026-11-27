/**
 * Layout Preset definitions.
 *
 * Provides pre-configured form layout templates
 * that determine column count, label positioning, and spacing.
 *
 * @since 3.2.0
 */

/**
 * Layout preset type.
 */
export type LayoutPresetType = 'form' | 'list';

/**
 * Form layout configuration within a preset.
 */
export interface FormLayoutConfig {
  columns: number;
  labelPosition: 'top' | 'left' | 'inline';
  fieldSpacing: number;
  sectionSpacing: number;
}

/**
 * Layout preset definition.
 */
export interface LayoutPreset {
  code: string;
  name: string;
  description: string;
  type: LayoutPresetType;
  formLayout: FormLayoutConfig;
}

/**
 * Pre-defined form layout presets.
 */
export const FORM_LAYOUT_PRESETS: LayoutPreset[] = [
  {
    code: 'single-column',
    name: '1 Column',
    description: 'Single column layout, one field per row',
    type: 'form',
    formLayout: {
      columns: 1,
      labelPosition: 'top',
      fieldSpacing: 16,
      sectionSpacing: 24,
    },
  },
  {
    code: 'two-column',
    name: '2 Columns',
    description: 'Two column layout for compact forms',
    type: 'form',
    formLayout: {
      columns: 2,
      labelPosition: 'top',
      fieldSpacing: 16,
      sectionSpacing: 24,
    },
  },
  {
    code: 'three-column',
    name: '3 Columns',
    description: 'Three column layout for wide screens',
    type: 'form',
    formLayout: {
      columns: 3,
      labelPosition: 'top',
      fieldSpacing: 12,
      sectionSpacing: 20,
    },
  },
  {
    code: 'four-column',
    name: '4 Columns',
    description: 'Four column layout for dense data entry',
    type: 'form',
    formLayout: {
      columns: 4,
      labelPosition: 'top',
      fieldSpacing: 8,
      sectionSpacing: 16,
    },
  },
];

/**
 * Get a preset by code.
 */
export function getPresetByCode(code: string): LayoutPreset | undefined {
  return FORM_LAYOUT_PRESETS.find((p) => p.code === code);
}

/**
 * Get the default preset.
 */
export function getDefaultPreset(): LayoutPreset {
  return FORM_LAYOUT_PRESETS[1]; // two-column as default
}
