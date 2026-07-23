/**
 * `aura dsl pull` — adopt a running instance's config as a local baseline.
 *
 * The platform export endpoint returns each resource's importSnapshot grouped by
 * its (singular, lowercase) resource-type code. This maps those to the CLI's
 * plural config-file names so `pull` can write config/<file>.json.
 */

export const PLATFORM_TYPE_TO_FILE: Record<string, string> = {
  model: 'models',
  field: 'fields',
  model_field_binding: 'bindings',
  binding_rule: 'bindingRules',
  command: 'commands',
  page: 'pages',
  dict: 'dicts',
  menu: 'menus',
  permission: 'permissions',
  role: 'roles',
  role_permission: 'rolePermissions',
  named_query: 'namedQueries',
  agent_definition: 'agents',
  saved_view: 'savedViews',
  notification_template: 'notificationTemplates',
  process: 'processes',
};

/** Turn a platform export `{ <type>: [...] }` into `{ <file>: [...] }`. */
export function exportToFiles(exported: Record<string, unknown[]>): Record<string, unknown[]> {
  const files: Record<string, unknown[]> = {};
  for (const [type, list] of Object.entries(exported)) {
    const file = PLATFORM_TYPE_TO_FILE[type] ?? `${type}s`;
    files[file] = Array.isArray(list) ? list : [];
  }
  return files;
}
