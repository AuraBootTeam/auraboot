import { ColorPicker } from '~/plugins/core-designer/components/studio/workbench/panels/property-editors/ColorPicker';
import { useI18n } from '~/contexts/I18nContext';
import { getLocalizedText } from '~/utils/i18n';
import {
  propertyEditorRegistry,
  type RegisteredPropertyEditorProps,
} from './PropertyEditorRegistry';

function ColorPropertyEditor({ schema, adapter }: RegisteredPropertyEditorProps) {
  const { locale } = useI18n();
  return (
    <ColorPicker
      value={typeof adapter.value === 'string' ? adapter.value : ''}
      label={getLocalizedText(schema.label, locale)}
      disabled={adapter.disabled || adapter.readOnly}
      onChange={(value) => adapter.setValue(value)}
    />
  );
}

// Stable across module generations, but separate from ordinary extension registration.
const colorEditorOwner = Symbol.for('auraboot.designer.builtin.color');

/** Register the built-in module through its explicit SSR/HMR lifecycle owner. */
export function registerColorPropertyEditor(): () => void {
  return propertyEditorRegistry.registerOwned('designer.color', ColorPropertyEditor, colorEditorOwner);
}
