/**
 * DSL Registry Context
 *
 * Provides the DSL registry data to the entire React tree.
 * Loads the registry once on mount (authenticated users only)
 * and exposes convenience query methods via useDslRegistry().
 */

import React, { createContext, useContext, useState, useEffect, useCallback, useMemo } from 'react';
import { useAuth } from './AuthContext';
import type {
  DslRegistryData,
  DslEnumEntry,
  RenderComponentEntry,
  BlockRendererEntry,
  CommandHandlerEntry,
} from '~/services/dslRegistryService';
import {
  fetchDslRegistry,
  getFallbackRegistry,
  getEnumCodes,
  getEnumOptions,
  invalidateRegistryCache,
} from '~/services/dslRegistryService';

// ---------------------------------------------------------------------------
// Context type
// ---------------------------------------------------------------------------

interface DslRegistryContextType {
  /** The full registry data */
  registry: DslRegistryData;
  /** Whether the registry is still being fetched */
  loading: boolean;
  /** Whether the registry is the fallback (not from server) */
  isFallback: boolean;

  // -- Enum helpers --

  /** Get all entries for a named enum */
  getEnum: (name: string) => DslEnumEntry[];
  /** Get enum codes as string array */
  getEnumCodes: (name: string) => string[];
  /** Get enum as { label, value } options */
  getEnumOptions: (name: string) => Array<{ label: string; value: string }>;

  // -- Shortcut accessors for commonly used enums --

  /** DataType enum codes */
  dataTypes: string[];
  /** FieldType enum codes */
  fieldTypes: string[];
  /** BlockType enum codes */
  blockTypes: string[];
  /** ChartType enum codes */
  chartTypes: string[];

  // -- Extension helpers --

  /** Render components from the extension registry */
  renderComponents: RenderComponentEntry[];
  /** Block renderers from the extension registry */
  blockRenderers: BlockRendererEntry[];
  /** Command handlers from the extension registry */
  commandHandlers: CommandHandlerEntry[];
  /** Get render components compatible with a given data type */
  getRenderComponentsForDataType: (dataType: string) => RenderComponentEntry[];
  /** Get the default render component for a data type */
  getDefaultComponentForDataType: (dataType: string) => string | undefined;

  /** Force re-fetch */
  refresh: () => void;
}

// ---------------------------------------------------------------------------
// Default context
// ---------------------------------------------------------------------------

const fallback = getFallbackRegistry();

const DslRegistryContext = createContext<DslRegistryContextType>({
  registry: fallback,
  loading: false,
  isFallback: true,
  getEnum: () => [],
  getEnumCodes: () => [],
  getEnumOptions: () => [],
  dataTypes: getEnumCodes(fallback, 'DataType'),
  fieldTypes: getEnumCodes(fallback, 'FieldType'),
  blockTypes: getEnumCodes(fallback, 'BlockType'),
  chartTypes: getEnumCodes(fallback, 'ChartType'),
  renderComponents: [],
  blockRenderers: [],
  commandHandlers: [],
  getRenderComponentsForDataType: () => [],
  getDefaultComponentForDataType: () => undefined,
  refresh: () => {},
});

// ---------------------------------------------------------------------------
// Provider
// ---------------------------------------------------------------------------

export function DslRegistryProvider({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth();
  const [registry, setRegistry] = useState<DslRegistryData>(fallback);
  const [loading, setLoading] = useState(false);
  const [isFallback, setIsFallback] = useState(true);

  const load = useCallback(async () => {
    if (!isAuthenticated) return;
    try {
      setLoading(true);
      const data = await fetchDslRegistry();
      setRegistry(data);
      setIsFallback(data.version.includes('fallback'));
    } catch {
      // fetchDslRegistry already returns fallback on error
    } finally {
      setLoading(false);
    }
  }, [isAuthenticated]);

  useEffect(() => {
    load();
  }, [load]);

  const refresh = useCallback(() => {
    invalidateRegistryCache();
    load();
  }, [load]);

  // Memoized convenience accessors
  const value = useMemo<DslRegistryContextType>(() => {
    const getEnumFn = (name: string): DslEnumEntry[] => registry.enums[name] || [];
    const getEnumCodesFn = (name: string): string[] => getEnumCodes(registry, name);
    const getEnumOptionsFn = (name: string) => getEnumOptions(registry, name);

    const renderComponents = registry.extensions?.renderComponents || [];
    const blockRenderers = registry.extensions?.blockRenderers || [];
    const commandHandlers = registry.extensions?.commandHandlers || [];

    const getRenderComponentsForDataType = (dataType: string): RenderComponentEntry[] =>
      renderComponents.filter(
        (rc) => !rc.dataTypes || rc.dataTypes.length === 0 || rc.dataTypes.includes(dataType),
      );

    const getDefaultComponentForDataType = (dataType: string): string | undefined =>
      registry.mappings?.dataTypeDefaults?.[dataType];

    return {
      registry,
      loading,
      isFallback,
      getEnum: getEnumFn,
      getEnumCodes: getEnumCodesFn,
      getEnumOptions: getEnumOptionsFn,
      dataTypes: getEnumCodesFn('DataType'),
      fieldTypes: getEnumCodesFn('FieldType'),
      blockTypes: getEnumCodesFn('BlockType'),
      chartTypes: getEnumCodesFn('ChartType'),
      renderComponents,
      blockRenderers,
      commandHandlers,
      getRenderComponentsForDataType,
      getDefaultComponentForDataType,
      refresh,
    };
  }, [registry, loading, isFallback, refresh]);

  return <DslRegistryContext.Provider value={value}>{children}</DslRegistryContext.Provider>;
}

// ---------------------------------------------------------------------------
// Hook
// ---------------------------------------------------------------------------

/**
 * Access the DSL registry data and helpers.
 *
 * @example
 * const { dataTypes, getEnumOptions } = useDslRegistry();
 * const options = getEnumOptions('DataType');
 */
export function useDslRegistry(): DslRegistryContextType {
  return useContext(DslRegistryContext);
}
