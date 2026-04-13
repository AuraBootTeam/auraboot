/**
 * ComponentLoader - 动态组件加载器
 * 支持动态加载 Smart 组件,带缓存机制
 */

import React, { useState, useEffect } from 'react';
import type { ComponentType } from 'react';
import { componentRegistry, initializeComponentRegistry } from '~/framework/meta/registry/components';
import { getRuntimeComponentEntry } from '~/framework/meta/rendering/components/runtime-component-loaders';

// LRU-style component cache with max size
const MAX_CACHE_SIZE = 100;
const componentCache = new Map<string, ComponentType<any>>();
const nameResolutionCache = new Map<string, string>();
let registryInitialized = false;

function cacheSet(key: string, value: ComponentType<any>) {
  if (componentCache.size >= MAX_CACHE_SIZE) {
    // Evict oldest entry (first key in insertion order)
    const firstKey = componentCache.keys().next().value;
    if (firstKey !== undefined) {
      componentCache.delete(firstKey);
    }
  }
  componentCache.set(key, value);
}

function ensureRegistryInitialized() {
  if (!registryInitialized && componentRegistry.getAllComponents().length === 0) {
    initializeComponentRegistry();
    registryInitialized = true;
  }
}

export interface ComponentLoaderProps {
  componentName: string;
  props?: Record<string, any>;
  fallback?: React.ReactNode;
}

/**
 * ComponentLoader 组件
 */
export const ComponentLoader: React.FC<ComponentLoaderProps> = ({
  componentName,
  props = {},
  fallback = null,
}) => {
  const [Component, setComponent] = useState<ComponentType<any> | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let mounted = true;

    const loadComponent = async () => {
      try {
        setLoading(true);
        setError(null);

        ensureRegistryInitialized();
        let normalizedName = resolveComponentName(componentName);

        if (componentCache.has(normalizedName)) {
          if (mounted) {
            setComponent(() => componentCache.get(normalizedName)!);
            setLoading(false);
          }
          return;
        }

        let entry =
          getRuntimeComponentEntry(normalizedName) ||
          getRuntimeComponentEntry(normalizedName.toLowerCase());
        if (!entry) {
          throw new Error(`Unknown component: ${componentName} (normalized: ${normalizedName})`);
        }

        const module = await entry.loader();
        const exportKey = entry.exportName === 'default' ? 'default' : entry.exportName;
        const LoadedComponent = module[exportKey];

        if (!LoadedComponent) {
          throw new Error(`Component ${normalizedName} not found in module export ${exportKey}`);
        }

        cacheSet(entry.componentName || normalizedName, LoadedComponent);
        if (normalizedName !== (entry.componentName || normalizedName)) {
          cacheSet(normalizedName, LoadedComponent);
        }

        if (mounted) {
          setComponent(() => LoadedComponent);
        }
      } catch (err) {
        if (mounted) {
          setError(err instanceof Error ? err : new Error('Failed to load component'));
          console.error(`Failed to load component ${componentName}:`, err);
        }
      } finally {
        if (mounted) {
          setLoading(false);
        }
      }
    };

    loadComponent();

    return () => {
      mounted = false;
    };
  }, [componentName]);

  if (loading) {
    return (
      fallback || (
        <div className="flex items-center justify-center p-4">
          <span className="loading loading-spinner loading-sm"></span>
          <span className="ml-2 text-sm text-gray-500">Loading {componentName}...</span>
        </div>
      )
    );
  }

  if (error) {
    return (
      <div className="alert alert-error">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-6 w-6 shrink-0 stroke-current"
          fill="none"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            d="M10 14l2-2m0 0l2-2m-2 2l-2-2m2 2l2 2m7-2a9 9 0 11-18 0 9 9 0 0118 0z"
          />
        </svg>
        <span>
          Failed to load {componentName}: {error.message}
        </span>
      </div>
    );
  }

  if (!Component) {
    return (
      <div className="alert alert-warning">
        <svg
          xmlns="http://www.w3.org/2000/svg"
          className="h-6 w-6 shrink-0 stroke-current"
          fill="none"
          viewBox="0 0 24 24"
        >
          <path
            strokeLinecap="round"
            strokeLinejoin="round"
            strokeWidth="2"
            d="M12 9v2m0 4h.01m-6.938 4h13.856c1.54 0 2.502-1.667 1.732-3L13.732 4c-.77-1.333-2.694-1.333-3.464 0L3.34 16c-.77 1.333.192 3 1.732 3z"
          />
        </svg>
        <span>Component not found: {componentName}</span>
      </div>
    );
  }

  return <Component {...props} />;
};

function resolveComponentName(name: string): string {
  const cached = nameResolutionCache.get(name);
  if (cached) return cached;

  const normalized = name.trim();
  const compact = normalized.replace(/[-_]/g, '');

  let result = normalized;
  if (hasRuntimeEntry(normalized)) {
    result = normalized;
  } else {
    const lower = normalized.toLowerCase();
    if (hasRuntimeEntry(lower)) {
      result = lower;
    } else if (compact && hasRuntimeEntry(compact)) {
      result = compact;
    } else if (compact && hasRuntimeEntry(compact.toLowerCase())) {
      result = compact.toLowerCase();
    } else {
      const compactLower = compact.toLowerCase();
      const smartName = normalized.startsWith('Smart')
        ? normalized
        : `Smart${normalized.charAt(0).toUpperCase()}${normalized.slice(1)}`;

      if (hasRuntimeEntry(smartName)) {
        result = smartName;
      } else if (compactLower) {
        const compactSmartName = compactLower.startsWith('smart')
          ? compactLower
          : `Smart${compactLower.charAt(0).toUpperCase()}${compactLower.slice(1)}`;
        if (hasRuntimeEntry(compactSmartName)) {
          result = compactSmartName;
        } else if (componentRegistry.getComponent(compactLower)) {
          result = compactLower;
        } else if (componentRegistry.getComponent(lower)) {
          result = lower;
        }
      } else if (componentRegistry.getComponent(lower)) {
        result = lower;
      }
    }
  }

  nameResolutionCache.set(name, result);
  return result;
}

function hasRuntimeEntry(name: string): boolean {
  return Boolean(getRuntimeComponentEntry(name));
}
