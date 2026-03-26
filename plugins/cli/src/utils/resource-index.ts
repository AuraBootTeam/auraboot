import { PluginFiles } from './plugin-loader.js';

/**
 * Cross-reference index built from PluginFiles.
 * Built once, reused by all dsl commands.
 */
export interface ResourceIndex {
  pluginId: string;
  namespace: string;

  // Primary maps (code → resource)
  models: Map<string, any>;
  fields: Map<string, any>;
  commands: Map<string, any>;
  pages: Map<string, any>;
  permissions: Map<string, any>;
  menus: Map<string, any>;
  dicts: Map<string, any>;

  // Relationship maps
  fieldsByModel: Map<string, any[]>;           // modelCode → bound fields
  commandsByModel: Map<string, any[]>;         // modelCode → commands
  pagesByModel: Map<string, any[]>;            // modelCode → pages
  referenceFields: Map<string, any[]>;         // targetModel → fields referencing it
  bindingsByModel: Map<string, any[]>;         // modelCode → bindings
  bindingsByField: Map<string, string[]>;      // fieldCode → modelCodes

  // i18n
  i18nKeys: Map<string, any>;                 // key → i18n entry
  expectedI18nKeys: string[];
  missingI18nKeys: string[];

  // Raw arrays
  raw: {
    models: any[];
    fields: any[];
    commands: any[];
    pages: any[];
    permissions: any[];
    menus: any[];
    dicts: any[];
    bindings: any[];
    i18n: any[];
  };
}

/**
 * Build a ResourceIndex from loaded PluginFiles.
 */
export function buildResourceIndex(files: PluginFiles): ResourceIndex {
  const get = (key: string) => files.resourceFiles.get(key) || [];

  const rawModels = get('models');
  const rawFields = get('fields');
  const rawCommands = get('commands');
  const rawPages = get('pages');
  const rawPermissions = get('permissions');
  const rawMenus = get('menus');
  const rawDicts = get('dicts');
  const rawBindings = get('bindings') || get('modelFieldBindings');
  const rawI18n = get('i18n') || get('i18nResources');

  // Primary maps
  const models = new Map<string, any>();
  for (const m of rawModels) models.set(m.code, m);

  const fields = new Map<string, any>();
  for (const f of rawFields) fields.set(f.code, f);

  const commands = new Map<string, any>();
  for (const c of rawCommands) commands.set(c.code, c);

  const pages = new Map<string, any>();
  for (const p of rawPages) pages.set(p.pageKey, p);

  const permissions = new Map<string, any>();
  for (const p of rawPermissions) permissions.set(p.code, p);

  const menus = new Map<string, any>();
  for (const m of rawMenus) menus.set(m.code, m);

  const dicts = new Map<string, any>();
  for (const d of rawDicts) dicts.set(d.code, d);

  // Relationship: bindings
  const fieldsByModel = new Map<string, any[]>();
  const bindingsByModel = new Map<string, any[]>();
  const bindingsByField = new Map<string, string[]>();

  for (const b of rawBindings) {
    const mc = b.modelCode;
    const fc = b.fieldCode;

    if (!bindingsByModel.has(mc)) bindingsByModel.set(mc, []);
    bindingsByModel.get(mc)!.push(b);

    if (!bindingsByField.has(fc)) bindingsByField.set(fc, []);
    bindingsByField.get(fc)!.push(mc);

    // Resolve field def and attach to fieldsByModel
    const fieldDef = fields.get(fc);
    if (fieldDef) {
      if (!fieldsByModel.has(mc)) fieldsByModel.set(mc, []);
      fieldsByModel.get(mc)!.push({ ...fieldDef, binding: b });
    }
  }

  // Relationship: commands by model
  const commandsByModel = new Map<string, any[]>();
  for (const c of rawCommands) {
    const mc = c.modelCode;
    if (mc) {
      if (!commandsByModel.has(mc)) commandsByModel.set(mc, []);
      commandsByModel.get(mc)!.push(c);
    }
  }

  // Relationship: pages by model
  const pagesByModel = new Map<string, any[]>();
  for (const p of rawPages) {
    const mc = p.modelCode || p.dslSchema?.modelCode;
    if (mc) {
      if (!pagesByModel.has(mc)) pagesByModel.set(mc, []);
      pagesByModel.get(mc)!.push(p);
    }
  }

  // Relationship: reference fields (who references whom)
  const referenceFields = new Map<string, any[]>();
  for (const f of rawFields) {
    if (f.dataType === 'reference' && f.extension?.referenceModel) {
      const target = f.extension.referenceModel;
      if (!referenceFields.has(target)) referenceFields.set(target, []);
      referenceFields.get(target)!.push(f);
    }
  }

  // i18n
  const i18nKeys = new Map<string, any>();
  for (const entry of rawI18n) {
    if (entry.key) i18nKeys.set(entry.key, entry);
  }

  // Expected i18n keys
  const expectedI18nKeys: string[] = [];
  for (const m of rawModels) {
    expectedI18nKeys.push(`model.${m.code}._meta.label`);
  }
  for (const b of rawBindings) {
    expectedI18nKeys.push(`model.${b.modelCode}.${b.fieldCode}.label`);
  }

  const missingI18nKeys = expectedI18nKeys.filter(k => !i18nKeys.has(k));

  return {
    pluginId: files.manifest.pluginId,
    namespace: files.manifest.namespace,
    models,
    fields,
    commands,
    pages,
    permissions,
    menus,
    dicts,
    fieldsByModel,
    commandsByModel,
    pagesByModel,
    referenceFields,
    bindingsByModel,
    bindingsByField,
    i18nKeys,
    expectedI18nKeys,
    missingI18nKeys,
    raw: {
      models: rawModels,
      fields: rawFields,
      commands: rawCommands,
      pages: rawPages,
      permissions: rawPermissions,
      menus: rawMenus,
      dicts: rawDicts,
      bindings: rawBindings,
      i18n: rawI18n,
    },
  };
}
