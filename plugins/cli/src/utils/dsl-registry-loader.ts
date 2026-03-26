import { readFileSync, existsSync } from 'fs';
import { resolve, dirname } from 'path';

export interface DslEnumEntry {
  code: string;
  label: string;
  since: string;
}

export interface DslRegistryData {
  version: string;
  exportedAt: string;
  enums: Record<string, DslEnumEntry[]>;
  extensions: Record<string, unknown[]>;
  mappings: Record<string, unknown>;
}

let cachedRegistry: DslRegistryData | null = null;

export function loadDslRegistry(): DslRegistryData {
  if (cachedRegistry) return cachedRegistry;

  const candidates = [
    resolve(dirname(dirname(dirname(__dirname))), 'schemas', 'dsl-registry.json'),
    resolve(process.cwd(), 'plugins', 'schemas', 'dsl-registry.json'),
  ];

  for (const path of candidates) {
    if (existsSync(path)) {
      const raw = readFileSync(path, 'utf-8');
      cachedRegistry = JSON.parse(raw) as DslRegistryData;
      return cachedRegistry;
    }
  }

  throw new Error(
    `dsl-registry.json not found. Searched: ${candidates.join(', ')}. ` +
    `Run the backend and fetch from GET /api/dsl/registry to generate it.`
  );
}

export function getEnumCodes(enumName: string): Set<string> {
  const registry = loadDslRegistry();
  const entries = registry.enums[enumName];
  if (!entries) {
    throw new Error(`Unknown enum '${enumName}' in DSL registry`);
  }
  return new Set(entries.map(e => e.code));
}

/**
 * Safe wrapper: returns enum codes from registry, falling back to hardcoded
 * values if the registry file doesn't exist yet.
 */
export function safeGetEnumCodes(enumName: string, fallback: string[]): Set<string> {
  try {
    return getEnumCodes(enumName);
  } catch {
    return new Set(fallback);
  }
}

export function resetRegistryCache(): void {
  cachedRegistry = null;
}
