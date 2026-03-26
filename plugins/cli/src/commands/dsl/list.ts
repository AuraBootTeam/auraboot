import { loadPlugin } from '../../utils/plugin-loader.js';
import { buildResourceIndex, ResourceIndex } from '../../utils/resource-index.js';
import { successOutput, errorOutput, formatOutput, printTable, FormatOptions, CliOutput } from '../../utils/output-formatter.js';

type ResourceType = 'models' | 'fields' | 'commands' | 'pages' | 'permissions' | 'menus' | 'dicts';

const TYPE_ALIASES: Record<string, ResourceType> = {
  model: 'models', models: 'models',
  field: 'fields', fields: 'fields',
  command: 'commands', commands: 'commands',
  page: 'pages', pages: 'pages',
  permission: 'permissions', permissions: 'permissions',
  menu: 'menus', menus: 'menus',
  dict: 'dicts', dicts: 'dicts',
};

interface ListItem {
  code: string;
  type?: string;
  modelCode?: string;
  [key: string]: any;
}

interface ListData {
  resourceType: ResourceType;
  count: number;
  items: ListItem[];
}

function resolveType(input: string): ResourceType | null {
  return TYPE_ALIASES[input.toLowerCase()] || null;
}

function listModels(idx: ResourceIndex): ListItem[] {
  return idx.raw.models.map(m => ({
    code: m.code,
    modelType: m.modelType || 'entity',
    fieldCount: (idx.fieldsByModel.get(m.code) || []).length,
    commandCount: (idx.commandsByModel.get(m.code) || []).length,
    pageCount: (idx.pagesByModel.get(m.code) || []).length,
  }));
}

function listFields(idx: ResourceIndex, modelFilter?: string): ListItem[] {
  if (modelFilter) {
    return (idx.fieldsByModel.get(modelFilter) || []).map(f => ({
      code: f.code,
      dataType: f.dataType,
      modelCode: modelFilter,
      required: f.binding?.required ?? false,
    }));
  }
  return idx.raw.fields.map(f => ({
    code: f.code,
    dataType: f.dataType,
    boundTo: (idx.bindingsByField.get(f.code) || []).join(', '),
  }));
}

function listCommands(idx: ResourceIndex, modelFilter?: string): ListItem[] {
  const cmds = modelFilter
    ? (idx.commandsByModel.get(modelFilter) || [])
    : idx.raw.commands;
  return cmds.map(c => ({
    code: c.code,
    type: c.type,
    modelCode: c.modelCode,
    inputFieldCount: (c.inputFields || []).length,
  }));
}

function listPages(idx: ResourceIndex, modelFilter?: string): ListItem[] {
  const pgs = modelFilter
    ? (idx.pagesByModel.get(modelFilter) || [])
    : idx.raw.pages;
  return pgs.map(p => ({
    code: p.pageKey,
    pageType: p.pageType || p.dslSchema?.kind,
    modelCode: p.modelCode || p.dslSchema?.modelCode,
  }));
}

function listPermissions(idx: ResourceIndex): ListItem[] {
  return idx.raw.permissions.map(p => ({
    code: p.code,
    resourceType: p.resourceType,
    module: p.module,
  }));
}

function listMenus(idx: ResourceIndex): ListItem[] {
  return idx.raw.menus.map(m => ({
    code: m.code,
    parentCode: m.parentCode || '-',
    path: m.path,
    type: m.type === 0 ? 'dir' : m.type === 1 ? 'menu' : 'btn',
  }));
}

function listDicts(idx: ResourceIndex): ListItem[] {
  return idx.raw.dicts.map(d => ({
    code: d.code,
    dictType: d.dictType || 'static',
    itemCount: (d.items || []).length,
  }));
}

const PRETTY_HEADERS: Record<ResourceType, string[]> = {
  models: ['Code', 'Type', 'Fields', 'Commands', 'Pages'],
  fields: ['Code', 'DataType', 'BoundTo'],
  commands: ['Code', 'Type', 'Model', 'InputFields'],
  pages: ['PageKey', 'PageType', 'Model'],
  permissions: ['Code', 'ResourceType', 'Module'],
  menus: ['Code', 'Parent', 'Path', 'Type'],
  dicts: ['Code', 'DictType', 'Items'],
};

function toRow(type: ResourceType, item: ListItem): string[] {
  switch (type) {
    case 'models': return [item.code, item.modelType, String(item.fieldCount), String(item.commandCount), String(item.pageCount)];
    case 'fields': return [item.code, item.dataType, item.boundTo || item.modelCode || '-'];
    case 'commands': return [item.code, item.type || '-', item.modelCode || '-', String(item.inputFieldCount)];
    case 'pages': return [item.code, item.pageType || '-', item.modelCode || '-'];
    case 'permissions': return [item.code, item.resourceType || '-', item.module || '-'];
    case 'menus': return [item.code, item.parentCode || '-', item.path || '-', item.type || '-'];
    case 'dicts': return [item.code, item.dictType, String(item.itemCount)];
  }
}

export async function listCommand(type: string, options: { dir: string; pretty: boolean; quiet: boolean; model?: string }): Promise<void> {
  const resourceType = resolveType(type);
  if (!resourceType) {
    const output = errorOutput('dsl.list', [{ code: 'invalid_type', message: `Unknown resource type: ${type}. Valid types: ${Object.keys(TYPE_ALIASES).filter(k => k.endsWith('s')).join(', ')}` }]);
    formatOutput(output, { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet });
    process.exit(2);
  }

  const files = loadPlugin(options.dir);
  const idx = buildResourceIndex(files);
  const fmt: FormatOptions = { format: options.pretty ? 'pretty' : 'json', quiet: options.quiet };

  let items: ListItem[];
  switch (resourceType) {
    case 'models': items = listModels(idx); break;
    case 'fields': items = listFields(idx, options.model); break;
    case 'commands': items = listCommands(idx, options.model); break;
    case 'pages': items = listPages(idx, options.model); break;
    case 'permissions': items = listPermissions(idx); break;
    case 'menus': items = listMenus(idx); break;
    case 'dicts': items = listDicts(idx); break;
  }

  const output: CliOutput<ListData> = successOutput('dsl.list', { resourceType, count: items.length, items }, files.manifest.pluginId);

  if (options.pretty) {
    formatOutput(output, fmt);
    // Field-filtered headers
    const headers = options.model && resourceType === 'fields'
      ? ['Code', 'DataType', 'Model', 'Required']
      : PRETTY_HEADERS[resourceType];
    const rows = items.map(item => {
      if (options.model && resourceType === 'fields') {
        return [item.code, item.dataType, item.modelCode || '-', String(item.required)];
      }
      return toRow(resourceType, item);
    });
    printTable(headers, rows);
    console.log(`\n${items.length} ${resourceType} found.`);
  } else {
    formatOutput(output, fmt);
  }
}
