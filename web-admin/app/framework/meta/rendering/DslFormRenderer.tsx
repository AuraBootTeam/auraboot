/**
 * DslFormRenderer — L1 SDK stable rendering surface for DSL-driven forms
 *
 * Thin component that renders a DSL form from the `useDslForm` hook's return value.
 * This is the companion to `useDslForm` — together they form the complete L1 SDK API:
 *
 * @example
 * ```tsx
 * const form = useDslForm({
 *   pageKey: 'order_new',
 *   initialValues: { status: 'draft' },
 *   onSubmit: async (payload) => { await saveOrder(payload.values); },
 * });
 *
 * return <DslFormRenderer form={form} />;
 * ```
 *
 * Consumers (e.g. BpmTaskDrawer, QuickCreateModal) should use this component
 * rather than wiring into internal rendering hooks directly.
 */

import React, { Suspense, useEffect } from 'react';
import { profileRegistry } from '~/framework/meta/profiles/ProfileRegistry';
import { ProfileProvider } from '~/framework/meta/profiles/ProfileContext';
import { LoadingSpinner } from '~/ui/LoadingSpinner';
import { ErrorAlert } from '~/ui/ErrorAlert';
import type { UseDslFormReturn } from '~/framework/meta/hooks/useDslForm';
import { useAuraBotSafe } from '~/plugins/core-aurabot/hooks/useAuraBotSafe';
import { DslFormFillProvider } from './DslFormFillContext';

// Ensure built-in profiles are registered before resolution
import '~/framework/meta/profiles/admin';
import '~/framework/meta/profiles/report';

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface DslFormRendererProps {
  /** Return value from useDslForm — contains schema, state, and rendererProps */
  form: UseDslFormReturn;
  /**
   * Whether to show form action buttons (submit/cancel).
   * Passed through to the PageContent component via rendererProps.
   * @default true
   */
  showButtons?: boolean;
  /**
   * Compact layout mode — reduces spacing for use in drawers and modals.
   * @default false
   */
  compact?: boolean;
  /** Additional CSS class applied to the outermost wrapper div */
  className?: string;
}

// ---------------------------------------------------------------------------
// Component
// ---------------------------------------------------------------------------

/**
 * Renders a DSL form based on the state returned by `useDslForm`.
 *
 * Rendering pipeline:
 * 1. Show FormPageSkeleton while schema is loading
 * 2. Show error message if schema loading failed
 * 3. Resolve the DSL profile (from schema.profile or "admin" fallback)
 * 4. Look up the page renderer for schema.kind (typically "form")
 * 5. Render the PageContent component with form.rendererProps
 */
export function DslFormRenderer({
  form,
  showButtons: _showButtons = true,
  compact: _compact = false,
  className,
}: DslFormRendererProps) {
  const { loading, error, schema, rendererProps } = form;

  // Register form fill handler with AuraBot so AI can populate fields
  const auraBot = useAuraBotSafe();
  useEffect(() => {
    if (!auraBot || !form.setFieldValue) return;
    const handler = (fields: Record<string, any>) => {
      Object.entries(fields).forEach(([fieldCode, value]) => {
        form.setFieldValue(fieldCode, value);
      });
    };
    auraBot.registerFormFillHandler(handler);
    return () => auraBot.unregisterFormFillHandler();
  }, [auraBot, form.setFieldValue]);

  // --- 1. Loading state ---
  if (loading) {
    // Try to resolve the profile skeleton for a richer loading experience.
    // Profile may not be known yet (no schema), so fall back gracefully.
    let Skeleton: React.ComponentType | undefined;
    try {
      const profile = profileRegistry.resolve(null);
      Skeleton = profile?.skeletons?.get('form');
    } catch {
      // Profile resolution may fail if no profiles are registered yet — ignore.
    }

    return (
      <div className={className} data-testid="dsl-form-renderer-loading">
        {Skeleton ? <Skeleton /> : <LoadingSpinner />}
      </div>
    );
  }

  // --- 2. Error state ---
  if (error) {
    return (
      <div className={className} data-testid="dsl-form-renderer-error">
        <ErrorAlert error={error.message} />
      </div>
    );
  }

  // --- 3. Not enabled / no schema yet ---
  if (!schema || !form.enabled) {
    return null;
  }

  // --- 4. Resolve profile ---
  let profile;
  try {
    profile = profileRegistry.resolve(schema);
  } catch {
    // If the profile named in the schema is not registered, fall back to "admin"
    profile = profileRegistry.get('admin');
  }

  if (!profile) {
    return (
      <div className={className} data-testid="dsl-form-renderer-error">
        <ErrorAlert error="No DSL profile available for rendering. Ensure profile 'admin' is registered." />
      </div>
    );
  }

  // --- 5. Resolve page renderer for this schema kind ---
  // schema.kind is at the top level of the loaded DSL schema (e.g. "form", "list", "detail")
  const schemaKind: string = (schema as any)?.kind ?? 'form';
  const PageContent = profile.pageRenderers.get(schemaKind);

  if (!PageContent) {
    if (process.env.NODE_ENV === 'development') {
      console.warn(
        `[DslFormRenderer] No page renderer found for kind="${schemaKind}" in profile="${profile.name}". ` +
          `Available kinds: ${Array.from(profile.pageRenderers.keys()).join(', ')}`,
      );
    }
    return (
      <div className={className} data-testid="dsl-form-renderer-error">
        <ErrorAlert
          error={`Form renderer not available for kind "${schemaKind}" in profile "${profile.name}".`}
        />
      </div>
    );
  }

  // --- 6. Resolve skeleton fallback for lazy chunk loading ---
  const Skeleton = profile.skeletons?.get(schemaKind);
  const suspenseFallback = Skeleton ? <Skeleton /> : <LoadingSpinner />;

  // --- 7. Render ---
  return (
    <ProfileProvider value={profile}>
      <DslFormFillProvider setFieldValue={form.setFieldValue}>
        <div className={className} data-testid="dsl-form-renderer">
          <Suspense fallback={suspenseFallback}>
            <PageContent {...rendererProps} />
          </Suspense>
        </div>
      </DslFormFillProvider>
    </ProfileProvider>
  );
}

export default DslFormRenderer;
