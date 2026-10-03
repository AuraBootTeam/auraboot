/**
 * PropertySchema definitions for List config tabs.
 *
 * Each tab composes an ExtendedPropertySchema[] array that is handed to
 * `SchemaBlockConfigPanel` for schema-driven rendering. No hand-coded JSX
 * panels — Studio red-line requires all configuration editors to go through
 * PropertyFieldRenderer via SchemaBlockConfigPanel.
 */

import type { ExtendedPropertySchema } from '~/shared/designer/SchemaBlockConfigPanel';

/** Per-column detail editor schema (applied when a column is selected). */
export function buildColumnDetailSchemas(
  t: (key: string) => string,
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'width',
      label: t('list_editor.width'),
      type: 'number',
      group: t('list_editor.size_group'),
      placeholder: t('list_editor.auto'),
      description: t('list_editor.width_hint'),
    },
    {
      key: 'align',
      label: t('list_editor.align'),
      type: 'select',
      group: t('list_editor.size_group'),
      description: t('list_editor.align_hint'),
      options: [
        { label: t('list_editor.left_align'), value: 'left' },
        { label: t('list_editor.center_align'), value: 'center' },
        { label: t('list_editor.right_align'), value: 'right' },
      ],
    },
    {
      key: 'renderer',
      label: t('list_editor.renderer'),
      type: 'select',
      group: t('list_editor.display_group'),
      description: t('list_editor.renderer_hint'),
      options: [
        { label: t('list_editor.text'), value: 'text' },
        { label: t('list_editor.richtext'), value: 'richtext' },
        { label: t('list_editor.badge'), value: 'badge' },
        { label: t('list_editor.link'), value: 'link' },
        { label: t('list_editor.image'), value: 'image' },
      ],
    },
    {
      key: 'format',
      label: t('list_editor.format'),
      type: 'text',
      group: t('list_editor.display_group'),
      placeholder: t('list_editor.format_placeholder'),
      description: t('list_editor.format_hint'),
    },
  ];
}

/** Per-filter detail editor schema (applied when a filter is selected). */
export function buildFilterDetailSchemas(
  t: (key: string) => string,
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'operator',
      label: t('list_editor.operator'),
      type: 'select',
      group: t('list_editor.condition_group'),
      description: t('list_editor.operator_hint'),
      options: [
        { label: t('list_editor.equal'), value: 'eq' },
        { label: t('list_editor.not_equal_option'), value: 'neq' },
        { label: t('list_editor.contains'), value: 'like' },
        { label: t('list_editor.between_option'), value: 'between' },
        { label: t('list_editor.greater_than'), value: 'gt' },
        { label: t('list_editor.greater_or_equal'), value: 'gte' },
        { label: t('list_editor.less_than'), value: 'lt' },
        { label: t('list_editor.less_or_equal'), value: 'lte' },
      ],
    },
    {
      key: 'defaultValue',
      label: t('list_editor.default_value'),
      type: 'text',
      group: t('list_editor.condition_group'),
      description: t('list_editor.default_value_hint'),
    },
    {
      key: 'displayMode',
      label: t('list_editor.display_mode'),
      type: 'select',
      group: t('list_editor.appearance_group'),
      description: t('list_editor.display_mode_hint'),
      options: [
        { label: t('list_editor.inline'), value: 'inline' },
        { label: t('list_editor.drawer'), value: 'drawer' },
        { label: t('list_editor.top_bar'), value: 'top-bar' },
      ],
    },
  ];
}

/** Toolbar preset toggles (gated by capabilities at render time). */
export function buildToolbarPresetSchemas(
  t: (key: string) => string,
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'presetCreate',
      label: t('list_editor.create'),
      type: 'boolean',
      group: t('list_editor.preset_buttons'),
    },
    {
      key: 'presetRefresh',
      label: t('list_editor.refresh'),
      type: 'boolean',
      group: t('list_editor.preset_buttons'),
    },
    {
      key: 'presetExport',
      label: t('list_editor.export'),
      type: 'boolean',
      group: t('list_editor.preset_buttons'),
    },
    {
      key: 'presetBulkDelete',
      label: t('list_editor.bulk_delete'),
      type: 'boolean',
      group: t('list_editor.preset_buttons'),
    },
  ];
}

/** Custom button schema (for the add-custom-button list editor). */
export function buildCustomButtonSchemas(
  t: (key: string) => string,
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'label',
      label: t('list_editor.button_label'),
      type: 'text',
      required: true,
      group: t('list_editor.basic_group'),
      description: t('list_editor.button_label_hint'),
    },
    {
      key: 'icon',
      label: t('list_editor.icon'),
      type: 'icon',
      group: t('list_editor.basic_group'),
      description: t('list_editor.icon_hint'),
    },
    {
      key: 'code',
      label: t('list_editor.button_code'),
      type: 'text',
      group: t('list_editor.basic_group'),
      placeholder: 'refresh_orders',
      description: t('list_editor.button_code_hint'),
    },
    {
      key: 'actionKind',
      label: t('list_editor.action_kind'),
      type: 'select',
      group: t('list_editor.binding_group'),
      defaultValue: 'command',
      description: t('list_editor.action_kind_hint'),
      options: [
        { label: t('list_editor.execute_command'), value: 'command' },
        { label: t('list_editor.refresh_source'), value: 'refresh' },
      ],
    },
    {
      key: 'command',
      label: t('list_editor.command'),
      type: 'text',
      required: true,
      placeholder: 'plugin:action',
      group: t('list_editor.binding_group'),
      dependsOn: { field: 'actionKind', anyOf: [undefined, 'command'] },
      description: t('list_editor.command_hint'),
    },
    {
      key: 'targetDataSource',
      label: t('list_editor.target_source'),
      type: 'text',
      required: true,
      placeholder: 'ds_list',
      group: t('list_editor.binding_group'),
      dependsOn: { field: 'actionKind', value: 'refresh' },
      description: t('list_editor.target_source_hint'),
    },
    {
      key: 'requiresSelection',
      label: t('list_editor.selection_required'),
      type: 'boolean',
      group: t('list_editor.binding_group'),
      description: t('list_editor.selection_required_hint'),
    },
  ];
}

/**
 * Behavior tab schemas — option lists for sort fields are derived from
 * `capabilities.sortableFields` (whitelist enforced at render time).
 */
export function buildBehaviorSchemas(
  sortableFields: string[],
  _filterableFields: string[],
  t: (key: string) => string,
): ExtendedPropertySchema<string>[] {
  return [
    {
      key: 'defaultSortField',
      label: t('list_behavior.sort_field'),
      type: 'select',
      group: t('list_behavior.sorting_group'),
      description: t('list_behavior.sort_description'),
      options: [
        { label: t('list_behavior.sort_unset'), value: '__none__' },
        ...sortableFields.map((f) => ({ label: f, value: f })),
      ],
    },
    {
      key: 'defaultSortOrder',
      label: t('list_behavior.sort_order'),
      type: 'select',
      group: t('list_behavior.sorting_group'),
      description: t('list_behavior.sort_order_description'),
      // Show only when a real sort field is selected (not the '__none__' sentinel).
      // anyOf lists actual field values — '__none__' is excluded, so this row
      // hides automatically when no sort field is chosen.
      dependsOn: { field: 'defaultSortField', anyOf: sortableFields },
      options: [
        { label: t('list_behavior.descending'), value: 'desc' },
        { label: t('list_behavior.ascending'), value: 'asc' },
      ],
    },
    {
      key: 'pageSize',
      label: t('list_behavior.page_size'),
      type: 'number',
      group: t('list_behavior.pagination_group'),
      defaultValue: 20,
      description: t('list_behavior.page_size_description'),
    },
    {
      key: 'multiSelect',
      label: t('list_behavior.enable_multi_select'),
      type: 'boolean',
      group: t('list_behavior.interaction_group'),
      description: t('list_behavior.multi_select_description'),
    },
    {
      key: 'rowClickAction',
      label: t('list_behavior.row_click_action'),
      type: 'select',
      group: t('list_behavior.interaction_group'),
      description: t('list_behavior.row_click_description'),
      options: [
        { label: t('list_behavior.open_detail'), value: 'detail' },
        { label: t('list_behavior.open_drawer'), value: 'drawer' },
        { label: t('list_behavior.no_action'), value: 'none' },
      ],
    },
    {
      key: 'emptyStateText',
      label: t('list_behavior.empty_text'),
      type: 'text',
      group: t('list_behavior.display_group'),
      placeholder: t('list_behavior.empty_placeholder'),
      description: t('list_behavior.empty_description'),
    },
  ];
}
