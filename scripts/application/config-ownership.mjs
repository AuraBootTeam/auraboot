import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, resolve } from 'node:path';

const BPM_LITERAL = /(?:^|[^a-z0-9])(bpm|sla|smartengine|smart_engine)(?:[^a-z0-9]|$)/i;

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

function resourceIdentity(resource, relativePath, bpmExclusiveFieldCodes = new Set()) {
  if (relativePath.endsWith('i18n.json')) return JSON.stringify(resource);
  if (relativePath.endsWith('models.json') || relativePath.endsWith('dicts.json')
      || relativePath.endsWith('permissions.json') || relativePath.endsWith('capabilities.json')) {
    return resource.code ?? resource.key ?? '';
  }
  if (relativePath.endsWith('fields.json')) {
    return bpmExclusiveFieldCodes.has(resource.code) ? 'bpm' : '';
  }
  if (relativePath.endsWith('bindings.json')) {
    return resource.modelCode ?? '';
  }
  if (relativePath.endsWith('commands.json')) {
    return `${resource.modelCode ?? ''} ${resource.code ?? ''}`;
  }
  if (relativePath.endsWith('pages.json')) {
    return `${resource.modelCode ?? ''} ${resource.pageKey ?? ''}`;
  }
  if (relativePath.endsWith('menus.json')) {
    return `${resource.code ?? ''} ${resource.path ?? ''} ${resource.pageKey ?? ''} ${resource.permissionCode ?? ''}`;
  }
  if (relativePath.endsWith('bindingRules.json')) {
    return resource.commandCode ?? '';
  }
  return '';
}

export function isBpmOwnedConfigResource(resource, relativePath, bpmExclusiveFieldCodes) {
  return BPM_LITERAL.test(resourceIdentity(resource, relativePath, bpmExclusiveFieldCodes));
}

function stripNestedBpmEntries(value) {
  if (Array.isArray(value)) {
    return value
      .filter((entry) => !BPM_LITERAL.test(JSON.stringify(entry)))
      .map(stripNestedBpmEntries);
  }
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([key, child]) => [key, stripNestedBpmEntries(child)]));
  }
  return value;
}

/**
 * Split the historically mixed platform-admin config plugin into immutable
 * core and BPM-owned release inputs. Source mode keeps using the original
 * directory; artifact mode consumes only the generated core directory.
 */
export function writePlatformAdminConfigSplit(sourceRoot, outputRoot) {
  const coreRoot = resolve(outputRoot, 'core', 'platform-admin');
  const bpmRoot = resolve(outputRoot, 'product', 'bpm', 'platform-admin');
  mkdirSync(coreRoot, { recursive: true });
  mkdirSync(bpmRoot, { recursive: true });
  cpSync(sourceRoot, coreRoot, { recursive: true });
  cpSync(sourceRoot, bpmRoot, { recursive: true });

  const sourceManifest = readJson(resolve(sourceRoot, 'plugin.json'));
  const bindings = readJson(resolve(sourceRoot, sourceManifest.resourceDirs.modelFieldBindings));
  const bpmFieldCodes = new Set(bindings
    .filter((binding) => BPM_LITERAL.test(binding.modelCode ?? ''))
    .map((binding) => binding.fieldCode));
  const coreFieldCodes = new Set(bindings
    .filter((binding) => !BPM_LITERAL.test(binding.modelCode ?? ''))
    .map((binding) => binding.fieldCode));
  const bpmExclusiveFieldCodes = new Set([...bpmFieldCodes].filter((code) => !coreFieldCodes.has(code)));
  writeJson(resolve(coreRoot, 'plugin.json'), {
    ...sourceManifest,
    description: 'DSL-driven administration pages for AuraBoot platform capabilities',
  });
  writeJson(resolve(bpmRoot, 'plugin.json'), {
    ...sourceManifest,
    pluginId: 'com.auraboot.bpm-admin',
    namespace: 'bpm-admin',
    displayName: 'Aura BPM Administration',
    'displayName:zh-CN': 'Aura BPM 管理',
    'displayName:en': 'Aura BPM Administration',
    description: 'BPM-owned administration resources extracted from the platform release',
  });

  const manifest = [];
  for (const relativePath of Object.values(sourceManifest.resourceDirs ?? {})) {
    const sourcePath = resolve(sourceRoot, relativePath);
    const resources = readJson(sourcePath);
    if (!Array.isArray(resources)) {
      throw new Error(`platform-admin resource must be an array: ${relativePath}`);
    }
    const core = resources
      .filter((resource) => !isBpmOwnedConfigResource(resource, relativePath, bpmExclusiveFieldCodes))
      .map(stripNestedBpmEntries);
    const bpm = resources.filter((resource) => isBpmOwnedConfigResource(
      resource,
      relativePath,
      bpmExclusiveFieldCodes,
    ));
    writeJson(resolve(coreRoot, relativePath), core);
    writeJson(resolve(bpmRoot, relativePath), bpm);
    manifest.push({
      file: basename(relativePath),
      sourceCount: resources.length,
      coreCount: core.length,
      bpmCount: bpm.length,
    });
  }
  writeJson(resolve(outputRoot, 'ownership-manifest.json'), {
    schemaVersion: 1,
    source: 'plugins/platform-admin',
    classifier: 'bpm-literal-v1',
    resources: manifest,
  });
  return { coreRoot, bpmRoot, manifest };
}
