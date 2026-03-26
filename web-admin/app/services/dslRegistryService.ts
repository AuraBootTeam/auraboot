/**
 * DSL Registry Service
 *
 * Fetches and caches the DSL registry from the backend.
 * The registry contains all closed enums, open extension registries,
 * and default mappings for the DSL system.
 *
 * @see DslRegistryController on the backend
 */

import { get } from '~/services/http-client';
import type { Result } from '~/services/http-client';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DslEnumEntry {
  code: string;
  label: string;
  since?: string;
  deprecated?: boolean;
  description?: string;
}

export interface RenderComponentEntry {
  code: string;
  source: string;
  category?: string;
  dataTypes?: string[];
  description?: string;
}

export interface BlockRendererEntry {
  code: string;
  source: string;
  description?: string;
}

export interface CommandHandlerEntry {
  code: string;
  source: string;
  description?: string;
  riskLevel?: string;
}

export interface SideEffectHandlerEntry {
  code: string;
  source: string;
  description?: string;
}

export interface AutomationActionEntry {
  code: string;
  source: string;
  description?: string;
}

export interface ExpressionFunctionEntry {
  name: string;
  source: string;
  description?: string;
  type?: string;
}

export interface DslRegistryData {
  version: string;
  exportedAt: string;
  enums: Record<string, DslEnumEntry[]>;
  extensions: {
    commandHandlers: CommandHandlerEntry[];
    sideEffectHandlers: SideEffectHandlerEntry[];
    automationActions: AutomationActionEntry[];
    expressionFunctions: ExpressionFunctionEntry[];
    renderComponents: RenderComponentEntry[];
    blockRenderers: BlockRendererEntry[];
  };
  mappings: {
    dataTypeDefaults: Record<string, string>;
  };
}

// ---------------------------------------------------------------------------
// In-memory cache
// ---------------------------------------------------------------------------

let cachedRegistry: DslRegistryData | null = null;
let fetchPromise: Promise<DslRegistryData> | null = null;

// ---------------------------------------------------------------------------
// Default fallback (minimal set for offline / error scenarios)
// ---------------------------------------------------------------------------

const FALLBACK_REGISTRY: DslRegistryData = {
  version: '0.0-fallback',
  exportedAt: '',
  enums: {
    DataType: [
      { code: 'string', label: 'String' },
      { code: 'text', label: 'Text' },
      { code: 'integer', label: 'Integer' },
      { code: 'decimal', label: 'Decimal' },
      { code: 'boolean', label: 'Boolean' },
      { code: 'date', label: 'Date' },
      { code: 'datetime', label: 'DateTime' },
      { code: 'json', label: 'json' },
      { code: 'enum', label: 'Enum' },
      { code: 'reference', label: 'Reference' },
      { code: 'computed', label: 'Computed' },
      { code: 'ai_text', label: 'AI Text' },
      { code: 'money', label: 'Money' },
    ],
    FieldType: [
      { code: 'input', label: 'Input' },
      { code: 'number', label: 'Number' },
      { code: 'select', label: 'Select' },
      { code: 'radio', label: 'Radio' },
      { code: 'checkbox', label: 'Checkbox' },
      { code: 'date', label: 'Date' },
      { code: 'datetime', label: 'DateTime' },
      { code: 'textarea', label: 'Textarea' },
      { code: 'rich_text', label: 'Rich Text' },
      { code: 'switch', label: 'Switch' },
      { code: 'upload', label: 'Upload' },
      { code: 'custom', label: 'Custom' },
      { code: 'ai_input', label: 'AI Input' },
    ],
    BlockType: [
      { code: 'form', label: 'Form' },
      { code: 'form-section', label: 'Form Section' },
      { code: 'form-buttons', label: 'Form Buttons' },
      { code: 'form-wizard', label: 'Form Wizard' },
      { code: 'table', label: 'Table' },
      { code: 'data-table', label: 'Data Table' },
      { code: 'filters', label: 'Filters' },
      { code: 'filter-form', label: 'Filter Form' },
      { code: 'toolbar', label: 'Toolbar' },
      { code: 'action', label: 'Action' },
      { code: 'description', label: 'Description' },
      { code: 'chart', label: 'Chart' },
      { code: 'tabs', label: 'Tabs' },
      { code: 'list-tabs', label: 'List Tabs' },
      { code: 'sub-table', label: 'Sub Table' },
      { code: 'monthly-grid', label: 'Monthly Grid' },
      { code: 'stat-card', label: 'Stat Card' },
      { code: 'custom', label: 'Custom' },
    ],
    ChartType: [
      { code: 'number', label: 'Number' },
      { code: 'bar', label: 'Bar' },
      { code: 'line', label: 'Line' },
      { code: 'pie', label: 'Pie' },
      { code: 'table', label: 'Table' },
    ],
  },
  extensions: {
    commandHandlers: [],
    sideEffectHandlers: [],
    automationActions: [],
    expressionFunctions: [],
    renderComponents: [],
    blockRenderers: [],
  },
  mappings: {
    dataTypeDefaults: {
      STRING: 'input',
      TEXT: 'textarea',
      INTEGER: 'number',
      DECIMAL: 'number',
      BOOLEAN: 'switch',
      DATE: 'date',
      DATETIME: 'datetime',
      ENUM: 'select',
      REFERENCE: 'select',
    },
  },
};

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/**
 * Fetch the DSL registry from the backend.
 * Returns the cached version if already loaded.
 * On failure, returns a minimal fallback so the UI stays functional.
 */
export async function fetchDslRegistry(): Promise<DslRegistryData> {
  if (cachedRegistry) return cachedRegistry;

  // De-duplicate concurrent fetches
  if (fetchPromise) return fetchPromise;

  fetchPromise = (async () => {
    try {
      const result: Result<DslRegistryData> = await get<DslRegistryData>('/api/dsl/registry');
      if (result.code === '0' && result.data && result.data.version) {
        cachedRegistry = result.data;
      } else if (result.data && (result.data as any).enums) {
        // Backend returns the registry directly (no wrapper)
        cachedRegistry = result.data;
      } else {
        console.warn('[DslRegistry] Unexpected response format, using fallback');
        cachedRegistry = FALLBACK_REGISTRY;
      }
      return cachedRegistry;
    } catch (err) {
      console.warn('[DslRegistry] Failed to fetch registry, using fallback:', err);
      cachedRegistry = FALLBACK_REGISTRY;
      return cachedRegistry;
    } finally {
      fetchPromise = null;
    }
  })();

  return fetchPromise;
}

/**
 * Get the cached registry synchronously.
 * Returns null if not yet loaded. Use fetchDslRegistry() first.
 */
export function getCachedRegistry(): DslRegistryData | null {
  return cachedRegistry;
}

/**
 * Get the fallback registry (for use before async load completes).
 */
export function getFallbackRegistry(): DslRegistryData {
  return FALLBACK_REGISTRY;
}

/**
 * Invalidate the cache and force a re-fetch on next access.
 */
export function invalidateRegistryCache(): void {
  cachedRegistry = null;
  fetchPromise = null;
}

// ---------------------------------------------------------------------------
// Helper: extract enum codes as string array
// ---------------------------------------------------------------------------

/**
 * Get codes from a specific enum in the registry.
 */
export function getEnumCodes(registry: DslRegistryData, enumName: string): string[] {
  return (registry.enums[enumName] || []).map((e) => e.code);
}

/**
 * Get an enum as label-value options (for select dropdowns).
 */
export function getEnumOptions(
  registry: DslRegistryData,
  enumName: string,
): Array<{ label: string; value: string }> {
  return (registry.enums[enumName] || []).map((e) => ({
    label: e.label,
    value: e.code,
  }));
}
