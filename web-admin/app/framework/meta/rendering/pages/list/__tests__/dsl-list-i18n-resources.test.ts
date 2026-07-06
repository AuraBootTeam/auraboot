/**
 * DSL list-page i18n resource completeness guard.
 *
 * The OSS frontend probes specific i18n keys (`common.sort`, `common.fields`,
 * `common.add_filter`, `common.my_records`, `common.created_today`,
 * `common.modified_this_week`, `common.preset_views`, `common.search`,
 * `common.filter`,
 * `common.created_at`, `common.create`, `common.detail`, `common.submit`)
 * when rendering DSL list pages. If any of these go missing from
 * `platform/src/main/resources/i18n.zh-CN.yaml`, users will see English
 * tokens (e.g. "Sort / Fields / Created Today") leaking into the page.
 *
 * This test reads the yaml directly (line-based) so a future yaml refactor
 * cannot silently drop these keys without a failing test.
 */
import { describe, expect, it } from 'vitest';
import * as fs from 'node:fs';
import * as path from 'node:path';

const REPO_ROOT = path.resolve(__dirname, '../../../../../../../..');
const ZH_CN_YAML = path.resolve(REPO_ROOT, 'platform/src/main/resources/i18n.zh-CN.yaml');
const EN_US_YAML = path.resolve(REPO_ROOT, 'platform/src/main/resources/i18n.en-US.yaml');
const SEED_JSON = path.resolve(REPO_ROOT, 'platform/src/main/resources/seed/i18n-base.json');

const REQUIRED_COMMON_KEYS = [
  // Toolbar literals (#3)
  'sort',
  'fields',
  'filter',
  'search',
  'add_filter',
  'clear_all',
  'search_fields',
  'no_fields_found',
  'common_fields',
  'other_fields',
  'my_records',
  'created_today',
  'modified_this_week',
  'preset_views',
  'view_saved',
  'saved_view_select',
  'saved_view_personal_group',
  'saved_view_team_group',
  'saved_view_global_group',
  'saved_view_scope_personal',
  'saved_view_scope_team',
  'saved_view_scope_global',
  'saved_view_default',
  'saved_view_default_view',
  'saved_view_new',
  'saved_view_new_personal',
  'saved_view_manage',
  'saved_view_empty',
  'saved_view_empty_hint',
  'saved_view_search_placeholder',
  'saved_view_manage_search_placeholder',
  'saved_view_manage_no_results',
  'saved_view_auto_name',
  'saved_view_copy_name',
  'saved_view_untitled',
  'saved_view_locked_preset',
  'saved_view_capability_blocked',
  'saved_view_panel_subtitle',
  'saved_view_personal_draft',
  'saved_view_save_current',
  'saved_view_save_as_personal',
  'saved_view_current_saved',
  'saved_view_save_filters',
  'saved_view_personal_quota',
  'saved_view_personal_quota_reached',
  'saved_view_choose_type',
  'saved_view_cancel',
  'saved_view_create_cancel',
  'saved_view_create_saving',
  'saved_view_create_save',
  'saved_view_create_not_saveable',
  'saved_view_config_title',
  'saved_view_config_help',
  'saved_view_select_field',
  'saved_view_type_table',
  'saved_view_type_kanban',
  'saved_view_type_calendar',
  'saved_view_type_gallery',
  'saved_view_type_gantt',
  'saved_view_type_tree',
  'saved_view_type_timeline',
  'saved_view_type_form',
  'saved_view_type_status_available',
  'saved_view_type_status_degraded',
  'saved_view_type_status_blocked',
  'saved_view_field_groupByField',
  'saved_view_field_titleField',
  'saved_view_field_calendarDateField',
  'saved_view_field_calendarTitleField',
  'saved_view_field_ganttStartDateField',
  'saved_view_field_ganttEndDateField',
  'saved_view_field_ganttTitleField',
  'saved_view_field_galleryImageField',
  'saved_view_field_galleryTitleField',
  'saved_view_field_treeParentField',
  'saved_view_field_treeTitleField',
  'saved_view_field_timelineStartField',
  'saved_view_field_timelineEndField',
  'saved_view_field_timelineResourceField',
  'saved_view_field_timelineTitleField',
  'saved_view_reason_missing_kanban_group_field',
  'saved_view_reason_missing_title_field',
  'saved_view_reason_missing_date_field',
  'saved_view_reason_missing_image_field',
  'saved_view_reason_missing_tree_parent_field',
  'saved_view_reason_missing_timeline_resource_field',
  'saved_view_reason_kanban_drag_command_missing',
  'saved_view_reason_tree_reorder_command_missing',
  'saved_view_reason_single_date_field_reused',
  'saved_view_action_set_default',
  'saved_view_action_default',
  'saved_view_action_edit',
  'saved_view_action_copy',
  'saved_view_action_delete',
  'saved_view_edit_name',
  'saved_view_edit_description',
  'saved_view_edit_description_placeholder',
  'saved_view_edit_save',
  'saved_view_duplicate_title',
  'saved_view_duplicate_name',
  'saved_view_duplicate_submit',
  'saved_view_delete_confirm',
  'saved_view_not_configured',
  'saved_view_configure',
  'saved_view_switch_to_table',
  'saved_view_switch_to_table_full',
  'saved_view_error',
  'saved_view_diagnostics_summary',
  'saved_view_current_mapping',
  'saved_view_total_records',
  'saved_view_valid_records',
  'saved_view_issue_records',
  'saved_view_first_n',
  'saved_view_data_limit_summary',
  'saved_view_shared_draft',
  'saved_view_save_shared',
  'saved_view_save_shared_disabled_reason',
  'saved_view_locked_preset_reason',
  'saved_view_save_shared_confirm_title',
  'saved_view_save_shared_confirm_content',
  'saved_view_shared_changes',
  'saved_view_shared_saved',
  'saved_view_shared_save_failed',
  'saved_view_copy_to_personal',
  'saved_view_dismiss_draft',
  'saved_view_copied_to_personal',
  'saved_view_copy_failed',
  'saved_view_copy_disabled_reason',
  'saved_view_save_preset_to_personal',
  'saved_view_preset_saved_to_personal',
  'saved_view_preset_save_failed',
  // System audit field column headers (#2)
  'created_at',
  'updated_at',
  'creator',
  'modifier',
  // Row-action / form button bare-string labels (#4)
  'create',
  'edit',
  'delete',
  'view',
  'detail',
  'submit',
  'cancel',
];

const REQUIRED_JSON_EDITOR_KEYS = [
  'common.json_editor.valid',
  'common.json_editor.invalid',
  'common.json_editor.invalid_short',
  'common.json_editor.format',
];

/**
 * Extract `common:` block lines (between `^common:` and the next top-level
 * key starting at column 0 that's not a comment/blank). Returns flattened
 * `common.*` keys for scalar values one nesting level below common.
 */
function readFlattenedCommonBlock(yamlPath: string): Record<string, string> {
  const raw = fs.readFileSync(yamlPath, 'utf-8');
  const lines = raw.split('\n');
  const result: Record<string, string> = {};
  let inCommon = false;
  let nestedPrefix = '';
  for (const line of lines) {
    if (/^common:\s*$/.test(line)) {
      inCommon = true;
      nestedPrefix = '';
      continue;
    }
    if (inCommon) {
      // Top-level key (column 0, alpha) ends the common block.
      if (/^[A-Za-z_][\w-]*:/.test(line) && !/^common:/.test(line)) {
        inCommon = false;
        nestedPrefix = '';
        continue;
      }
      const nested = line.match(/^ {2}([a-z_][\w-]*):\s*$/);
      if (nested) {
        nestedPrefix = nested[1];
        continue;
      }
      const m = line.match(/^ {2}([a-z_][\w-]*):\s*(.+?)\s*$/);
      if (m && !m[2].startsWith('#')) {
        result[`common.${m[1]}`] = m[2];
        nestedPrefix = '';
        continue;
      }
      const nestedScalar = line.match(/^ {4}([a-z_][\w-]*):\s*(.+?)\s*$/);
      if (nestedPrefix && nestedScalar && !nestedScalar[2].startsWith('#')) {
        result[`common.${nestedPrefix}.${nestedScalar[1]}`] = nestedScalar[2];
      }
    }
  }
  return result;
}

describe('DSL list page i18n zh-CN yaml resource', () => {
  it('platform yaml file exists', () => {
    expect(fs.existsSync(ZH_CN_YAML)).toBe(true);
  });

  it('common: top-level block is present and non-empty', () => {
    const common = readFlattenedCommonBlock(ZH_CN_YAML);
    expect(Object.keys(common).length).toBeGreaterThan(10);
  });

  it.each(REQUIRED_COMMON_KEYS)('zh-CN yaml defines common.%s with Chinese characters', (key) => {
    const common = readFlattenedCommonBlock(ZH_CN_YAML);
    const value = common[`common.${key}`];
    expect(value, `common.${key} must be defined to avoid leaking English literal`).toBeDefined();
    expect((value as string).length, `common.${key} value must be non-empty`).toBeGreaterThan(0);
    expect(/[一-龥]/.test(value), `common.${key}="${value}" must contain Chinese characters`).toBe(
      true,
    );
  });

  it.each(REQUIRED_JSON_EDITOR_KEYS)('%s exists in zh-CN and en-US yaml fallbacks', (key) => {
    expect(readFlattenedCommonBlock(ZH_CN_YAML)[key]).toBeDefined();
    expect(readFlattenedCommonBlock(EN_US_YAML)[key]).toBeDefined();
  });

  it.each(REQUIRED_JSON_EDITOR_KEYS)('%s exists in seed/i18n-base.json', (key) => {
    const entries = JSON.parse(fs.readFileSync(SEED_JSON, 'utf-8')) as Array<Record<string, string>>;
    const entry = entries.find((item) => item.key === key);
    expect(entry, `${key} must be seeded for DB-backed /api/i18n`).toBeDefined();
    expect(entry?.['zh-CN']).toBeTruthy();
    expect(entry?.['en-US']).toBeTruthy();
  });
});
