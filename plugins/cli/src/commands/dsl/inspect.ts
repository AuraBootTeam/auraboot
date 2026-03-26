import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex, ResourceIndex } from '../../utils/resource-index.js';
import { successOutput, errorOutput, formatOutput, printTable, printTree, printStats, FormatOptions } from '../../utils/output-formatter.js';
import chalk from 'chalk';

type InspectType = 'model' | 'command' | 'page' | 'field' | 'menu' | 'permission' | 'dict';

const TYPE_ALIASES: Record<string, InspectType> = {
  model: 'model', models: 'model',
  command: 'command', commands: 'command',
  page: 'page', pages: 'page',
  field: 'field', fields: 'field',
  menu: 'menu', menus: 'menu',
  permission: 'permission', permissions: 'permission',
  dict: 'dict', dicts: 'dict',
};

function inspectModel(code: string, idx: ResourceIndex) {
  const model = idx.models.get(code);
  if (!model) return null;

  const fields = (idx.fieldsByModel.get(code) || []).map((f: any) => ({
    code: f.code,
    dataType: f.dataType,
    required: f.binding?.required ?? false,
  }));
  const commands = (idx.commandsByModel.get(code) || []).map((c: any) => ({
    code: c.code,
    type: c.type,
  }));
  const pages = (idx.pagesByModel.get(code) || []).map((p: any) => ({
    pageKey: p.pageKey,
    pageType: p.pageType || p.dslSchema?.kind,
  }));
  const referencedBy = (idx.referenceFields.get(code) || []).map((f: any) => ({
    fieldCode: f.code,
    fromModels: idx.bindingsByField.get(f.code) || [],
  }));

  // i18n coverage
  const modelLabelKey = `model.${code}._meta.label`;
  const hasModelLabel = idx.i18nKeys.has(modelLabelKey);
  const fieldKeys = fields.map((f: any) => `model.${code}.${f.code}.label`);
  const missingFieldLabels = fieldKeys.filter((k: string) => !idx.i18nKeys.has(k));

  return {
    ...model,
    fields,
    commands,
    pages,
    referencedBy,
    i18n: {
      hasModelLabel,
      totalFieldKeys: fieldKeys.length,
      missingFieldLabels,
    },
  };
}

function inspectCommand(code: string, idx: ResourceIndex) {
  const cmd = idx.commands.get(code);
  if (!cmd) return null;

  const model = cmd.modelCode ? idx.models.get(cmd.modelCode) : null;
  const resolvedInputFields = (cmd.inputFields || []).map((fc: string) => {
    const f = idx.fields.get(fc);
    return { code: fc, dataType: f?.dataType || 'unknown', exists: !!f };
  });

  return {
    ...cmd,
    modelExists: !!model,
    resolvedInputFields,
  };
}

function inspectPage(key: string, idx: ResourceIndex) {
  const page = idx.pages.get(key);
  if (!page) return null;

  const mc = page.modelCode || page.dslSchema?.modelCode;
  const model = mc ? idx.models.get(mc) : null;

  // Extract block types from dslSchema
  const blocks: string[] = [];
  if (page.dslSchema?.areas) {
    for (const area of Object.values(page.dslSchema.areas) as any[]) {
      if (area?.blocks) {
        for (const b of area.blocks) {
          blocks.push(b.blockType || b.type || 'unknown');
        }
      }
    }
  }

  return {
    ...page,
    modelExists: mc ? !!model : undefined,
    blockTypes: blocks,
  };
}

function inspectField(code: string, idx: ResourceIndex) {
  const field = idx.fields.get(code);
  if (!field) return null;

  const boundModels = idx.bindingsByField.get(code) || [];
  return {
    ...field,
    boundModels,
  };
}

function inspectMenu(code: string, idx: ResourceIndex) {
  const menu = idx.menus.get(code);
  if (!menu) return null;

  const permExists = menu.permissionCode ? idx.permissions.has(menu.permissionCode) : undefined;
  const children = idx.raw.menus.filter((m: any) => m.parentCode === code).map((m: any) => m.code);

  return {
    ...menu,
    permissionExists: permExists,
    children,
  };
}

function inspectPermission(code: string, idx: ResourceIndex) {
  const perm = idx.permissions.get(code);
  if (!perm) return null;

  const usedByCommands = idx.raw.commands
    .filter((c: any) => (c.permissions || []).includes(code))
    .map((c: any) => c.code);
  const usedByMenus = idx.raw.menus
    .filter((m: any) => m.permissionCode === code)
    .map((m: any) => m.code);

  return { ...perm, usedByCommands, usedByMenus };
}

function inspectDict(code: string, idx: ResourceIndex) {
  const dict = idx.dicts.get(code);
  if (!dict) return null;

  // Find fields referencing this dict
  const usedByFields = idx.raw.fields
    .filter((f: any) => f.extension?.dictCode === code || f.dictCode === code)
    .map((f: any) => f.code);

  return { ...dict, usedByFields };
}

function prettyPrintModel(data: any): void {
  console.log(chalk.bold.cyan(`Model: ${data.code}`));
  printStats({
    'Type': data.modelType || 'entity',
    'Fields': data.fields.length,
    'Commands': data.commands.length,
    'Pages': data.pages.length,
    'Referenced By': data.referencedBy.length,
  });

  if (data.fields.length > 0) {
    console.log(chalk.bold('\nFields:'));
    printTable(
      ['Code', 'DataType', 'Required'],
      data.fields.map((f: any) => [f.code, f.dataType, String(f.required)]),
    );
  }

  if (data.commands.length > 0) {
    console.log(chalk.bold('\nCommands:'));
    printTable(
      ['Code', 'Type'],
      data.commands.map((c: any) => [c.code, c.type]),
    );
  }

  if (data.pages.length > 0) {
    console.log(chalk.bold('\nPages:'));
    printTable(
      ['PageKey', 'PageType'],
      data.pages.map((p: any) => [p.pageKey, p.pageType || '-']),
    );
  }

  if (data.referencedBy.length > 0) {
    console.log(chalk.bold('\nReferenced By:'));
    for (const ref of data.referencedBy) {
      console.log(`  ${ref.fieldCode} (from: ${ref.fromModels.join(', ')})`);
    }
  }

  // i18n
  const i18nStatus = data.i18n.hasModelLabel ? chalk.green('✓') : chalk.red('✗');
  console.log(chalk.bold('\ni18n Coverage:'));
  console.log(`  Model label: ${i18nStatus}`);
  console.log(`  Field labels: ${data.i18n.totalFieldKeys - data.i18n.missingFieldLabels.length}/${data.i18n.totalFieldKeys}`);
  if (data.i18n.missingFieldLabels.length > 0) {
    console.log(chalk.yellow(`  Missing: ${data.i18n.missingFieldLabels.join(', ')}`));
  }
}

export async function inspectCommand_(type: string, code: string | undefined, options: { dir: string; pretty: boolean; quiet: boolean }): Promise<void> {
  const inspectType = TYPE_ALIASES[type.toLowerCase()];
  if (!inspectType) {
    const output = errorOutput('dsl.inspect', [{ code: 'invalid_type', message: `Unknown type: ${type}` }]);
    formatOutput(output, { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet });
    process.exit(2);
  }

  if (!code) {
    const output = errorOutput('dsl.inspect', [{ code: 'missing_code', message: `Code is required for inspect. Use 'aura dsl list ${type}' to see available codes.` }]);
    formatOutput(output, { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet });
    process.exit(2);
  }

  const files = loadPlugin(options.dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  let data: any;
  switch (inspectType) {
    case 'model': data = inspectModel(code, idx); break;
    case 'command': data = inspectCommand(code, idx); break;
    case 'page': data = inspectPage(code, idx); break;
    case 'field': data = inspectField(code, idx); break;
    case 'menu': data = inspectMenu(code, idx); break;
    case 'permission': data = inspectPermission(code, idx); break;
    case 'dict': data = inspectDict(code, idx); break;
  }

  if (!data) {
    const output = errorOutput('dsl.inspect', [{ code: 'not_found', message: `${inspectType} '${code}' not found`, suggestion: `Use 'aura dsl list ${type}' to see available codes` }]);
    formatOutput(output, fmt);
    process.exit(1);
  }

  const output = successOutput('dsl.inspect', { type: inspectType, code, ...data }, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);
    if (inspectType === 'model') {
      prettyPrintModel(data);
    } else {
      // Generic pretty print for other types
      console.log(chalk.bold.cyan(`${inspectType}: ${code}`));
      const { fields, commands, pages, resolvedInputFields, blockTypes, boundModels, children, usedByCommands, usedByMenus, usedByFields, items, ...meta } = data;
      for (const [k, v] of Object.entries(meta)) {
        if (v !== undefined && v !== null && typeof v !== 'object') {
          console.log(`  ${chalk.dim(k)}: ${v}`);
        }
      }
      // Print arrays if present
      if (resolvedInputFields) {
        console.log(chalk.bold('\nInput Fields:'));
        printTable(['Code', 'DataType', 'Exists'], resolvedInputFields.map((f: any) => [f.code, f.dataType, String(f.exists)]));
      }
      if (blockTypes?.length) console.log(`\n${chalk.bold('Block Types:')} ${blockTypes.join(', ')}`);
      if (boundModels?.length) console.log(`\n${chalk.bold('Bound To Models:')} ${boundModels.join(', ')}`);
      if (children?.length) console.log(`\n${chalk.bold('Children:')} ${children.join(', ')}`);
      if (usedByCommands?.length) console.log(`\n${chalk.bold('Used By Commands:')} ${usedByCommands.join(', ')}`);
      if (usedByMenus?.length) console.log(`\n${chalk.bold('Used By Menus:')} ${usedByMenus.join(', ')}`);
      if (usedByFields?.length) console.log(`\n${chalk.bold('Used By Fields:')} ${usedByFields.join(', ')}`);
      if (items?.length) {
        console.log(chalk.bold('\nItems:'));
        printTable(['Value', 'Label'], items.map((i: any) => [i.value, i.label || i['label:zh-CN'] || '-']));
      }
    }
  } else {
    formatOutput(output, fmt);
  }
}
