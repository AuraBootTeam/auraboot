import { existsSync, writeFileSync, mkdirSync } from 'fs';
import { join, resolve } from 'path';
import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex } from '../../utils/resource-index.js';
import { successOutput, errorOutput, formatOutput, FormatOptions } from '../../utils/output-formatter.js';
import chalk from 'chalk';

interface ScaffoldResult {
  type: string;
  code: string;
  generated: GeneratedFile[];
}

interface GeneratedFile {
  path: string;
  resourceType: string;
  itemCount: number;
  action: 'create' | 'append';
}

// Field type shorthand parsing: "name:TEXT,status:SELECT,amount:DECIMAL,customer:REFERENCE:crm_customer"
interface ParsedField {
  code: string;
  dataType: string;
  referenceModel?: string;
}

function parseFieldSpec(spec: string): ParsedField[] {
  if (!spec) return [];
  return spec.split(',').map(s => {
    const parts = s.trim().split(':');
    const field: ParsedField = { code: parts[0], dataType: parts[1] || 'string' };
    if (parts[2]) field.referenceModel = parts[2];
    return field;
  });
}

function generateModelScaffold(code: string, namespace: string, fields: ParsedField[], description?: string) {
  const model = {
    code,
    'displayName:zh-CN': code,
    'displayName:en': code,
    description: description || `Auto-generated model: ${code}`,
    semantic_description: description || `${code} management entity`,
    modelType: 'entity',
    extension: {},
  };

  const fieldDefs = [
    // Always include a name/title field
    ...fields.map(f => ({
      code: `${code}_${f.code}`,
      'displayName:zh-CN': f.code,
      'displayName:en': f.code,
      dataType: f.dataType.toUpperCase(),
      constraints: f.dataType === 'string' ? { maxLength: 200 } : {},
      extension: f.referenceModel ? { referenceModel: f.referenceModel } : {},
    })),
  ];

  // Check if status field exists for state machine
  const hasStatus = fields.some(f => f.code === 'status' || f.dataType === 'select');

  const bindings = fieldDefs.map((f, i) => ({
    modelCode: code,
    fieldCode: f.code,
    sequence: (i + 1) * 10,
    required: i === 0,
    visible: true,
    editable: true,
    displayConfig: { searchable: i < 3, sortable: i < 3 },
  }));

  // CRUD commands
  const shortName = code.replace(`${namespace}_`, '');
  const fieldNames = fields.map(f => f.code).join(', ');
  const createHint = fields.length > 0
    ? `Create a new ${shortName} with ${fieldNames}`
    : `Create a new ${shortName} with required fields`;
  const commands: any[] = [
    {
      code: `${namespace}:create_${shortName}`,
      'displayName:zh-CN': `Create ${code}`,
      'displayName:en': `Create ${code}`,
      type: 'create',
      modelCode: code,
      inputFields: fieldDefs.map(f => f.code),
      permissions: [`${namespace.toUpperCase()}.${shortName}.manage`],
      agent_hint: createHint,
      cmd_risk_level: 'L1',
      precondition_description: 'All required fields must be provided',
      idempotent: false,
      reversible: false,
    },
    {
      code: `${namespace}:update_${shortName}`,
      'displayName:zh-CN': `Update ${code}`,
      'displayName:en': `Update ${code}`,
      type: 'update',
      modelCode: code,
      inputFields: fieldDefs.map(f => f.code),
      permissions: [`${namespace.toUpperCase()}.${shortName}.manage`],
      agent_hint: `Update an existing ${shortName} record`,
      cmd_risk_level: 'L1',
      precondition_description: 'Record must exist and be accessible',
      idempotent: true,
      reversible: true,
    },
    {
      code: `${namespace}:delete_${shortName}`,
      'displayName:zh-CN': `Delete ${code}`,
      'displayName:en': `Delete ${code}`,
      type: 'delete',
      modelCode: code,
      permissions: [`${namespace.toUpperCase()}.${shortName}.manage`],
      agent_hint: `Delete a ${shortName} permanently`,
      cmd_risk_level: 'L4',
      precondition_description: 'Record must exist. This action is irreversible',
      idempotent: true,
      reversible: false,
    },
  ];

  // Pages: LIST + FORM
  const listPage = {
    pageKey: `${code}_list`,
    'name:zh-CN': `${code} List`,
    'name:en': `${code} List`,
    pageType: 'list',
    modelCode: code,
    dslSchema: {
      kind: 'List',
      version: '1.0.0',
      id: `list.${code}`,
      modelCode: code,
      layout: {
        areas: ['toolbar', 'main'],
        areasConfig: {
          toolbar: { type: 'flex', direction: 'row', justify: 'space-between', align: 'center' },
          main: { type: 'grid', cols: 12, rowGap: 0, colGap: 0 },
        },
      },
      areas: {
        toolbar: {
          blocks: [{
            id: 'block_toolbar',
            blockType: 'toolbar',
            buttons: [{
              code: 'create',
              action: 'create',
              primary: true,
              icon: 'Plus',
              commandCode: commands[0].code,
            }],
          }],
        },
        main: {
          blocks: [{
            id: 'block_table',
            blockType: 'data-table',
            columns: fieldDefs.slice(0, 6).map(f => ({ field: f.code, width: 150 })),
            rowActions: [
              { code: 'edit', action: 'edit', icon: 'Edit', commandCode: commands[1].code, navigateTo: `${code}_form` },
              { code: 'delete', action: 'delete', icon: 'Trash2', commandCode: commands[2].code },
            ],
            defaultSort: { field: 'created_at', order: 'desc' },
          }],
        },
      },
    },
  };

  const formPage = {
    pageKey: `${code}_form`,
    'name:zh-CN': `${code} Form`,
    'name:en': `${code} Form`,
    pageType: 'form',
    modelCode: code,
    dslSchema: {
      kind: 'Form',
      version: '1.0.0',
      id: `form.${code}`,
      modelCode: code,
      layout: {
        areas: ['main'],
        areasConfig: {
          main: { type: 'flex', direction: 'column', rowGap: 16 },
        },
      },
      areas: {
        main: {
          blocks: [{
            id: 'section_basic',
            blockType: 'form-section',
            title: { 'zh-CN': 'Basic Info', 'en-US': 'Basic Info' },
            columns: 2,
            fields: fieldDefs.map((f, i) => ({
              field: f.code,
              span: f.dataType === 'text' ? 2 : 1,
            })),
          }],
        },
      },
    },
  };

  // Permissions
  const shortCode = code.replace(`${namespace}_`, '');
  const permissions = [
    {
      code: `${namespace.toUpperCase()}.${shortCode}.manage`,
      'name:zh-CN': `${shortCode} Management`,
      'name:en': `${shortCode} Management`,
      resourceType: 'operation',
      module: namespace,
    },
    {
      code: `${namespace.toUpperCase()}.${shortCode}.read`,
      'name:zh-CN': `View ${shortCode}`,
      'name:en': `View ${shortCode}`,
      resourceType: 'data',
      module: namespace,
    },
  ];

  // Menu
  const menu = {
    code: `${namespace.toUpperCase()}_${shortCode.toUpperCase()}_LIST`,
    parentCode: null,
    'name:zh-CN': shortCode,
    'name:en': shortCode,
    path: `/dynamic/${code.replace(/_/g, '-')}`,
    icon: 'FileText',
    type: 1,
    permissionCode: `${namespace.toUpperCase()}.${shortCode}.read`,
    orderNo: 10,
    visible: true,
  };

  // i18n
  const i18n: any[] = [
    { key: `model.${code}._meta.label`, 'zh-CN': code, 'en-US': code, source: 'import', refType: 'model' },
    ...fieldDefs.map(f => ({
      key: `model.${code}.${f.code}.label`,
      'zh-CN': f.code.replace(`${code}_`, ''),
      'en-US': f.code.replace(`${code}_`, ''),
      source: 'import',
      refType: 'field',
    })),
  ];

  return { model, fieldDefs, bindings, commands, listPage, formPage, permissions, menu, i18n };
}

function generateCommandsForModel(modelCode: string, namespace: string, idx: any) {
  const fields = idx.fieldsByModel.get(modelCode) || [];
  const editableFields = fields.filter((f: any) => f.binding?.editable !== false).map((f: any) => f.code);

  const shortCode = modelCode.replace(`${namespace}_`, '');
  const fieldNames = editableFields.slice(0, 4).join(', ');
  const createHint = editableFields.length > 0
    ? `Create a new ${shortCode} with ${fieldNames}`
    : `Create a new ${shortCode} with required fields`;
  const commands: any[] = [
    {
      code: `${namespace}:create_${shortCode}`,
      'displayName:zh-CN': `Create ${shortCode}`,
      'displayName:en': `Create ${shortCode}`,
      type: 'create',
      modelCode,
      inputFields: editableFields,
      permissions: [`${namespace.toUpperCase()}.${shortCode}.manage`],
      agent_hint: createHint,
      cmd_risk_level: 'L1',
      precondition_description: 'All required fields must be provided',
      idempotent: false,
      reversible: false,
    },
    {
      code: `${namespace}:update_${shortCode}`,
      'displayName:zh-CN': `Update ${shortCode}`,
      'displayName:en': `Update ${shortCode}`,
      type: 'update',
      modelCode,
      inputFields: editableFields,
      permissions: [`${namespace.toUpperCase()}.${shortCode}.manage`],
      agent_hint: `Update an existing ${shortCode} record`,
      cmd_risk_level: 'L1',
      precondition_description: 'Record must exist and be accessible',
      idempotent: true,
      reversible: true,
    },
    {
      code: `${namespace}:delete_${shortCode}`,
      'displayName:zh-CN': `Delete ${shortCode}`,
      'displayName:en': `Delete ${shortCode}`,
      type: 'delete',
      modelCode,
      permissions: [`${namespace.toUpperCase()}.${shortCode}.manage`],
      agent_hint: `Delete a ${shortCode} permanently`,
      cmd_risk_level: 'L4',
      precondition_description: 'Record must exist. This action is irreversible',
      idempotent: true,
      reversible: false,
    },
  ];

  // Add STATE_TRANSITION if model has a status-like field
  const statusField = fields.find((f: any) =>
    f.dataType === 'enum' && (f.code.includes('status') || f.code.includes('state')),
  );
  if (statusField) {
    commands.push({
      code: `${namespace}:transition_${shortCode}`,
      'displayName:zh-CN': `Transition ${shortCode}`,
      'displayName:en': `Transition ${shortCode}`,
      type: 'state_transition',
      modelCode,
      stateField: statusField.code,
      permissions: [`${namespace.toUpperCase()}.${shortCode}.manage`],
      agent_hint: `Change the status of a ${shortCode}`,
      cmd_risk_level: 'L1',
      precondition_description: 'Record must exist and current status must allow transition',
      idempotent: false,
      reversible: true,
    });
  }

  return commands;
}

function generatePagesForModel(modelCode: string, namespace: string, idx: any) {
  const fields = idx.fieldsByModel.get(modelCode) || [];
  const fieldCodes = fields.map((f: any) => f.code);

  const listPage = {
    pageKey: `${modelCode}_list`,
    'name:zh-CN': `${modelCode} List`,
    'name:en': `${modelCode} List`,
    pageType: 'list',
    modelCode,
    dslSchema: {
      kind: 'List',
      version: '1.0.0',
      id: `list.${modelCode}`,
      modelCode,
      layout: {
        areas: ['toolbar', 'main'],
        areasConfig: {
          toolbar: { type: 'flex', direction: 'row', justify: 'space-between', align: 'center' },
          main: { type: 'grid', cols: 12 },
        },
      },
      areas: {
        toolbar: { blocks: [{ id: 'block_toolbar', blockType: 'toolbar', buttons: [{ code: 'create', action: 'create', primary: true, icon: 'Plus' }] }] },
        main: {
          blocks: [{
            id: 'block_table',
            blockType: 'data-table',
            columns: fieldCodes.slice(0, 8).map((fc: string) => ({ field: fc, width: 150 })),
            rowActions: [
              { code: 'edit', action: 'edit', icon: 'Edit' },
              { code: 'delete', action: 'delete', icon: 'Trash2' },
            ],
            defaultSort: { field: 'created_at', order: 'desc' },
          }],
        },
      },
    },
  };

  const formPage = {
    pageKey: `${modelCode}_form`,
    'name:zh-CN': `${modelCode} Form`,
    'name:en': `${modelCode} Form`,
    pageType: 'form',
    modelCode,
    dslSchema: {
      kind: 'Form',
      version: '1.0.0',
      id: `form.${modelCode}`,
      modelCode,
      layout: { areas: ['main'], areasConfig: { main: { type: 'flex', direction: 'column', rowGap: 16 } } },
      areas: {
        main: {
          blocks: [{
            id: 'section_basic',
            blockType: 'form-section',
            title: { 'zh-CN': 'Basic Info', 'en-US': 'Basic Info' },
            columns: 2,
            fields: fieldCodes.map((fc: string) => ({ field: fc, span: 1 })),
          }],
        },
      },
    },
  };

  const detailPage = {
    pageKey: `${modelCode}_detail`,
    'name:zh-CN': `${modelCode} Detail`,
    'name:en': `${modelCode} Detail`,
    pageType: 'detail',
    modelCode,
    dslSchema: {
      kind: 'Detail',
      version: '1.0.0',
      id: `detail.${modelCode}`,
      modelCode,
      layout: { areas: ['main'], areasConfig: { main: { type: 'flex', direction: 'column', rowGap: 16 } } },
      areas: {
        main: {
          blocks: [{
            id: 'section_basic',
            blockType: 'detail-section',
            title: { 'zh-CN': 'Basic Info', 'en-US': 'Basic Info' },
            columns: 2,
            fields: fieldCodes.map((fc: string) => ({ field: fc, span: 1 })),
          }],
        },
      },
    },
  };

  return [listPage, formPage, detailPage];
}

export async function scaffoldCommand(
  subType: string,
  code: string,
  options: { dir: string; pretty: boolean; quiet: boolean; fields?: string; namespace?: string; dryRun?: boolean },
): Promise<void> {
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };
  const dir = resolve(options.dir);

  if (subType === 'model') {
    // Generate full model scaffold from scratch
    const ns = options.namespace || code.split('_')[0];
    const parsedFields = parseFieldSpec(options.fields || 'name:STRING,description:TEXT');
    const scaffold = generateModelScaffold(code, ns, parsedFields);

    const generated: GeneratedFile[] = [];
    const filesToWrite: Array<{ path: string; resourceType: string; data: any[] }> = [
      { path: 'config/models.json', resourceType: 'models', data: [scaffold.model] },
      { path: 'config/fields.json', resourceType: 'fields', data: scaffold.fieldDefs },
      { path: 'config/bindings.json', resourceType: 'bindings', data: scaffold.bindings },
      { path: 'config/commands.json', resourceType: 'commands', data: scaffold.commands },
      { path: 'config/permissions.json', resourceType: 'permissions', data: scaffold.permissions },
      { path: 'config/menus.json', resourceType: 'menus', data: [scaffold.menu] },
      { path: 'config/i18n.json', resourceType: 'i18n', data: scaffold.i18n },
    ];

    // Pages as separate array
    filesToWrite.push({ path: 'config/pages.json', resourceType: 'pages', data: [scaffold.listPage, scaffold.formPage] });

    if (options.dryRun) {
      const result: ScaffoldResult = {
        type: 'model',
        code,
        generated: filesToWrite.map(f => ({ path: f.path, resourceType: f.resourceType, itemCount: f.data.length, action: 'create' as const })),
      };
      const output = successOutput('dsl.scaffold', { ...result, dryRun: true, preview: { model: scaffold.model, fields: scaffold.fieldDefs, commands: scaffold.commands } });
      if (options.pretty) {
        formatOutput(output, fmt);
        console.log(chalk.bold.cyan(`[DRY RUN] Scaffold model: ${code}`));
        console.log(chalk.dim(`Namespace: ${ns}`));
        console.log(chalk.dim(`Fields: ${parsedFields.map(f => `${f.code}:${f.dataType}`).join(', ')}`));
        console.log();
        console.log(chalk.bold('Files to generate:'));
        for (const f of filesToWrite) {
          console.log(`  ${chalk.green('+')} ${f.path} (${f.data.length} ${f.resourceType})`);
        }
      } else {
        formatOutput(output, fmt);
      }
      return;
    }

    // Write files: merge with existing or create new
    const configDir = join(dir, 'config');
    if (!existsSync(configDir)) mkdirSync(configDir, { recursive: true });

    for (const f of filesToWrite) {
      const filePath = join(dir, f.path);
      let existing: any[] = [];
      if (existsSync(filePath)) {
        try {
          const content = JSON.parse(require('fs').readFileSync(filePath, 'utf-8'));
          existing = Array.isArray(content) ? content : [content];
        } catch { /* ignore parse errors */ }
      }
      const merged = [...existing, ...f.data];
      writeFileSync(filePath, JSON.stringify(merged, null, 2) + '\n', 'utf-8');
      generated.push({ path: f.path, resourceType: f.resourceType, itemCount: f.data.length, action: existing.length > 0 ? 'append' : 'create' });
    }

    const result: ScaffoldResult = { type: 'model', code, generated };
    const output = successOutput('dsl.scaffold', result);
    if (options.pretty) {
      formatOutput(output, fmt);
      console.log(chalk.green(`✓ Model '${code}' scaffolded successfully.`));
      for (const g of generated) {
        const icon = g.action === 'create' ? chalk.green('+') : chalk.yellow('~');
        console.log(`  ${icon} ${g.path} (${g.itemCount} ${g.resourceType})`);
      }
    } else {
      formatOutput(output, fmt);
    }

  } else if (subType === 'commands') {
    const files = loadPlugin(dir);
    const idx = buildResourceIndex(files);

    if (!idx.models.has(code)) {
      const output = errorOutput('dsl.scaffold', [{ code: 'not_found', message: `Model '${code}' not found` }]);
      formatOutput(output, fmt);
      process.exit(1);
    }

    const ns = files.manifest.namespace;
    const commands = generateCommandsForModel(code, ns, idx);

    if (options.dryRun) {
      const output = successOutput('dsl.scaffold', { type: 'commands', code, dryRun: true, commands });
      if (options.pretty) {
        formatOutput(output, fmt);
        console.log(chalk.bold.cyan(`[DRY RUN] Scaffold commands for: ${code}`));
        for (const c of commands) {
          console.log(`  ${chalk.green('+')} ${c.code} (${c.type})`);
        }
      } else {
        formatOutput(output, fmt);
      }
      return;
    }

    // Append to commands.json
    const cmdPath = join(dir, 'config', 'commands.json');
    let existing: any[] = [];
    if (existsSync(cmdPath)) {
      existing = JSON.parse(require('fs').readFileSync(cmdPath, 'utf-8'));
    }
    writeFileSync(cmdPath, JSON.stringify([...existing, ...commands], null, 2) + '\n', 'utf-8');

    const output = successOutput('dsl.scaffold', {
      type: 'commands',
      code,
      generated: [{ path: 'config/commands.json', resourceType: 'commands', itemCount: commands.length, action: 'append' as const }],
    });
    if (options.pretty) {
      formatOutput(output, fmt);
      console.log(chalk.green(`✓ ${commands.length} commands scaffolded for '${code}'.`));
    } else {
      formatOutput(output, fmt);
    }

  } else if (subType === 'pages') {
    const files = loadPlugin(dir);
    const idx = buildResourceIndex(files);

    if (!idx.models.has(code)) {
      const output = errorOutput('dsl.scaffold', [{ code: 'not_found', message: `Model '${code}' not found` }]);
      formatOutput(output, fmt);
      process.exit(1);
    }

    const ns = files.manifest.namespace;
    const pages = generatePagesForModel(code, ns, idx);

    if (options.dryRun) {
      const output = successOutput('dsl.scaffold', { type: 'pages', code, dryRun: true, pages: pages.map(p => ({ pageKey: p.pageKey, pageType: p.pageType })) });
      if (options.pretty) {
        formatOutput(output, fmt);
        console.log(chalk.bold.cyan(`[DRY RUN] Scaffold pages for: ${code}`));
        for (const p of pages) {
          console.log(`  ${chalk.green('+')} ${p.pageKey} (${p.pageType})`);
        }
      } else {
        formatOutput(output, fmt);
      }
      return;
    }

    // Append to pages.json
    const pagesPath = join(dir, 'config', 'pages.json');
    let existing: any[] = [];
    if (existsSync(pagesPath)) {
      existing = JSON.parse(require('fs').readFileSync(pagesPath, 'utf-8'));
    }
    writeFileSync(pagesPath, JSON.stringify([...existing, ...pages], null, 2) + '\n', 'utf-8');

    const output = successOutput('dsl.scaffold', {
      type: 'pages',
      code,
      generated: [{ path: 'config/pages.json', resourceType: 'pages', itemCount: pages.length, action: 'append' as const }],
    });
    if (options.pretty) {
      formatOutput(output, fmt);
      console.log(chalk.green(`✓ ${pages.length} pages scaffolded for '${code}'.`));
    } else {
      formatOutput(output, fmt);
    }

  } else {
    const output = errorOutput('dsl.scaffold', [{ code: 'invalid_subtype', message: `Unknown scaffold type: ${subType}. Valid: model, commands, pages` }]);
    formatOutput(output, fmt);
    process.exit(2);
  }
}
