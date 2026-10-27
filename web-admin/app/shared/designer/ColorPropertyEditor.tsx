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

/** Stable imported component identity makes repeated SSR initialization idempotent. */
export function registerColorPropertyEditor(): () => void {
  return propertyEditorRegistry.register('designer.color', ColorPropertyEditor);
}
