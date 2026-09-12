import { cpSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { basename, resolve } from 'node:path';

const BPM_LITERAL = /(?:^|[^a-z0-9])(bpm|sla|smartengine|smart_engine)(?:[^a-z0-9]|$)/i;

function readJson(path) {
  return JSON.parse(readFileSync(path, 'utf8'));
}

function writeJson(path, value) {
  writeFileSync(path, `${JSON.stringify(value, null, 2)}\n`);
}

export function isBpmOwnedConfigResource(resource) {
  return BPM_LITERAL.test(JSON.stringify(resource));
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
    const core = resources.filter((resource) => !isBpmOwnedConfigResource(resource));
    const bpm = resources.filter(isBpmOwnedConfigResource);
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
