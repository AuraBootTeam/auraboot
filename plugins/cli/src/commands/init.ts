import { mkdirSync, writeFileSync, existsSync } from 'fs';
import { join, resolve } from 'path';
import chalk from 'chalk';
import { log } from '../utils/logger.js';

interface InitOptions {
  pluginId: string;
  namespace: string;
  displayName: string;
  pluginType: 'config' | 'hybrid';
  includeSampleModel: boolean;
}

/**
 * CLI flag options accepted by `aura plugin init`.
 *
 * - `dir`            : project output directory (defaults to `<cwd>/<name>`)
 * - `nonInteractive` : skip all prompts; require remaining values via flags
 * - `pluginId` / `namespace` / `displayName` / `pluginType` /
 *   `includeSampleModel` / `noSampleModel` : direct overrides for the
 *   corresponding interactive prompt answers
 */
export interface InitCliOptions {
  dir?: string;
  nonInteractive?: boolean;
  pluginId?: string;
  namespace?: string;
  displayName?: string;
  pluginType?: 'config' | 'hybrid';
  includeSampleModel?: boolean;
  noSampleModel?: boolean;
}

export interface InitResult {
  targetDir: string;
  options: InitOptions;
}

const PLUGIN_ID_RE = /^[a-z][a-z0-9.-]*$/;
const NAMESPACE_RE = /^[a-z][a-z0-9_]*$/;

function defaultPluginId(name: string): string {
  return `com.mycompany.${name}`;
}

function defaultNamespace(name: string): string {
  return name.replace(/[^a-z0-9]/g, '').substring(0, 10);
}

function defaultDisplayName(name: string): string {
  return name.replace(/-/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}

/**
 * Resolve the final InitOptions either from CLI flags (non-interactive)
 * or from inquirer prompts. Exported for unit testing.
 */
export async function resolveInitOptions(
  name: string,
  cli: InitCliOptions,
): Promise<InitOptions> {
  if (cli.nonInteractive) {
    const missing: string[] = [];
    const pluginId = cli.pluginId ?? defaultPluginId(name);
    const namespace = cli.namespace ?? defaultNamespace(name);
    const displayName = cli.displayName ?? defaultDisplayName(name);
    const pluginType = cli.pluginType ?? 'config';

    if (!PLUGIN_ID_RE.test(pluginId)) {
      missing.push(`--plugin-id (got '${pluginId}', must match ${PLUGIN_ID_RE})`);
    }
    if (!NAMESPACE_RE.test(namespace)) {
      missing.push(`--namespace (got '${namespace}', must match ${NAMESPACE_RE})`);
    }
    if (pluginType !== 'config' && pluginType !== 'hybrid') {
      missing.push(`--plugin-type (got '${pluginType}', must be 'config' or 'hybrid')`);
    }
    if (missing.length > 0) {
      throw new Error(
        `aura plugin init --non-interactive: invalid or missing flag(s):\n  - ${missing.join('\n  - ')}`,
      );
    }

    let includeSampleModel: boolean;
    if (cli.noSampleModel === true) {
      includeSampleModel = false;
    } else if (cli.includeSampleModel === false) {
      includeSampleModel = false;
    } else {
      includeSampleModel = true;
    }

    return { pluginId, namespace, displayName, pluginType, includeSampleModel };
  }

  // Interactive path — dynamic import keeps inquirer ESM-only and out of unit tests.
  const { default: inquirer } = await import('inquirer');
  const answers = (await inquirer.prompt([
    {
      type: 'input',
      name: 'pluginId',
      message: 'Plugin ID:',
      default: cli.pluginId ?? defaultPluginId(name),
      validate: (v: string) => PLUGIN_ID_RE.test(v) || 'Must be lowercase reverse domain format',
    },
    {
      type: 'input',
      name: 'namespace',
      message: 'Namespace:',
      default: cli.namespace ?? defaultNamespace(name),
      validate: (v: string) => NAMESPACE_RE.test(v) || 'Must be lowercase alphanumeric',
    },
    {
      type: 'input',
      name: 'displayName',
      message: 'Display Name:',
      default: cli.displayName ?? defaultDisplayName(name),
    },
    {
      type: 'list',
      name: 'pluginType',
      message: 'Plugin Type:',
      default: cli.pluginType ?? 'config',
      choices: [
        { name: 'config — Pure JSON DSL configuration', value: 'config' },
        { name: 'hybrid — JSON DSL + Java backend code', value: 'hybrid' },
      ],
    },
    {
      type: 'confirm',
      name: 'includeSampleModel',
      message: 'Include sample model?',
      default: cli.includeSampleModel ?? true,
    },
  ])) as InitOptions;

  return answers;
}

/**
 * Create a new plugin from template.
 *
 * Backwards compatible:
 *   - `initCommand('my-plugin')` → interactive prompts, output at `./my-plugin`
 *   - `initCommand('my-plugin', { nonInteractive: true, dir: '/tmp/x' })` →
 *     skip prompts, write to `/tmp/x` (or fail with missing-flag error)
 */
export async function initCommand(
  name?: string,
  cli: InitCliOptions = {},
): Promise<InitResult> {
  const effectiveName = name || 'my-plugin';
  const options = await resolveInitOptions(effectiveName, cli);

  // --dir takes priority; fallback is `<cwd>/<name>` (same as previous behavior).
  const targetDir = cli.dir ? resolve(cli.dir) : resolve(effectiveName);

  if (existsSync(targetDir)) {
    const msg = `Directory '${targetDir}' already exists.`;
    if (cli.nonInteractive) {
      throw new Error(msg);
    }
    log.error(msg);
    process.exit(1);
  }

  log.blank();
  generatePlugin(targetDir, options);

  log.blank();
  log.success(`Plugin created at ${chalk.cyan(targetDir)}`);
  log.blank();
  if (!cli.nonInteractive) {
    console.log(chalk.bold('Next steps:'));
    console.log(`  cd ${cli.dir ?? effectiveName}`);
    console.log('  aura plugin validate .');
    console.log('  aura plugin publish . --target http://localhost:5173');
  }

  return { targetDir, options };
}

function generatePlugin(dir: string, opts: InitOptions): void {
  const ns = opts.namespace;
  const configDir = join(dir, 'config');
  mkdirSync(configDir, { recursive: true });

  // plugin.json
  const manifest = {
    pluginId: opts.pluginId,
    namespace: ns,
    version: '1.0.0',
    displayName: opts.displayName,
    dslVersion: 1,
    pluginType: opts.pluginType,
    author: '',
    dependencies: [],
  };
  writeFileSync(join(dir, 'plugin.json'), JSON.stringify(manifest, null, 2) + '\n');
  log.success('Created plugin.json');

  if (opts.includeSampleModel) {
    const modelCode = `${ns}_sample`;

    // models.json
    writeFileSync(join(configDir, 'models.json'), JSON.stringify([{
      code: modelCode,
      'displayName:zh-CN': opts.displayName,
      'displayName:en': opts.displayName,
      modelType: 'entity',
      modelCategory: 'master',
      extension: { titleField: 'name', tableName: `mt_${modelCode}`, softDelete: true },
    }], null, 2) + '\n');
    log.success('Created config/models.json (1 sample model)');

    // fields.json
    const fields = [
      { code: 'name', 'displayName:zh-CN': '名称', 'displayName:en': 'Name', dataType: 'text', constraints: { required: true, maxLength: 200 }, feature: { searchable: true } },
      { code: 'description', 'displayName:zh-CN': '描述', 'displayName:en': 'Description', dataType: 'text', constraints: { maxLength: 500 } },
      { code: 'status', 'displayName:zh-CN': '状态', 'displayName:en': 'Status', dataType: 'text', constraints: { maxLength: 50 } },
    ];
    writeFileSync(join(configDir, 'fields.json'), JSON.stringify(fields, null, 2) + '\n');
    log.success(`Created config/fields.json (${fields.length} sample fields)`);

    // bindings.json
    const bindings = fields.map((f, i) => ({
      modelCode: modelCode,
      fieldCode: f.code,
      sequence: i + 1,
      required: f.code === 'name',
      visible: true,
      editable: true,
    }));
    writeFileSync(join(configDir, 'bindings.json'), JSON.stringify(bindings, null, 2) + '\n');
    log.success('Created config/bindings.json');

    // dicts.json
    const dicts = [
      {
        code: `${ns}_status`, name: 'Status', 'name:zh-CN': '状态', dictType: 'static',
        items: [
          { value: 'draft', label: 'Draft', 'label:zh-CN': '草稿', sortNo: 10, status: 'enabled' },
          { value: 'active', label: 'Active', 'label:zh-CN': '启用', sortNo: 20, status: 'enabled' },
          { value: 'archived', label: 'Archived', 'label:zh-CN': '归档', sortNo: 30, status: 'enabled' },
        ],
      },
    ];
    writeFileSync(join(configDir, 'dicts.json'), JSON.stringify(dicts, null, 2) + '\n');
    log.success('Created config/dicts.json');

    // permissions.json
    const permissions = [
      { code: `${ns}.sample.manage`, 'name:zh-CN': `${opts.displayName} 管理`, 'name:en': `${opts.displayName} Manage`, resourceType: 'operation', module: ns },
    ];
    writeFileSync(join(configDir, 'permissions.json'), JSON.stringify(permissions, null, 2) + '\n');
    log.success('Created config/permissions.json');

    // menus.json — points at the model's platform-generated default list page (<model>_list)
    const menus = [
      { code: `${modelCode}_menu`, parentCode: null, 'name:zh-CN': opts.displayName, 'name:en': opts.displayName, path: `/dynamic/${modelCode.replace(/_/g, '-')}`, pageKey: `${modelCode}_list`, component: null, icon: 'IconList', type: 1, permissionCode: `${ns}.sample.manage`, orderNo: 100, visible: true },
    ];
    writeFileSync(join(configDir, 'menus.json'), JSON.stringify(menus, null, 2) + '\n');
    log.success('Created config/menus.json');

    // roles.json
    writeFileSync(join(configDir, 'roles.json'), JSON.stringify([], null, 2) + '\n');
    log.success('Created config/roles.json');

    // pages / commands / i18n — start empty. The platform auto-generates default
    // list/form/detail pages for the model, and dynamic models get CRUD for free.
    // Build richer pages with the Page Designer or the `auraboot-ui-builder` skill.
    for (const empty of ['pages', 'commands', 'i18n']) {
      writeFileSync(join(configDir, `${empty}.json`), '[]\n');
    }
    log.success('Created config/pages.json, commands.json, i18n.json (empty — add via Page Designer / ui-builder)');
  } else {
    // Empty config files
    for (const file of ['models', 'fields', 'bindings', 'commands', 'pages', 'permissions', 'roles', 'menus', 'dicts', 'i18n']) {
      writeFileSync(join(configDir, `${file}.json`), '[]\n');
    }
    log.success('Created empty config files');
  }
}
