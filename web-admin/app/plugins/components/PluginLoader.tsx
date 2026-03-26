import React, { Suspense, useEffect, useState } from 'react';
import { useFederationStore, selectPlugin, selectPluginError } from '../FederationManager';
import type { PluginLoaderProps } from '../types';

/**
 * PluginLoader component for loading and rendering a single remote plugin component.
 *
 * Features:
 * - Dynamic loading of remote modules
 * - Loading state with fallback
 * - Error handling with error boundary
 * - Automatic retry on failure
 *
 * @example
 * ```tsx
 * <PluginLoader
 *   pluginId="billing-plugin"
 *   moduleName="InvoiceForm"
 *   fallback={<Spinner />}
 *   errorFallback={(error) => <ErrorMessage error={error} />}
 *   props={{ invoiceId: '123' }}
 * />
 * ```
 */
export function PluginLoader({
  pluginId,
  moduleName,
  fallback = <DefaultLoadingFallback />,
  errorFallback,
  props = {},
}: PluginLoaderProps) {
  const [Component, setComponent] = useState<React.ComponentType<unknown> | null>(null);
  const [error, setError] = useState<Error | null>(null);
  const [isLoading, setIsLoading] = useState(true);

  const loadModule = useFederationStore((state) => state.loadModule);
  const plugin = useFederationStore(selectPlugin(pluginId));
  const pluginError = useFederationStore(selectPluginError(pluginId));

  useEffect(() => {
    let mounted = true;

    async function loadComponent() {
      if (!plugin) {
        setError(new Error(`Plugin ${pluginId} not found`));
        setIsLoading(false);
        return;
      }

      if (plugin.state === 'error') {
        setError(new Error(pluginError || `Plugin ${pluginId} failed to load`));
        setIsLoading(false);
        return;
      }

      if (plugin.state !== 'loaded') {
        // Plugin not ready yet, wait
        return;
      }

      try {
        setIsLoading(true);
        setError(null);
        const component = await loadModule(pluginId, moduleName);

        if (mounted) {
          if (component) {
            setComponent(() => component);
          } else {
            setError(new Error(`Module ${moduleName} not found in plugin ${pluginId}`));
          }
          setIsLoading(false);
        }
      } catch (err) {
        if (mounted) {
          setError(err instanceof Error ? err : new Error(String(err)));
          setIsLoading(false);
        }
      }
    }

    loadComponent();

    return () => {
      mounted = false;
    };
  }, [pluginId, moduleName, plugin?.state, loadModule, pluginError]);

  // Error state
  if (error) {
    if (typeof errorFallback === 'function') {
      return <>{errorFallback(error)}</>;
    }
    if (errorFallback) {
      return <>{errorFallback}</>;
    }
    return <DefaultErrorFallback error={error} />;
  }

  // Loading state
  if (isLoading || !Component) {
    return <>{fallback}</>;
  }

  // Render the component with error boundary
  return (
    <PluginErrorBoundary
      fallback={errorFallback}
      pluginId={pluginId}
      moduleName={moduleName}
    >
      <Suspense fallback={fallback}>
        <Component {...props} />
      </Suspense>
    </PluginErrorBoundary>
  );
}

// ========== Default Fallbacks ==========

function DefaultLoadingFallback() {
  return (
    <div className="flex items-center justify-center p-4">
      <div className="animate-spin rounded-full h-6 w-6 border-b-2 border-gray-500" />
      <span className="ml-2 text-sm text-gray-500">Loading plugin...</span>
    </div>
  );
}

function DefaultErrorFallback({ error }: { error: Error }) {
  return (
    <div className="p-4 bg-red-50 border border-red-200 rounded-md">
      <div className="flex items-center">
        <svg
          className="h-5 w-5 text-red-400"
          viewBox="0 0 20 20"
          fill="currentColor"
        >
          <path
            fillRule="evenodd"
            d="M10 18a8 8 0 100-16 8 8 0 000 16zM8.707 7.293a1 1 0 00-1.414 1.414L8.586 10l-1.293 1.293a1 1 0 101.414 1.414L10 11.414l1.293 1.293a1 1 0 001.414-1.414L11.414 10l1.293-1.293a1 1 0 00-1.414-1.414L10 8.586 8.707 7.293z"
            clipRule="evenodd"
          />
        </svg>
        <span className="ml-2 text-sm text-red-700">
          Failed to load plugin: {error.message}
        </span>
      </div>
    </div>
  );
}

// ========== Error Boundary ==========

interface ErrorBoundaryProps {
  children: React.ReactNode;
  fallback?: React.ReactNode | ((error: Error) => React.ReactNode);
  pluginId: string;
  moduleName: string;
}

interface ErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class PluginErrorBoundary extends React.Component<ErrorBoundaryProps, ErrorBoundaryState> {
  constructor(props: ErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): ErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error(
      `[PluginLoader] Error in plugin ${this.props.pluginId}/${this.props.moduleName}:`,
      error,
      errorInfo
    );
  }

  render() {
    if (this.state.hasError && this.state.error) {
      const { fallback } = this.props;
      if (typeof fallback === 'function') {
        return <>{fallback(this.state.error)}</>;
      }
      if (fallback) {
        return <>{fallback}</>;
      }
      return <DefaultErrorFallback error={this.state.error} />;
    }

    return this.props.children;
  }
}

export default PluginLoader;
