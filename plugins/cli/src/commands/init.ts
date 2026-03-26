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
 * Create a new plugin from template.
 */
export async function initCommand(name?: string): Promise<void> {
  // Dynamic import for inquirer (ESM)
  const { default: inquirer } = await import('inquirer');

  const defaultName = name || 'my-plugin';
  const defaultNs = defaultName.replace(/[^a-z0-9]/g, '').substring(0, 10);

  const answers = await inquirer.prompt([
    {
      type: 'input',
      name: 'pluginId',
      message: 'Plugin ID:',
      default: `com.mycompany.${defaultName}`,
      validate: (v: string) => /^[a-z][a-z0-9.-]*$/.test(v) || 'Must be lowercase reverse domain format',
    },
    {
      type: 'input',
      name: 'namespace',
      message: 'Namespace:',
      default: defaultNs,
      validate: (v: string) => /^[a-z][a-z0-9_]*$/.test(v) || 'Must be lowercase alphanumeric',
    },
    {
      type: 'input',
      name: 'displayName',
      message: 'Display Name:',
      default: defaultName.replace(/-/g, ' ').replace(/\b\w/g, (c) => c.toUpperCase()),
    },
    {
      type: 'list',
      name: 'pluginType',
      message: 'Plugin Type:',
      choices: [
        { name: 'config — Pure JSON DSL configuration', value: 'config' },
        { name: 'hybrid — JSON DSL + Java backend code', value: 'hybrid' },
      ],
    },
    {
      type: 'confirm',
      name: 'includeSampleModel',
      message: 'Include sample model?',
      default: true,
    },
  ]) as InitOptions;

  const targetDir = resolve(name || defaultName);

  if (existsSync(targetDir)) {
    log.error(`Directory '${targetDir}' already exists.`);
    process.exit(1);
  }

  log.blank();
  generatePlugin(targetDir, answers);

  log.blank();
  log.success(`Plugin created at ${chalk.cyan(targetDir)}`);
  log.blank();
  console.log(chalk.bold('Next steps:'));
  console.log(`  cd ${name || defaultName}`);
  console.log('  aura plugin validate .');
  console.log('  aura plugin publish . --target http://localhost:5173');
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
      displayName: 'Sample',
      modelType: 'entity',
    }], null, 2) + '\n');
    log.success('Created config/models.json (1 sample model)');

    // fields.json
    const fields = [
      { code: 'name', displayName: 'Name', fieldType: 'text' },
      { code: 'description', displayName: 'Description', fieldType: 'long_text' },
      { code: 'status', displayName: 'Status', fieldType: 'dict', dictCode: `${ns}_status` },
    ];
    writeFileSync(join(configDir, 'fields.json'), JSON.stringify(fields, null, 2) + '\n');
    log.success(`Created config/fields.json (${fields.length} sample fields)`);

    // bindings.json
    const bindings = fields.map((f, i) => ({
      modelCode: modelCode,
      fieldCode: f.code,
      required: f.code === 'name',
      orderNo: (i + 1) * 10,
    }));
    writeFileSync(join(configDir, 'bindings.json'), JSON.stringify(bindings, null, 2) + '\n');
    log.success('Created config/bindings.json');

    // commands.json
    const commands = [
      { code: `${ns}:create-${ns}-sample`, modelCode: modelCode, type: 'create', displayName: 'Create' },
      { code: `${ns}:update-${ns}-sample`, modelCode: modelCode, type: 'update', displayName: 'Update' },
      { code: `${ns}:delete-${ns}-sample`, modelCode: modelCode, type: 'delete', displayName: 'Delete' },
    ];
    writeFileSync(join(configDir, 'commands.json'), JSON.stringify(commands, null, 2) + '\n');
    log.success(`Created config/commands.json (${commands.length} CRUD commands)`);

    // pages.json
    const pages = [
      {
        pageKey: `${modelCode}_list`,
        pageName: `${opts.displayName} List`,
        schemaType: 'list',
        dslSchema: {
          kind: 'List',
          layout: { areas: ['filters', 'toolbar', 'main'] },
          areas: {
            filters: { blocks: [{ blockType: 'filter-form', fields: ['name', 'status'] }] },
            toolbar: { blocks: [{ blockType: 'toolbar', actions: ['create'] }] },
            main: { blocks: [{ blockType: 'data-table', columns: ['name', 'description', 'status'] }] },
          },
        },
      },
      {
        pageKey: `${modelCode}_form`,
        pageName: `${opts.displayName} Form`,
        schemaType: 'form',
        dslSchema: {
          kind: 'Form',
          layout: { areas: ['main', 'actions'] },
          areas: {
            main: { blocks: [{ blockType: 'form', fields: ['name', 'description', 'status'] }] },
            actions: { blocks: [{ blockType: 'form-buttons', actions: ['save', 'cancel'] }] },
          },
        },
      },
    ];
    writeFileSync(join(configDir, 'pages.json'), JSON.stringify(pages, null, 2) + '\n');
    log.success(`Created config/pages.json (${pages.length} pages)`);

    // permissions.json
    const permissions = [
      { code: `${ns}_sample_view`, name: 'View Sample', resource: `/${ns}/sample`, action: 'read', resourceType: 'page', type: 'menu', module: 'plugin' },
      { code: `${ns}_sample_manage`, name: 'Manage Sample', resource: `/${ns}/sample`, action: 'manage', resourceType: 'page', type: 'operation', module: 'plugin' },
    ];
    writeFileSync(join(configDir, 'permissions.json'), JSON.stringify(permissions, null, 2) + '\n');
    log.success('Created config/permissions.json');

    // roles.json
    writeFileSync(join(configDir, 'roles.json'), JSON.stringify([], null, 2) + '\n');
    log.success('Created config/roles.json');

    // menus.json
    const menus = [
      { code: `${ns}_sample_menu`, name: opts.displayName, path: `/dynamic/${modelCode.replace(/_/g, '-')}`, type: 1, permissionCode: `${ns}_sample_view`, orderNo: 100, visible: true },
    ];
    writeFileSync(join(configDir, 'menus.json'), JSON.stringify(menus, null, 2) + '\n');
    log.success('Created config/menus.json');

    // dicts.json
    const dicts = [
      {
        code: `${ns}_status`, name: 'Status', dictType: 'enum',
        items: [
          { code: 'draft', label: 'Draft', orderNo: 1 },
          { code: 'active', label: 'Active', orderNo: 2 },
          { code: 'archived', label: 'Archived', orderNo: 3 },
        ],
      },
    ];
    writeFileSync(join(configDir, 'dicts.json'), JSON.stringify(dicts, null, 2) + '\n');
    log.success('Created config/dicts.json');

    // i18n.json
    const i18n = [
      { key: `model.${modelCode}._meta.label`, 'zh-CN': opts.displayName, 'en-US': 'Sample', source: 'import', refType: 'model' },
      { key: `model.${modelCode}.name.label`, 'zh-CN': '名称', 'en-US': 'Name', source: 'import', refType: 'field' },
      { key: `model.${modelCode}.description.label`, 'zh-CN': '描述', 'en-US': 'Description', source: 'import', refType: 'field' },
      { key: `model.${modelCode}.status.label`, 'zh-CN': '状态', 'en-US': 'Status', source: 'import', refType: 'field' },
    ];
    writeFileSync(join(configDir, 'i18n.json'), JSON.stringify(i18n, null, 2) + '\n');
    log.success('Created config/i18n.json (zh-CN + en-US)');
  } else {
    // Empty config files
    for (const file of ['models', 'fields', 'bindings', 'commands', 'pages', 'permissions', 'roles', 'menus', 'dicts', 'i18n']) {
      writeFileSync(join(configDir, `${file}.json`), '[]\n');
    }
    log.success('Created empty config files');
  }
}
