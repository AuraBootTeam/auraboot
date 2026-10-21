import { readFileSync, existsSync, readdirSync, statSync, realpathSync } from 'fs';
import { join, resolve, relative, isAbsolute } from 'path';

export interface PluginManifest {
  pluginId: string;
  namespace: string;
  version: string;
  resourceDirs?: Record<string, string>;
  displayName?: string;
  description?: string;
  author?: string;
  dslVersion?: number;
  pluginType?: string;
  minPlatformVersion?: string;
  dependencies?: Array<string | { pluginId: string; version?: string }>;
  backend?: {
    jarPath?: string;
    entryClass?: string;
  };
}

export interface PluginFiles {
  dir: string;
  manifest: PluginManifest;
  configDir: string;
  resourceFiles: Map<string, any[]>; // resourceType -> parsed JSON arrays
}

/**
 * Load a plugin from a directory.
 * Expects plugin.json at the root and config/ directory with resource files.
 */
export function loadPlugin(dir: string): PluginFiles {
  const pluginDir = resolve(dir);

  if (!existsSync(pluginDir)) {
    throw new Error(`Directory not found: ${pluginDir}`);
  }

  const manifestPath = join(pluginDir, 'plugin.json');
  if (!existsSync(manifestPath)) {
    throw new Error(`plugin.json not found in ${pluginDir}`);
  }

  const manifest = JSON.parse(readFileSync(manifestPath, 'utf-8')) as PluginManifest;

  const configDir = join(pluginDir, 'config');
  const resourceFiles = new Map<string, any[]>();

  if (existsSync(configDir)) {
    for (const file of readdirSync(configDir, { withFileTypes: true })) {
      const fileName = file.name;
      const filePath = join(configDir, fileName);

      if (file.isFile() && fileName.endsWith('.json')) {
        const resourceType = fileName.replace('.json', '');
        try {
          const content = JSON.parse(readFileSync(filePath, 'utf-8'));
          resourceFiles.set(resourceType, Array.isArray(content) ? content : [content]);
        } catch (e) {
          throw new Error(`Invalid JSON in ${fileName}: ${(e as Error).message}`);
        }
      } else if (file.isDirectory()) {
        // Directory mode: each file in the directory is a single resource
        const resources: any[] = [];
        for (const subFile of readdirSync(filePath)) {
          if (subFile.endsWith('.json')) {
            try {
              const content = JSON.parse(readFileSync(join(filePath, subFile), 'utf-8'));
              if (Array.isArray(content)) {
                resources.push(...content);
              } else {
                resources.push(content);
              }
            } catch (e) {
              throw new Error(`Invalid JSON in ${fileName}/${subFile}: ${(e as Error).message}`);
            }
          }
        }
        if (resources.length > 0) {
          resourceFiles.set(fileName, resources);
        }
      }
    }
  }

  // Public declarations follow explicit manifest paths rather than conventional config filenames.
  for (const resourceType of ['publicResources', 'publicQueries', 'pageContributions']) {
    const declaredPath = manifest.resourceDirs?.[resourceType];
    if (declaredPath === undefined) continue;
    if (typeof declaredPath !== 'string' || !declaredPath) {
      throw new Error(`Invalid public publication path: ${resourceType}`);
    }
    const resourcePath = resolve(pluginDir, declaredPath);
    if (!existsSync(resourcePath)) throw new Error(`Required public publication path missing: ${declaredPath}`);
    const root = realpathSync(pluginDir);
    const assertContained = (path: string) => {
      const diff = relative(root, realpathSync(path));
      if (diff === '..' || diff.startsWith('../') || isAbsolute(diff)) {
        throw new Error(`Public publication path escapes plugin: ${declaredPath}`);
      }
    };
    assertContained(resourcePath);
    const isDirectory = statSync(resourcePath).isDirectory();
    const files = isDirectory
      ? readdirSync(resourcePath, { withFileTypes: true })
        .filter(file => file.isFile() && file.name.endsWith('.json'))
        .map(file => join(resourcePath, file.name)).sort()
      : [resourcePath];
    const resources: unknown[] = [];
    for (const file of files) {
      assertContained(file);
      try {
        const content = JSON.parse(readFileSync(file, 'utf-8'));
        if (!isDirectory && !Array.isArray(content)) throw new Error('Single-file public declarations require an array');
        resources.push(...(Array.isArray(content) ? content : [content]));
      } catch (error) {
        throw new Error(`Invalid public publication file ${relative(pluginDir, file)}: ${(error as Error).message}`);
      }
    }
    resourceFiles.set(resourceType, resources);
  }

  return { dir: pluginDir, manifest, configDir, resourceFiles };
}

/**
 * Count total resource files.
 */
export function countResources(files: PluginFiles): number {
  let count = 0;
  for (const [, resources] of files.resourceFiles) {
    count += resources.length;
  }
  return count;
}
