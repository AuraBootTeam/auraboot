import type { DslBlockV3 } from '../../types';
import type { RuntimePickerOption } from '../runtimeExecution';
import type { RuntimeFieldComponent, RuntimeSelectOption } from './runtimeContexts';
import { getStringProp } from './runtimePresentation';





/**
 * `field` blocks whose configured component is executed by the *designer runtime* and
 * therefore cannot be represented by a platform `FieldConfig`, so they stay on the
 * designer's own renderer even when model metadata is available (true-WYSIWYG path).
 *
 * Only `picker` qualifies. The designer's picker is a data-source component: its option
 * source is authored as `pickerDataSource` (`model` | `named-query`) + `pickerSource` /
 * `pickerQueryCode` / `valueField` / `displayField` / `pageSize` and executed through
 * `runtimeServices.loadPickerOptions` → `/api/query-builder/execute`, including
 * server-side `searchable` filtering. A platform `FieldConfig` can only bind a dict, a
 * model reference, or a static option list, so routing a picker through the platform
 * control would silently drop the authored option source (the "shared renderer silently
 * ignores DSL config" class of bug) — on top of the platform registry having no generic
 * `picker` at all, which is what produced "Unknown component: picker".
 *
 * The edit canvas has no designer runtime to execute that option source, so there
 * `buildPreviewFieldConfig` still translates `picker` into the closest real platform
 * picker (`resolvePickerPlatformComponent`) instead of rendering an error box.
 */
export function isDesignerRuntimeOnlyComponent(block: DslBlockV3): boolean {
  return normalizeRuntimeFieldComponent(block.props?.component) === 'picker';
}

export function normalizeRuntimeFieldComponent(value: unknown): RuntimeFieldComponent {
  const component = getStringProp(value).toLowerCase();
  if (
    component === 'textarea' ||
    component === 'select' ||
    component === 'date' ||
    component === 'number' ||
    component === 'checkbox' ||
    component === 'switch' ||
    component === 'radio' ||
    component === 'upload' ||
    component === 'picker' ||
    component === 'rich-text'
  ) {
    return component;
  }
  if (component === 'money' || component === 'moneyinput') return 'number';
  if (component === 'richtext' || component === 'rich_text') return 'rich-text';
  return 'input';
}

export function getRuntimeFieldControlId(block: DslBlockV3, component: RuntimeFieldComponent): string {
  if (component === 'textarea') return `runtime-textarea-${block.id}`;
  if (component === 'select') return `runtime-select-${block.id}`;
  if (component === 'checkbox') return `runtime-checkbox-${block.id}`;
  if (component === 'switch') return `runtime-switch-${block.id}`;
  if (component === 'radio') return `runtime-radio-${block.id}`;
  if (component === 'upload') return `runtime-upload-${block.id}`;
  if (component === 'picker') return `runtime-picker-${block.id}`;
  if (component === 'rich-text') return `runtime-rich-text-${block.id}`;
  return `runtime-input-${block.id}`;
}

export function normalizeRuntimePickerOptions(options: RuntimePickerOption[]): RuntimeSelectOption[] {
  return options.flatMap((option) => {
    const value = getStringProp(option.value);
    const label = getStringProp(option.label) || value;
    return value ? [{ label, value }] : [];
  });
}

export function getRuntimeSelectOptions(value: unknown): RuntimeSelectOption[] {
  if (!Array.isArray(value)) return [];
  return value.flatMap((item) => {
    if (typeof item === 'string' || typeof item === 'number' || typeof item === 'boolean') {
      const optionValue = String(item);
      return [{ label: optionValue, value: optionValue }];
    }
    if (!item || typeof item !== 'object') return [];

    const option = item as Record<string, unknown>;
    const optionValue = getStringProp(option.value || option.code || option.key);
    const optionLabel = getStringProp(option.label || option.name || option.title) || optionValue;
    return optionValue ? [{ label: optionLabel, value: optionValue }] : [];
  });
}

export function hasExecutablePickerDataSource(block: DslBlockV3): boolean {
  return Boolean(getRuntimePickerDataSourceMode(block));
}

export function getRuntimePickerMeta(block: DslBlockV3): string {
  const mode = getRuntimePickerDataSourceMode(block);
  const source =
    mode === 'named-query'
      ? getStringProp(block.props?.pickerQueryCode) || getStringProp(block.props?.pickerSource)
      : getStringProp(block.props?.pickerSource);
  const displayField = getStringProp(block.props?.displayField);
  const valueField = getStringProp(block.props?.valueField);
  return [mode, source, displayField, valueField].filter(Boolean).join(' / ');
}

function getRuntimePickerDataSourceMode(block: DslBlockV3): string {
  const source = getStringProp(block.props?.pickerDataSource || block.dataSource?.type)
    .replace(/[_\s]/g, '-')
    .toLowerCase();
  if (source === 'model' || source === 'query-builder' || source === 'dynamic-model') {
    return 'model';
  }
  if (source === 'namedquery' || source === 'named-query' || source === 'nq') {
    return 'named-query';
  }
  return '';
}
