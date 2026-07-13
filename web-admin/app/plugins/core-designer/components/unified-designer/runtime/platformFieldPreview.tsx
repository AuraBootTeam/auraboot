/**
 * Shared WYSIWYG field preview for the unified designer.
 *
 * Both the design-time canvas (`CanvasHost`, edit/layout modes) and the in-designer
 * runtime preview (`RecursiveBlockRenderer`, preview mode) render form `field` blocks
 * through the *real* platform control (`ControlledFieldRenderer`, the same renderer the
 * live `/p/` page uses) so the designer is true WYSIWYG — instead of a generic input
 * labeled with the raw field code.
 *
 * `buildPreviewFieldConfig` maps a designer block + resolved model-field metadata into a
 * platform `FieldConfig`. `EditCanvasFieldPreview` is a self-contained, non-interactive
 * variant (read-only + `pointer-events-none` + its own `DataSourceManager`) for the edit
 * canvas, where clicks must fall through to block selection and no data is bound.
 */
import React from 'react';
import type { FieldConfig } from '~/framework/meta/schemas/types';
import { ControlledFieldRenderer } from '~/framework/meta/rendering/ControlledFieldRenderer';
import { createExpressionContext } from '~/framework/meta/runtime/expression/context';
import { DataSourceProvider } from '~/framework/meta/contexts/DataSourceContext';
import { DataSourceManager } from '~/framework/meta/runtime/data-pipeline/DataSourceManager';
import type { DslBlockV3, ModelFieldDefinition } from '../types';

/**
 * dataType → default control, mirroring the live form's `DATA_TYPE_TO_COMPONENT`
 * (framework/meta/rendering/pages/FormPageContent). Applied only when the field has no
 * explicit renderComponent, so number/date/boolean fields preview as
 * stepper/date-picker/switch instead of a plain input — matching the published page.
 */
export const PREVIEW_DATA_TYPE_COMPONENT: Record<string, string> = {
  string: 'SmartInput',
  text: 'SmartTextarea',
  decimal: 'SmartNumberInput',
  integer: 'SmartNumberInput',
  enum: 'SmartSelect',
  date: 'SmartDatePicker',
  datetime: 'SmartDatePicker',
  boolean: 'SmartSwitch',
  reference: 'SmartSelect',
  json: 'SmartJsonEditor',
  jsonb: 'SmartJsonEditor',
  file: 'SmartUpload',
  money: 'SmartMoneyInput',
};

/**
 * FieldConfig keys that live at the top level, not inside `field.props`. Keeping them out
 * of the props bag avoids leaking them onto DOM elements (e.g. the `visibleWhen`
 * unknown-DOM-attribute React warning) when smart components spread `props`.
 */
const FIELD_CONFIG_RESERVED_KEYS = new Set([
  'field',
  'label',
  'component',
  'type',
  'dataType',
  'dictCode',
  'required',
  'visibleWhen',
  'readOnly',
  'readonly',
  'validation',
]);

/**
 * Model fields for the page's primary model, shared by the designer canvas + preview so a
 * `field` block can resolve its display label / renderComponent / dict / extension props.
 */
export const DesignerModelFieldsContext = React.createContext<ModelFieldDefinition[]>([]);

/** Build a platform {@link FieldConfig} from a designer block + resolved model field. */
export function buildPreviewFieldConfig(
  block: DslBlockV3,
  modelField: ModelFieldDefinition,
): FieldConfig {
  const blockProps = (block.props ?? {}) as Record<string, unknown>;
  const extensionProps = modelField.extensionProps ?? {};
  const merged: Record<string, unknown> = {
    ...extensionProps,
    ...(modelField.refTarget ? { refTarget: modelField.refTarget } : {}),
    ...blockProps,
  };
  // Component-facing props only: strip FieldConfig top-level keys so they are not spread
  // onto DOM nodes by leaf smart components.
  const componentProps: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(merged)) {
    if (!FIELD_CONFIG_RESERVED_KEYS.has(key)) componentProps[key] = value;
  }
  const required =
    typeof blockProps.required === 'boolean'
      ? (blockProps.required as boolean)
      : Boolean(modelField.required);
  const visibleWhen = blockProps.visibleWhen ?? extensionProps.visibleWhen;
  const dataType = (blockProps.dataType as string | undefined) ?? modelField.type;
  const explicitComponent = (blockProps.component as string | undefined) ?? modelField.component;
  const component =
    explicitComponent ??
    (dataType ? PREVIEW_DATA_TYPE_COMPONENT[dataType.toLowerCase()] : undefined);
  return {
    field: block.field ?? modelField.code,
    label: (blockProps.label ?? block.title ?? modelField.label) as FieldConfig['label'],
    component,
    type: dataType,
    dictCode: (blockProps.dictCode as string | undefined) ?? modelField.dictCode,
    required,
    ...(visibleWhen != null ? { visibleWhen } : {}),
    props: componentProps,
  } as FieldConfig;
}

/**
 * Non-interactive real-control preview for the edit-mode canvas. Renders the exact live
 * control (its normal empty chrome — not a read-only text collapse), wrapped in
 * `pointer-events-none` so clicks fall through to block selection and the control can't be
 * edited. Self-provides a lightweight `DataSourceManager` (SmartSelect/dict/reference
 * controls hard-require one, else `useFieldDataSource` throws).
 */
export function EditCanvasFieldPreview({
  block,
  modelField,
  locale,
}: {
  block: DslBlockV3;
  modelField: ModelFieldDefinition;
  locale: string;
}) {
  const fieldConfig = React.useMemo(
    () => buildPreviewFieldConfig(block, modelField),
    [block, modelField],
  );
  const context = React.useMemo(() => createExpressionContext({ locale }), [locale]);
  const dataSourceManager = React.useMemo(
    () => new DataSourceManager(createExpressionContext({ locale })),
    [locale],
  );

  return (
    <div
      className="pointer-events-none p-3"
      data-testid={`canvas-field-preview-${block.id}`}
      data-wysiwyg="platform"
      data-field-component={fieldConfig.component ?? undefined}
    >
      <DataSourceProvider manager={dataSourceManager}>
        <ControlledFieldRenderer
          field={fieldConfig}
          value={undefined}
          onChange={() => {}}
          context={context}
        />
      </DataSourceProvider>
    </div>
  );
}
