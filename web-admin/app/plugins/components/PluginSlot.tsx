import React, { Suspense } from 'react';
import { useFederationStore, selectSlotContributions } from '../FederationManager';
import type { SlotProps, SlotContributionWithComponent, SlotComponentProps } from '../types';

/**
 * PluginSlot component for rendering plugin contributions at extension points.
 *
 * This component renders all plugin components that have been registered for a specific slot.
 * Components are rendered in priority order (lower priority values render first).
 *
 * @example
 * ```tsx
 * // In your page layout
 * <div className="page-header">
 *   <h1>Page Title</h1>
 *   <PluginSlot
 *     slotId="page:header:actions"
 *     context={{ pageId: 'invoice-list' }}
 *     fallback={<span>Loading actions...</span>}
 *   />
 * </div>
 *
 * // In a form
 * <form>
 *   <FormFields />
 *   <PluginSlot slotId="form:after-fields" context={{ formData }} />
 *   <SubmitButton />
 * </form>
 * ```
 */
export function PluginSlot({
  slotId,
  context = {},
  fallback,
  className,
}: SlotProps) {
  const contributions = useFederationStore(selectSlotContributions(slotId));

  if (contributions.length === 0) {
    return null;
  }

  return (
    <div className={className} data-slot-id={slotId}>
      {contributions.map((contribution, index) => (
        <SlotContributionRenderer
          key={`${contribution.pluginId}-${contribution.componentName}-${index}`}
          contribution={contribution}
          slotId={slotId}
          context={context}
          fallback={fallback}
        />
      ))}
    </div>
  );
}

/**
 * Render a single slot contribution.
 */
interface SlotContributionRendererProps {
  contribution: SlotContributionWithComponent;
  slotId: string;
  context: Record<string, unknown>;
  fallback?: React.ReactNode;
}

function SlotContributionRenderer({
  contribution,
  slotId,
  context,
  fallback,
}: SlotContributionRendererProps) {
  const { pluginId, component: Component, props = {} } = contribution;

  // Get plugin info for the namespace
  const plugin = useFederationStore((state) => state.plugins.get(pluginId));
  const namespace = plugin?.namespace || pluginId;

  // Merge contribution props with slot context
  const componentProps: SlotComponentProps = {
    pluginId,
    namespace,
    slotId,
    context: { ...context, ...props },
  };

  return (
    <SlotErrorBoundary pluginId={pluginId} slotId={slotId}>
      <Suspense fallback={fallback || <SlotLoadingFallback />}>
        <Component {...componentProps} />
      </Suspense>
    </SlotErrorBoundary>
  );
}

/**
 * Loading fallback for slot contributions.
 */
function SlotLoadingFallback() {
  return (
    <div className="animate-pulse bg-gray-100 rounded h-8 w-full" />
  );
}

/**
 * Error fallback for slot contributions.
 */
function SlotErrorFallback({ pluginId, error }: { pluginId: string; error: Error }) {
  // In production, you might want to hide this completely or log to an error service
  if (process.env.NODE_ENV === 'production') {
    return null;
  }

  return (
    <div className="p-2 bg-red-50 border border-red-200 rounded text-xs text-red-600">
      Plugin error ({pluginId}): {error.message}
    </div>
  );
}

// ========== Error Boundary ==========

interface SlotErrorBoundaryProps {
  children: React.ReactNode;
  pluginId: string;
  slotId: string;
}

interface SlotErrorBoundaryState {
  hasError: boolean;
  error: Error | null;
}

class SlotErrorBoundary extends React.Component<SlotErrorBoundaryProps, SlotErrorBoundaryState> {
  constructor(props: SlotErrorBoundaryProps) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): SlotErrorBoundaryState {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error(
      `[PluginSlot] Error in slot ${this.props.slotId} from plugin ${this.props.pluginId}:`,
      error,
      errorInfo
    );
  }

  render() {
    if (this.state.hasError && this.state.error) {
      return (
        <SlotErrorFallback
          pluginId={this.props.pluginId}
          error={this.state.error}
        />
      );
    }

    return this.props.children;
  }
}

// ========== Utility Components ==========

/**
 * Wrapper to conditionally render slot only if there are contributions.
 */
export function ConditionalSlot({
  slotId,
  wrapper: Wrapper,
  wrapperProps = {},
  ...slotProps
}: SlotProps & {
  wrapper?: React.ComponentType<{ children: React.ReactNode } & Record<string, unknown>>;
  wrapperProps?: Record<string, unknown>;
}) {
  const contributions = useFederationStore(selectSlotContributions(slotId));

  if (contributions.length === 0) {
    return null;
  }

  const slot = <PluginSlot slotId={slotId} {...slotProps} />;

  if (Wrapper) {
    return <Wrapper {...wrapperProps}>{slot}</Wrapper>;
  }

  return slot;
}

/**
 * Hook to check if a slot has any contributions.
 */
export function useSlotHasContributions(slotId: string): boolean {
  const contributions = useFederationStore(selectSlotContributions(slotId));
  return contributions.length > 0;
}

/**
 * Hook to get slot contribution count.
 */
export function useSlotContributionCount(slotId: string): number {
  const contributions = useFederationStore(selectSlotContributions(slotId));
  return contributions.length;
}

export default PluginSlot;
