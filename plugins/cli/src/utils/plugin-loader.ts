import { readFileSync, existsSync, readdirSync, statSync } from 'fs';
import { join, resolve } from 'path';

export interface PluginManifest {
  pluginId: string;
  namespace: string;
  version: string;
  displayName?: string;
  description?: string;
  author?: string;
  dslVersion?: number;
  pluginType?: string;
  minPlatformVersion?: string;
  dependencies?: Array<string | { pluginId: string; version?: string }>;
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
    for (const file of readdirSync(configDir)) {
      const filePath = join(configDir, file);
      const stat = statSync(filePath);

      if (stat.isFile() && file.endsWith('.json')) {
        const resourceType = file.replace('.json', '');
        try {
          const content = JSON.parse(readFileSync(filePath, 'utf-8'));
          resourceFiles.set(resourceType, Array.isArray(content) ? content : [content]);
        } catch (e) {
          throw new Error(`Invalid JSON in ${file}: ${(e as Error).message}`);
        }
      } else if (stat.isDirectory()) {
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
              throw new Error(`Invalid JSON in ${file}/${subFile}: ${(e as Error).message}`);
            }
          }
        }
        if (resources.length > 0) {
          resourceFiles.set(file, resources);
        }
      }
    }
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
