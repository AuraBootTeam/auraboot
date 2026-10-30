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

/**
 * Stable imported component identity makes repeated SSR initialization idempotent.
 *
 * Vite SSR may still evaluate this module via two resolved paths (direct import plus
 * barrel re-export), giving the second registration a fresh component identity that
 * the registry treats as a conflict. The first registration already serves every
 * reader, so the duplicate is swallowed instead of failing the page behind the
 * dev error overlay.
 */
export function registerColorPropertyEditor(): () => void {
  try {
    return propertyEditorRegistry.register('designer.color', ColorPropertyEditor);
  } catch {
    // already registered via the other resolution path — safe to ignore
    return () => {};
  }
}
