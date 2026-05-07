export type {
  PropertyType,
  PropertySchema,
  ValidationResult,
  ValidationError,
  BaseDefinition,
} from './types';

export { DesignerRegistry, createRegistry } from './types';

export { PropertyFieldRenderer } from './PropertyFieldRenderer';
export type { PropertyFieldRendererProps } from './PropertyFieldRenderer';

export { LocalizedTextInput } from './LocalizedTextInput';
export type { LocalizedTextInputProps, LocalizedTextValue } from './LocalizedTextInput';

export { DesignerToolbar } from './DesignerToolbar';
export type { DesignerToolbarProps } from './DesignerToolbar';

export { DesignerPalette } from './DesignerPalette';
export type { PaletteItem, DesignerPaletteProps } from './DesignerPalette';

export { DesignerEmptyState } from './DesignerEmptyState';
export type { DesignerEmptyStateProps } from './DesignerEmptyState';

export { DESIGNER_I18N, resolveDesignerText } from './designerI18n';

export { SchemaBlockConfigPanel } from './SchemaBlockConfigPanel';
export type { ExtendedPropertySchema, SchemaBlockConfigPanelProps } from './SchemaBlockConfigPanel';
