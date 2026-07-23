import { createHash } from 'crypto';
import { existsSync, mkdirSync, readFileSync, writeFileSync } from 'fs';
import { dirname } from 'path';
import type { PluginFiles } from '../utils/plugin-loader.js';

/**
 * Desired-state fingerprint for the DSL reconciler.
 *
 * Each DSL resource is keyed `<type>:<natural-id>` and hashed. Comparing a
 * freshly computed fingerprint against a persisted prior state yields the
 * create / update / destroy plan (see computePlan) — the same desired-state
 * model NocoBase's dsl-reconciler uses, but with an explicit risk level.
 */

export interface DslState {
  pluginId: string;
  resources: Record<string, string>;
}

const ID_FIELDS = ['code', 'key', 'pageKey', 'modelCode', 'name', 'id'];

function resourceId(resource: any): string {
  if (resource && typeof resource === 'object') {
    for (const f of ID_FIELDS) {
      if (typeof resource[f] === 'string' && resource[f]) return resource[f];
    }
  }
  // No natural id — fall back to a short content hash so identity is stable.
  return createHash('sha256').update(JSON.stringify(resource)).digest('hex').slice(0, 12);
}

function hashResource(resource: unknown): string {
  return createHash('sha256').update(JSON.stringify(resource)).digest('hex');
}

export function fingerprint(files: PluginFiles): DslState {
  const resources: Record<string, string> = {};
  for (const [type, list] of files.resourceFiles) {
    for (const resource of list) {
      resources[`${type}:${resourceId(resource)}`] = hashResource(resource);
    }
  }
  return { pluginId: files.manifest.pluginId, resources };
}

export function readState(path: string): DslState | null {
  if (!existsSync(path)) return null;
  const parsed = JSON.parse(readFileSync(path, 'utf8'));
  if (parsed && typeof parsed === 'object' && parsed.resources) return parsed as DslState;
  return null;
}

export function writeState(path: string, state: DslState): void {
  mkdirSync(dirname(path), { recursive: true });
  writeFileSync(path, `${JSON.stringify(state, null, 2)}\n`);
}
