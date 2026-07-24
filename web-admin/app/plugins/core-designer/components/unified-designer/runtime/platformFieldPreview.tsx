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
 * platform `FieldConfig`. That mapping is a *translation*, not a pass-through: the designer
 * has component ids the platform registry does not (`picker`) and ids whose same-named
 * platform component is a broken legacy control (`date`), so both are resolved to the real
 * platform control here — see `resolvePickerPlatformComponent` and
 * `DESIGNER_COMPONENT_TO_PLATFORM`.
 *
 * `EditCanvasFieldPreview` is a self-contained, non-interactive variant (read-only +
 * `pointer-events-none` + its own `DataSourceManager`) for the edit canvas, where clicks
 * must fall through to block selection and no data is bound.
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
 * Designer dataTypes that mean "points at another model's record". The platform's
 * reference wiring (`ControlledFieldRenderer` auto-builds the `/api/dynamic/<model>/list`
 * option data source) keys off `reference`, so relation-ish types are normalized to it —
 * otherwise a relation field previews as an option-less control.
 */
const REFERENCE_LIKE_DATA_TYPES = new Set([
  'reference',
  'relation',
  'lookup',
  'ref',
  'belongsto',
  'hasone',
]);

/**
 * Designer component ids whose same-named platform component is a broken legacy control,
 * mapped to the real platform control.
 *
 * `date` is the notable one: it resolves through the component registry to the legacy
 * `ui/smart/datetime/Date` control — a bare `<input type="date">` that forwards the raw
 * change *event* straight to `onChange`, so the bound value becomes `[object Object]` and
 * the input reads back empty. `SmartDatePicker` is the real platform date control (string
 * round-trip through `useSmartField`, today/clear chrome, `FieldBase` label/help/error).
 */
const DESIGNER_COMPONENT_TO_PLATFORM: Record<string, string> = {
  date: 'SmartDatePicker',
  datetime: 'SmartDatePicker',
};

/** Ref-target model codes that the platform renders with its dedicated user pickers. */
const USER_LIKE_TARGET_MODELS = new Set([
  'user',
  'users',
  'sys_user',
  'sys_users',
  'ab_user',
  'ab_users',
  'member',
  'members',
  'sys_member',
]);

/** Ref-target model codes that the platform renders with its organization picker. */
const ORGANIZATION_LIKE_TARGET_MODELS = new Set([
  'org',
  'organization',
  'organizations',
  'sys_org',
  'sys_organization',
  'department',
  'departments',
  'sys_department',
]);

/**
 * Designer-only picker/authoring props with no platform component prop. They describe how
 * the *designer runtime* loads options (`/api/query-builder/execute`); leaving them in the
 * props bag would spread them onto DOM nodes (unknown-attribute React warnings) once the
 * picker actually renders a real control.
 */
const DESIGNER_ONLY_PROP_KEYS = new Set([
  'pickerDataSource',
  'pickerSource',
  'pickerQueryCode',
  'pickerParameters',
  'searchParameter',
  'searchPlaceholder',
  'richTextToolbar',
  'displayField',
  'maxFiles',
  'pageSize',
  'operator',
]);

function normalizeCode(value: unknown): string {
  return typeof value === 'string' ? value.trim().toLowerCase() : '';
}

/**
 * Resolve the designer's generic `picker` into a real platform component.
 *
 * The platform registry has no `picker` — only concrete pickers (`userselect`,
 * `memberpicker`, `organizationselect`, `treeselect`, …) — so `component: 'picker'` used to
 * render "Unknown component: picker". Dragging a relation model field into a form
 * auto-sets `component: 'picker'`, so every relation field hit that error.
 *
 * The choice is driven by the metadata the designer actually has on a field block: the
 * model field's `refTarget` (the authoritative relation target) first, then the designer's
 * own `pickerSource` (the model code an author typed into the inspector).
 */
export function resolvePickerPlatformComponent(
  props: Record<string, unknown>,
  modelField: ModelFieldDefinition,
): string {
  const extension = (modelField.extensionProps ?? {}) as Record<string, unknown>;
  const refTarget = {
    ...((extension.refTarget as Record<string, unknown> | undefined) ?? {}),
    ...((modelField.refTarget as Record<string, unknown> | undefined) ?? {}),
  };
  const targetModel = normalizeCode(
    refTarget.modelCode ?? refTarget.targetModel ?? props.pickerSource,
  );

  const isTree =
    props.tree === true ||
    normalizeCode(props.pickerMode) === 'tree' ||
    Boolean(props.treeParentField ?? extension.treeParentField ?? refTarget.parentField);
  if (isTree) return 'treeselect';

  if (USER_LIKE_TARGET_MODELS.has(targetModel)) {
    return props.multiple === true ? 'memberpicker' : 'userselect';
  }
  if (ORGANIZATION_LIKE_TARGET_MODELS.has(targetModel)) return 'organizationselect';

  // Everything else: the platform's reference control — a real select backed by the ref
  // target (ControlledFieldRenderer builds the option data source from `refTarget` /
  // `dictCode`), which is the closest faithful platform equivalent of a generic picker.
  return 'SmartSelect';
}

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
    if (!FIELD_CONFIG_RESERVED_KEYS.has(key) && !DESIGNER_ONLY_PROP_KEYS.has(key)) {
      componentProps[key] = value;
    }
  }
  // Designer `maxFiles` → platform SmartUpload `maxCount`. Without this the uploader keeps
  // its default limit of 1 ("已上传 1/1") and silently drops every file past the first,
  // even though the author configured `multiple` + `maxFiles`.
  const maxFiles = Number(merged.maxFiles);
  if (Number.isFinite(maxFiles) && maxFiles > 0 && componentProps.maxCount == null) {
    componentProps.maxCount = maxFiles;
  }
  const required =
    typeof blockProps.required === 'boolean'
      ? (blockProps.required as boolean)
      : Boolean(modelField.required);
  const visibleWhen = blockProps.visibleWhen ?? extensionProps.visibleWhen;
  const rawDataType = (blockProps.dataType as string | undefined) ?? modelField.type;
  // Relation-ish dataTypes are normalized to `reference` so the platform's reference
  // option data source engages (see REFERENCE_LIKE_DATA_TYPES).
  const dataType =
    rawDataType && REFERENCE_LIKE_DATA_TYPES.has(rawDataType.toLowerCase())
      ? 'reference'
      : rawDataType;
  const explicitComponent = (blockProps.component as string | undefined) ?? modelField.component;
  const designerComponent = normalizeCode(explicitComponent);
  const component =
    designerComponent === 'picker'
      ? resolvePickerPlatformComponent(merged, modelField)
      : (DESIGNER_COMPONENT_TO_PLATFORM[designerComponent] ??
        explicitComponent ??
        (dataType ? PREVIEW_DATA_TYPE_COMPONENT[dataType.toLowerCase()] : undefined));
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
